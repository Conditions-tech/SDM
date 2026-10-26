package io.github.conditionstech.sdm.engine

import java.util.Locale
import kotlin.math.roundToLong

/** Human-readable sizes, speeds and durations; the same wording as the Windows app. */
object Units {
    /** 1536 -> "1.5 KB". 1024-based units, as Windows Explorer does. */
    fun formatSize(bytes: Double): String {
        if (bytes < 1024) return "${bytes.roundToLong()} bytes"
        var size = bytes / 1024
        for (unit in listOf("KB", "MB")) {
            if (size < 1024) return oneDecimal(size, unit)
            size /= 1024
        }
        return oneDecimal(size, "GB")
    }

    /** 1536.0 -> "1.5 KB/s". */
    fun formatSpeed(bytesPerSecond: Double): String = "${formatSize(bytesPerSecond)}/s"

    /** Time left: 42 -> "42 s", 125 -> "2 min 05 s", 3725 -> "1 h 02 min". */
    fun formatDuration(seconds: Double): String {
        var s = maxOf(0L, seconds.roundToLong())
        if (s < 60) return "$s s"
        var minutes = s / 60
        s %= 60
        if (minutes < 60) return "$minutes min ${twoDigits(s)} s"
        val hours = minutes / 60
        minutes %= 60
        return "$hours h ${twoDigits(minutes)} min"
    }

    // Locale.ROOT: always a dot and Latin digits, whatever language the phone uses.
    private fun oneDecimal(value: Double, unit: String) = String.format(Locale.ROOT, "%.1f %s", value, unit)

    private fun twoDigits(value: Long) = String.format(Locale.ROOT, "%02d", value)
}
