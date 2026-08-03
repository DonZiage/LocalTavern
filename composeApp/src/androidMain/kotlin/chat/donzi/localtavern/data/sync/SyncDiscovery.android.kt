package chat.donzi.localtavern.data.sync

import android.content.Context
import android.net.wifi.WifiManager
import chat.donzi.localtavern.AndroidAppContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

// Same implementation as desktop: java.net.DatagramSocket is available on
// Android and supports UDP broadcast on WiFi networks. Android drops
// multicast/broadcast frames on WiFi unless the app holds a
// WifiManager.MulticastLock, so one is acquired for the socket's lifetime.
actual fun createDiscoverySocket(): DiscoverySocket = JvmDiscoverySocket()

private class JvmDiscoverySocket : DiscoverySocket {
    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        broadcast = true
        bind(InetSocketAddress(SYNC_DISCOVERY_PORT))
    }

    private val multicastLock: WifiManager.MulticastLock? =
        (AndroidAppContext.getContext()
            ?.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("LocalTavernSyncDiscovery")
            ?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
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
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock.release()
        }
        runCatching { socket.close() }
    }
}
