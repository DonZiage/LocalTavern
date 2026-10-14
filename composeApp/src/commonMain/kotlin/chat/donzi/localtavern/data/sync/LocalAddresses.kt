package chat.donzi.localtavern.data.sync

// LAN addresses this device can be reached on, for display during pairing.
// iOS returns empty (Bonjour-less manual IP entry), desktop/Android enumerate
// interfaces.
expect fun localIpAddresses(): List<String>
