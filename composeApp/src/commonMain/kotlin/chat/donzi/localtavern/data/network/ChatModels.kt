package chat.donzi.localtavern.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModelListResponse(
    val data: List<ModelData>
)

@Serializable
data class ModelData(
    val id: String,
    @SerialName("owned_by")
    val ownedBy: String? = null
)

// OpenRouter per-model endpoint details (GET /models/{id}/endpoints). The
// catalog lists the model publisher (the id prefix); only this endpoint lists
// the cloud providers that actually serve the model.
@Serializable
data class ModelEndpointsResponse(
    val data: ModelEndpointsData
)

@Serializable
data class ModelEndpointsData(
    @SerialName("endpoints")
    val endpoints: List<ModelEndpointInfo> = emptyList()
)

// OpenRouter ZDR registry (GET /endpoints/zdr): the data field is a plain
// array of endpoints whose provider retains no data.
@Serializable
data class ZdrEndpointsResponse(
    val data: List<ModelEndpointInfo> = emptyList()
)

@Serializable
data class ModelEndpointInfo(
    @SerialName("provider_name")
    val providerName: String? = null,
    @SerialName("model_id")
    val modelId: String? = null
)

data class ModelInfo(
    val id: String,
    val displayName: String,
    val provider: String
)

data class GenerationParams(
    val temperature: Double = 1.0,
    val topP: Double = 1.0,
    val topK: Long = 0,
    val presencePenalty: Double = 0.0,
    val frequencyPenalty: Double = 0.0,
    val maxTokens: Long = 0,
    // OpenAI-style reasoning models (o-series): "low" | "medium" | "high".
    val reasoningEffort: String? = null,
    // Anthropic extended thinking budget; non-null enables the thinking block.
    val thinkingBudgetTokens: Long? = null
)

// A single streamed chunk. Exactly one of content/reasoning may be non-null;
// reasoning carries the model's private chain-of-thought when supported.
data class StreamChunk(
    val content: String? = null,
    val reasoning: String? = null
)

// Result of a non-streaming request: the visible text plus any reasoning the
// model produced (o-series reasoning_content, Anthropic thinking blocks).
data class ChatResponse(
    val text: String,
    val reasoningText: String? = null
)

open class ApiRequestException(message: String) : Exception(message)

// The API answered (200) but delivered no content at all. Unlike a transport
// failure it must not be retried through the non-streaming fallback, because
// the same empty result would come back again and mask the error.
internal class EmptyResponseException : ApiRequestException("Empty response from API.")

// The API delivered tokens but the stream closed without a terminator
// ([DONE] / message_stop): the response was very likely truncated and must
// not be persisted as a complete reply. The caller (ChatController) recovers
// the full response with a non-streaming request and replaces the partial.
class StreamTruncatedException : ApiRequestException("Response stream ended before completion.")

enum class ApiStyle { OpenAI, Anthropic }

// Outcome of probing an endpoint: the API answered and accepted the key, the
// key was rejected (401/403), or the endpoint could not be reached at all
// (DNS/connection failure, 404, 5xx). The UI distinguishes auth failures from
// transport failures so an unreachable server is not reported as a bad key.
enum class ConnectionProbe { Ok, AuthFailed, Unreachable }

// A probe outcome plus the raw reason behind a failure (HTTP status or
// transport error message), so the UI can tell "404 on the URL" apart from
// "connection refused" instead of showing a generic message.
data class ProbeResult(
    val outcome: ConnectionProbe,
    val detail: String? = null
)
