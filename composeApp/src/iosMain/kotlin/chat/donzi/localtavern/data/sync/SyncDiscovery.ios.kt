package chat.donzi.localtavern.data.sync

// iOS discovery is not implemented yet: devices pair by entering the host's
// IP manually (as before). SyncDiscovery.start() catches the failure of
// createDiscoverySocket() and degrades gracefully to no discovery, so the app
// behaves exactly like earlier versions on iOS.
actual fun createDiscoverySocket(): DiscoverySocket =
    throw UnsupportedOperationException("Sync discovery is not supported on this platform")
