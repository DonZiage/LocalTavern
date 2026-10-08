package chat.donzi.localtavern.data.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.security.ApiKeyCipher
import chat.donzi.localtavern.data.security.SecretCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

// A new API key must never be silently discarded: when the security store is
// configured but unavailable (lost keystore/keychain key, corrupt passphrase
// file), saving a connection with a new key must REPORT the failure instead
// of quietly keeping the previous key while the UI claims the save happened.
class ApiConnectionKeySaveTest {

    // Reversible fake whose availability can be switched off mid-test, like a
    // keystore losing its key after a factory reset.
    private class FlakySecretCrypto : SecretCrypto {
        var available = true
        override val isAvailable: Boolean get() = available
        override val isProtected: Boolean get() = true
        override val backendName: String get() = "FlakyTest"
        override fun encrypt(plaintext: String): String? = if (available) "X($plaintext)" else null
        override fun decrypt(payload: String): String? =
            if (payload.startsWith("X(") && payload.endsWith(")")) payload.removeSurrounding("X(", ")") else payload
        override fun unlock(passphrase: String): Boolean = true
        override fun protect(passphrase: String) = Unit
        override fun removeProtection() = Unit
        override fun lock() {
            available = false
        }
    }

    private fun newRepo(crypto: FlakySecretCrypto): ApiSettingsRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { LocalTavernDB.Schema.create(it) }
        return ApiSettingsRepository(LocalTavernDB(driver), ApiKeyCipher(crypto))
    }

    @Test
    fun updateWithNewKey_whenStoreUnavailable_reportsFailureAndKeepsOldKey() = runTest {
        val crypto = FlakySecretCrypto()
        val repo = newRepo(crypto)
        val id = repo.insertApiConnection(
            provider = "p", name = "n", baseUrl = null, apiKey = "sk-old",
            model = "m", isActive = true
        )

        crypto.available = false
        val stored = repo.updateApiConnection(
            id = id, provider = "p", name = "n", baseUrl = null, apiKey = "sk-new",
            model = "m", isActive = true, chatCompletionMode = 0, temperature = 1.0, topP = 1.0,
            topK = 0L, presencePenalty = 0.0, frequencyPenalty = 0.0, contextLimit = 4096L,
            responseLimit = 1024L, displayOrder = 0L, timeoutLimit = 60L
        )
        assertFalse(stored, "A key that could not be encrypted must be reported as not saved")

        val after = repo.getAllApiConnections().first()
        assertEquals("sk-old", after.apiKey, "The previous key must be kept, never wiped or downgraded to plaintext")
    }

    @Test
    fun updateWithUnchangedKey_doesNotFail() = runTest {
        val crypto = FlakySecretCrypto()
        val repo = newRepo(crypto)
        val id = repo.insertApiConnection(
            provider = "p", name = "n", baseUrl = null, apiKey = "sk-old",
            model = "m", isActive = true
        )

        crypto.available = false
        // Parameter sliders re-save the connection with the (decrypted) stored
        // key: unchanged keys must not be treated as failed saves.
        val stored = repo.updateApiConnection(
            id = id, provider = "p", name = "n", baseUrl = null, apiKey = "sk-old",
            model = "m", isActive = true, chatCompletionMode = 0, temperature = 1.0, topP = 1.0,
            topK = 0L, presencePenalty = 0.0, frequencyPenalty = 0.0, contextLimit = 4096L,
            responseLimit = 1024L, displayOrder = 0L, timeoutLimit = 60L
        )
        assertTrue(stored, "An unchanged key must never be reported as failed")
        assertEquals("sk-old", repo.getAllApiConnections().first().apiKey)
    }

    @Test
    fun updateWithNewKey_whenStoreWorks_storesEncrypted() = runTest {
        val crypto = FlakySecretCrypto()
        val repo = newRepo(crypto)
        val id = repo.insertApiConnection(
            provider = "p", name = "n", baseUrl = null, apiKey = "sk-old",
            model = "m", isActive = true
        )

        val stored = repo.updateApiConnection(
            id = id, provider = "p", name = "n", baseUrl = null, apiKey = "sk-new",
            model = "m", isActive = true, chatCompletionMode = 0, temperature = 1.0, topP = 1.0,
            topK = 0L, presencePenalty = 0.0, frequencyPenalty = 0.0, contextLimit = 4096L,
            responseLimit = 1024L, displayOrder = 0L, timeoutLimit = 60L
        )
        assertTrue(stored)
        assertEquals("sk-new", repo.getAllApiConnections().first().apiKey)
    }
}
