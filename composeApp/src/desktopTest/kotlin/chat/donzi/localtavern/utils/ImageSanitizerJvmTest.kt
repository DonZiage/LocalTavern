package chat.donzi.localtavern.utils

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageSanitizerJvmTest {

    // 2x2 source: red green / blue yellow
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val yellow = 0xFFFFFF00.toInt()

    private fun makeSourcePng(): ByteArray {
        val surface = Surface.makeRasterN32Premul(2, 2)
        surface.use {
            it.canvas.clear(Color.TRANSPARENT)
            val paint = Paint()
            it.canvas.drawRect(Rect.makeLTRB(0f, 0f, 1f, 1f), paint.also { p -> p.color = red })
            it.canvas.drawRect(Rect.makeLTRB(1f, 0f, 2f, 1f), paint.also { p -> p.color = green })
            it.canvas.drawRect(Rect.makeLTRB(0f, 1f, 1f, 2f), paint.also { p -> p.color = blue })
            it.canvas.drawRect(Rect.makeLTRB(1f, 1f, 2f, 2f), paint.also { p -> p.color = yellow })
            val png = it.makeImageSnapshot().use { snap -> snap.encodeToData(EncodedImageFormat.PNG)!!.bytes }
            return png
        }
    }

    // Expected display layout per orientation (rows top-to-bottom), matching
    // the transform Android applies for the same EXIF values.
    private fun expectedLayout(orientation: Int): Array<IntArray> = when (orientation) {
        1 -> arrayOf(intArrayOf(red, green), intArrayOf(blue, yellow))
        2 -> arrayOf(intArrayOf(green, red), intArrayOf(yellow, blue))
        3 -> arrayOf(intArrayOf(yellow, blue), intArrayOf(green, red))
        4 -> arrayOf(intArrayOf(blue, yellow), intArrayOf(red, green))
        5 -> arrayOf(intArrayOf(red, blue), intArrayOf(green, yellow))
        6 -> arrayOf(intArrayOf(blue, red), intArrayOf(yellow, green))
        7 -> arrayOf(intArrayOf(yellow, green), intArrayOf(blue, red))
        else -> arrayOf(intArrayOf(green, yellow), intArrayOf(red, blue))
    }

    @Test
    fun orientationTransformProducesCorrectDisplay() {
        val sourcePng = makeSourcePng()
        for (orientation in 1..8) {
            val image = Image.makeFromEncoded(sourcePng)
            try {
                val transform = orientationTransform(orientation, image.width, image.height)
                assertEquals(2, transform.width, "Width for orientation $orientation")
                assertEquals(2, transform.height, "Height for orientation $orientation")

                val surface = Surface.makeRasterN32Premul(transform.width, transform.height)
                surface.use {
                    it.canvas.scale(1f, 1f)
                    it.canvas.concat(transform.matrix)
                    it.canvas.drawImage(image, 0f, 0f)
                    val snapshot = it.makeImageSnapshot()
                    try {
                        val bitmap = Bitmap.makeFromImage(snapshot)
                        try {
                            val expected = expectedLayout(orientation)
                            for (y in 0 until transform.height) {
                                for (x in 0 until transform.width) {
                                    val actual = bitmap.getColor(x, y)
                                    val expectedColor = expected[y][x]
                                    assertTrue(
                                        actual == expectedColor,
                                        "Orientation $orientation pixel ($x,$y): " +
                                            "expected 0x${expectedColor.toUInt().toString(16).padStart(8, '0')} " +
                                            "got 0x${actual.toUInt().toString(16).padStart(8, '0')}"
                                    )
                                }
                            }
                        } finally {
                            bitmap.close()
                        }
                    } finally {
                        snapshot.close()
                    }
                }
            } finally {
                image.close()
            }
        }
    }

    @Test
    fun downscaleImageForChatBakesOrientation() {
        val sourcePng = makeSourcePng()
        val jpeg = downscaleImageForChat(sourcePng) ?: throw AssertionError("Sanitize failed")
        val image = Image.makeFromEncoded(jpeg)
        try {
            assertEquals(2, image.width)
            assertEquals(2, image.height)
        } finally {
            image.close()
        }
    }
}
