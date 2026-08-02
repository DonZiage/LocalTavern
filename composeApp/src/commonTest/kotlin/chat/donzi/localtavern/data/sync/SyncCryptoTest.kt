package chat.donzi.localtavern.data.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncCryptoTest {

    private val crypto = SyncCrypto()

    @Test
    fun keyAgreement_derivesSameSecretBothDirections() = runTest {
        val (alicePriv, alicePub) = crypto.generateKeyPair()
        val (bobPriv, bobPub) = crypto.generateKeyPair()

        val aliceSecret = crypto.deriveSharedSecret(alicePriv, bobPub)
        val bobSecret = crypto.deriveSharedSecret(bobPriv, alicePub)

        assertContentEquals(aliceSecret, bobSecret, "Both sides must derive the identical shared secret")
        assertEquals(32, aliceSecret.size)
    }

    @Test
    fun keyAgreement_differsPerPeer() = runTest {
        val (alicePriv, alicePub) = crypto.generateKeyPair()
        val (bobPriv, bobPub) = crypto.generateKeyPair()
        val (carolPriv, carolPub) = crypto.generateKeyPair()

        val withBob = crypto.deriveSharedSecret(alicePriv, bobPub)
        val withCarol = crypto.deriveSharedSecret(alicePriv, carolPub)
        assertFalse(withBob.contentEquals(withCarol), "Secrets for different peers must differ")
    }

    @Test
    fun encryptDecrypt_roundTrips() = runTest {
        val (alicePriv, alicePub) = crypto.generateKeyPair()
        val (bobPriv, bobPub) = crypto.generateKeyPair()
        val secret = crypto.deriveSharedSecret(alicePriv, bobPub)
        val aad = "localtavern-sync|from=a|to=b".encodeToByteArray()

        val plaintext = "secret data".encodeToByteArray()
        val encrypted = crypto.encrypt(secret, aad, plaintext)

        // Payload = nonce(12) + ciphertext.
        assertTrue(encrypted.size > 12)
        assertFalse(encrypted.contentEquals(plaintext))

        val decrypted = crypto.decrypt(secret, aad, encrypted)
        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun decrypt_withWrongAad_fails() = runTest {
        val (alicePriv, alicePub) = crypto.generateKeyPair()
        val (bobPriv, bobPub) = crypto.generateKeyPair()
        val secret = crypto.deriveSharedSecret(alicePriv, bobPub)

        val encrypted = crypto.encrypt(
            secret,
            "localtavern-sync|from=a|to=b".encodeToByteArray(),
            "data".encodeToByteArray()
        )
        // Different recipient binding: must fail authentication.
        assertFailsWith<Exception> {
            crypto.decrypt(
                secret,
                "localtavern-sync|from=a|to=carol".encodeToByteArray(),
                encrypted
            )
        }
    }

    @Test
    fun decrypt_withTamperedPayload_fails() = runTest {
        val (alicePriv, alicePub) = crypto.generateKeyPair()
        val (bobPriv, bobPub) = crypto.generateKeyPair()
        val secret = crypto.deriveSharedSecret(alicePriv, bobPub)
        val aad = "ctx".encodeToByteArray()

        val encrypted = crypto.encrypt(secret, aad, "important".encodeToByteArray())
        val tampered = encrypted.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        assertFailsWith<Exception> { crypto.decrypt(secret, aad, tampered) }
    }

    @Test
    fun decrypt_withWrongKey_fails() = runTest {
        val (alicePriv, alicePub) = crypto.generateKeyPair()
        val (bobPriv, bobPub) = crypto.generateKeyPair()
        val (carolPriv, carolPub) = crypto.generateKeyPair()
        val aad = "ctx".encodeToByteArray()

        val secretAB = crypto.deriveSharedSecret(alicePriv, bobPub)
        val secretAC = crypto.deriveSharedSecret(alicePriv, carolPub)

        val encrypted = crypto.encrypt(secretAB, aad, "data".encodeToByteArray())
        assertFailsWith<Exception> { crypto.decrypt(secretAC, aad, encrypted) }
    }

    @Test
    fun encrypt_isNonDeterministic() = runTest {
        val (alicePriv, alicePub) = crypto.generateKeyPair()
        val (bobPriv, bobPub) = crypto.generateKeyPair()
        val secret = crypto.deriveSharedSecret(alicePriv, bobPub)
        val aad = "ctx".encodeToByteArray()
        val plaintext = "same".encodeToByteArray()

        val first = crypto.encrypt(secret, aad, plaintext)
        val second = crypto.encrypt(secret, aad, plaintext)
        assertFalse(first.contentEquals(second), "Each encryption must use a fresh nonce")
    }

    @Test
    fun pairingProof_verifiesOnlyWithTheMatchingPin() = runTest {
        val publicKey = ByteArray(32) { 7 }
        val nonce = ByteArray(16) { 3 }

        val proof = crypto.pairingProof("123456", "device-a", publicKey, nonce)

        assertTrue(crypto.verifyPairingProof("123456", "device-a", publicKey, nonce, proof))
        assertFalse(crypto.verifyPairingProof("654321", "device-a", publicKey, nonce, proof), "Wrong PIN must fail")
        assertFalse(crypto.verifyPairingProof("123456", "device-b", publicKey, nonce, proof), "Wrong device id must fail")
        assertFalse(crypto.verifyPairingProof("123456", "device-a", ByteArray(32) { 8 }, nonce, proof), "Swapped key must fail")
        assertFalse(crypto.verifyPairingProof("123456", "device-a", publicKey, ByteArray(16) { 9 }, proof), "Different nonce must fail")
    }

    @Test
    fun pairingProof_cannotBeReplayedAgainstASwappedKey() = runTest {
        val nonce = ByteArray(16) { 3 }

        // A sniffer cannot reuse a proof captured for one key against another
        // key: the proof covers the public key bytes.
        val keyA = ByteArray(32) { 1 }
        val keyB = ByteArray(32) { 2 }
        val proofForA = crypto.pairingProof("000000", "device-a", keyA, nonce)
        assertFalse(crypto.verifyPairingProof("000000", "device-a", keyB, nonce, proofForA))
    }

    @Test
    fun fingerprint_isStableAndCompact() = runTest {
        val key = ByteArray(32) { 42 }
        assertEquals(crypto.fingerprintOf(key), crypto.fingerprintOf(key), "Fingerprint must be deterministic")

        // "A1B2-C3D4-E5F6-G7H8": four hex groups with dashes.
        val fingerprint = crypto.fingerprintOf(key)
        assertTrue(fingerprint.matches(Regex("[0-9A-F]{4}(-[0-9A-F]{4}){3}")), "Unexpected fingerprint format: $fingerprint")
        assertFalse(crypto.fingerprintOf(ByteArray(32) { 43 }) == fingerprint, "Different keys must differ")
    }

    @Test
    fun channelKeys_differAcrossExchanges() = runTest {
        val (alicePriv, alicePub) = crypto.generateKeyPair()
        val (bobPriv, bobPub) = crypto.generateKeyPair()
        val (ephA1, ephA1Pub) = crypto.generateKeyPair()
        val (ephA2, ephA2Pub) = crypto.generateKeyPair()

        val staticShared = crypto.deriveSharedSecret(alicePriv, bobPub)
        assertContentEquals(
            staticShared,
            crypto.deriveSharedSecret(bobPriv, alicePub),
            "Static secret must be symmetric"
        )

        // Two exchanges use different ephemeral keys -> different channels.
        val channelA = crypto.deriveChannelSecret(staticShared, crypto.deriveSharedSecret(ephA1, bobPub))
        val channelB = crypto.deriveChannelSecret(staticShared, crypto.deriveSharedSecret(ephA2, bobPub))
        assertFalse(channelA.contentEquals(channelB), "Each exchange must use a fresh channel key")
        assertEquals(32, channelA.size)

        // The peer side derives the identical channel from the same ephemeral key.
        assertContentEquals(
            channelA,
            crypto.deriveChannelSecret(crypto.deriveSharedSecret(bobPriv, alicePub), crypto.deriveSharedSecret(bobPriv, ephA1Pub))
        )
    }

    @Test
    fun pairingProof_matchesRfc4231TestVector() = runTest {
        // RFC 4231 test case 2: key = "Jefe", data = "what do ya want for nothing?"
        val expected = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
        val hmac = crypto.hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray())
        assertEquals(expected, hmac.joinToString("") { "%02x".format(it) })
    }
}

class SyncIdentityTest {

    @Test
    fun create_serialize_deserialize_roundTrips() = runTest {
        val crypto = SyncCrypto()
        val identity = SyncIdentity.create("Test Device", crypto)
        assertEquals("Test Device", identity.deviceName)
        assertTrue(identity.deviceId.isNotBlank())
        assertEquals(32, identity.privateKeyBytes.size)
        assertEquals(32, identity.publicKeyBytes.size)

        val restored = SyncIdentity.deserialize(SyncIdentity.serialize(identity))
        assertEquals(identity, restored)
        assertContentEquals(identity.privateKeyBytes, restored!!.privateKeyBytes)
    }

    @Test
    fun deviceIds_areUnique() = runTest {
        val crypto = SyncCrypto()
        val a = SyncIdentity.create("A", crypto)
        val b = SyncIdentity.create("B", crypto)
        assertFalse(a.deviceId == b.deviceId)
    }

    @Test
    fun deserialize_garbage_returnsNull() {
        assertFalse(SyncIdentity.deserialize("not json".encodeToByteArray()) != null)
    }
}
