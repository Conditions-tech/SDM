package io.github.conditionstech.sdm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The Windows app's tests/unit/test_segments.py and test_content_range.py.
class SegmentsTest {
    private val mib = 1024L * 1024

    @Test
    fun planSplitsIntoEqualPartsWithTheRemainderLast() {
        val parts = Segments.plan(10 * mib + 3, 8, mib)
        assertEquals(8, parts.size)
        assertTrue(Segments.cover(parts, 10 * mib + 3))
        assertEquals(parts[0].size, parts[6].size)
        assertEquals(parts[0].size + 3, parts[7].size)
    }

    @Test
    fun planKeepsPartsAtLeastTheMinimum() {
        assertEquals(3, Segments.plan(3 * mib + 5, 8, mib).size)
        assertEquals(listOf(Segment(0, 999)), Segments.plan(1000, 8, mib))
    }

    @Test
    fun coverRejectsGapsOverlapsAndBadProgress() {
        assertTrue(Segments.cover(listOf(Segment(0, 9, 10), Segment(10, 19, 0)), 20))
        assertFalse(Segments.cover(listOf(Segment(0, 9), Segment(11, 19)), 20)) // gap
        assertFalse(Segments.cover(listOf(Segment(0, 10), Segment(10, 19)), 20)) // overlap
        assertFalse(Segments.cover(listOf(Segment(0, 9, 11), Segment(10, 19)), 20)) // too much
        assertFalse(Segments.cover(listOf(Segment(0, 9, -1), Segment(10, 19)), 20))
        assertFalse(Segments.cover(listOf(Segment(0, 9)), 20)) // short
        assertFalse(Segments.cover(emptyList(), 20))
    }

    @Test
    fun theSavedMapRoundTripsInTheWindowsFormat() {
        val parts = listOf(Segment(0, 9, 4), Segment(10, 19, 10))
        assertEquals("[[0,9,4],[10,19,10]]", Segments.encode(parts))
        assertEquals(parts, Segments.decode("[[0,9,4],[10,19,10]]", 20))
        assertEquals(parts, Segments.decode("[[0, 9, 4], [10, 19, 10]]", 20)) // Python's spacing
    }

    @Test
    fun hostileSavedMapsAreRefused() {
        val bad = listOf(
            null, "", "[]", "[[0,9,4]]", "[[0,9,4],[10,19,10]]x", "x[[0,9,4],[10,19,10]]",
            "[[0,9,4],,[10,19,10]]", "[[0,9,4][10,19,10]]", "[[0,9,4],[10,19,99]]",
            "[[0,9,-4],[10,19,10]]", "[[0,9,4],[10,19,1e3]]", "{\"a\":1}",
            "[[0,9,4],[10,19,10],[20,29,0]]", "[[0,9,99999999999999999999],[10,19,10]]",
        )
        for (text in bad) assertNull(text, Segments.decode(text, 20))
        assertNull(Segments.decode("[[0,9,4],[10,19,10]]", null))
    }

    @Test
    fun pickSplitHalvesTheBiggestPart() {
        // Part 1 has 4 MiB left, part 0 has 2 MiB: part 1 gives away its second half.
        val choice = Segments.pickSplit(listOf(0L to 2 * mib - 1, 2 * mib to 6 * mib - 1), mib)
        assertEquals(1 to 4 * mib, choice)
        assertNull(Segments.pickSplit(listOf(0L to 2 * mib - 2), mib)) // halves would be < 1 MiB
        assertEquals(0 to mib, Segments.pickSplit(listOf(0L to 2 * mib - 1, 2 * mib to 4 * mib - 1), mib)) // tie
    }

    @Test
    fun contentRange() {
        assertEquals(Triple(100L, 199L, 1000L), Segments.parseContentRange("bytes 100-199/1000"))
        for (bad in listOf(null, "", "bytes 100-199/*", "bytes 200-100/1000", "bytes 0-1000/1000", "items 0-1/2", "bytes -1-2/3")) {
            assertNull(bad, Segments.parseContentRange(bad))
        }
    }
}
