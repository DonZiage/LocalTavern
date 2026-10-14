package chat.donzi.localtavern.data.sync

import java.net.NetworkInterface

actual fun localIpAddresses(): List<String> {
    return runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { netIf ->
                netIf.inetAddresses.toList()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && it.hostAddress?.contains(':') == false }
                    .map { it.hostAddress }
            }
            .filterNotNull()
            .distinct()
    }.getOrDefault(emptyList())
}
