package io.github.conditionstech.sdm.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sleep timer on simulated time: minutes pass instantly. */
@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {
    private class FakePlayer : SleepTarget {
        var level = 1f
        var paused = false
        var atEndOfSong = false
        val volumes = mutableListOf<Float>()
        override fun setVolume(volume: Float) {
            level = volume
            volumes += volume
        }
        override fun pause() {
            paused = true
        }
        override fun pauseAtEndOfSong(enabled: Boolean) {
            atEndOfSong = enabled
        }
    }

    private val scope = TestScope()
    private val player = FakePlayer()
    private val timer = SleepTimer(scope, clock = { scope.testScheduler.currentTime }).also { it.target = player }

    @Test
    fun pausesAfterTheMinutesWithAFadeAndResetsTheVolume() {
        timer.start(15)
        scope.runCurrent()
        assertEquals(15 * 60_000L, timer.remainingMs())
        scope.advanceTimeBy(15 * 60_000L - 5_000) // 5 s before the end: half volume
        scope.runCurrent()
        assertEquals(0.5f, player.level, 0.05f)
        assertTrue(!player.paused)
        scope.advanceTimeBy(6_000)
        scope.runCurrent()
        assertTrue(player.paused)
        assertEquals(1f, player.level) // ready for the next play
        assertEquals(SleepState.Off, timer.state.value)
        assertTrue(player.volumes.any { it in 0.01f..0.99f }) // it really faded
    }

    @Test
    fun cancelStopsTheCountdownAndRestoresTheVolume() {
        timer.start(1)
        scope.advanceTimeBy(55_000)
        scope.runCurrent()
        assertTrue(player.level < 1f)
        timer.cancel()
        scope.advanceTimeBy(60_000)
        scope.runCurrent()
        assertTrue(!player.paused)
        assertEquals(1f, player.level)
        assertEquals(SleepState.Off, timer.state.value)
    }

    @Test
    fun aNewTimeReplacesTheOldOne() {
        timer.start(1)
        timer.start(30)
        scope.advanceTimeBy(2 * 60_000L)
        scope.runCurrent()
        assertTrue(!player.paused)
        assertEquals(28 * 60_000L, timer.remainingMs())
    }

    @Test
    fun endOfSongAsksThePlayerToStopThereAndEndsWhenItDoes() {
        timer.endOfSong()
        assertEquals(SleepState.EndOfSong, timer.state.value)
        assertTrue(player.atEndOfSong)
        timer.songEnded()
        assertEquals(SleepState.Off, timer.state.value)
        assertTrue(!player.atEndOfSong)
    }

    @Test
    fun switchingFromEndOfSongToMinutesTurnsTheEndOfSongStopOff() {
        timer.endOfSong()
        timer.start(10)
        assertTrue(!player.atEndOfSong)
        timer.songEnded() // a song ending now must not cancel the minutes
        assertTrue(timer.state.value is SleepState.Counting)
    }

    @Test
    fun customMinutesAreChecked() {
        timer.start(1)
        timer.start(1440)
        assertThrows(IllegalArgumentException::class.java) { timer.start(0) }
        assertThrows(IllegalArgumentException::class.java) { timer.start(1441) }
    }
}
