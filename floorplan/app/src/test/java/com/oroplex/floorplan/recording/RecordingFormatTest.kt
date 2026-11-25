package com.oroplex.floorplan.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.cos
import kotlin.math.sin

class RecordingFormatTest {
    @get:Rule
    val temp = TemporaryFolder()

    // ---------------------------------------------------------------------------------------------
    // Which frames are saved

    /** A pose at [x] metres along X, turned [yawDeg] about the vertical axis. */
    private fun pose(x: Double, yawDeg: Double = 0.0): FloatArray {
        val half = Math.toRadians(yawDeg) / 2
        return floatArrayOf(x.toFloat(), 1.4f, 0f, 0f, sin(half).toFloat(), 0f, cos(half).toFloat())
    }

    private val ms = 1_000_000L

    @Test
    fun `walking saves about five frames a second, standing still almost none`() {
        val policy = KeyframePolicy()
        var saved = 0
        // 3 seconds of 30 fps at 0.6 m/s.
        for (i in 0 until 90) {
            if (policy.offer(i * 33 * ms, pose(i * 0.02)) == KeyframePolicy.Decision.SAVE) saved++
        }
        assertTrue("$saved frames", saved in 13..18)
        assertEquals(0.6, policy.speed, 0.05)

        // 5 more seconds without moving: the last step, then one frame every two seconds, so a pause shows
        // in the recording without filling it.
        var still = 0
        for (i in 90 until 240) {
            if (policy.offer(i * 33 * ms, pose(89 * 0.02)) == KeyframePolicy.Decision.SAVE) still++
        }
        assertEquals(3, still)
    }

    @Test
    fun `turning on the spot counts as movement`() {
        val policy = KeyframePolicy()
        assertEquals(KeyframePolicy.Decision.SAVE, policy.offer(0, pose(0.0)))
        // 30 degrees per second: 3 degrees are reached after 100 ms, but no frame sooner than 180 ms after the last.
        assertEquals(KeyframePolicy.Decision.TOO_SOON, policy.offer(66 * ms, pose(0.0, 2.0)))
        assertEquals(KeyframePolicy.Decision.TOO_SOON, policy.offer(133 * ms, pose(0.0, 4.0)))
        assertEquals(KeyframePolicy.Decision.SAVE, policy.offer(200 * ms, pose(0.0, 6.0)))
        assertEquals(30.0, policy.turnRateDeg, 1.0)
        assertEquals(6.0, KeyframePolicy.rotationDeg(pose(0.0), pose(0.0, 6.0)), 1e-3)
        // Half a degree further is not a new view.
        assertEquals(KeyframePolicy.Decision.STILL, policy.offer(400 * ms, pose(0.0, 6.5)))
    }

    @Test
    fun `a fast pan is skipped as blurred, but never for long`() {
        val policy = KeyframePolicy()
        assertEquals(KeyframePolicy.Decision.SAVE, policy.offer(0, pose(0.0)))
        // 150 degrees per second.
        val decisions = (1..30).map { i -> policy.offer(i * 33 * ms, pose(0.0, i * 5.0)) }
        val firstSave = decisions.indexOf(KeyframePolicy.Decision.SAVE)
        assertTrue(decisions.take(firstSave).all { it == KeyframePolicy.Decision.TOO_SOON || it == KeyframePolicy.Decision.BLURRED })
        assertTrue(decisions.contains(KeyframePolicy.Decision.BLURRED))
        // Saved anyway once 0.7 s have passed without a frame: a blurred doorway beats a missing one.
        assertEquals(21, firstSave)
        assertTrue(policy.turnRateDeg > 140)
    }

    @Test
    fun `a repeated frame or a gap in time is handled`() {
        val policy = KeyframePolicy()
        assertEquals(KeyframePolicy.Decision.SAVE, policy.offer(1_000 * ms, pose(0.0)))
        assertEquals(KeyframePolicy.Decision.TOO_SOON, policy.offer(1_000 * ms, pose(0.5)))
        // After two seconds without frames (tracking was lost) the jump says nothing about speed.
        assertEquals(KeyframePolicy.Decision.SAVE, policy.offer(3_000 * ms, pose(2.0)))
        assertEquals(0.0, policy.speed, 0.0)
        assertEquals(0.0, policy.turnRateDeg, 0.0)
    }

    // ---------------------------------------------------------------------------------------------
    // Image planes

    /** A plane of [rows] x [cols] values laid out with [rowStride] / [pixelStride], padding filled with 0x7F. */
    private fun plane(rows: Int, cols: Int, rowStride: Int, pixelStride: Int, value: (r: Int, c: Int) -> Int): ByteBuffer {
        // Like a real camera plane, the last row stops at its last pixel.
        val size = (rows - 1) * rowStride + (cols - 1) * pixelStride + 1
        val bytes = ByteArray(size) { 0x7F }
        for (r in 0 until rows) for (c in 0 until cols) bytes[r * rowStride + c * pixelStride] = value(r, c).toByte()
        return ByteBuffer.wrap(bytes)
    }

    private fun expectedNv21(width: Int, height: Int): ByteArray {
        val out = ByteArray(ImagePlanes.nv21Size(width, height))
        var k = 0
        for (r in 0 until height) for (c in 0 until width) out[k++] = (r * 16 + c).toByte()
        for (r in 0 until height / 2) for (c in 0 until width / 2) {
            out[k++] = (200 + r * 4 + c).toByte() // V
            out[k++] = (100 + r * 4 + c).toByte() // U
        }
        return out
    }

    @Test
    fun `a camera image becomes NV21 whatever the plane layout`() {
        val w = 8
        val h = 4
        // (row stride of Y, row stride of U/V, pixel stride of U/V): planar, semi-planar, padded rows.
        for ((yStride, uvStride, pixelStride) in listOf(Triple(8, 4, 1), Triple(8, 8, 2), Triple(12, 12, 2), Triple(16, 6, 1))) {
            val y = plane(h, w, yStride, 1) { r, c -> r * 16 + c }
            val u = plane(h / 2, w / 2, uvStride, pixelStride) { r, c -> 100 + r * 4 + c }
            val v = plane(h / 2, w / 2, uvStride, pixelStride) { r, c -> 200 + r * 4 + c }
            y.position(3) // the planes' own positions must not matter, nor be moved
            val out = ByteArray(ImagePlanes.nv21Size(w, h))
            ImagePlanes.toNv21(y, u, v, w, h, yStride, uvStride, pixelStride, out, ByteArray(maxOf(yStride, uvStride)))
            assertArrayEquals("strides $yStride/$uvStride/$pixelStride", expectedNv21(w, h), out)
            assertEquals(3, y.position())
            assertEquals(0, u.position())
        }
        assertEquals(1280 * 720 * 3 / 2, ImagePlanes.nv21Size(1280, 720))
    }

    @Test
    fun `a depth plane is copied without its row padding`() {
        // 3 x 2 pixels of 16 bits, rows padded to 10 bytes.
        val bytes = ByteArray(10 + 6) { 0x7F }
        val pixels = listOf(listOf(0x0102, 0x0304, 0x0506), listOf(0x0708, 0x090A, 0x0B0C))
        for (r in 0..1) for (c in 0..2) {
            bytes[r * 10 + c * 2] = (pixels[r][c] and 0xFF).toByte()
            bytes[r * 10 + c * 2 + 1] = (pixels[r][c] shr 8).toByte()
        }
        val out = ImagePlanes.copyPlane(ByteBuffer.wrap(bytes), width = 3, height = 2, rowStride = 10, bytesPerPixel = 2)
        assertArrayEquals(byteArrayOf(2, 1, 4, 3, 6, 5, 8, 7, 10, 9, 12, 11), out)
        // Without padding it is one copy.
        val tight = ImagePlanes.copyPlane(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6)), 3, 2, 3, 1)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), tight)
    }

    // ---------------------------------------------------------------------------------------------
    // Records

    private val frame = FrameRecord(
        index = 12,
        timestampNs = 123_456_789_012L,
        segment = 0,
        image = WalkthroughFormat.rgbPath(12),
        pose = listOf(1.5f, 1.4f, -2.25f, 0f, 0.7071f, 0f, 0.7071f),
        displayPose = listOf(1.5f, 1.4f, -2.25f, 0.5f, 0.5f, -0.5f, 0.5f),
        intrinsics = Intrinsics(912.5f, 911.75f, 640.2f, 358.9f, 1280, 720),
        textureIntrinsics = Intrinsics(1368.75f, 1367.6f, 960.3f, 538.4f, 1920, 1080),
        turnRate = 12.5f,
        speed = 0.6f,
        depth = WalkthroughFormat.depthPath(12),
        confidence = WalkthroughFormat.confidencePath(12),
        depthWidth = 160,
        depthHeight = 90,
        depthTimestampNs = 123_456_000_000L,
    )

    @Test
    fun `a frame record is one line of JSON and reads back the same`() {
        val line = WalkthroughFormat.encodeFrame(frame)
        assertFalse(line.contains('\n'))
        assertTrue(line.contains("\"image\":\"rgb/000012.jpg\""))
        assertEquals(frame, WalkthroughFormat.decodeFrame(line))
        // A frame without depth simply leaves the fields out; a newer writer's extra fields are ignored.
        val plain = frame.copy(depth = null, confidence = null, depthWidth = null, depthHeight = null, depthTimestampNs = null)
        val plainLine = WalkthroughFormat.encodeFrame(plain)
        assertFalse(plainLine.contains("depth"))
        assertEquals(plain, WalkthroughFormat.decodeFrame(plainLine.dropLast(1) + ",\"somethingNew\":1}"))
        assertNull(WalkthroughFormat.decodeFrame(plainLine).depthTimestampNs)
    }

    @Test
    fun `numbers are written with a dot whatever the phone's language`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val line = StringBuilder()
            WalkthroughFormat.trajectoryLine(line, 42L, 1, true, floatArrayOf(1.5f, -0.25f, 3f, 0f, 0f, 0f, 1f))
            assertEquals("42,1,1,1.5,-0.25,3.0,0.0,0.0,0.0,1.0\n", line.toString())
            assertEquals(10, WalkthroughFormat.TRAJECTORY_HEADER.split(',').size)
            assertEquals("depth/000007.raw16", WalkthroughFormat.depthPath(7))
            assertTrue(WalkthroughFormat.encodeFrame(frame).contains("912.5"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `the summary reads back and says what it is`() {
        val meta = WalkthroughMeta(
            name = "Walkthrough-20261125-1432", startedAtMillis = 1_764_000_000_000L, durationSeconds = 187.5,
            device = "samsung SM-S948U", android = "16 (API 36)", app = "FloorPlan Scan 0.1.0 (build 1)",
            imageWidth = 1280, imageHeight = 720, jpegQuality = 90, depthEnabled = true, hardwareDepth = false,
            frames = 640, depthFrames = 410, droppedFrames = 3, trackingLostFrames = 12, segments = 1,
        )
        val text = WalkthroughFormat.encodeMeta(meta)
        assertTrue(text.contains("\"format\": \"floorplan-walkthrough\""))
        assertTrue(text.contains("\"version\": 1"))
        assertEquals(meta, WalkthroughFormat.decodeMeta(text))
    }

    /**
     * Writes a two-frame recording with the app's own code into `build/walkthrough-sample`, where
     * `tools/walkthrough/test_recording.py` reads it back: the phone's writer and the computer's reader are
     * two programs in two languages, and this is what keeps them agreeing on the format.
     */
    @Test
    fun `a sample for the computer-side reader`() {
        val dir = File("build/walkthrough-sample")
        dir.deleteRecursively()
        for (name in listOf(WalkthroughFormat.RGB_DIR, WalkthroughFormat.DEPTH_DIR, WalkthroughFormat.CONFIDENCE_DIR)) File(dir, name).mkdirs()
        val pose = listOf(1.5f, 1.4f, -2.25f, 0f, 0f, 0f, 1f)
        val first = frame.copy(
            index = 0, timestampNs = 1_000L, image = WalkthroughFormat.rgbPath(0), pose = pose, displayPose = pose,
            depth = WalkthroughFormat.depthPath(0), confidence = WalkthroughFormat.confidencePath(0),
            depthWidth = 3, depthHeight = 2, depthTimestampNs = 1_000L,
        )
        val second = frame.copy(
            index = 1, timestampNs = 3_000L, image = WalkthroughFormat.rgbPath(1), pose = pose, displayPose = pose,
            depth = null, confidence = null, depthWidth = null, depthHeight = null, depthTimestampNs = null,
        )
        File(dir, WalkthroughFormat.FRAMES_FILE).writeText(listOf(first, second).joinToString("") { WalkthroughFormat.encodeFrame(it) + "\n" })
        val trajectory = StringBuilder(WalkthroughFormat.TRAJECTORY_HEADER).append('\n')
        WalkthroughFormat.trajectoryLine(trajectory, 1_000L, 0, true, pose.toFloatArray())
        WalkthroughFormat.trajectoryLine(trajectory, 2_000L, 0, false, pose.toFloatArray())
        WalkthroughFormat.trajectoryLine(trajectory, 3_000L, 0, true, pose.toFloatArray())
        File(dir, WalkthroughFormat.TRAJECTORY_FILE).writeText(trajectory.toString())
        // 3 x 2 depth pixels in millimetres, little-endian, rows from the top; padded rows as a camera gives them.
        val millimetres = listOf(1_000, 1_500, 2_000, 1_200, 1_800, 2_500)
        val padded = ByteArray(8 + 6) { 0x7F }
        for (i in millimetres.indices) {
            val at = (i / 3) * 8 + (i % 3) * 2
            padded[at] = (millimetres[i] and 0xFF).toByte()
            padded[at + 1] = (millimetres[i] shr 8).toByte()
        }
        File(dir, first.depth!!).writeBytes(ImagePlanes.copyPlane(ByteBuffer.wrap(padded), 3, 2, 8, 2))
        File(dir, first.confidence!!).writeBytes(byteArrayOf(200.toByte(), -1, -1, -1, -1, -1))
        File(dir, first.image).writeBytes(byteArrayOf(1, 2, 3))
        File(dir, second.image).writeBytes(byteArrayOf(4, 5, 6))
        val wall = PlaneRecord("VERTICAL", pose, 2f, 1.5f, listOf(-1f, -0.75f, 1f, -0.75f, 1f, 0.75f, -1f, 0.75f))
        File(dir, WalkthroughFormat.PLANES_FILE).writeText(WalkthroughFormat.encodePlanes(listOf(wall)))
        val meta = WalkthroughMeta(
            name = "sample", startedAtMillis = 0L, durationSeconds = 0.1, device = "unit test", android = "-", app = "-",
            imageWidth = 1280, imageHeight = 720, jpegQuality = 90, depthEnabled = true, hardwareDepth = false,
            frames = 2, depthFrames = 1, droppedFrames = 0, trackingLostFrames = 1, segments = 1,
        )
        File(dir, WalkthroughFormat.META_FILE).writeText(WalkthroughFormat.encodeMeta(meta))
        assertEquals(2, File(dir, WalkthroughFormat.FRAMES_FILE).readLines().size)
    }

    // ---------------------------------------------------------------------------------------------
    // On the phone

    private fun fill(dir: File, frames: Int) {
        File(dir, "rgb").mkdirs()
        File(dir, "depth").mkdirs()
        for (i in 0 until frames) {
            File(dir, WalkthroughFormat.rgbPath(i)).writeBytes(ByteArray(2_000) { (it * 31 + i).toByte() })
            File(dir, WalkthroughFormat.depthPath(i)).writeBytes(ByteArray(600))
        }
        File(dir, WalkthroughFormat.FRAMES_FILE).writeText((0 until frames).joinToString("") { "{\"index\":$it}\n" })
        File(dir, WalkthroughFormat.META_FILE).writeText("{}")
    }

    @Test
    fun `a recording is packed into one zip and its folder removed`() {
        val store = WalkthroughStore(File(temp.root, "walkthroughs"))
        val dir = store.newRecordingDir(1_764_000_000_000L)
        assertTrue(dir.name.startsWith("Walkthrough-"))
        fill(dir, 5)
        assertEquals(listOf(dir), store.unfinished())

        val shares = ArrayList<Float>()
        val packed = store.pack(dir) { shares += it }
        assertFalse(dir.exists())
        assertTrue(store.unfinished().isEmpty())
        assertEquals(listOf(packed), store.recordings())
        assertEquals(dir.name, packed.name)
        assertEquals(1f, shares.last(), 0f)
        assertTrue(shares.zipWithNext().all { (a, b) -> b >= a })

        ZipFile(packed.file).use { zip ->
            val names = zip.entries().toList().map { it.name }.sorted()
            assertEquals(12, names.size)
            assertTrue(names.containsAll(listOf("meta.json", "frames.jsonl", "rgb/000004.jpg", "depth/000000.raw16")))
            val jpeg = zip.getInputStream(zip.getEntry("rgb/000003.jpg")).readBytes()
            assertArrayEquals(ByteArray(2_000) { (it * 31 + 3).toByte() }, jpeg)
            // Depth compresses, the JPEG is stored as it is.
            assertTrue(zip.getEntry("depth/000000.raw16").compressedSize < 100)
        }

        // A second recording in the same minute gets its own name; deleting works.
        val again = store.newRecordingDir(1_764_000_000_000L)
        assertEquals(dir.name + "-2", again.name)
        store.discard(again)
        assertFalse(again.exists())
        assertTrue(store.delete(packed))
        assertTrue(store.recordings().isEmpty())
    }

    @Test
    fun `an empty folder cannot be packed and nothing is left behind`() {
        val root = File(temp.root, "walkthroughs")
        val store = WalkthroughStore(root)
        val dir = store.newRecordingDir(1L)
        try {
            store.pack(dir)
            throw AssertionError("packing an empty recording must fail")
        } catch (e: java.io.IOException) {
            // expected
        }
        assertTrue(dir.exists())
        assertTrue(root.listFiles()!!.none { it.name.endsWith(".partial") || it.name.endsWith(".zip") })
    }

    @Test
    fun `recording stops while there is still room to pack it`() {
        val mb = 1024L * 1024
        assertFalse(WalkthroughStore.storageExhausted(freeBytes = 5_000 * mb, recordedBytes = 300 * mb))
        assertTrue(WalkthroughStore.storageExhausted(freeBytes = 450 * mb, recordedBytes = 300 * mb))
        // Before the first frame: 200 MB must be free to start at all.
        assertTrue(WalkthroughStore.storageExhausted(freeBytes = 150 * mb, recordedBytes = 0))
        assertFalse(WalkthroughStore.storageExhausted(freeBytes = 250 * mb, recordedBytes = 0))
    }
}
