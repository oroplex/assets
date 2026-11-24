package com.oroplex.floorplan.ui.view3d

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.render.Dollhouse
import com.oroplex.floorplan.core.render.FaceKind
import com.oroplex.floorplan.data.ProjectRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DollhouseStatus { LOADING, READY, MISSING_PROJECT, MISSING_FLOOR }

/** The 3D model of one floor, ready to draw. */
class DollhouseScene(
    val mesh: DollhouseMesh,
    /** Rooms on the floor, and how many of them could not be modeled (unusable outlines). */
    val roomCount: Int,
    val skippedRooms: Int,
) {
    val isEmpty: Boolean get() = mesh.faceCount == 0

    companion object {
        /** Model [rooms] with [settings] (Dollhouse.build) and flatten the result for rendering. */
        fun build(rooms: List<Room>, settings: PlanSettings): DollhouseScene {
            val faces = Dollhouse.build(rooms, settings)
            val modeled = faces.asSequence().filter { it.kind == FaceKind.FLOOR }.mapNotNull { it.roomId }.toSet()
            return DollhouseScene(DollhouseMesh.build(faces, rooms), rooms.size, rooms.count { it.id !in modeled })
        }
    }
}

data class DollhouseUiState(
    val status: DollhouseStatus = DollhouseStatus.LOADING,
    val projectName: String = "",
    val floorName: String = "",
    /** Rooms on the floor (whether or not they could be modeled). */
    val roomCount: Int = 0,
    /** Null until the first model is built. */
    val scene: DollhouseScene? = null,
    /** True when building the model failed outright (bad data); the screen says so instead of drawing. */
    val failed: Boolean = false,
)

/**
 * Loads one floor of a project, keeps its 3D model up to date (rebuilt off the main thread only when the
 * floor's rooms or the project settings change) and owns the orbit camera, so the view survives
 * configuration changes.
 *
 * The camera is Compose snapshot state: gestures update it and the canvas redraws without recomposing the
 * screen. It stays null until the viewport size is known ([onViewport]); then it frames the model.
 */
class DollhouseViewModel(
    private val repository: ProjectRepository,
    private val projectId: String,
    private val floorId: String,
    private val buildDispatcher: CoroutineDispatcher = Dispatchers.Default,
    val lens: Lens = Lens(),
    private val log: (message: String, error: Throwable) -> Unit = { m, e -> Log.w(TAG, m, e) },
) : ViewModel() {
    private val _state = MutableStateFlow(DollhouseUiState())
    val state: StateFlow<DollhouseUiState> = _state.asStateFlow()

    var camera: OrbitCamera? by mutableStateOf(null)
        private set

    private var limits: CameraLimits? = null
    private var viewport: Viewport? = null

    /** True once the user moved the camera; until then a rebuilt model is re-framed automatically. */
    private var userMoved = false

    private var builtFrom: Pair<List<Room>, PlanSettings>? = null
    private var buildJob: Job? = null

    private data class Viewport(val width: Double, val height: Double, val margin: Double)

    init {
        viewModelScope.launch {
            // get() waits for the store to load, so a null from observe() afterwards really means "missing".
            try {
                repository.get(projectId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log("Loading project $projectId failed", e)
            }
            repository.observe(projectId).collect { onProject(it) }
        }
    }

    private fun onProject(project: Project?) {
        if (project == null) {
            buildJob?.cancel()
            builtFrom = null
            _state.value = DollhouseUiState(status = DollhouseStatus.MISSING_PROJECT)
            return
        }
        val floor = project.floors.firstOrNull { it.id == floorId }
        if (floor == null) {
            buildJob?.cancel()
            builtFrom = null
            _state.value = DollhouseUiState(status = DollhouseStatus.MISSING_FLOOR, projectName = project.name)
            return
        }
        _state.update {
            it.copy(status = DollhouseStatus.READY, projectName = project.name, floorName = floor.name, roomCount = floor.rooms.size)
        }
        val source = floor.rooms to project.settings
        if (source == builtFrom) return
        builtFrom = source
        buildJob?.cancel()
        buildJob = viewModelScope.launch {
            val scene = withContext(buildDispatcher) {
                try {
                    DollhouseScene.build(source.first, source.second)
                } catch (e: Exception) {
                    log("Building the 3D model failed", e)
                    null
                }
            }
            _state.update { it.copy(scene = scene ?: it.scene, failed = scene == null) }
            if (scene != null) onSceneChanged(scene)
        }
    }

    private fun onSceneChanged(scene: DollhouseScene) {
        val newLimits = CameraLimits.around(scene.mesh.bounds)
        limits = newLimits
        val current = camera
        camera = if (current == null || !userMoved) framedCamera() else current.coerceIn(newLimits)
    }

    /** The canvas size (pixels) and the margin to keep around the framed model. Frames the model on first call. */
    fun onViewport(widthPx: Int, heightPx: Int, marginPx: Float) {
        if (widthPx <= 0 || heightPx <= 0) return
        val next = Viewport(widthPx.toDouble(), heightPx.toDouble(), marginPx.toDouble())
        if (next == viewport) return
        viewport = next
        if (camera == null || !userMoved) camera = framedCamera()
    }

    /** The camera that frames the whole model from the default angle, or null before the model and viewport are known. */
    fun framedCamera(): OrbitCamera? {
        val scene = _state.value.scene ?: return null
        val vp = viewport ?: return null
        if (scene.isEmpty) return null
        val framed = OrbitCamera.framing(scene.mesh.bounds, vp.width, vp.height, lens, vp.margin)
        val base = limits ?: CameraLimits.around(scene.mesh.bounds)
        // Very wide or tall models may need to start further out than the usual zoom range.
        val widened = if (framed.distance > base.maxDistance) base.copy(maxDistance = framed.distance * 1.5) else base
        limits = widened
        return framed.coerceIn(widened)
    }

    fun orbit(dxPx: Float, dyPx: Float, density: Float) = move { c, l -> OrbitGestures.orbit(c, dxPx.toDouble(), dyPx.toDouble(), density.toDouble(), l) }

    fun zoom(factor: Float) = move { c, l -> OrbitGestures.zoom(c, factor.toDouble(), l) }

    fun pan(dxPx: Float, dyPx: Float) = move { c, l ->
        OrbitGestures.pan(c, dxPx.toDouble(), dyPx.toDouble(), viewport?.height ?: 0.0, lens, l)
    }

    /** Set by the reset animation; [settled] marks the end of it (the view is framed again, not user-moved). */
    fun setCamera(next: OrbitCamera, settled: Boolean) {
        val l = limits
        camera = if (l != null) next.coerceIn(l) else next
        if (settled) userMoved = false
    }

    private inline fun move(transform: (OrbitCamera, CameraLimits) -> OrbitCamera) {
        val c = camera ?: return
        val l = limits ?: return
        val next = transform(c, l)
        if (next != c) {
            userMoved = true
            camera = next
        }
    }

    class Factory(
        private val repository: ProjectRepository,
        private val projectId: String,
        private val floorId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DollhouseViewModel::class.java)) { "Unknown ViewModel $modelClass" }
            return DollhouseViewModel(repository, projectId, floorId) as T
        }
    }

    private companion object {
        const val TAG = "Dollhouse"
    }
}
