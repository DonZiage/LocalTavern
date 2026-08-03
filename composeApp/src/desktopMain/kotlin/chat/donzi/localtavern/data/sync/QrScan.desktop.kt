package chat.donzi.localtavern.data.sync

// Desktop machines usually have no camera and no scanner app; receivers on
// desktop pick the host from the discovered-devices list or enter the address
// manually. The host side still shows the QR for the phone to scan.
actual val supportsQrScanning: Boolean = false

actual fun launchQrScanner(onResult: (String?) -> Unit) {
    onResult(null)
}
