package chat.donzi.localtavern.utils

import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private val messageDateFormat = kotlinx.datetime.LocalDateTime.Format {
    monthNumber(Padding.NONE)
    char('/')
    day(Padding.NONE)
    char('/')
    yearTwoDigits(baseYear = 2000)
    char(' ')
    amPmHour(Padding.NONE)
    char(':')
    minute()
    amPmMarker("AM", "PM")
}

/**
 * Compact message timestamps ("just now", "5m ago", "3h ago", or a short
 * date). [nowMillis] is a parameter (instead of reading the clock) so the
 * boundaries are unit-testable. Future timestamps (clock skew, sync) clamp to
 * "just now" rather than rendering a negative age.
 */
object MessageTime {

    fun format(timestampMillis: Long, nowMillis: Long): String {
        val age = nowMillis - timestampMillis
        if (age < 60_000L) return "just now"
        if (age < 3_600_000L) {
            val minutes = (age / 60_000L).toInt()
            return if (minutes <= 1) "1m ago" else "${minutes}m ago"
        }
        if (age < 86_400_000L) {
            val hours = (age / 3_600_000L).toInt()
            return if (hours <= 1) "1h ago" else "${hours}h ago"
        }
        return try {
            val dateTime = Instant.fromEpochMilliseconds(timestampMillis)
                .toLocalDateTime(TimeZone.currentSystemDefault())
            messageDateFormat.format(dateTime)
        } catch (_: Exception) {
            ""
        }
    }
}
