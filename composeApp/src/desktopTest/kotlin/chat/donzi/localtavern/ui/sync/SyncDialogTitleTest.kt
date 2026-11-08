package chat.donzi.localtavern.ui.sync

import chat.donzi.localtavern.data.sync.PairingStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SyncDialogTitleTest {

    @Test
    fun syncing_afterFingerprint_hasOwnTitleInsteadOfPinTitle() {
        val title = syncDialogTitle(
            step = SyncRole.Receiver,
            receiverStep = ReceiverStep.Pin,
            postPair = PostPairState.Syncing,
            pairingStage = PairingStage.Idle,
            pendingFingerprint = null,
            isConnecting = false,
            isSyncing = true
        )
        assertNotEquals("Enter the pairing PIN", title, "Initial sync must not be titled as the PIN entry screen")
        assertEquals("Syncing your devices", title)
    }

    @Test
    fun pin_entryKeepsPinTitleBeforePairing() {
        val title = syncDialogTitle(
            step = SyncRole.Receiver,
            receiverStep = ReceiverStep.Pin,
            postPair = null,
            pairingStage = PairingStage.Idle,
            pendingFingerprint = null,
            isConnecting = false,
            isSyncing = false
        )
        assertEquals("Enter the pairing PIN", title)
    }

    @Test
    fun connecting_hasDistinctTitle() {
        val title = syncDialogTitle(
            step = SyncRole.Receiver,
            receiverStep = ReceiverStep.Pin,
            postPair = null,
            pairingStage = PairingStage.Idle,
            pendingFingerprint = null,
            isConnecting = true,
            isSyncing = false
        )
        assertNotEquals("Enter the pairing PIN", title)
        assertEquals("Connecting…", title)
    }

    @Test
    fun syncSuccessAndFailure_haveOwnTitles() {
        assertEquals(
            "Sync complete",
            syncDialogTitle(SyncRole.Receiver, ReceiverStep.Pin, PostPairState.Success, PairingStage.Idle, null, false, false)
        )
        assertEquals(
            "Sync failed",
            syncDialogTitle(SyncRole.Receiver, ReceiverStep.Pin, PostPairState.Failed, PairingStage.Idle, null, false, false)
        )
    }

    @Test
    fun hostPaired_hasOwnTitleInsteadOfShowCode() {
        val title = syncDialogTitle(
            step = SyncRole.Host,
            receiverStep = ReceiverStep.Find,
            postPair = null,
            pairingStage = PairingStage.Paired,
            pendingFingerprint = null,
            isConnecting = false,
            isSyncing = false
        )
        assertNotEquals("Show pairing code", title)
        assertEquals("Pairing complete", title)
    }

    @Test
    fun roleChoice_andHostWaiting_keepTheirTitles() {
        assertEquals(
            "Pair devices",
            syncDialogTitle(null, ReceiverStep.Find, null, PairingStage.Idle, null, false, false)
        )
        assertEquals(
            "Show pairing code",
            syncDialogTitle(SyncRole.Host, ReceiverStep.Find, null, PairingStage.WaitingForReceiver, null, false, false)
        )
    }
}
