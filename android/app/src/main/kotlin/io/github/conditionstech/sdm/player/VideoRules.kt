package io.github.conditionstech.sdm.player

/**
 * The video player's rules (M17c), in plain Kotlin so they are tested on the PC: where to
 * resume, and what a swipe or a double tap means.
 */
object VideoRules {
    /** Speeds offered by the speed button. */
    val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    const val SKIP_MS = 10_000L

    /**
     * The position to remember when the user leaves a video, or null to forget it: nothing
     * for the first 5 seconds, and nothing near the end (the video counts as watched).
     */
    fun positionToRemember(positionMs: Long, durationMs: Long): Long? = when {
        durationMs <= 0 || positionMs < 5_000 -> null
        positionMs > durationMs * 95 / 100 || durationMs - positionMs < 10_000 -> null
        else -> positionMs
    }

    /** A sideways swipe across the whole screen moves 90 seconds; never past the start or end. */
    fun seekBySwipe(startMs: Long, dragPx: Float, screenWidthPx: Float, durationMs: Long): Long {
        if (screenWidthPx <= 0) return startMs
        val delta = (dragPx / screenWidthPx * 90_000).toLong()
        return (startMs + delta).coerceIn(0, maxOf(0, durationMs))
    }

    /** An upward swipe over the full height adds 1.0 (brightness 0..1, or a volume fraction). */
    fun levelBySwipe(start: Float, dragUpPx: Float, screenHeightPx: Float): Float {
        if (screenHeightPx <= 0) return start
        return (start + dragUpPx / screenHeightPx).coerceIn(0f, 1f)
    }

    /** A double tap on the left third goes back, on the right third forward, in the middle nothing. */
    fun doubleTapSkip(xPx: Float, screenWidthPx: Float): Long = when {
        screenWidthPx <= 0 -> 0
        xPx < screenWidthPx / 3 -> -SKIP_MS
        xPx > screenWidthPx * 2 / 3 -> SKIP_MS
        else -> 0
    }

    /** The next speed when the speed button is pressed (after the fastest, back to the slowest). */
    fun nextSpeed(current: Float): Float {
        val index = SPEEDS.indexOfFirst { it >= current - 0.01f }
        return SPEEDS[if (index < 0 || index == SPEEDS.lastIndex) 0 else index + 1]
    }

    fun speedLabel(speed: Float): String = if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "$speed×"
}
