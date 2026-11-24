package com.oroplex.floorplan.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.data.SettingsRepository
import com.oroplex.floorplan.data.appContainer
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Accepted ranges for the editable lengths (meters); deliberately narrower than the repository's sanity limits. */
private val WALL_THICKNESS_RANGE = 0.025..0.6
private val CEILING_RANGE = 1.5..6.0
private val DOOR_RANGE = 1.5..3.5
private val DOOR_WIDTH_RANGE = 0.4..2.5
private val WINDOW_WIDTH_RANGE = 0.2..4.0
private val WINDOW_HEIGHT_RANGE = 0.2..3.0
private val WINDOW_SILL_RANGE = 0.0..2.5
private const val MIN_TOLERANCE_DEG = 1f
private const val MAX_TOLERANCE_DEG = 10f

/** Imperial display precision choices: denominator to label. */
private val PRECISIONS = listOf(1 to "1\"", 2 to "1/2\"", 4 to "1/4\"", 8 to "1/8\"")

/**
 * App settings: defaults for new projects, company branding for exports, help and about.
 *
 * @param onShowTutorial replays the welcome tour; the "Show tutorial again" row is hidden when null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onShowTutorial: (() -> Unit)? = null) {
    val container = appContainer()
    val repository = container.settings
    val settings by repository.defaults.collectAsStateWithLifecycle()
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { text ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmReset = true }) {
                        Icon(Icons.Outlined.RestartAlt, contentDescription = "Restore default settings")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding)
                .imePadding(),
            contentPadding = padding,
        ) {
            item(key = "intro") {
                Text(
                    "These defaults apply to new projects. Existing projects keep their own settings; " +
                        "change those from the plan editor's menu.",
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item(key = "units") {
                SectionHeader("Units")
                UnitsSetting(settings, repository)
            }

            item(key = "labels") {
                SectionHeader("Plan labels")
                SwitchSetting(
                    title = "Show wall lengths",
                    supporting = "Dimension every wall along its inside face",
                    checked = settings.showWallLengths,
                    onCheckedChange = { v -> repository.update { it.copy(showWallLengths = v) } },
                )
                SwitchSetting(
                    title = "Show room areas",
                    supporting = "Print each room's net floor area under its name",
                    checked = settings.showRoomAreas,
                    onCheckedChange = { v -> repository.update { it.copy(showRoomAreas = v) } },
                )
                SwitchSetting(
                    title = "Show overall dimensions",
                    supporting = "Total width and depth of the floor, outside the plan",
                    checked = settings.showOverallDimensions,
                    onCheckedChange = { v -> repository.update { it.copy(showOverallDimensions = v) } },
                )
            }

            item(key = "walls") {
                SectionHeader("Walls")
                LengthSettingField(
                    label = "Wall thickness",
                    valueMeters = settings.wallThickness,
                    unitSystem = settings.unitSystem,
                    imperialDenominator = settings.imperialDenominator,
                    range = WALL_THICKNESS_RANGE,
                    supportingText = "Drawn on the plan. Interior walls are typically 4 1/2\" (11.4 cm).",
                    onCommit = { v -> repository.update { it.copy(wallThickness = v) } },
                )
                SquaringSetting(settings, repository)
            }

            item(key = "heights") {
                SectionHeader("Defaults for new rooms")
                LengthSettingField(
                    label = "Ceiling height",
                    valueMeters = settings.defaultCeilingHeight,
                    unitSystem = settings.unitSystem,
                    imperialDenominator = settings.imperialDenominator,
                    range = CEILING_RANGE,
                    supportingText = "Used when a room's ceiling isn't measured",
                    onCommit = { v -> repository.update { it.copy(defaultCeilingHeight = v) } },
                )
                LengthSettingField(
                    label = "Door width",
                    valueMeters = settings.defaultDoorWidth,
                    unitSystem = settings.unitSystem,
                    imperialDenominator = settings.imperialDenominator,
                    range = DOOR_WIDTH_RANGE,
                    supportingText = "For newly placed doors and openings",
                    onCommit = { v -> repository.update { it.copy(defaultDoorWidth = v) } },
                )
                LengthSettingField(
                    label = "Door height",
                    valueMeters = settings.defaultDoorHeight,
                    unitSystem = settings.unitSystem,
                    imperialDenominator = settings.imperialDenominator,
                    range = DOOR_RANGE,
                    supportingText = "For newly placed doors and openings",
                    onCommit = { v -> repository.update { it.copy(defaultDoorHeight = v) } },
                )
                LengthSettingField(
                    label = "Window width",
                    valueMeters = settings.defaultWindowWidth,
                    unitSystem = settings.unitSystem,
                    imperialDenominator = settings.imperialDenominator,
                    range = WINDOW_WIDTH_RANGE,
                    supportingText = "For newly placed windows",
                    onCommit = { v -> repository.update { it.copy(defaultWindowWidth = v) } },
                )
                LengthSettingField(
                    label = "Window height",
                    valueMeters = settings.defaultWindowHeight,
                    unitSystem = settings.unitSystem,
                    imperialDenominator = settings.imperialDenominator,
                    range = WINDOW_HEIGHT_RANGE,
                    supportingText = "For newly placed windows",
                    onCommit = { v -> repository.update { it.copy(defaultWindowHeight = v) } },
                )
                LengthSettingField(
                    label = "Window sill height",
                    valueMeters = settings.defaultWindowSill,
                    unitSystem = settings.unitSystem,
                    imperialDenominator = settings.imperialDenominator,
                    range = WINDOW_SILL_RANGE,
                    allowZero = true,
                    supportingText = "Floor to the bottom of the window",
                    onCommit = { v -> repository.update { it.copy(defaultWindowSill = v) } },
                )
            }

            item(key = "branding") {
                SectionHeader("Company branding")
                BrandingSection(container.branding, onMessage = showMessage)
            }

            item(key = "tips") {
                SectionHeader("Help and scanning tips")
                if (onShowTutorial != null) {
                    ListItem(
                        modifier = Modifier.clickable(onClick = onShowTutorial),
                        leadingContent = {
                            Icon(Icons.Outlined.School, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        },
                        headlineContent = { Text("Show tutorial again") },
                        supportingContent = { Text("The four-page welcome tour: how scanning works and how to get accurate plans") },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                }
                ScanningTips()
            }

            item(key = "about") {
                SectionHeader("About")
                AboutSection()
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Restore defaults?") },
            text = {
                Text(
                    "Units, labels, wall thickness, squaring and default sizes go back to their original values. " +
                        "Company branding is kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    repository.update { PlanSettings() }
                    confirmReset = false
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitsSetting(settings: PlanSettings, repository: SettingsRepository) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val options = listOf(UnitSystem.IMPERIAL to "Imperial (ft, in)", UnitSystem.METRIC to "Metric (m, cm)")
            options.forEachIndexed { index, (system, label) ->
                SegmentedButton(
                    selected = settings.unitSystem == system,
                    onClick = { repository.update { it.copy(unitSystem = system) } },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(label, maxLines = 1) }
            }
        }

        val imperial = settings.unitSystem == UnitSystem.IMPERIAL
        Text(
            "Imperial precision",
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = if (imperial) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            PRECISIONS.forEachIndexed { index, (denominator, label) ->
                SegmentedButton(
                    selected = settings.imperialDenominator == denominator,
                    onClick = { repository.update { it.copy(imperialDenominator = denominator) } },
                    shape = SegmentedButtonDefaults.itemShape(index, PRECISIONS.size),
                    enabled = imperial,
                ) { Text(label, maxLines = 1) }
            }
        }
        val precisionLabel = PRECISIONS.firstOrNull { it.first == settings.imperialDenominator }?.second
            ?: "1/${settings.imperialDenominator}\""
        Text(
            if (imperial) {
                "Lengths are rounded to the nearest $precisionLabel."
            } else {
                "Metric lengths show meters with two decimals (centimeters below 1 m)."
            },
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SquaringSetting(settings: PlanSettings, repository: SettingsRepository) {
    SwitchSetting(
        title = "Square corners automatically",
        supporting = "Snap walls that are almost perpendicular to exactly 90° when a room is solved",
        checked = settings.squareAngles,
        onCheckedChange = { v -> repository.update { it.copy(squareAngles = v) } },
    )
    val stored = settings.squareToleranceDeg.toFloat().coerceIn(MIN_TOLERANCE_DEG, MAX_TOLERANCE_DEG)
    // Local while dragging; written to the repository once the gesture ends.
    var dragValue by remember(stored) { mutableFloatStateOf(stored) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Squaring tolerance",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                color = if (settings.squareAngles) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("±${dragValue.roundToInt()}°", style = MaterialTheme.typography.bodyLarge)
        }
        Slider(
            value = dragValue,
            onValueChange = { dragValue = it },
            onValueChangeFinished = {
                val deg = dragValue.roundToInt().toDouble()
                repository.update { it.copy(squareToleranceDeg = deg) }
            },
            valueRange = MIN_TOLERANCE_DEG..MAX_TOLERANCE_DEG,
            steps = (MAX_TOLERANCE_DEG - MIN_TOLERANCE_DEG).roundToInt() - 1,
            enabled = settings.squareAngles,
        )
        Text(
            "Only walls within this angle of the room's main direction are snapped; larger angles are kept as measured.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
