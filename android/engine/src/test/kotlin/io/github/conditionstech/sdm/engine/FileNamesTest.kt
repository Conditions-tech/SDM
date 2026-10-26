package io.github.conditionstech.sdm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The Windows app's tests/unit/test_filenames.py (ADR-0008), for the Android engine.
class FileNamesTest {
    @Test
    fun contentDisposition() {
        val cases = mapOf(
            "attachment; filename=\"report.pdf\"" to "report.pdf",
            "attachment; filename=report.pdf" to "report.pdf",
            "attachment; filename=\"say \\\"hi\\\".txt\"" to "say \"hi\".txt",
            "attachment; filename*=UTF-8''%E2%82%AC%20rates.pdf" to "€ rates.pdf",
            "attachment; filename=\"plain.pdf\"; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf" to "résumé.pdf",
            "attachment; filename*=ISO-8859-1''caf%E9.txt" to "café.txt",
            "attachment; filename*=UTF-8''a+b%2.txt" to "a+b%2.txt", // no '+' rule, bad % kept
            "inline" to null,
            "" to null,
        )
        for ((header, expected) in cases) {
            assertEquals(header, expected, FileNames.fromContentDisposition(header))
        }
        assertEquals(null, FileNames.fromContentDisposition(null))
    }

    @Test
    fun fromUrl() {
        assertEquals("setup.exe", FileNames.fromUrl("https://example.com/files/setup.exe"))
        assertEquals("my file.zip", FileNames.fromUrl("https://example.com/files/my%20file.zip?x=1#y"))
        assertEquals(null, FileNames.fromUrl("https://example.com/files/"))
        assertEquals(null, FileNames.fromUrl("https://example.com"))
    }

    @Test
    fun sanitize() {
        val cases = mapOf(
            "report.pdf" to "report.pdf",
            "../../evil.apk" to "evil.apk",
            "..\\..\\Windows\\evil.exe" to "evil.exe",
            "C:\\Windows\\System32\\x.dll" to "x.dll",
            "a<b>c:d\"e|f?g*h.txt" to "a_b_c_d_e_f_g_h.txt",
            "tab\there\u0000.txt" to "tab_here_.txt",
            "invoice\u202etxt.exe" to "invoicetxt.exe",
            "name.txt. . " to "name.txt",
            "  spaced.txt  " to "spaced.txt",
            "CON" to "_CON",
            "con.txt" to "_con.txt",
            "LPT9.tar.gz" to "_LPT9.tar.gz",
            "CONSOLE.txt" to "CONSOLE.txt",
            "lone\ud800surrogate.txt" to "lone_surrogate.txt",
            "e\u0301te.txt" to "\u00e9te.txt", // NFC
            ".." to "download",
            "" to "download",
        )
        for ((raw, expected) in cases) assertEquals(raw, expected, FileNames.sanitize(raw))
        assertEquals("download", FileNames.sanitize(null))
    }

    @Test
    fun longNamesAreShortenedKeepingTheExtension() {
        val name = FileNames.sanitize("x".repeat(300) + ".zip", maxBytes = 50)
        assertTrue(name.endsWith(".zip"))
        assertEquals(50, name.length)
    }

    @Test
    fun lengthIsMeasuredInUtf8BytesWithoutSplittingEmoji() {
        val name = FileNames.sanitize("😀".repeat(40) + ".txt", maxBytes = 21)
        assertTrue(name.endsWith(".txt"))
        assertEquals("😀".repeat(4) + ".txt", name) // 4 x 4 bytes + 4: a fifth emoji won't fit
        assertTrue(FileNames.utf8Length(name) <= 21)
    }

    @Test
    fun numbered() {
        assertEquals("a.txt", FileNames.numbered("a.txt", 0))
        assertEquals("a (1).txt", FileNames.numbered("a.txt", 1))
        assertEquals("archive.tar (2).gz", FileNames.numbered("archive.tar.gz", 2))
        assertEquals("README (3)", FileNames.numbered("README", 3))
        assertEquals(".bashrc (1)", FileNames.numbered(".bashrc", 1))
    }
}
