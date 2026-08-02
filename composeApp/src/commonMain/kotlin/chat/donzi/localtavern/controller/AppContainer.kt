package chat.donzi.localtavern.controller

import chat.donzi.localtavern.data.database.ApiSettingsRepository
import chat.donzi.localtavern.data.database.CharacterRepository
import chat.donzi.localtavern.data.database.DriverFactory
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.SessionRepository
import chat.donzi.localtavern.data.network.ChatClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json

class AppContainer(driverFactory: DriverFactory) {
    val database: LocalTavernDB = LocalTavernDB(driverFactory.createDriver())
    val characterRepository: CharacterRepository = CharacterRepository(database)
    val sessionRepository: SessionRepository = SessionRepository(database)
    val apiSettingsRepository: ApiSettingsRepository = ApiSettingsRepository(database)

    val httpClient: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(HttpTimeout) {
            // Total request timeout is disabled so long-running SSE streams are not killed.
            requestTimeoutMillis = 0
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
    }
    val chatClient: ChatClient = ChatClient(httpClient)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val chatController: ChatController = ChatController(sessionRepository, apiSettingsRepository, chatClient, appScope)
    val appState: AppState = AppState(characterRepository, apiSettingsRepository, sessionRepository, appScope)

    // Cancels all app-level coroutines (generation, flows, settings writes)
    // and releases the HTTP client. Called when the composition is disposed,
    // e.g. on Android activity recreation: without this, an in-flight
    // generation keeps running in a leaked scope and its stop button in the
    // new UI silently does nothing.
    fun close() {
        appScope.cancel()
        httpClient.close()
    }
}
