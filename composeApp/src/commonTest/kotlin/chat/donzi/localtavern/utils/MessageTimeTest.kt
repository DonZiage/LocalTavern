package chat.donzi.localtavern.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageTimeTest {

    private val now = 1_700_000_000_000L

    @Test
    fun justNow_coversSecondsAndFutureSkew() {
        assertEquals("just now", MessageTime.format(now - 30_000L, now))
        assertEquals("just now", MessageTime.format(now, now))
        // Sync/future timestamps must not render a negative age.
        assertEquals("just now", MessageTime.format(now + 60_000L, now))
    }

    @Test
    fun minutes_ago() {
        assertEquals("1m ago", MessageTime.format(now - 60_000L, now))
        assertEquals("5m ago", MessageTime.format(now - 5 * 60_000L, now))
        assertEquals("59m ago", MessageTime.format(now - 59 * 60_000L, now))
    }

    @Test
    fun hours_ago() {
        assertEquals("1h ago", MessageTime.format(now - 3_600_000L, now))
        assertEquals("3h ago", MessageTime.format(now - 3 * 3_600_000L, now))
    }

    @Test
    fun olderThanADay_rendersShortDate() {
        val out = MessageTime.format(now - 2 * 86_400_000L, now)
        // Exact rendering depends on the device timezone; it must be a short
        // date like "11/12/23 4:05 PM", never a relative age.
        assertTrue("ago" !in out && "just now" != out, "Old messages must show a date, got $out")
        assertTrue('/' in out, "Date must be slash-separated, got $out")
    }
}
