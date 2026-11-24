package com.oroplex.floorplan.core.io

import com.oroplex.floorplan.core.model.Project
import kotlinx.serialization.json.Json

object ProjectJson {
    /** Longest failure reason quoted in a decode error (parser messages can embed large input excerpts). */
    private const val MAX_REASON_LENGTH = 200

    /**
     * - ignoreUnknownKeys / coerceInputValues: files written by a newer app version still open (unknown
     *   fields are skipped, an unknown enum value such as a new room type falls back to the field's default).
     * - encodeDefaults: backups are explicit and stable even if a default changes later.
     * - allowSpecialFloatingPointValues: a NaN that slipped in from bad AR data must never make a project
     *   unsavable (that would lose the user's work); it round-trips and the geometry code ignores it.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
    }

    /** Compact JSON with defaults encoded (stable for backups). */
    fun encode(project: Project): String = json.encodeToString(Project.serializer(), project)

    /**
     * Lenient decode (ignores unknown keys). Throws IllegalArgumentException with a clear message on bad input.
     * The message always starts with "Not a valid FloorPlan project file: " followed by a short reason.
     */
    fun decode(json: String): Project {
        if (json.isBlank()) throw IllegalArgumentException("Not a valid FloorPlan project file: the file is empty")
        return try {
            this.json.decodeFromString(Project.serializer(), json)
        } catch (e: Exception) {
            throw IllegalArgumentException("Not a valid FloorPlan project file: ${reason(e)}", e)
        } catch (e: StackOverflowError) {
            throw IllegalArgumentException("Not a valid FloorPlan project file: the data is nested too deeply", e)
        }
    }

    /** First line of the parser's message, trimmed to a readable length. */
    private fun reason(e: Throwable): String {
        val line = e.message?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
            ?: return e::class.simpleName ?: "unreadable data"
        return if (line.length <= MAX_REASON_LENGTH) line else line.take(MAX_REASON_LENGTH - 1) + "…"
    }
}
