package io.github.conditionstech.sdm.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoRulesTest {
    @Test
    fun resumePositionsSkipTheStartAndTheEnd() {
        val hour = 3_600_000L
        assertNull(VideoRules.positionToRemember(3_000, hour)) // just started
        assertEquals(600_000L, VideoRules.positionToRemember(600_000, hour))
        assertNull(VideoRules.positionToRemember(hour - 60_000, hour)) // past 95 %: watched
        assertNull(VideoRules.positionToRemember(55_000, 60_000)) // under 10 s left
        assertNull(VideoRules.positionToRemember(10_000, 0)) // unknown length
    }

    @Test
    fun sidewaysSwipesSeekWithinTheVideo() {
        assertEquals(45_000L + 30_000, VideoRules.seekBySwipe(45_000, 360f, 1080f, 600_000)) // a third: 30 s
        assertEquals(0L, VideoRules.seekBySwipe(5_000, -1080f, 1080f, 600_000))
        assertEquals(60_000L, VideoRules.seekBySwipe(50_000, 1080f, 1080f, 60_000))
        assertEquals(7_000L, VideoRules.seekBySwipe(7_000, 100f, 0f, 60_000))
    }

    @Test
    fun verticalSwipesChangeTheLevelWithinZeroAndOne() {
        assertEquals(0.75f, VideoRules.levelBySwipe(0.5f, 500f, 2000f), 0.001f)
        assertEquals(1f, VideoRules.levelBySwipe(0.9f, 1000f, 2000f), 0.001f)
        assertEquals(0f, VideoRules.levelBySwipe(0.1f, -1000f, 2000f), 0.001f)
    }

    @Test
    fun doubleTapsSkipOnTheSidesOnly() {
        assertEquals(-10_000L, VideoRules.doubleTapSkip(100f, 1080f))
        assertEquals(0L, VideoRules.doubleTapSkip(540f, 1080f))
        assertEquals(10_000L, VideoRules.doubleTapSkip(1000f, 1080f))
    }

    @Test
    fun speedCyclesAndLabels() {
        assertEquals(1.25f, VideoRules.nextSpeed(1f))
        assertEquals(0.5f, VideoRules.nextSpeed(2f))
        assertEquals(0.75f, VideoRules.nextSpeed(0.5f))
        assertEquals("1×", VideoRules.speedLabel(1f))
        assertEquals("1.5×", VideoRules.speedLabel(1.5f))
    }
}
