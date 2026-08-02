package chat.donzi.localtavern.controller

import chat.donzi.localtavern.data.database.ApiSettingsRepository
import chat.donzi.localtavern.data.database.PricingRepository
import chat.donzi.localtavern.data.database.SessionRepository
import chat.donzi.localtavern.data.network.ChatClient
import chat.donzi.localtavern.data.network.GenerationParams
import chat.donzi.localtavern.data.network.StreamChunk
import chat.donzi.localtavern.data.network.StreamTruncatedException
import chat.donzi.localtavern.data.network.apiStyleForProvider
import chat.donzi.localtavern.data.network.isOpenAIEffortModel
import chat.donzi.localtavern.data.network.isReasoningModel
import chat.donzi.localtavern.data.pricing.CostEstimator
import chat.donzi.localtavern.data.pricing.PricingCatalog
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Persona
import chat.donzi.localtavern.utils.ChatMessage
import chat.donzi.localtavern.utils.ContextManager
import chat.donzi.localtavern.utils.DefaultTokenizer
import chat.donzi.localtavern.utils.ImageAttachment
import chat.donzi.localtavern.utils.detectMimeType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Runs one full streaming generation: inserts the "..." placeholder, builds
 * the payload, opens the stream with an idle-token timeout, persists the
 * result (or removes the placeholder on failure) and surfaces errors through
 * the caller-provided state flow.
 *
 * The caller owns the isGenerating flag and the outer error surfacing; this
 * class owns everything that happens between placeholder insertion and the
 * final refresh. Cancellation is rethrown after cleanup so the caller can
 * unwind its own state.
 */
class GenerationRunner(
    private val sessionRepository: SessionRepository,
    private val apiSettingsRepository: ApiSettingsRepository,
    private val pricingRepository: PricingRepository,
    private val chatClient: ChatClient,
    private val scope: CoroutineScope,
    private val payloadDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    suspend fun run(
        sessionId: String,
        character: Character?,
        persona: Persona?,
        targetParentId: String?,
        state: MutableStateFlow<ChatUiState>,
        isCurrentView: () -> Boolean,
        onRefresh: suspend (sessionId: String) -> Unit
    ) {
        val activeConnection = apiSettingsRepository.getActiveApiConnection()
        if (activeConnection == null) {
            state.update { it.copy(errorMessage = "No active API connection configured.", errorIsWarning = false) }
            onRefresh(sessionId)
            return
        }

        val aiMessageId = sessionRepository.insertMessage(sessionId, "assistant", "...", targetParentId)
        onRefresh(sessionId)

        val responseBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        var lastStateTime = 0L
        var lastPersistTime = 0L

        // The placeholder is inserted before the payload is built and the
        // stream is opened. Any failure in that phase must remove the
        // placeholder, or a phantom "..." message would remain in the session
        // with no error surfaced.
        try {
            val (messagesPayload, inputTokens) = withContext(payloadDispatcher) {
                val dbMessages = sessionRepository.getMessagesForSession(sessionId)

                val chatHistory = dbMessages
                    .filter { it.id != aiMessageId }
                    .map { msg ->
                        val attachmentsList = msg.images.mapNotNull { imgBytes ->
                            // Unknown formats (corrupt picks, odd containers)
                            // have no media type any vision API accepts; sending
                            // them mislabeled would fail the request, so they
                            // are skipped from the payload.
                            val mimeType = detectMimeType(imgBytes) ?: return@mapNotNull null
                            ImageAttachment(
                                base64 = kotlin.io.encoding.Base64.encode(imgBytes),
                                mimeType = mimeType
                            )
                        }
                        ChatMessage(
                            role = msg.role,
                            content = msg.content,
                            images = attachmentsList
                        )
                    }

                val blocks = apiSettingsRepository.getAllPromptBlocks()
                val payload = ContextManager.buildPayload(
                    blocks = blocks, character = character, persona = persona, chatHistory = chatHistory,
                    contextLimit = activeConnection.contextLimit, responseLimit = activeConnection.responseLimit
                )
                // Prompt tokens drive both the context budget (the tokenizer
                // already approximates it there) and the cost estimate; reuse
                // the same heuristic counter.
                val promptTokens = payload.sumOf { msg ->
                    DefaultTokenizer.countTokens(msg.content).toLong() + msg.images.size * ContextManager.TOKENS_PER_IMAGE
                }
                payload to promptTokens
            }

            val timeoutLimitSeconds = activeConnection.timeoutLimit
            val tokenChannel = Channel<StreamChunk>(Channel.UNLIMITED)

            // Idle timeout: the deadline restarts on every token, so a
            // continuously streaming response is never killed by a total-stream
            // deadline, while a stalled stream (long silence with no tokens)
            // still surfaces a timeout.
            val timeoutDuration = if (timeoutLimitSeconds <= 0L) null else timeoutLimitSeconds.seconds
            var lastTokenAt = TimeSource.Monotonic.markNow()

            // Reasoning mode: override wins, otherwise auto-detect from the
            // model name (o-series, R1, reasoner, ...).
            val reasoningEnabled = when (activeConnection.reasoningOverride) {
                1 -> true
                2 -> false
                else -> isReasoningModel(activeConnection.model)
            }
            val isAnthropic = apiStyleForProvider(activeConnection.provider) == chat.donzi.localtavern.data.network.ApiStyle.Anthropic
            val effectiveResponseLimit = activeConnection.responseLimit.takeIf { it > 0 } ?: 8192L

            val generationParams = GenerationParams(
                temperature = activeConnection.temperature,
                topP = activeConnection.topP,
                topK = activeConnection.topK,
                presencePenalty = activeConnection.presencePenalty,
                frequencyPenalty = activeConnection.frequencyPenalty,
                maxTokens = activeConnection.responseLimit,
                reasoningEffort = if (reasoningEnabled && isOpenAIEffortModel(activeConnection.model)) "medium" else null,
                thinkingBudgetTokens = if (reasoningEnabled && isAnthropic) effectiveResponseLimit.coerceAtLeast(1024) else null
            )

            // Resolve pricing once per request: DB override wins, otherwise the
            // bundled catalog. The model can be null on legacy connections;
            // estimate() then returns null.
            val pricingOverride = runCatching {
                activeConnection.model?.let { model ->
                    pricingRepository.getPricing(activeConnection.provider, model)
                }
            }.getOrNull()
            val pricing = PricingCatalog.resolve(activeConnection.provider, activeConnection.model, pricingOverride)

            fun updateLiveCost(outputTokens: Long) {
                val estimate = CostEstimator.estimate(pricing, inputTokens, outputTokens)
                state.update { st ->
                    if (!isCurrentView()) {
                        st
                    } else {
                        st.copy(liveCostEstimate = estimate)
                    }
                }
            }

            val streamJob = scope.launch {
                try {
                    chatClient.streamChatRequest(
                        baseUrl = activeConnection.baseUrl ?: "", apiKey = activeConnection.apiKey ?: "",
                        model = activeConnection.model ?: "", messages = messagesPayload,
                        isChatCompletion = activeConnection.isChatCompletion,
                        params = generationParams, provider = activeConnection.provider,
                        timeoutSeconds = activeConnection.timeoutLimit
                    ).collect { chunk -> tokenChannel.send(chunk) }
                } catch (e: Exception) {
                    tokenChannel.close(e)
                    return@launch
                }
                tokenChannel.close()
            }

            try {
                while (true) {
                    val channelResult = if (timeoutDuration == null) {
                        tokenChannel.receiveCatching()
                    } else {
                        val elapsedSinceLastToken = lastTokenAt.elapsedNow()
                        if (elapsedSinceLastToken >= timeoutDuration) {
                            // Force the timeout so the partial response is kept
                            // with a warning.
                            withTimeout(Duration.ZERO) { tokenChannel.receiveCatching() }
                        } else {
                            withTimeout(timeoutDuration - elapsedSinceLastToken) { tokenChannel.receiveCatching() }
                        }
                    }

                    if (channelResult.isClosed) {
                        val cause = channelResult.exceptionOrNull()
                        if (cause != null) {
                            throw cause
                        }
                        break
                    }

                    val chunk = channelResult.getOrNull() ?: break

                    // Any progress resets the idle timer.
                    lastTokenAt = TimeSource.Monotonic.markNow()
                    chunk.content?.let { responseBuilder.append(it) }
                    chunk.reasoning?.let { reasoningBuilder.append(it) }

                    val now = Clock.System.now().toEpochMilliseconds()
                    // Throttle UI and DB updates so fast token streams do not
                    // rebuild the message list (and re-encode the full response
                    // string) on every single token.
                    if (now - lastStateTime >= 50) {
                        lastStateTime = now
                        val snapshot = responseBuilder.toString()
                        val reasoningSnapshot = reasoningBuilder.toString()
                        state.update { st ->
                            if (!isCurrentView()) {
                                st
                            } else {
                                st.copy(messages = st.messages.map { msg -> if (msg.id == aiMessageId) msg.copy(content = snapshot, reasoningText = reasoningSnapshot) else msg })
                            }
                        }
                        updateLiveCost(DefaultTokenizer.countTokens(responseBuilder.toString()).toLong())
                    }
                    if (now - lastPersistTime >= 250) {
                        lastPersistTime = now
                        sessionRepository.updateMessageContentAndReasoning(aiMessageId, responseBuilder.toString(), reasoningBuilder.toString().takeIf { it.isNotBlank() })
                    }
                }
                val fullResponse = responseBuilder.toString()
                if (fullResponse.isBlank()) {
                    sessionRepository.deleteMessage(aiMessageId)
                } else {
                    sessionRepository.updateMessageContentAndReasoning(
                        aiMessageId, fullResponse,
                        reasoningBuilder.toString().takeIf { it.isNotBlank() }
                    )
                    persistCostEstimate(aiMessageId, pricing, inputTokens, responseBuilder.toString())
                }
                onRefresh(sessionId)
            } catch (e: StreamTruncatedException) {
                // The stream delivered tokens but closed without a terminator:
                // recover the full response with a non-streaming request and
                // replace the partial text, so a truncated reply is never
                // persisted as complete (and the partial is never duplicated).
                streamJob.cancel()
                try {
                    val recovered = withContext(payloadDispatcher) {
                        chatClient.sendChatRequest(
                            baseUrl = activeConnection.baseUrl ?: "", apiKey = activeConnection.apiKey ?: "",
                            model = activeConnection.model ?: "", messages = messagesPayload,
                            isChatCompletion = activeConnection.isChatCompletion, params = generationParams,
                            provider = activeConnection.provider, timeoutSeconds = activeConnection.timeoutLimit
                        )
                    }
                    if (recovered.text.isBlank()) {
                        sessionRepository.deleteMessage(aiMessageId)
                    } else {
                        sessionRepository.updateMessageContentAndReasoning(
                            aiMessageId, recovered.text,
                            recovered.reasoningText?.takeIf { it.isNotBlank() }
                        )
                        persistCostEstimate(aiMessageId, pricing, inputTokens, recovered.text)
                        state.update { st ->
                            if (!isCurrentView()) {
                                st
                            } else {
                                st.copy(messages = st.messages.map { msg -> if (msg.id == aiMessageId) msg.copy(content = recovered.text, reasoningText = recovered.reasoningText) else msg })
                            }
                        }
                    }
                    onRefresh(sessionId)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    // Recovery failed: keep the partial text and surface the
                    // truncation error.
                    state.update { it.copy(errorMessage = e.message ?: "Response was interrupted.", errorIsWarning = false) }
                    onRefresh(sessionId)
                }
            } catch (_: TimeoutCancellationException) {
                streamJob.cancel()
                val partialResponse = responseBuilder.toString()
                if (partialResponse.isBlank()) {
                    sessionRepository.deleteMessage(aiMessageId)
                } else {
                    sessionRepository.updateMessageContentAndReasoning(
                        aiMessageId, partialResponse,
                        reasoningBuilder.toString().takeIf { it.isNotBlank() }
                    )
                    persistCostEstimate(aiMessageId, pricing, inputTokens, partialResponse)
                }
                state.update { it.copy(errorMessage = "Response timeout exceeded.", errorIsWarning = true) }
                onRefresh(sessionId)
            } catch (_: CancellationException) {
                streamJob.cancel()
                withContext(NonCancellable) {
                    val partialResponse = responseBuilder.toString()
                    if (partialResponse.isBlank()) {
                        sessionRepository.deleteMessage(aiMessageId)
                    } else {
                        sessionRepository.updateMessageContentAndReasoning(
                            aiMessageId, partialResponse,
                            reasoningBuilder.toString().takeIf { it.isNotBlank() }
                        )
                        persistCostEstimate(aiMessageId, pricing, inputTokens, partialResponse)
                    }
                }
                onRefresh(sessionId)
            } catch (e: Exception) {
                streamJob.cancel()
                val partialResponse = responseBuilder.toString()
                if (partialResponse.isBlank()) {
                    sessionRepository.deleteMessage(aiMessageId)
                } else {
                    sessionRepository.updateMessageContentAndReasoning(
                        aiMessageId, partialResponse,
                        reasoningBuilder.toString().takeIf { it.isNotBlank() }
                    )
                    persistCostEstimate(aiMessageId, pricing, inputTokens, partialResponse)
                }
                state.update { it.copy(errorMessage = e.message ?: "Unknown error occurred", errorIsWarning = false) }
                onRefresh(sessionId)
            }
        } catch (e: CancellationException) {
            // Generation was stopped before any token was handled; drop the
            // placeholder so it cannot linger in the session.
            withContext(NonCancellable) {
                sessionRepository.deleteMessage(aiMessageId)
            }
            onRefresh(sessionId)
            throw e
        } catch (e: Exception) {
            // Payload build or stream setup failed before any token was
            // handled; remove the placeholder and surface the error.
            withContext(NonCancellable) {
                sessionRepository.deleteMessage(aiMessageId)
            }
            state.update { it.copy(errorMessage = e.message ?: "Unknown error occurred", errorIsWarning = false) }
            onRefresh(sessionId)
        }
    }

    // Persists the estimated cost of a completed generation on the message row.
    // The estimate is heuristic (tokenizer-based) and labelled as such in UI.
    private suspend fun persistCostEstimate(
        messageId: String,
        pricing: chat.donzi.localtavern.data.database.ModelPricing?,
        inputTokens: Long,
        responseText: String
    ) {
        val outputTokens = DefaultTokenizer.countTokens(responseText).toLong()
        val estimate = CostEstimator.estimate(pricing, inputTokens, outputTokens)
        sessionRepository.updateMessageCostEstimate(messageId, estimate?.totalUsd)
    }
}
