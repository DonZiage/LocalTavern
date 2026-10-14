package chat.donzi.localtavern.data.sync

// Per-exchange channel key with one-sided forward secrecy. The static shared
// secret provides authentication (only paired devices can derive it); a fresh
// ephemeral X25519 key provides per-exchange secrecy — each exchange encrypts
// under a key that exists for exactly one round trip, and the sender's
// ephemeral private key is discarded immediately, so payloads a device SENT
// cannot be decrypted later even with its long-term keys. This protection is
// one-sided (static-ephemeral DH): what a device RECEIVED remains decryptable
// if its own static key is later compromised, because the peer's ephemeral
// public key is shipped in cleartext. Rotating the identity key (see
// SyncService.rotateIdentityKey) additionally invalidates the static secrets
// entirely.
class SyncChannelKeys(
    private val crypto: SyncCrypto,
    private val identityProvider: () -> SyncIdentity
) {
    data class ExchangeKey(val key: ByteArray, val ephemeralPublicKey: ByteArray)

    // Outbound direction: the fresh ephemeral keypair seals THIS exchange, and
    // its public half is shipped inside the request for the peer to re-derive
    // the same channel key.
    suspend fun outboundChannelKey(peerPublicKey: ByteArray): ExchangeKey {
        val identity = identityProvider()
        val staticShared = crypto.deriveSharedSecret(identity.privateKeyBytes, peerPublicKey)
        val (ephemeralPrivate, ephemeralPublic) = crypto.generateKeyPair()
        val ephemeralShared = crypto.deriveSharedSecret(ephemeralPrivate, peerPublicKey)
        return ExchangeKey(
            key = crypto.deriveChannelSecret(staticShared, ephemeralShared),
            ephemeralPublicKey = ephemeralPublic
        )
    }

    // Inbound direction: the peer's ephemeral public key (shipped with the
    // request/response) replaces our own ephemeral half; the DH result is the
    // same secret the peer derived with its ephemeral private key.
    suspend fun inboundChannelKey(peerPublicKey: ByteArray, peerEphemeralPublicKey: ByteArray): ByteArray {
        val identity = identityProvider()
        val staticShared = crypto.deriveSharedSecret(identity.privateKeyBytes, peerPublicKey)
        val ephemeralShared = crypto.deriveSharedSecret(identity.privateKeyBytes, peerEphemeralPublicKey)
        return crypto.deriveChannelSecret(staticShared, ephemeralShared)
    }
}
