package chat.donzi.localtavern.data.sync

// Platform QR scanning bridge. The host shows a QR code; the receiver scans
// it (camera) or picks the host from the discovered-devices list (which needs
// no camera). Desktop machines typically have no camera and no scanner app,
// so they fall back to the discovery list / manual entry.
expect val supportsQrScanning: Boolean

/** Launches the platform QR scanner; [onResult] is called with the scanned
 *  text, or null when the scan was cancelled/failed. */
expect fun launchQrScanner(onResult: (String?) -> Unit)
