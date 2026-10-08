package chat.donzi.localtavern.data.sync

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.jdk.JDK
import kotlinx.coroutines.test.runTest
import org.bouncycastle.jce.provider.BouncyCastleProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Runs the shared sync crypto through the BouncyCastle-backed JDK provider —
// the EXACT crypto stack Android uses on-device (see androidMain: the
// cryptography-provider-jdk-bc artifact). The common SyncCryptoTest exercises
// the default desktop provider; this one proves the algorithms Android
// depends on (X25519, HKDF-SHA256, AES-256-GCM, PBKDF2-HMAC-SHA256, SHA-256)
// behave identically through BC, so an Android-only regression cannot slip
// through the desktop-only test suite.
class SyncCryptoBouncyCastleTest {

    private val bcCrypto = SyncCrypto(CryptographyProvider.JDK(BouncyCastleProvider()))

    @Test
    fun keyAgreement_derivesSameSecretBothDirections() = runTest {
        val (alicePriv, alicePub) = bcCrypto.generateKeyPair()
        val (bobPriv, bobPub) = bcCrypto.generateKeyPair()

        val aliceSecret = bcCrypto.deriveSharedSecret(alicePriv, bobPub)
        val bobSecret = bcCrypto.deriveSharedSecret(bobPriv, alicePub)

        assertContentEquals(aliceSecret, bobSecret)
        assertEquals(32, aliceSecret.size)
    }

    @Test
    fun keyAgreement_differsPerPeer() = runTest {
        val (alicePriv, _) = bcCrypto.generateKeyPair()
        val (_, bobPub) = bcCrypto.generateKeyPair()
        val (_, carolPub) = bcCrypto.generateKeyPair()

        val withBob = bcCrypto.deriveSharedSecret(alicePriv, bobPub)
        val withCarol = bcCrypto.deriveSharedSecret(alicePriv, carolPub)
        assertFalse(withBob.contentEquals(withCarol))
    }

    @Test
    fun channelSecrets_deriveFreshKeysPerExchange() = runTest {
        val staticShared = ByteArray(32) { 7 }
        val (_, ephA) = bcCrypto.generateKeyPair()
        val (_, ephB) = bcCrypto.generateKeyPair()

        val keyA = bcCrypto.deriveChannelSecret(staticShared, ephA)
        val keyB = bcCrypto.deriveChannelSecret(staticShared, ephB)
        assertEquals(32, keyA.size)
        assertFalse(keyA.contentEquals(keyB), "Different ephemeral exchanges must derive different channel keys")
    }

    @Test
    fun encryptDecrypt_roundTripsWithAssociatedData() = runTest {
        val (alicePriv, alicePub) = bcCrypto.generateKeyPair()
        val (bobPriv, bobPub) = bcCrypto.generateKeyPair()
        val secret = bcCrypto.deriveSharedSecret(alicePriv, bobPub)
        val aad = "localtavern-sync|from=a|to=b|ex=1".encodeToByteArray()

        val plaintext = "secret data".encodeToByteArray()
        val encrypted = bcCrypto.encrypt(secret, aad, plaintext)

        assertTrue(encrypted.size > 12)
        assertFalse(encrypted.contentEquals(plaintext))
        assertContentEquals(plaintext, bcCrypto.decrypt(secret, aad, encrypted))
    }

    @Test
    fun decrypt_withWrongAssociatedData_fails() = runTest {
        val (alicePriv, bobPub) = bcCrypto.generateKeyPair()
        val (bobPriv, _) = bcCrypto.generateKeyPair()
        val secret = bcCrypto.deriveSharedSecret(alicePriv, bobPub)
        val encrypted = bcCrypto.encrypt(secret, "aad-one".encodeToByteArray(), "data".encodeToByteArray())

        val tampered = kotlin.runCatching {
            bcCrypto.decrypt(secret, "aad-two".encodeToByteArray(), encrypted)
        }
        assertTrue(tampered.isFailure, "GCM tag must fail when the associated data is tampered with")
    }

    @Test
    fun pairingProof_verifiesWithRightPinOnly() = runTest {
        val (_, pub) = bcCrypto.generateKeyPair()
        val nonce = ByteArray(16) { 3 }

        val proof = bcCrypto.pairingProof("123456", "device-a", pub, nonce)
        assertTrue(bcCrypto.verifyPairingProof("123456", "device-a", pub, nonce, proof))
        assertFalse(bcCrypto.verifyPairingProof("654321", "device-a", pub, nonce, proof), "Wrong PIN must not verify")
        assertFalse(
            bcCrypto.verifyPairingProof("123456", "device-b", pub, nonce, proof),
            "A proof bound to another device id must not verify"
        )
    }

    @Test
    fun pairingProof_isBoundToNonceAndKey() = runTest {
        val (_, pub) = bcCrypto.generateKeyPair()
        val (_, otherPub) = bcCrypto.generateKeyPair()
        val nonce = ByteArray(16) { 5 }

        val proof = bcCrypto.pairingProof("123456", "device-a", pub, nonce)
        assertFalse(
            bcCrypto.verifyPairingProof("123456", "device-a", otherPub, nonce, proof),
            "A proof for a different public key must not verify"
        )
        assertFalse(
            bcCrypto.verifyPairingProof("123456", "device-a", pub, ByteArray(16) { 6 }, proof),
            "A proof under a different nonce must not verify"
        )
    }

    @Test
    fun fingerprints_areDeterministicAndOrderIndependent() = runTest {
        val (_, pubA) = bcCrypto.generateKeyPair()
        val (_, pubB) = bcCrypto.generateKeyPair()

        val forward = bcCrypto.pairingFingerprint(pubA, pubB)
        val reverse = bcCrypto.pairingFingerprint(pubB, pubA)
        assertEquals(forward, reverse, "Both pairing devices must display the same fingerprint")
        assertEquals(19, forward.length, "16 hex chars grouped by dashes")

        assertEquals(forward, bcCrypto.pairingFingerprint(pubA, pubB), "Fingerprints must be deterministic")
        val solo = bcCrypto.fingerprintOf(pubA)
        assertEquals(19, solo.length)
    }

    @Test
    fun hmacSha256_matchesKnownVector() = runTest {
        // RFC 4231 test case 1: HMAC-SHA256(key="Jefe", data="what do ya want for nothing?")
        val key = "Jefe".encodeToByteArray()
        val data = "what do ya want for nothing?".encodeToByteArray()
        val expected = hexDecode(
            "5bdcc146bf60754e6a042426089575c7" +
                "5a003f089d2739839dec58b964ec3843"
        )
        assertContentEquals(expected, bcCrypto.hmacSha256(key, data))
    }

    private fun hexDecode(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index ->
            ((Character.digit(hex[index * 2], 16) shl 4) or Character.digit(hex[index * 2 + 1], 16)).toByte()
        }
}
