package chat.donzi.localtavern.data.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.security.ApiKeyCipher
import chat.donzi.localtavern.data.security.SecretCrypto
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

// Red-team: insert must not silently drop the key when encryption is unavailable.
class ApiConnectionInsertKeyLossRedTeamTest {

    private class LockedCrypto : SecretCrypto {
        override val isAvailable = true
        override val isProtected = true
        override val backendName = "Locked"
        override fun encrypt(plaintext: String): String? = null // locked: cannot encrypt
        override fun decrypt(payload: String): String? = payload
        override fun unlock(passphrase: String): Boolean = false
        override fun protect(passphrase: String) {}
        override fun removeProtection() {}
        override fun lock() {}
    }

    @Test
    fun insertWhileLocked_doesNotSilentlyLoseKey() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        val db = LocalTavernDB(driver)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = ApiSettingsRepository(db, ApiKeyCipher(LockedCrypto()), dispatcher)
        // Must fail LOUDLY (throw) instead of inserting a keyless row with a
        // success return — the typed secret must never silently vanish.
        assertFailsWith<IllegalStateException> {
            repo.insertApiConnection(
                provider = "test", name = "T", baseUrl = "https://example.com",
                apiKey = "sk-live-secret", model = "m", isActive = true
            )
        }
        // And no keyless row may have been left behind.
        assert(repo.getAllApiConnections().none { it.name == "T" })
    }
}
