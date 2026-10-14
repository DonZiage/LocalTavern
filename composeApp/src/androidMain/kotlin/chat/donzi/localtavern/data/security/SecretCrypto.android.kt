package chat.donzi.localtavern.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// API keys are encrypted with a non-exportable AES key held by the
// AndroidKeyStore. The key never leaves the keystore; the database only ever
// sees "ltv1:<base64(iv||ciphertext)>" blobs.
actual fun createSecretCrypto(): SecretCrypto = AndroidKeyStoreSecretCrypto()

class AndroidKeyStoreSecretCrypto : SecretCrypto {

    private companion object {
        const val KEY_ALIAS = "localtavern_master_key"
        const val GCM_TAG_BITS = 128
        const val IV_SIZE = 12
    }

    override val isAvailable: Boolean get() = true
    override val isProtected: Boolean get() = true
    override val backendName: String get() = "Android Keystore"

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    override fun encrypt(plaintext: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // IV (12 bytes) precedes the ciphertext; GCM appends its tag to it.
        Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }.getOrNull()

    override fun decrypt(payload: String): String? = runCatching {
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        if (bytes.size < IV_SIZE + 16) return@runCatching payload
        val iv = bytes.copyOfRange(0, IV_SIZE)
        val ciphertext = bytes.copyOfRange(IV_SIZE, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrElse { payload }

    override fun unlock(passphrase: String): Boolean = true
    override fun protect(passphrase: String) = Unit
    override fun removeProtection() = Unit
    override fun lock() = Unit
}
