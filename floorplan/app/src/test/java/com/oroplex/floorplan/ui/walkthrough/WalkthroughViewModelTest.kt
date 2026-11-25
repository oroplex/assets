package com.oroplex.floorplan.ui.walkthrough

import com.oroplex.floorplan.recording.RecordingInfo
import com.oroplex.floorplan.recording.RecordingProgress
import com.oroplex.floorplan.recording.RecordingResult
import com.oroplex.floorplan.recording.WalkthroughFormat
import com.oroplex.floorplan.recording.WalkthroughRecording
import com.oroplex.floorplan.recording.WalkthroughStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class WalkthroughViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var root: File
    private var freeBytes = 50_000L * 1024 * 1024
    private var now = 1_764_000_000_000L

    /** Stands in for the AR layer: "records" by writing small files into the folder it is given. */
    private class FakeRecording : WalkthroughRecording {
        val state = MutableStateFlow(RecordingProgress())
        override val progress = state
        var directory: File? = null
        var info: RecordingInfo? = null
        var refuse = false
        var stoppedInterrupted: Boolean? = null
        private var frames = 0
        private var bytes = 0L

        override fun start(directory: File, info: RecordingInfo): Boolean {
            if (refuse) {
                state.value = RecordingProgress(problem = "Couldn't start recording: the storage is not writable.")
                return false
            }
            this.directory = directory
            this.info = info
            frames = 0
            bytes = 0L
            File(directory, WalkthroughFormat.RGB_DIR).mkdirs()
            state.value = RecordingProgress(active = true)
            return true
        }

        fun record(count: Int, bytesEach: Int = 1_000) {
            val dir = directory!!
            repeat(count) {
                File(dir, WalkthroughFormat.rgbPath(frames)).writeBytes(ByteArray(bytesEach))
                frames++
                bytes += bytesEach
            }
            state.value = state.value.copy(frames = frames, bytes = bytes, imageWidth = 1280, imageHeight = 720)
        }

        /** The recorder ends the recording itself (its length limit). */
        fun endByItself() {
            state.value = state.value.copy(active = false, problem = "The recording reached its length limit and was stopped.")
        }

        override suspend fun stop(interrupted: Boolean): RecordingResult? {
            val dir = directory ?: return null
            stoppedInterrupted = interrupted
            directory = null
            state.value = state.value.copy(active = false)
            return RecordingResult(dir, frames, bytes, 12.0, 0)
        }
    }

    private fun viewModel() = WalkthroughViewModel(
        store = WalkthroughStore(root) { freeBytes },
        describe = { name -> RecordingInfo(name, "test phone", "16", "FloorPlan Scan test") },
        io = dispatcher,
        clockMillis = { now },
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        root = File(temp.root, "walkthroughs")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `record, stop, and the recording is one zip on the phone`() = runTest(dispatcher) {
        val vm = viewModel()
        val recorder = FakeRecording()
        assertEquals(WalkthroughPhase.IDLE, vm.state.value.phase)
        vm.start(recorder)
        assertEquals(WalkthroughPhase.RECORDING, vm.state.value.phase)
        assertTrue(recorder.info!!.name.startsWith("Walkthrough-"))
        // A folder that is being written is not "unfinished".
        vm.refresh()
        assertEquals(0, vm.state.value.unfinished)

        recorder.record(40)
        now += 7_000
        advanceTimeBy(600)
        assertEquals(40, vm.state.value.progress.frames)
        assertEquals(7, vm.state.value.elapsedSeconds)

        vm.stop(recorder)
        val state = vm.state.value
        assertEquals(WalkthroughPhase.DONE, state.phase)
        assertEquals(false, recorder.stoppedInterrupted)
        val saved = assertNotNullAnd(state.saved)
        assertEquals(40, state.savedFrames)
        assertTrue(saved.file.isFile && saved.file.name.endsWith(".zip"))
        assertEquals(listOf(saved), state.recordings)
        assertEquals(1, root.listFiles()!!.size)

        vm.recordAnother()
        assertEquals(WalkthroughPhase.IDLE, vm.state.value.phase)
        vm.delete(saved)
        assertTrue(vm.state.value.recordings.isEmpty())
        assertFalse(saved.file.exists())
    }

    private fun <T> assertNotNullAnd(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    @Test
    fun `a recording without a single frame is not kept`() = runTest(dispatcher) {
        val vm = viewModel()
        val recorder = FakeRecording()
        vm.start(recorder)
        vm.stop(recorder)
        assertEquals(WalkthroughPhase.IDLE, vm.state.value.phase)
        assertTrue(vm.state.value.message!!.startsWith("Nothing was recorded"))
        assertTrue(root.listFiles().isNullOrEmpty())
    }

    @Test
    fun `discard throws the recording away`() = runTest(dispatcher) {
        val vm = viewModel()
        val recorder = FakeRecording()
        vm.start(recorder)
        recorder.record(10)
        vm.discard(recorder)
        assertEquals(WalkthroughPhase.IDLE, vm.state.value.phase)
        assertEquals("Recording discarded.", vm.state.value.message)
        assertTrue(root.listFiles().isNullOrEmpty())
        vm.dismissMessage()
        assertNull(vm.state.value.message)
    }

    @Test
    fun `starting needs a camera, storage and a recorder that agrees`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.start(null)
        assertEquals(WalkthroughPhase.IDLE, vm.state.value.phase)
        assertTrue(vm.state.value.message!!.contains("camera is not ready"))

        freeBytes = 100L * 1024 * 1024
        vm.start(FakeRecording())
        assertEquals(WalkthroughPhase.IDLE, vm.state.value.phase)
        assertTrue(vm.state.value.message!!.contains("Not enough free storage"))
        assertTrue(root.listFiles().isNullOrEmpty())

        freeBytes = 50_000L * 1024 * 1024
        vm.start(FakeRecording().apply { refuse = true })
        assertEquals(WalkthroughPhase.IDLE, vm.state.value.phase)
        assertTrue(vm.state.value.message!!.contains("not writable"))
        assertTrue(root.listFiles().isNullOrEmpty())
    }

    @Test
    fun `recording stops and is saved before the storage runs out`() = runTest(dispatcher) {
        val vm = viewModel()
        val recorder = FakeRecording()
        vm.start(recorder)
        recorder.record(20)
        freeBytes = 150L * 1024 * 1024
        advanceTimeBy(2_600)
        assertEquals(WalkthroughPhase.DONE, vm.state.value.phase)
        assertEquals(true, recorder.stoppedInterrupted)
        assertTrue(vm.state.value.message!!.contains("Storage is almost full"))
        assertNotNull(vm.state.value.saved)
    }

    @Test
    fun `a recording the recorder ends itself is still saved`() = runTest(dispatcher) {
        val vm = viewModel()
        val recorder = FakeRecording()
        vm.start(recorder)
        recorder.record(20)
        recorder.endByItself()
        advanceTimeBy(600)
        assertEquals(WalkthroughPhase.DONE, vm.state.value.phase)
        assertTrue(vm.state.value.message!!.contains("length limit"))
        assertEquals(20, vm.state.value.savedFrames)
    }

    @Test
    fun `leaving while recording asks first, then saves and leaves`() = runTest(dispatcher) {
        val vm = viewModel()
        val recorder = FakeRecording()
        vm.requestLeave()
        assertTrue(vm.state.value.leave)

        val recording = viewModel()
        recording.start(recorder)
        recorder.record(5)
        recording.requestLeave()
        assertTrue(recording.state.value.confirmLeave)
        assertFalse(recording.state.value.leave)
        recording.dismissLeave()
        assertFalse(recording.state.value.confirmLeave)
        assertEquals(WalkthroughPhase.RECORDING, recording.state.value.phase)

        recording.requestLeave()
        recording.stopAndLeave(recorder, discard = false)
        assertTrue(recording.state.value.leave)
        assertEquals(WalkthroughPhase.DONE, recording.state.value.phase)
        assertEquals(1, recording.state.value.recordings.size)
    }

    @Test
    fun `discard and leave keeps nothing`() = runTest(dispatcher) {
        val vm = viewModel()
        val recorder = FakeRecording()
        vm.start(recorder)
        recorder.record(5)
        vm.requestLeave()
        vm.stopAndLeave(recorder, discard = true)
        assertTrue(vm.state.value.leave)
        assertTrue(root.listFiles().isNullOrEmpty())
    }

    @Test
    fun `a recording cut off by a closed app is found and recovered`() = runTest(dispatcher) {
        val left = File(root, "Walkthrough-20261124-0900")
        File(left, WalkthroughFormat.RGB_DIR).mkdirs()
        File(left, WalkthroughFormat.rgbPath(0)).writeBytes(ByteArray(500))
        val vm = viewModel()
        assertEquals(1, vm.state.value.unfinished)
        vm.recover()
        assertEquals(WalkthroughPhase.IDLE, vm.state.value.phase)
        assertEquals("1 recording recovered.", vm.state.value.message)
        assertEquals(0, vm.state.value.unfinished)
        assertEquals(listOf("Walkthrough-20261124-0900"), vm.state.value.recordings.map { it.name })
    }

    @Test
    fun `the coach says the most urgent thing`() {
        val fine = RecordingProgress(active = true, tracking = true, turnRateDeg = 20f)
        assertEquals(WalkthroughCoach.TIPS[0], WalkthroughCoach.hint(fine, 0).text)
        assertEquals(WalkthroughCoach.TIPS[1], WalkthroughCoach.hint(fine, 9).text)
        assertEquals(WalkthroughCoach.TIPS[0], WalkthroughCoach.hint(fine, 8 * WalkthroughCoach.TIPS.size).text)
        assertFalse(WalkthroughCoach.hint(fine, 0).warning)

        val fast = WalkthroughCoach.hint(fine.copy(turnRateDeg = 90f), 3)
        assertTrue(fast.warning && fast.text.startsWith("Slow down"))
        // Lost tracking matters more than speed.
        val lost = WalkthroughCoach.hint(fine.copy(tracking = false, turnRateDeg = 90f), 3)
        assertTrue(lost.warning && lost.text.startsWith("Tracking lost"))
        val problem = WalkthroughCoach.hint(fine.copy(problem = "A frame could not be saved. Is the storage full?"), 3)
        assertTrue(problem.warning && problem.text.contains("storage"))

        assertEquals("0:00", WalkthroughCoach.clock(0))
        assertEquals("3:07", WalkthroughCoach.clock(187))
        assertEquals("640 kB", WalkthroughCoach.size(640_000))
        assertEquals("412 MB", WalkthroughCoach.size(412_300_000))
        assertEquals("1.3 GB", WalkthroughCoach.size(1_340_000_000))
    }
}
