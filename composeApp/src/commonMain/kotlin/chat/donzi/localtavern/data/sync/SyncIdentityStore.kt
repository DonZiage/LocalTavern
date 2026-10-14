package chat.donzi.localtavern.data.sync

// Platform-private storage for the sync identity (never synced, never shared).
expect fun createSyncIdentityStore(): SyncIdentityStore

interface SyncIdentityStore {
    fun load(): ByteArray?
    fun save(bytes: ByteArray)
}
