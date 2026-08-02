package chat.donzi.localtavern.data.sync

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

// Same implementation as desktop: java.net.DatagramSocket is available on
// Android and supports UDP broadcast on WiFi networks.
actual fun createDiscoverySocket(): DiscoverySocket = JvmDiscoverySocket()

private class JvmDiscoverySocket : DiscoverySocket {
    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        broadcast = true
        bind(InetSocketAddress(SYNC_DISCOVERY_PORT))
    }

    override fun send(data: ByteArray, host: String, port: Int) {
        val packet = DatagramPacket(data, data.size, InetAddress.getByName(host), port)
        socket.send(packet)
    }

    override fun receive(timeoutMillis: Long): Pair<ByteArray, String>? {
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        return runCatching {
            socket.soTimeout = timeoutMillis.coerceAtLeast(1).toInt()
            socket.receive(packet)
            buffer.copyOf(packet.length) to (packet.address?.hostAddress ?: "")
        }.getOrNull()
    }

    override fun close() {
        runCatching { socket.close() }
    }
}
