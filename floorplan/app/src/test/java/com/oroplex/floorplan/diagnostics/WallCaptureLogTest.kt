package com.oroplex.floorplan.diagnostics

import com.oroplex.floorplan.core.fit.WallCaptureRecord
import com.oroplex.floorplan.core.geometry.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class WallCaptureLogTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun capture(time: Long, samples: Int = 3) = WallCaptureRecord(
        timeMillis = time,
        floorY = -1.3,
        ceilingY = 1.2,
        viewer = Vec3(0.0, 0.1, 0.0),
        aimPoint = Vec3(0.0, 0.2, -3.0),
        cameras = listOf(Vec3(0.0, 0.1, 0.0)),
        samples = List(samples) { WallCaptureRecord.Sample(0, 'D', it == 0, Vec3(it / 10.0, 0.2, -3.0)) },
        plane = null,
        note = "capture $time",
    )

    private fun exported(store: WallCaptureStore, maxBytes: Long): Pair<List<WallCaptureRecord>, Pair<Int, Int>> {
        val out = ByteArrayOutputStream()
        val counts = store.export(out, maxBytes)
        return WallCaptureRecord.decodeAll(out.toString("UTF-8")) to counts
    }

    @Test
    fun `only the newest captures are kept`() {
        val directory = File(temp.root, "captures")
        val store = WallCaptureStore(directory, maxFiles = 3)
        for (time in 1L..5L) store.save(capture(time))
        assertEquals(3, directory.listFiles()!!.size)
        val (records, counts) = exported(store, Long.MAX_VALUE)
        assertEquals(listOf(3L, 4L, 5L), records.map { it.timeMillis })
        assertEquals(3 to 3, counts)
    }

    @Test
    fun `two captures in the same millisecond are both kept`() {
        val store = WallCaptureStore(File(temp.root, "captures"), maxFiles = 10)
        store.save(capture(7L))
        store.save(capture(7L, samples = 5))
        assertEquals(listOf(3, 5), exported(store, Long.MAX_VALUE).first.map { it.samples.size })
    }

    @Test
    fun `a shared file that is too small carries the newest whole captures`() {
        val store = WallCaptureStore(File(temp.root, "captures"), maxFiles = 10)
        for (time in 1L..4L) store.save(capture(time))
        val one = capture(1L).encode().toByteArray(Charsets.UTF_8).size.toLong()
        val (records, counts) = exported(store, 2 * one + one / 2)
        assertEquals(listOf(3L, 4L), records.map { it.timeMillis })
        assertEquals(2 to 4, counts)
        assertEquals(capture(4L), records.last())
    }

    @Test
    fun `the shared section says what it holds and can be read back out of a whole log`() {
        ScanLog.init(temp.newFolder("diagnostics"))
        ScanLog.clear()
        WallCaptureLog.init(File(temp.root, "diagnostics"))
        WallCaptureLog.clear()

        val empty = ByteArrayOutputStream()
        WallCaptureLog.export(empty)
        assertTrue(empty.toString("UTF-8").contains(WallCaptureLog.HEADING + "\n(no wall captures recorded yet)"))

        ScanLog.add("measure WALL ok 0.9 s 12 frames")
        WallCaptureLog.record(capture(10L))
        WallCaptureLog.record(capture(11L, samples = 40))
        val out = ByteArrayOutputStream()
        ScanLog.export(out, "FloorPlan Scan on a test phone")
        WallCaptureLog.export(out)
        val text = out.toString("UTF-8")
        assertTrue(text.indexOf("measure WALL ok") < text.indexOf(WallCaptureLog.HEADING))
        assertTrue(text.contains("2 of 2 recent wall captures"))
        assertEquals(listOf(capture(10L), capture(11L, samples = 40)), WallCaptureRecord.decodeAll(text))

        WallCaptureLog.clear()
        val cleared = ByteArrayOutputStream()
        WallCaptureLog.export(cleared)
        assertTrue(WallCaptureRecord.decodeAll(cleared.toString("UTF-8")).isEmpty())
    }
}
