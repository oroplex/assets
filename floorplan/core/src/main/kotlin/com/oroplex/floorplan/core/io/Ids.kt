package com.oroplex.floorplan.core.io

import java.util.UUID

object Ids {
    fun newId(): String = UUID.randomUUID().toString()
}
