package chat.donzi.localtavern.data.database

import chat.donzi.localtavern.domain.ApiConfig
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Message
import chat.donzi.localtavern.domain.Persona
import chat.donzi.localtavern.domain.PromptBlock
import chat.donzi.localtavern.domain.Session
import chat.donzi.localtavern.utils.deserializeImageRefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

// SillyTavern v2 separates example dialogue with "<START>"; the app's own
// exports historically used "|||". Accept both so imported cards round-trip.
private val exampleSeparator = Regex("(?:\\|\\|\\||<START>)")

fun CharacterEntity.toDomain(): Character = Character(
    id = id,
    name = name,
    description = description,
    personality = personality ?: "",
    scenario = scenario ?: "",
    firstMes = firstMes,
    mesExample = mesExample?.split(exampleSeparator)?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
    creatorNotes = creatorNotes,
    altGreetings = altGreetings?.split("|||")?.filter { it.isNotBlank() } ?: emptyList(),
    avatarData = avatarData,
    isAssistant = isAssistant == 1L,
    systemPrompt = systemPrompt,
    postHistoryInstructions = postHistoryInstructions,
    creator = creator,
    characterVersion = characterVersion,
    tags = tags?.let { raw ->
        runCatching { json.decodeFromJsonElement<List<String>>(json.parseToJsonElement(raw)) }.getOrNull()
    } ?: emptyList(),
    extensions = parseJsonObject(extensions),
    characterBook = parseJsonObject(characterBook)
)

private fun parseJsonObject(raw: String?): JsonObject? {
    if (raw.isNullOrBlank()) return null
    return runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
}

fun PersonaEntity.toDomain(): Persona = Persona(
    id = id,
    name = name,
    description = description,
    avatarData = avatarData
)

fun MessageEntity.toDomain(): Message = Message(
    id = id,
    sessionId = sessionId,
    role = role,
    content = content,
    timestamp = timestamp,
    parentId = parentId,
    isActivePath = isActivePath == 1L,
    // images are hydrated from the blob store by the repository (needs IO +
    // a BlobStore, which the mapper does not have); imageRefs ride with the
    // row and identify the blobs.
    images = emptyList(),
    imageRefs = deserializeImageRefs(imageRefs),
    reasoningText = reasoningText,
    costEstimateUsd = costEstimate
)

fun ChatSession.toDomain(): Session = Session(
    id = id,
    characterId = characterId,
    personaId = personaId,
    title = title,
    lastTimestamp = lastTimestamp,
    currentMessageId = currentMessageId,
    parentSessionId = parentSessionId
)

fun ApiConnection.toDomain(): ApiConfig = ApiConfig(
    id = id,
    provider = provider,
    name = name,
    baseUrl = baseUrl,
    apiKey = apiKey,
    model = model,
    inferenceProvider = inferenceProvider,
    quantization = quantization,
    isActive = isActive == 1L,
    chatCompletionMode = chatCompletionMode.toInt(),
    lastUsed = lastUsed,
    temperature = temperature,
    topP = topP,
    topK = topK,
    presencePenalty = presencePenalty,
    frequencyPenalty = frequencyPenalty,
    contextLimit = contextLimit,
    responseLimit = responseLimit,
    displayOrder = displayOrder,
    timeoutLimit = timeoutLimit,
    reasoningOverride = reasoningOverride.toInt()
)

fun PromptBlockEntity.toDomain(): PromptBlock = PromptBlock(
    id = id,
    name = name,
    template = template,
    isEnabled = isEnabled == 1L,
    isCustom = isCustom == 1L
)
