package com.oroplex.floorplan.core.io

import com.oroplex.floorplan.core.model.Project

object ProjectJson {
    /** Compact JSON with defaults encoded (stable for backups). */
    fun encode(project: Project): String = TODO("implement")

    /** Lenient decode (ignores unknown keys). Throws IllegalArgumentException with a clear message on bad input. */
    fun decode(json: String): Project = TODO("implement")
}
