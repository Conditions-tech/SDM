package io.github.conditionstech.sdm.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the sleep timer is doing (M17b). */
sealed interface SleepState {
    data object Off : SleepState
    data class Counting(val endsAtMs: Long) : SleepState
    data object EndOfSong : SleepState
}

/** The player as the sleep timer sees it; the playback service provides the real one. */
interface SleepTarget {
    fun setVolume(volume: Float)
    fun pause()
    fun pauseAtEndOfSong(enabled: Boolean)
}

/**
 * The music sleep timer (M17b): stop after a number of minutes, fading the volume out over the
 * last [fadeMs], or at the end of the current song. Plain Kotlin with an injected clock, so its
 * tests run on the PC. It lives with the app (not a screen), so it keeps counting while the
 * music plays with the screen off.
 */
class SleepTimer(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = 250,
    private val fadeMs: Long = 10_000,
) {
    private val current = MutableStateFlow<SleepState>(SleepState.Off)
    val state: StateFlow<SleepState> = current.asStateFlow()
    private var job: Job? = null

    /** The playback service connects itself here; until then nothing is controlled. */
    var target: SleepTarget? = null

    /** Stop after [minutes] (1 to 1440: up to a day). */
    fun start(minutes: Int) {
        require(minutes in 1..MAX_MINUTES) { "minutes must be between 1 and $MAX_MINUTES" }
        reset()
        val endsAt = clock() + minutes * 60_000L
        current.value = SleepState.Counting(endsAt)
        job = scope.launch {
            while (true) {
                val left = endsAt - clock()
                if (left <= 0) {
                    target?.pause()
                    target?.setVolume(1f) // ready for the next time the user presses play
                    current.value = SleepState.Off
                    return@launch
                }
                target?.setVolume(if (left < fadeMs) left.toFloat() / fadeMs else 1f)
                delay(minOf(tickMs, left))
            }
        }
    }

    /** Stop when the current song ends (the player pauses there by itself). */
    fun endOfSong() {
        reset()
        current.value = SleepState.EndOfSong
        target?.pauseAtEndOfSong(true)
    }

    /** The player paused at the end of a song: an end-of-song timer has done its job. */
    fun songEnded() {
        if (current.value == SleepState.EndOfSong) cancel()
    }

    fun cancel() {
        reset()
        current.value = SleepState.Off
    }

    /** Milliseconds left, or null when not counting. */
    fun remainingMs(): Long? = (current.value as? SleepState.Counting)?.let { maxOf(0, it.endsAtMs - clock()) }

    private fun reset() {
        job?.cancel()
        job = null
        target?.setVolume(1f)
        target?.pauseAtEndOfSong(false)
    }

    companion object {
        const val MAX_MINUTES = 24 * 60
        val PRESETS = listOf(15, 30, 45, 60, 90)
    }
}
