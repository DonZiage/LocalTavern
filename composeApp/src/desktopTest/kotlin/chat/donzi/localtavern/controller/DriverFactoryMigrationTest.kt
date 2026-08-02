package chat.donzi.localtavern.controller

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.database.CharacterRepository
import chat.donzi.localtavern.data.database.DriverFactory
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.SyncPeer
import chat.donzi.localtavern.data.models.SillyTavernCardV2
import chat.donzi.localtavern.data.sync.SyncIdentity
import chat.donzi.localtavern.data.sync.SyncRepository
import chat.donzi.localtavern.domain.Character
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DriverFactoryMigrationTest {

    private val v1CharacterDdl = """
        CREATE TABLE CharacterEntity (
            id TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            description TEXT,
            personality TEXT,
            scenario TEXT,
            firstMes TEXT,
            mesExample TEXT,
            creatorNotes TEXT,
            altGreetings TEXT,
            avatarData BLOB,
            isAssistant INTEGER NOT NULL DEFAULT 0,
            updatedAt INTEGER NOT NULL DEFAULT 0,
            isDeleted INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE PersonaEntity (
            id TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            description TEXT,
            avatarData BLOB,
            updatedAt INTEGER NOT NULL DEFAULT 0,
            isDeleted INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE ChatSession (
            id TEXT PRIMARY KEY NOT NULL,
            characterId TEXT NOT NULL,
            personaId TEXT NOT NULL,
            title TEXT,
            lastTimestamp INTEGER NOT NULL,
            currentMessageId TEXT,
            parentSessionId TEXT,
            updatedAt INTEGER NOT NULL DEFAULT 0,
            isDeleted INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE MessageEntity (
            id TEXT PRIMARY KEY NOT NULL,
            sessionId TEXT NOT NULL,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            timestamp INTEGER NOT NULL,
            parentId TEXT,
            isActivePath INTEGER NOT NULL DEFAULT 1,
            updatedAt INTEGER NOT NULL DEFAULT 0,
            isDeleted INTEGER NOT NULL DEFAULT 0,
            imageData BLOB
        );
        CREATE TABLE ApiConnection (
            id TEXT PRIMARY KEY NOT NULL,
            provider TEXT NOT NULL,
            name TEXT NOT NULL,
            baseUrl TEXT,
            apiKey TEXT,
            model TEXT,
            isActive INTEGER NOT NULL DEFAULT 0,
            isChatCompletion INTEGER NOT NULL DEFAULT 1,
            lastUsed INTEGER DEFAULT 0,
            temperature REAL NOT NULL DEFAULT 1.0,
            topP REAL NOT NULL DEFAULT 1.0,
            topK INTEGER NOT NULL DEFAULT 0,
            presencePenalty REAL NOT NULL DEFAULT 0.0,
            frequencyPenalty REAL NOT NULL DEFAULT 0.0,
            contextLimit INTEGER NOT NULL DEFAULT 4096,
            responseLimit INTEGER NOT NULL DEFAULT 0,
            displayOrder INTEGER NOT NULL DEFAULT 0,
            timeoutLimit INTEGER NOT NULL DEFAULT 60,
            updatedAt INTEGER NOT NULL DEFAULT 0,
            isDeleted INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE AppSettings (
            id INTEGER PRIMARY KEY CHECK (id = 1) DEFAULT 1,
            isDarkMode INTEGER NOT NULL DEFAULT 1,
            activePersonaId TEXT
        );
        CREATE TABLE PromptBlockEntity (
            id TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            template TEXT NOT NULL,
            isEnabled INTEGER NOT NULL DEFAULT 1,
            isCustom INTEGER NOT NULL DEFAULT 0,
            displayOrder INTEGER NOT NULL DEFAULT 0,
            updatedAt INTEGER NOT NULL DEFAULT 0,
            isDeleted INTEGER NOT NULL DEFAULT 0
        );
    """.trimIndent()

    private fun tempDir(): File = Files.createTempDirectory("localtavern-test").toFile()

    @Test
    fun `existing v1 database is migrated to v2 and data survives`() = runTest {
        val tempDir = tempDir()
        try {
            val dbFile = File(tempDir, ".localtavern/local_tavern.db")
            dbFile.parentFile.mkdirs()

            // Simulate a database written by a previous release: v1 schema,
            // never-stamped user_version, and a stored character.
            val v1Driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
            try {
                // xerial's execute() runs a single statement; split the DDL
                // into one call per table.
                Regex("(?<=;)\\s*").split(v1CharacterDdl.trim())
                    .filter { it.isNotBlank() }
                    .forEach { statement -> v1Driver.execute(null, statement, 0) }
                v1Driver.execute(
                    null,
                    "INSERT INTO CharacterEntity(id, name, description, personality, scenario, firstMes, mesExample, creatorNotes, altGreetings, avatarData, isAssistant, updatedAt, isDeleted) " +
                        "VALUES ('c1', 'Old Character', 'desc', 'personality', 'scenario', 'Hello!', NULL, NULL, NULL, NULL, 0, 1, 0);",
                    0
                )
            } finally {
                v1Driver.close()
            }

            val oldUserHome = System.getProperty("user.home")
            System.setProperty("user.home", tempDir.absolutePath)
            try {
                val driver = DriverFactory().createDriver()
                try {
                    val database = LocalTavernDB(driver)
                    val repository = CharacterRepository(database)

                    val characters = repository.getAllCharacters()
                    assertEquals(1, characters.size)
                    val migrated = characters.first()
                    assertEquals("Old Character", migrated.name)
                    assertEquals("Hello!", migrated.firstMes)

                    // New columns are present and writable.
                    repository.upsertCharacter(
                        SillyTavernCardV2(
                            name = "New Character",
                            description = "d",
                            personality = "p",
                            scenario = "s",
                            first_mes = "Hi",
                            mes_example = "A<START>B",
                            creator_notes = "notes",
                            system_prompt = "sys",
                            post_history_instructions = "post",
                            creator = "creator",
                            character_version = "1.0",
                            tags = listOf("fantasy", "adventure"),
                            extensions = buildJsonObject { put("key", "value") },
                            character_book = buildJsonObject { put("book", true) }
                        )
                    )

                    val fresh = repository.getAllCharacters().first { it.name == "New Character" }
                    assertEquals("sys", fresh.systemPrompt)
                    assertEquals("post", fresh.postHistoryInstructions)
                    assertEquals("creator", fresh.creator)
                    assertEquals("1.0", fresh.characterVersion)
                    assertEquals(listOf("fantasy", "adventure"), fresh.tags)
                    assertEquals("value", fresh.extensions?.get("key")?.jsonPrimitive?.content)
                    assertNotNull(fresh.characterBook)

                    // <START>-separated examples are split on read-back.
                    assertEquals(listOf("A", "B"), fresh.mesExample)
                } finally {
                    driver.close()
                }
            } finally {
                System.setProperty("user.home", oldUserHome)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `fresh database is created at the latest schema`() = runTest {
        val tempDir = tempDir()
        try {
            val oldUserHome = System.getProperty("user.home")
            System.setProperty("user.home", tempDir.absolutePath)
            try {
                val driver = DriverFactory().createDriver()
                try {
                    val database = LocalTavernDB(driver)
                    val repository = CharacterRepository(database)
                    repository.upsertCharacter(
                        SillyTavernCardV2(
                            name = "Fresh",
                            system_prompt = "round trip",
                            creator = "me",
                            tags = listOf("a")
                        )
                    )
                    val character = repository.getAllCharacters().first()
                    assertEquals("Fresh", character.name)
                    assertEquals("round trip", character.systemPrompt)
                    assertEquals("me", character.creator)
                    assertEquals(listOf("a"), character.tags)
                } finally {
                    driver.close()
                }
            } finally {
                System.setProperty("user.home", oldUserHome)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `character editor save preserves round-trip fields`() = runTest {
        val tempDir = tempDir()
        try {
            val oldUserHome = System.getProperty("user.home")
            System.setProperty("user.home", tempDir.absolutePath)
            try {
                val driver = DriverFactory().createDriver()
                try {
                    val database = LocalTavernDB(driver)
                    val repository = CharacterRepository(database)

                    val id = repository.upsertCharacter(
                        SillyTavernCardV2(
                            name = "Rich",
                            system_prompt = "keep me",
                            creator = "someone",
                            tags = listOf("t1")
                        )
                    )
                    repository.updateCharacter(
                        id = id,
                        name = "Rich Renamed",
                        personality = "p",
                        scenario = "s",
                        description = null,
                        firstMes = "Hi",
                    )
                    val updated = repository.getAllCharacters().first { it.id == id }
                    assertEquals("Rich Renamed", updated.name)
                    assertEquals("keep me", updated.systemPrompt)
                    assertEquals("someone", updated.creator)
                    assertEquals(listOf("t1"), updated.tags)
                } finally {
                    driver.close()
                }
            } finally {
                System.setProperty("user.home", oldUserHome)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `existing v2 database migrates to v4 and gains working sync tables`() = runTest {
        val tempDir = tempDir()
        try {
            val dbFile = File(tempDir, ".localtavern/local_tavern.db")
            dbFile.parentFile.mkdirs()

            // A database written by the current release: v2 schema (no sync
            // tables, no reasoning/cost columns) and stored data.
            val v2Driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
            try {
                Regex("(?<=;)\\s*").split(v1CharacterDdl.trim())
                    .filter { it.isNotBlank() }
                    .forEach { statement -> v2Driver.execute(null, statement, 0) }
                // v1 -> v2 columns.
                v2Driver.execute(null, "ALTER TABLE CharacterEntity ADD COLUMN systemPrompt TEXT;", 0)
                v2Driver.execute(null, "ALTER TABLE CharacterEntity ADD COLUMN postHistoryInstructions TEXT;", 0)
                v2Driver.execute(null, "ALTER TABLE CharacterEntity ADD COLUMN creator TEXT;", 0)
                v2Driver.execute(null, "ALTER TABLE CharacterEntity ADD COLUMN characterVersion TEXT;", 0)
                v2Driver.execute(null, "ALTER TABLE CharacterEntity ADD COLUMN tags TEXT;", 0)
                v2Driver.execute(null, "ALTER TABLE CharacterEntity ADD COLUMN extensions TEXT;", 0)
                v2Driver.execute(null, "ALTER TABLE CharacterEntity ADD COLUMN characterBook TEXT;", 0)
                v2Driver.execute(null, "PRAGMA user_version = 2;", 0)
                v2Driver.execute(
                    null,
                    "INSERT INTO PersonaEntity(id, name, description, avatarData, updatedAt, isDeleted) VALUES ('p1', 'Persisted', 'desc', NULL, 5, 0);",
                    0
                )
            } finally {
                v2Driver.close()
            }

            val oldUserHome = System.getProperty("user.home")
            System.setProperty("user.home", tempDir.absolutePath)
            try {
                val driver = DriverFactory().createDriver()
                try {
                    val database = LocalTavernDB(driver)

                    // Pre-existing data survives the migration.
                    val personas = database.localTavernDBQueries.selectAllPersonas().executeAsList()
                    assertEquals("Persisted", personas.first().name)

                    // The sync tables exist and are fully functional.
                    val identity = SyncIdentity(
                        deviceId = "device-a",
                        deviceName = "A",
                        privateKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 1 }),
                        publicKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 2 })
                    )
                    val syncRepository = SyncRepository(database, identity)
                    syncRepository.upsertPeer(
                        SyncPeer(
                            deviceId = "peer-1", name = "Laptop", publicKey = ByteArray(32) { 9 },
                            lastKnownAddress = "10.0.0.2:47324", receivedCursor = 0L,
                            peerReceivedCursor = 0L, lastSyncAt = 0L, updatedAt = 0L, isDeleted = 0L
                        )
                    )
                    val peer = syncRepository.getPeer("peer-1")
                    assertNotNull(peer)
                    assertEquals("Laptop", peer.name)
                    assertEquals(32, peer.publicKey?.size)
                } finally {
                    driver.close()
                }
            } finally {
                System.setProperty("user.home", oldUserHome)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `dev v3 database with legacy xPublicKey column migrates to v4`() = runTest {
        val tempDir = tempDir()
        try {
            val dbFile = File(tempDir, ".localtavern/local_tavern.db")
            dbFile.parentFile.mkdirs()

            // Intermediate dev build schema: v3 with the duplicate xPublicKey
            // column that the v3 -> v4 migration must drop.
            val v3Driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
            try {
                Regex("(?<=;)\\s*").split(v1CharacterDdl.trim())
                    .filter { it.isNotBlank() }
                    .forEach { statement -> v3Driver.execute(null, statement, 0) }
                v3Driver.execute(null, "ALTER TABLE CharacterEntity ADD COLUMN systemPrompt TEXT;", 0)
                v3Driver.execute(null, "ALTER TABLE CharacterEntity ADD COLUMN postHistoryInstructions TEXT;", 0)
                v3Driver.execute(null, "ALTER TABLE CharacterEntity ADD COLUMN creator TEXT;", 0)
                v3Driver.execute(null, "ALTER TABLE CharacterEntity ADD COLUMN characterVersion TEXT;", 0)
                v3Driver.execute(null, "ALTER TABLE CharacterEntity ADD COLUMN tags TEXT;", 0)
                v3Driver.execute(null, "ALTER TABLE CharacterEntity ADD COLUMN extensions TEXT;", 0)
                v3Driver.execute(null, "ALTER TABLE CharacterEntity ADD COLUMN characterBook TEXT;", 0)
                v3Driver.execute(null, "ALTER TABLE MessageEntity ADD COLUMN reasoningText TEXT;", 0)
                v3Driver.execute(null, "ALTER TABLE MessageEntity ADD COLUMN costEstimate REAL;", 0)
                v3Driver.execute(null, "ALTER TABLE ApiConnection ADD COLUMN reasoningOverride INTEGER NOT NULL DEFAULT 0;", 0)
                v3Driver.execute(
                    null,
                    "CREATE TABLE ModelPricing (provider TEXT NOT NULL, modelPattern TEXT NOT NULL, inputPerMillion REAL NOT NULL, outputPerMillion REAL NOT NULL, currency TEXT NOT NULL DEFAULT 'USD', PRIMARY KEY (provider, modelPattern));",
                    0
                )
                v3Driver.execute(
                    null,
                    "CREATE TABLE SyncPeer (deviceId TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, publicKey BLOB, xPublicKey BLOB, lastKnownAddress TEXT, receivedCursor INTEGER NOT NULL DEFAULT 0, peerReceivedCursor INTEGER NOT NULL DEFAULT 0, lastSyncAt INTEGER NOT NULL DEFAULT 0, updatedAt INTEGER NOT NULL DEFAULT 0, isDeleted INTEGER NOT NULL DEFAULT 0);",
                    0
                )
                v3Driver.execute(
                    null,
                    "INSERT INTO SyncPeer(deviceId, name, publicKey, xPublicKey, lastKnownAddress, receivedCursor, peerReceivedCursor, lastSyncAt, updatedAt, isDeleted) VALUES ('peer-1', 'Old Dev Peer', X'01020304', X'AA', '10.0.0.9:47324', 0, 0, 0, 0, 0);",
                    0
                )
                v3Driver.execute(null, "PRAGMA user_version = 3;", 0)
            } finally {
                v3Driver.close()
            }

            val oldUserHome = System.getProperty("user.home")
            System.setProperty("user.home", tempDir.absolutePath)
            try {
                val driver = DriverFactory().createDriver()
                try {
                    val database = LocalTavernDB(driver)

                    // The peer survives the migration...
                    val peer = database.localTavernDBQueries.selectSyncPeerAny("peer-1").executeAsOneOrNull()
                    assertNotNull(peer)
                    assertEquals("Old Dev Peer", peer.name)

                    // ...and the duplicate column is gone.
                    val columns = driver.executeQuery(
                        null,
                        "SELECT name FROM pragma_table_info('SyncPeer');",
                        { cursor ->
                            val names = mutableListOf<String>()
                            while (cursor.next().value == true) names.add(cursor.getString(0)!!)
                            app.cash.sqldelight.db.QueryResult.Value(names)
                        },
                        0
                    ).value
                    assertTrue("xPublicKey" !in columns, "xPublicKey must be dropped by the v3 -> v4 migration")

                    // The app-facing sync queries work against the migrated row.
                    val identity = SyncIdentity(
                        deviceId = "device-a",
                        deviceName = "A",
                        privateKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 1 }),
                        publicKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 2 })
                    )
                    val syncRepository = SyncRepository(database, identity)
                    assertEquals("Old Dev Peer", syncRepository.getPeer("peer-1")?.name)
                } finally {
                    driver.close()
                }
            } finally {
                System.setProperty("user.home", oldUserHome)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `character domain round-trips through export json`() {
        val character = Character(
            id = "x",
            name = "Card",
            description = "d",
            personality = "p",
            scenario = "s",
            firstMes = "Hello",
            mesExample = listOf("A", "B"),
            creatorNotes = "notes",
            altGreetings = listOf("Alt 1"),
            avatarData = null,
            systemPrompt = "system",
            postHistoryInstructions = "post",
            creator = "creator",
            characterVersion = "2.0",
            tags = listOf("tag1"),
            extensions = buildJsonObject { put("ext", "value") },
            characterBook = buildJsonObject { put("book", "yes") }
        )
        val jsonString = chat.donzi.localtavern.utils.CharacterManager.exportToJson(character).decodeToString()
        val parsed = Json { ignoreUnknownKeys = true }.parseToJsonElement(jsonString)
        val data = parsed.jsonObject["data"]!!.jsonObject
        assertEquals("Card", data["name"]!!.jsonPrimitive.content)
        assertEquals("A<START>B", data["mes_example"]!!.jsonPrimitive.content)
        assertEquals("system", data["system_prompt"]!!.jsonPrimitive.content)
        assertEquals("post", data["post_history_instructions"]!!.jsonPrimitive.content)
        assertEquals("creator", data["creator"]!!.jsonPrimitive.content)
        assertEquals("2.0", data["character_version"]!!.jsonPrimitive.content)
        assertEquals("tag1", data["tags"]!!.jsonArray.first().jsonPrimitive.content)
        assertTrue(data.containsKey("extensions"))
        assertTrue(data.containsKey("character_book"))
    }
}
