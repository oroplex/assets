package com.oroplex.floorplan.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.oroplex.floorplan.core.measure.Units
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.UnitSystem
import kotlin.math.abs

@Composable
internal fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
internal fun SwitchSetting(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    ListItem(
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

/**
 * Parses [text] as a length for a settings field: [Units.parseLength] plus, when [allowZero], an
 * explicit zero ("0", "0 in", "0.0 m", ...), which parseLength deliberately rejects.
 */
internal fun parseSettingLength(text: String, system: UnitSystem, allowZero: Boolean): Double? {
    val parsed = Units.parseLength(text, system)
    if (parsed != null) return parsed
    return if (allowZero && ZERO_LENGTH.matches(text.trim())) 0.0 else null
}

private val ZERO_LENGTH = Regex("""^0+(?:[.,]0*)?\s*(?:m|cm|mm|in|inch|inches|ft|feet|'|")?$""", RegexOption.IGNORE_CASE)

/** Small pure rules of the settings screen (unit-tested). */
internal object SettingsRules {
    /** Explains the interior wall thickness with a typical value in the user's own units. */
    fun interiorWallHelp(system: UnitSystem): String = when (system) {
        UnitSystem.IMPERIAL -> "Drawn on the plan. Interior walls are typically 4 1/2\"."
        UnitSystem.METRIC -> "Drawn on the plan. Interior walls are typically about 10 cm."
    }

    /** Explains the exterior wall thickness, in the words of the editor's project settings. */
    const val EXTERIOR_WALL_HELP = "Used for overall dimensions, which measure to the outside of the exterior walls."

    /**
     * The exterior wall thickness in effect, which is what the field shows: the stored value, or the interior
     * wall thickness when none is set ([PlanSettings.exteriorWallThickness] null means "same as interior").
     */
    fun exteriorWallThickness(settings: PlanSettings): Double =
        settings.exteriorWallThickness?.takeIf { it.isFinite() && it > 0.0 } ?: settings.wallThickness
}

/**
 * A length setting edited as text in the user's units (e.g. `4 1/2"`, `11.4 cm`).
 *
 * The value is committed on the keyboard's Done action or when focus leaves the field. Invalid or
 * out-of-range input shows an error on Done; leaving the field with invalid input reverts to the
 * saved value so the screen never shows a number that is not actually in effect.
 */
@Composable
internal fun LengthSettingField(
    label: String,
    valueMeters: Double,
    unitSystem: UnitSystem,
    imperialDenominator: Int,
    range: ClosedFloatingPointRange<Double>,
    supportingText: String,
    onCommit: (Double) -> Unit,
    modifier: Modifier = Modifier,
    allowZero: Boolean = false,
) {
    fun format(meters: Double): String = Units.formatLength(meters, unitSystem, imperialDenominator)

    val formatted = remember(valueMeters, unitSystem, imperialDenominator) { format(valueMeters) }
    // Reset the text whenever the saved value or its formatting changes (e.g. units switched).
    var text by rememberSaveable(formatted) { mutableStateOf(formatted) }
    // Text that represents the value currently in effect. Re-parsing it would turn the display
    // rounding (e.g. 1/2") into a real change, so it is never committed again.
    var committedText by remember(formatted) { mutableStateOf(formatted) }
    var error by remember(formatted) { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    /** Returns true when the text was valid (and committed if it changed the value). */
    fun commit(): Boolean {
        if (text.trim() == committedText) {
            error = null
            return true
        }
        val parsed = parseSettingLength(text, unitSystem, allowZero)
        error = when {
            parsed == null -> "Not a length. Try ${if (unitSystem == UnitSystem.IMPERIAL) "8' 0\" or 96\"" else "2.44 m or 244 cm"}."
            parsed !in range -> "Must be between ${format(range.start)} and ${format(range.endInclusive)}."
            else -> null
        }
        if (parsed == null || error != null) return false
        text = format(parsed)
        committedText = text
        if (abs(parsed - valueMeters) > 1e-9) onCommit(parsed)
        return true
    }

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                error = null
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    val wasFocused = focused
                    focused = state.isFocused
                    if (wasFocused && !state.isFocused && !commit()) {
                        text = committedText
                        error = null
                    }
                },
            label = { Text(label) },
            singleLine = true,
            isError = error != null,
            supportingText = { Text(error ?: supportingText) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onDone = { if (commit()) focusManager.clearFocus() }),
        )
    }
}
