package chat.donzi.localtavern.data.sync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceNameTest {

    @Test
    fun sanitize_rejectsBlankInput() {
        assertNull(DeviceName.sanitize(""))
        assertNull(DeviceName.sanitize("   \t "))
        assertNull(DeviceName.sanitize("\n"))
    }

    @Test
    fun sanitize_collapsesWhitespaceRuns() {
        assertEquals("My Device", DeviceName.sanitize("  My\t\n Device  "))
    }

    @Test
    fun sanitize_stripsControlCharacters() {
        assertEquals("Name", DeviceName.sanitize("Na\u0007me"))
        assertEquals("Clean", DeviceName.sanitize("Clean\u0000"))
    }

    @Test
    fun sanitize_stripsZeroWidthDirectionalAndBidiOverrideChars() {
        // Bidi override: strips without changing the visible text.
        assertEquals("Name", DeviceName.sanitize("Na\u202Eme\u202C"))
        // Zero-width space.
        assertEquals("ab", DeviceName.sanitize("a\u200Bb"))
        // Bidi isolate / word joiner / LRM-RLM.
        assertEquals("x", DeviceName.sanitize("\u2066x\u2069"))
        assertEquals("ab", DeviceName.sanitize("a\u200Eb"))
        assertEquals("ab", DeviceName.sanitize("a\u200Fb"))
        assertEquals("ab", DeviceName.sanitize("a\u2060b"))
        // Only unsafe chars: nothing usable remains.
        assertNull(DeviceName.sanitize("\u200B\u2060\u202A"))
    }

    @Test
    fun sanitize_capsLengthAtMax() {
        val long = "a".repeat(200)
        assertEquals("a".repeat(DeviceName.MAX_LENGTH), DeviceName.sanitize(long))
        assertEquals(DeviceName.MAX_LENGTH, DeviceName.sanitize(long)!!.length)
    }

    @Test
    fun sanitize_keepsNormalNames() {
        assertEquals("Crimson Lynx 42", DeviceName.sanitize("Crimson Lynx 42"))
        assertEquals("Quiet Falcon", DeviceName.sanitize("  Quiet Falcon  "))
        assertNotNull(DeviceName.sanitize("Tablet Zero"))
    }

    @Test
    fun generate_makesReadablePracticallyUniqueNames() {
        val names = (1..200).map { DeviceName.generate() }
        assertTrue(names.all { it.isNotBlank() }, "Names must never be blank")
        assertTrue(names.all { DeviceName.sanitize(it) != null }, "Names must be display-safe")
        assertTrue(names.all { it.length <= DeviceName.MAX_LENGTH })
        assertTrue(names.distinct().size > 150, "200 draws must practically never collide")
    }

    @Test
    fun generate_withFixedSeed_isDeterministic() {
        assertEquals(DeviceName.generate(Random(42)), DeviceName.generate(Random(42)))
    }
}
