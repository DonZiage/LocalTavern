package chat.donzi.localtavern.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairPayloadTest {

    @Test
    fun roundTripPreservesFields() {
        val payload = PairPayload(
            host = "192.168.1.42",
            port = 47324,
            deviceId = "abc123def456",
            deviceName = "Kitchen Tablet"
        )
        val parsed = PairPayload.fromQrText(payload.toQrText())
        assertEquals(payload, parsed)
    }

    @Test
    fun roundTripWithUnicodeAndSpaces() {
        val payload = PairPayload(
            host = "192.168.1.7",
            port = 47324,
            deviceId = "device-1",
            deviceName = "Größe & Söhne #1"
        )
        assertEquals(payload, PairPayload.fromQrText(payload.toQrText()))
    }

    @Test
    fun rejectsForeignQrText() {
        assertNull(PairPayload.fromQrText("https://example.com"))
        assertNull(PairPayload.fromQrText("localtavern://other"))
    }

    @Test
    fun rejectsMissingOrBadFields() {
        assertNull(PairPayload.fromQrText("localtavern://pair?h=1.2.3.4"))
        assertNull(PairPayload.fromQrText("localtavern://pair?h=1.2.3.4&p=99999&d=x"))
        assertNull(PairPayload.fromQrText("localtavern://pair?h=&p=47324&d=x"))
    }
}

class QrCodeTest {

    @Test
    fun generatesQrForPairingPayload() {
        val payload = PairPayload(
            host = "192.168.1.42",
            port = 47324,
            deviceId = "abc123def456",
            deviceName = "Kitchen Tablet"
        ).toQrText()
        val qr = QrCode.encodeText(payload, QrCode.Ecc.MEDIUM)
        assertTrue(qr.size >= 21)
        assertTrue(qr.size <= 45)
        // Top-left finder pattern (center at 3,3): dark outer ring, light
        // inner ring, dark 3x3 core.
        assertTrue(qr.getModule(0, 0))
        assertTrue(!qr.getModule(1, 1))
        assertTrue(qr.getModule(2, 2))
        assertTrue(qr.getModule(3, 3))
        // Top-right and bottom-left finder centers.
        assertTrue(qr.getModule(qr.size - 4, 3))
        assertTrue(qr.getModule(3, qr.size - 4))
    }
}
