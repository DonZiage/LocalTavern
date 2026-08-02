package chat.donzi.localtavern.data.database

import chat.donzi.localtavern.domain.ApiConfig
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Message
import chat.donzi.localtavern.domain.Persona
import chat.donzi.localtavern.domain.PromptBlock
import chat.donzi.localtavern.domain.Session
import chat.donzi.localtavern.utils.deserializeImageList

fun CharacterEntity.toDomain(): Character = Character(
    id = id,
    name = name,
    description = description,
    personality = personality ?: "",
    scenario = scenario ?: "",
    firstMes = firstMes,
    mesExample = mesExample?.split("|||")?.filter { it.isNotBlank() } ?: emptyList(),
    creatorNotes = creatorNotes,
    altGreetings = altGreetings?.split("|||")?.filter { it.isNotBlank() } ?: emptyList(),
    avatarData = avatarData,
    isAssistant = isAssistant == 1L
)

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
    images = deserializeImageList(imageData)
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
    isActive = isActive == 1L,
    isChatCompletion = isChatCompletion == 1L,
    lastUsed = lastUsed,
    temperature = temperature,
    topP = topP,
    topK = topK,
    presencePenalty = presencePenalty,
    frequencyPenalty = frequencyPenalty,
    contextLimit = contextLimit,
    responseLimit = responseLimit,
    displayOrder = displayOrder,
    timeoutLimit = timeoutLimit
)

fun PromptBlockEntity.toDomain(): PromptBlock = PromptBlock(
    id = id,
    name = name,
    template = template,
    isEnabled = isEnabled == 1L,
    isCustom = isCustom == 1L
)
