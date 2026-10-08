package chat.donzi.localtavern.data.sync

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals

// Renders a generated QR code into a bitmap and decodes it with ZXing, an
// independent reader — proves the generated codes are scannable by real
// scanners, not just structurally plausible.
class QrCodeScannabilityTest {

    @Test
    fun zxingCanDecodeGeneratedQr() {
        val payload = PairPayload(
            host = "192.168.1.42",
            port = 47324,
            deviceId = "abc123def456",
            deviceName = "Kitchen Tablet"
        ).toQrText()

        val qr = QrCode.encodeText(payload, QrCode.Ecc.MEDIUM)
        val quiet = 4
        val scale = 4
        val pixels = (qr.size + quiet * 2) * scale
        val image = BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, pixels, pixels)
        graphics.color = Color.BLACK
        for (y in 0 until qr.size) {
            for (x in 0 until qr.size) {
                if (qr.getModule(x, y)) {
                    graphics.fillRect((x + quiet) * scale, (y + quiet) * scale, scale, scale)
                }
            }
        }

        val source = RGBLuminanceSource(
            image.width,
            image.height,
            image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        )
        val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)))
        assertEquals(payload, result.text)
    }
}
