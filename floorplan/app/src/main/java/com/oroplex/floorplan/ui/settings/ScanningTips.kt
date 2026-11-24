package com.oroplex.floorplan.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.SlowMotionVideo
import androidx.compose.material.icons.outlined.SocialDistance
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Texture
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

private data class Tip(val icon: ImageVector, val title: String, val detail: String)

private val TIPS = listOf(
    Tip(
        Icons.Outlined.WbSunny,
        "Use good lighting",
        "Turn on the lights and open the blinds. AR tracking needs a bright scene with visible detail.",
    ),
    Tip(
        Icons.Outlined.Texture,
        "Aim at textured wall areas above furniture",
        "Pictures, outlets, trim and door frames give the depth sensing detail to lock onto; blank or " +
            "glossy paint is harder. Aim above sofas, beds and cabinets so furniture isn't measured as wall.",
    ),
    Tip(
        Icons.Outlined.SocialDistance,
        "Stay within 1–4 m (3–13 ft) of walls",
        "Depth is most accurate at that range. Step back from walls that are very close and walk closer to far ones.",
    ),
    Tip(
        Icons.Outlined.SlowMotionVideo,
        "Move slowly",
        "Move the phone smoothly and hold steady while a wall is captured. Fast motion or covering the " +
            "camera makes tracking drift.",
    ),
    Tip(
        Icons.Outlined.FormatListNumbered,
        "Capture walls in order",
        "Go around the room in one direction, capturing each wall once, so corners are solved between neighbors.",
    ),
    Tip(
        Icons.Outlined.Map,
        "Scan the whole floor in one session",
        "Rooms scanned in the same session share one coordinate frame, so they line up automatically. " +
            "Use “Scan next room” instead of starting a new scan.",
    ),
    Tip(
        Icons.Outlined.Straighten,
        "Calibrate one wall with a tape measure",
        "Measure one long wall and enter it in the editor (tap the wall length, then Calibrate). The whole " +
            "floor is rescaled, removing the typical 1–3 % AR scale error.",
    ),
)

@Composable
internal fun ScanningTips() {
    Column(Modifier.fillMaxWidth()) {
        TIPS.forEach { tip ->
            ListItem(
                leadingContent = { Icon(tip.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(tip.title) },
                supportingContent = { Text(tip.detail) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            )
        }
    }
}
