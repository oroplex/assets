package com.oroplex.floorplan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.oroplex.floorplan.ui.projects.ProjectListScreen
import com.oroplex.floorplan.ui.theme.FloorPlanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FloorPlanTheme {
                // TODO: NavHost (projects, editor/{projectId}, capture/{projectId}/{floorId}, export/{projectId}, settings)
                ProjectListScreen(onOpenProject = {}, onOpenSettings = {})
            }
        }
    }
}
