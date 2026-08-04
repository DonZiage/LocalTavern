package chat.donzi.localtavern.data.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.Security.SecItemCopyMatching
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateDecryptedData
import platform.Security.SecKeyCreateEncryptedData
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyRef
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrIsExtractable
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecAttrLabel
import platform.Security.kSecClass
import platform.Security.kSecClassKey
import platform.Security.kSecReturnRef
import platform.Security.kSecKeyAlgorithmECIESEncryptionCofactorVariableIVX963SHA256AESGCM
import platform.posix.memcpy

// API keys are encrypted with a P-256 EC keypair that lives only in the iOS
// Keychain (non-extractable). Payloads use ECIES with AES-GCM (authenticated
// encryption). The database stores "ltv1:<base64(encrypted)>" blobs; without
// the keychain the blobs are unreadable but never destroyed.
actual fun createSecretCrypto(): SecretCrypto = IosKeychainSecretCrypto()

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
class IosKeychainSecretCrypto : SecretCrypto {

    private companion object {
        const val KEY_LABEL = "chat.donzi.localtavern.master"
    }

    override val isAvailable: Boolean get() = true
    override val isProtected: Boolean get() = true
    override val backendName: String get() = "iOS Keychain"

    private fun cfString(value: String): CFTypeRef? =
        CFStringCreateWithCString(null, value, kCFStringEncodingUTF8)

    private fun baseQuery(): CFMutableDictionaryRef? {
        val query = CFDictionaryCreateMutable(null, 5, null, null)
        CFDictionaryAddValue(query, kSecClass, kSecClassKey)
        CFDictionaryAddValue(query, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
        CFDictionaryAddValue(query, kSecAttrKeyClass, kSecAttrKeyClassPrivate)
        CFDictionaryAddValue(query, kSecAttrLabel, cfString(KEY_LABEL))
        CFDictionaryAddValue(query, kSecReturnRef, kCFBooleanTrue)
        return query
    }

    // The keypair is generated once and kept permanently in the keychain;
    // the private key is non-extractable, the public key is derived from it.
    private fun getOrCreatePrivateKey(): SecKeyRef? {
        val existing = memScoped {
            val out = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(baseQuery() as CFDictionaryRef?, out.ptr)
            if (status == errSecSuccess) out.value as SecKeyRef? else null
        }
        if (existing != null) return existing

        val params = CFDictionaryCreateMutable(null, 6, null, null)
        CFDictionaryAddValue(params, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
        CFDictionaryAddValue(params, kSecAttrKeySizeInBits, cfNumberInt(256))
        CFDictionaryAddValue(params, kSecAttrIsPermanent, kCFBooleanTrue)
        CFDictionaryAddValue(params, kSecAttrLabel, cfString(KEY_LABEL))
        CFDictionaryAddValue(params, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        CFDictionaryAddValue(params, kSecAttrIsExtractable, kCFBooleanFalse)
        return SecKeyCreateRandomKey(params as CFDictionaryRef?, null)
    }

    override fun encrypt(plaintext: String): String? {
        val privateKey = getOrCreatePrivateKey() ?: return null
        val publicKey = SecKeyCopyPublicKey(privateKey) ?: return null
        return runCatching {
            val plain = plaintext.encodeToByteArray()
            val data = plain.toNSData()
            val encrypted = SecKeyCreateEncryptedData(
                publicKey,
                kSecKeyAlgorithmECIESEncryptionCofactorVariableIVX963SHA256AESGCM,
                CFBridgingRetain(data) as CFDataRef?,
                null
            ) ?: return null
            (CFBridgingRelease(encrypted) as? NSData)?.toByteArray()?.let { base64Encode(it) }
        }.getOrNull()
    }

    override fun decrypt(payload: String): String? {
        val privateKey = getOrCreatePrivateKey() ?: return payload
        return runCatching {
            val bytes = base64Decode(payload)
            if (bytes.isEmpty()) return@runCatching payload
            val data = bytes.toNSData()
            val decrypted = SecKeyCreateDecryptedData(
                privateKey,
                kSecKeyAlgorithmECIESEncryptionCofactorVariableIVX963SHA256AESGCM,
                CFBridgingRetain(data) as CFDataRef?,
                null
            ) ?: return@runCatching payload
            val plain = (CFBridgingRelease(decrypted) as? NSData)?.toByteArray() ?: return@runCatching payload
            plain.decodeToString()
        }.getOrElse { payload }
    }

    private fun base64Encode(bytes: ByteArray): String =
        bytes.toNSData().base64EncodedStringWithOptions(0uL)

    private fun base64Decode(text: String): ByteArray =
        NSData.create(base64EncodedString = text, options = 0uL)?.toByteArray() ?: ByteArray(0)

    private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
        NSData.dataWithBytes(bytes = pinned.addressOf(0), length = size.toULong())
    }

    private fun NSData.toByteArray(): ByteArray {
        val length = this.length.toInt()
        if (length == 0) return ByteArray(0)
        val bytes = ByteArray(length)
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), this.bytes, length.convert())
        }
        return bytes
    }

    override fun unlock(passphrase: String): Boolean = true
    override fun protect(passphrase: String) = Unit
    override fun removeProtection() = Unit
    override fun lock() = Unit
}

@OptIn(ExperimentalForeignApi::class)
private fun cfNumberInt(value: Int): CFTypeRef? = memScoped {
    val number = alloc<IntVar>().also { it.value = value }
    platform.CoreFoundation.CFNumberCreate(null, kCFNumberIntType, number.ptr)
}

private const val errSecSuccess = 0
