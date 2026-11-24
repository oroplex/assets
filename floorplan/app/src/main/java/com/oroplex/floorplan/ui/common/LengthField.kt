package com.oroplex.floorplan.ui.common

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.oroplex.floorplan.core.model.PlanSettings

/**
 * State of one length input. [value] is the parsed length in meters, or null while the text is not a
 * valid length. While the text is still the untouched initial text, [value] returns the exact initial
 * meters, so confirming a dialog without editing a field never re-rounds it to the display precision.
 */
@Stable
class LengthFieldState(
    private val initialText: String,
    private val initialMeters: Double?,
    val settings: PlanSettings,
    val smallUnits: Boolean,
    val allowZero: Boolean,
    text: String = initialText,
) {
    var text by mutableStateOf(text)

    val isEdited: Boolean get() = text != initialText

    val value: Double?
        get() = if (!isEdited && initialMeters != null) {
            initialMeters
        } else {
            LengthInput.parse(text, settings.unitSystem, smallUnits, allowZero)
        }
}

/**
 * Remembers a [LengthFieldState] prefilled with [initialMeters] formatted in the project's units.
 * The typed text survives configuration changes; the state is recreated when [settings] change units.
 */
@Composable
fun rememberLengthFieldState(
    initialMeters: Double?,
    settings: PlanSettings,
    smallUnits: Boolean = false,
    allowZero: Boolean = false,
): LengthFieldState {
    val initialText = initialMeters?.takeIf { it.isFinite() }?.let { settings.formatLength(it) }.orEmpty()
    val saver = Saver<LengthFieldState, String>(
        save = { it.text },
        restore = { LengthFieldState(initialText, initialMeters, settings, smallUnits, allowZero, text = it) },
    )
    return rememberSaveable(settings.unitSystem, settings.imperialDenominator, smallUnits, allowZero, saver = saver) {
        LengthFieldState(initialText, initialMeters, settings, smallUnits, allowZero)
    }
}

/**
 * Text field for a length in the project's units. Shows the parsed interpretation live ("= 2' 8\"") so the
 * user can see how the input was understood, an error when it cannot be parsed, or [error] (e.g. a range
 * violation computed by the caller) when the value parses but is not acceptable.
 */
@Composable
fun LengthField(
    state: LengthFieldState,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
) {
    val parsed = state.value
    val blank = state.text.isBlank()
    val supporting: String
    val isError: Boolean
    when {
        blank -> {
            supporting = LengthInput.example(state.settings.unitSystem, state.smallUnits)
            isError = false
        }
        parsed == null -> {
            supporting = "Not a valid length. " + LengthInput.example(state.settings.unitSystem, state.smallUnits)
            isError = true
        }
        error != null -> {
            supporting = error
            isError = true
        }
        else -> {
            supporting = "= " + state.settings.formatLength(parsed)
            isError = false
        }
    }
    OutlinedTextField(
        value = state.text,
        onValueChange = { state.text = it },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = { Text(supporting) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        modifier = modifier,
    )
}
