package chat.donzi.localtavern.data.sync

import kotlinx.serialization.Serializable

// Row snapshots exchanged during sync. ByteArray fields are base64-encoded
// by kotlinx.serialization automatically (avatars, message images, keys).

@Serializable
data class SyncCharacter(
    val id: String,
    val name: String,
    val description: String?,
    val personality: String,
    val scenario: String,
    val firstMes: String?,
    val mesExample: String?,
    val creatorNotes: String?,
    val altGreetings: String?,
    val avatarData: ByteArray?,
    val isAssistant: Long,
    val updatedAt: Long,
    val isDeleted: Long,
    val systemPrompt: String?,
    val postHistoryInstructions: String?,
    val creator: String?,
    val characterVersion: String?,
    val tags: String?,
    val extensions: String?,
    val characterBook: String?
)

@Serializable
data class SyncPersona(
    val id: String,
    val name: String,
    val description: String?,
    val avatarData: ByteArray?,
    val updatedAt: Long,
    val isDeleted: Long
)

@Serializable
data class SyncSession(
    val id: String,
    val characterId: String,
    val personaId: String,
    val title: String?,
    val lastTimestamp: Long,
    val currentMessageId: String?,
    val parentSessionId: String?,
    val updatedAt: Long,
    val isDeleted: Long
)

@Serializable
data class SyncMessage(
    val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val parentId: String?,
    val isActivePath: Long,
    val updatedAt: Long,
    val isDeleted: Long,
    val imageData: ByteArray?,
    val reasoningText: String?,
    val costEstimate: Double?
)

@Serializable
data class SyncApiConnection(
    val id: String,
    val provider: String,
    val name: String,
    val baseUrl: String?,
    val apiKey: String?,
    val model: String?,
    // OpenRouter-style upstream provider routing; null = endpoint default.
    // Default value keeps envelopes from older peers parseable.
    val inferenceProvider: String? = null,
    // OpenRouter quantization preference; null = endpoint default.
    val quantization: String? = null,
    val isActive: Long,
    val isChatCompletion: Long,
    val lastUsed: Long?,
    val temperature: Double,
    val topP: Double,
    val topK: Long,
    val presencePenalty: Double,
    val frequencyPenalty: Double,
    val contextLimit: Long,
    val responseLimit: Long,
    val displayOrder: Long,
    val timeoutLimit: Long,
    val reasoningOverride: Long,
    val updatedAt: Long,
    val isDeleted: Long
)

@Serializable
data class SyncPromptBlock(
    val id: String,
    val name: String,
    val template: String,
    val isEnabled: Long,
    val isCustom: Long,
    val displayOrder: Long,
    val updatedAt: Long,
    val isDeleted: Long
)

@Serializable
data class SyncChanges(
    val characters: List<SyncCharacter> = emptyList(),
    val personas: List<SyncPersona> = emptyList(),
    val sessions: List<SyncSession> = emptyList(),
    val messages: List<SyncMessage> = emptyList(),
    val apiConnections: List<SyncApiConnection> = emptyList(),
    val promptBlocks: List<SyncPromptBlock> = emptyList()
) {
    val isEmpty: Boolean
        get() = characters.isEmpty() && personas.isEmpty() && sessions.isEmpty() &&
            messages.isEmpty() && apiConnections.isEmpty() && promptBlocks.isEmpty()

    /** Highest updatedAt carried by any row in these changes. */
    val maxUpdatedAt: Long
        get() = (characters.maxOfOrNull { it.updatedAt } ?: 0L)
            .coerceAtLeast(personas.maxOfOrNull { it.updatedAt } ?: 0L)
            .coerceAtLeast(sessions.maxOfOrNull { it.updatedAt } ?: 0L)
            .coerceAtLeast(messages.maxOfOrNull { it.updatedAt } ?: 0L)
            .coerceAtLeast(apiConnections.maxOfOrNull { it.updatedAt } ?: 0L)
            .coerceAtLeast(promptBlocks.maxOfOrNull { it.updatedAt } ?: 0L)
}

// One side's view of an exchange: everything the sender changed since the
// recipient's last cursor, plus the sender's own cursor watermark.
@Serializable
data class SyncEnvelope(
    val fromDeviceId: String,
    val cursor: Long,
    val changes: SyncChanges
)

// Pairing handshake. The PIN is never transmitted: [nonce] is a fresh random
// value and [pinProof] is HMAC-SHA256(pin, deviceId|publicKey|nonce), which
// the host verifies against the PIN it displayed. A sniffer that relays the
// exchange cannot swap keys without knowing the PIN.
@Serializable
data class PairRequest(
    val deviceId: String,
    val deviceName: String,
    val publicKey: String,     // base64 X25519 public key
    val nonce: String,         // base64 random 16 bytes
    val pinProof: String       // base64 HMAC-SHA256 over deviceId|publicKey|nonce
)

@Serializable
data class PairResponse(
    val ok: Boolean,
    val message: String = "",
    val deviceId: String = "",
    val deviceName: String = "",
    val publicKey: String = "" // base64 X25519 public key
)

@Serializable
data class HelloResponse(
    val deviceId: String,
    val deviceName: String
)
