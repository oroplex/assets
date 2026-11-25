package com.oroplex.floorplan.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class ScanLogTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `entries are appended in order`() {
        val log = BoundedLogFile(File(temp.root, "a/log.txt"), maxBytes = 1_000)
        log.append("one\n")
        log.append("two\n")
        assertEquals("one\ntwo\n", log.read())
    }

    @Test
    fun `the file never grows past its limit and keeps the newest whole lines`() {
        val file = File(temp.root, "log.txt")
        val log = BoundedLogFile(file, maxBytes = 400)
        repeat(100) { log.append("line number $it\n") }
        assertTrue("${file.length()} bytes", file.length() <= 400)
        val text = log.read()
        assertTrue(text.startsWith(BoundedLogFile.TRIMMED))
        assertTrue(text.endsWith("line number 99\n"))
        // Every kept line is whole.
        val lines = text.removePrefix(BoundedLogFile.TRIMMED).lines().filter { it.isNotEmpty() }
        assertTrue(lines.all { Regex("line number \\d+").matches(it) })
        assertFalse(text.contains("line number 0\n"))
    }

    @Test
    fun `one huge entry is cut to fit instead of being lost`() {
        val file = File(temp.root, "log.txt")
        val log = BoundedLogFile(file, maxBytes = 200)
        log.append("x".repeat(1_000) + "END\n")
        assertTrue(file.length() <= 200)
        assertTrue(log.read().endsWith("END\n"))
    }

    @Test
    fun `the shared file has the header then the log and a crash is written at once`() {
        ScanLog.init(temp.newFolder("diagnostics"))
        ScanLog.clear()
        ScanLog.add("Wall measured: 3.21 m")
        ScanLog.addNow("CRASH on thread \"main\"\njava.lang.IllegalStateException: boom")
        val out = ByteArrayOutputStream()
        ScanLog.export(out, "FloorPlan Scan 0.1.0 on a test phone")
        val text = out.toString("UTF-8")
        assertTrue(text.startsWith("FloorPlan Scan 0.1.0 on a test phone\n\n"))
        assertTrue(text.contains("Wall measured: 3.21 m"))
        assertTrue(text.contains("IllegalStateException: boom"))
        // Entries carry a time stamp.
        assertTrue(Regex("\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3}  Wall measured").containsMatchIn(text))
    }

    @Test
    fun `an empty log still exports something readable`() {
        ScanLog.init(temp.newFolder("empty"))
        ScanLog.clear()
        val out = ByteArrayOutputStream()
        ScanLog.export(out, "header")
        assertEquals("header\n\n(nothing recorded yet)\n", out.toString("UTF-8"))
    }
}
