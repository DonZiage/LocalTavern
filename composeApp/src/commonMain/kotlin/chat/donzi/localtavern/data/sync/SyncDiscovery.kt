package chat.donzi.localtavern.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

// A device announced on the local network. [address] is the sender's IP as
// seen by the UDP packet; it may differ from the sync server's view if the
// device has multiple interfaces, so it is best-effort for reconnection.
data class DiscoveredPeer(
    val deviceId: String,
    val deviceName: String,
    val syncPort: Int,
    val address: String,
    val lastSeen: Long
)

@Serializable
data class DiscoveryAnnouncement(
    val deviceId: String,
    val deviceName: String,
    val syncPort: Int
)

// Minimal UDP socket abstraction. expect/actual because Android and the JVM
// share java.net.DatagramSocket while other targets may not support it.
expect fun createDiscoverySocket(): DiscoverySocket

interface DiscoverySocket {
    fun send(data: ByteArray, host: String, port: Int)

    /** Returns (data, sourceIp) or null when nothing arrived in time. */
    fun receive(timeoutMillis: Long): Pair<ByteArray, String>?

    fun close()
}

// LAN service discovery: periodically broadcasts a small announcement packet
// and listens for announcements from other LocalTavern devices. This lets a
// device learn the CURRENT address of a paired peer (DHCP changes no longer
// break sync) and lets the connect dialog list devices instead of requiring
// the user to type an IP.
//
// The announcement is plaintext and contains only public identity info; it
// never carries sync payloads (those go through the encrypted exchange).
class SyncDiscovery(
    private val identity: SyncIdentity,
    private val scope: CoroutineScope,
    private val syncPort: Int,
    private val discoveryPort: Int = SYNC_DISCOVERY_PORT,
    private val announceIntervalMillis: Long = 15_000,
    private val peerTtlMillis: Long = 60_000,
    private val socketFactory: () -> DiscoverySocket = { createDiscoverySocket() },
    // Resolved per announcement so a display-name rename is reflected on the
    // LAN without recreating discovery (the container wires it to the live
    // identity; the default keeps the constructed one for tests).
    private val deviceNameProvider: () -> String = { identity.deviceName },
    private val onPeerSeen: suspend (DiscoveredPeer) -> Unit = {}
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _state = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val state: StateFlow<List<DiscoveredPeer>> = _state.asStateFlow()

    private var socket: DiscoverySocket? = null
    private var announceJob: Job? = null
    private var listenJob: Job? = null

    /** True while discovery is actually running (false on unsupported targets). */
    val isActive: Boolean get() = announceJob != null

    fun start() {
        if (announceJob != null) return
        val created = runCatching { socketFactory() }.getOrNull()
        if (created == null) return
        socket = created
        announceJob = scope.launch(Dispatchers.IO) {
            announce()
            while (isActive) {
                delay(announceIntervalMillis)
                announce()
            }
        }
        listenJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val received = runCatching { created.receive(2_000) }.getOrNull()
                if (received == null) continue
                val (data, sourceAddress) = received
                val announcement = runCatching {
                    json.decodeFromString<DiscoveryAnnouncement>(data.decodeToString())
                }.getOrNull() ?: continue
                if (announcement.deviceId == identity.deviceId) continue

                val seen = DiscoveredPeer(
                    deviceId = announcement.deviceId,
                    deviceName = announcement.deviceName,
                    syncPort = announcement.syncPort,
                    address = sourceAddress,
                    lastSeen = now()
                )
                _state.update { peers ->
                    (peers.filterNot { it.deviceId == seen.deviceId } + seen)
                        .filter { now() - it.lastSeen < peerTtlMillis }
                        .sortedBy { it.deviceName.lowercase() }
                }
                onPeerSeen(seen)
            }
        }
    }

    fun stop() {
        announceJob?.cancel()
        listenJob?.cancel()
        announceJob = null
        listenJob = null
        socket?.close()
        socket = null
    }

    private suspend fun announce() {
        val socket = socket ?: return
        val data = json
            .encodeToString(
                DiscoveryAnnouncement.serializer(),
                DiscoveryAnnouncement(identity.deviceId, deviceNameProvider(), syncPort)
            )
            .encodeToByteArray()
        runCatching { socket.send(data, BROADCAST_ADDRESS, discoveryPort) }
    }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private companion object {
        // Limited broadcast: received by every host on the local subnet.
        const val BROADCAST_ADDRESS = "255.255.255.255"
    }
}
