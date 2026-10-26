package io.github.conditionstech.sdm.engine

import org.junit.Assert.assertEquals
import org.junit.Test

// The same cases as the Windows app's tests/unit/test_units.py, so both apps say the same.
class UnitsTest {
    @Test
    fun sizes() {
        assertEquals("0 bytes", Units.formatSize(0.0))
        assertEquals("1023 bytes", Units.formatSize(1023.0))
        assertEquals("1.5 KB", Units.formatSize(1536.0))
        assertEquals("12.1 MB", Units.formatSize(12_673_227.0))
        assertEquals("2.0 GB", Units.formatSize(2.0 * 1024 * 1024 * 1024))
    }

    @Test
    fun speeds() {
        assertEquals("1.5 KB/s", Units.formatSpeed(1536.0))
    }

    @Test
    fun durations() {
        assertEquals("0 s", Units.formatDuration(-3.0))
        assertEquals("42 s", Units.formatDuration(42.0))
        assertEquals("2 min 05 s", Units.formatDuration(125.0))
        assertEquals("1 h 02 min", Units.formatDuration(3725.0))
    }
}
