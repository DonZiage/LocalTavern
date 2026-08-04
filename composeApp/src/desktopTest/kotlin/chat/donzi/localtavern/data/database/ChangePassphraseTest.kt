package chat.donzi.localtavern.data.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.security.ApiKeyCipher
import chat.donzi.localtavern.data.security.DesktopPassphraseSecretCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

// changePassphrase is the trickiest desktop flow: keys are encrypted under
// the OLD derived key and re-encrypted under the NEW one, and the in-memory
// key is replaced by protect() — so decryption must happen BEFORE the new
// key is derived, or every stored key is permanently lost.
class ChangePassphraseTest {

    private fun tempDir(): java.io.File =
        java.nio.file.Files.createTempDirectory("localtavern-changepass").toFile()

    private fun newRepo(dir: java.io.File): Triple<ApiSettingsRepository, DesktopPassphraseSecretCrypto, LocalTavernDB> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { LocalTavernDB.Schema.create(it) }
        val crypto = DesktopPassphraseSecretCrypto(dir)
        val database = LocalTavernDB(driver)
        return Triple(ApiSettingsRepository(database, ApiKeyCipher(crypto)), crypto, database)
    }

    @Test
    fun changePassphrase_wrongCurrent_leavesEverythingUntouched() = runTest {
        val dir = tempDir()
        try {
            val (repo, crypto, _) = newRepo(dir)
            crypto.protect("old pass")
            repo.reencryptAllApiKeys()
            repo.insertApiConnection(provider = "p", name = "n", baseUrl = null, apiKey = "sk-1", model = "m", isActive = true)

            assertFalse(repo.changePassphrase("wrong", "new pass"))
            assertTrue(crypto.isAvailable, "A failed change must not drop the current key")
            val after = repo.getAllApiConnections().first()
            assertEquals("sk-1", after.apiKey, "Keys must still decrypt with the old passphrase")
            assertTrue(crypto.unlock("old pass"), "The old passphrase must keep working")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun changePassphrase_reencryptsAllKeysUnderNewKey() = runTest {
        val dir = tempDir()
        try {
            val (repo, crypto, database) = newRepo(dir)
            crypto.protect("old pass")
            repo.reencryptAllApiKeys()
            repo.insertApiConnection(provider = "p", name = "n", baseUrl = null, apiKey = "sk-1", model = "m", isActive = true)
            repo.insertApiConnection(provider = "p2", name = "n2", baseUrl = null, apiKey = "sk-2", model = "m2", isActive = false)

            assertTrue(repo.changePassphrase("old pass", "new pass"))
            assertTrue(crypto.unlock("new pass"), "The protection file must now verify against the new passphrase")

            // A fresh backend (as after a restart) must unlock only with the
            // new passphrase and decrypt every stored key.
            val fresh = DesktopPassphraseSecretCrypto(dir)
            assertFalse(fresh.unlock("old pass"), "The old passphrase must be dead after a change")
            assertTrue(fresh.unlock("new pass"))
            val apiKeyCipher = ApiKeyCipher(fresh)

            // Prove the RAW stored blobs (as written to the database) decrypt
            // under the new key: read them without going through the repo.
            val rawKeys = database.localTavernDBQueries.selectAllApiConnections().executeAsList().map { it.apiKey }
            rawKeys.forEach { stored ->
                assertTrue(stored!!.startsWith("ltv1:"), "Keys must be stored encrypted after a change")
                assertTrue(apiKeyCipher.decryptFromStorage(stored) in listOf("sk-1", "sk-2"))
            }
            val keys = repo.getAllApiConnections().map { it.apiKey }
            assertEquals(listOf("sk-1", "sk-2"), keys)
        } finally {
            dir.deleteRecursively()
        }
    }
}
