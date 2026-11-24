package com.oroplex.floorplan.ui.settings

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oroplex.floorplan.core.render.PlanColors
import com.oroplex.floorplan.data.Branding
import com.oroplex.floorplan.data.BrandingRepository
import com.oroplex.floorplan.data.BrandingRules
import com.oroplex.floorplan.export.PlanSheetPainter
import com.oroplex.floorplan.export.SheetBranding
import com.oroplex.floorplan.export.SheetBrandings
import com.oroplex.floorplan.export.SheetLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "BrandingSection"

/** Sample property shown in the header preview. */
private const val PREVIEW_PROJECT = "Smith Residence"
private const val PREVIEW_ADDRESS = "1234 Oak Street, Springfield"

/**
 * Company name, contact line and logo printed in the header of exported PDFs and PNGs, with a live
 * preview of that header on paper. Text is saved as you type; the logo is copied into app storage.
 */
@Composable
internal fun BrandingSection(repository: BrandingRepository, onMessage: (String) -> Unit) {
    val branding by repository.branding.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // The stored path never changes when a logo is replaced, so a local revision re-triggers decoding.
    var logoRevision by rememberSaveable { mutableIntStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    val logo = rememberLogoBitmap(branding.logoPath, logoRevision)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            val result = repository.setLogo(uri)
            importing = false
            result
                .onSuccess {
                    logoRevision++
                    onMessage("Logo updated")
                }
                .onFailure { onMessage(it.message ?: BrandingRules.MSG_UNREADABLE) }
        }
    }
    fun pickLogo() {
        try {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No photo picker", e)
            onMessage("No app is available to pick an image.")
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            "Shown in the header of every PDF and PNG you export.",
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BrandingTextField(
            label = "Company name",
            initial = branding.companyName,
            maxLength = BrandingRules.MAX_COMPANY_LENGTH,
            supporting = "For example, Oak & Stone Realty",
            capitalization = KeyboardCapitalization.Words,
            onChange = { v -> repository.update { it.copy(companyName = v) } },
        )
        BrandingTextField(
            label = "Contact line",
            initial = branding.contactLine,
            maxLength = BrandingRules.MAX_CONTACT_LENGTH,
            supporting = "Phone, email, website or license number",
            capitalization = KeyboardCapitalization.None,
            onChange = { v -> repository.update { it.copy(contactLine = v) } },
        )
        LogoRow(
            logo = logo,
            hasLogo = branding.logoPath != null,
            importing = importing,
            onPick = ::pickLogo,
            onRemove = {
                repository.clearLogo()
                onMessage("Logo removed")
            },
        )
        HeaderPreview(branding, logo)
    }
}

@Composable
private fun BrandingTextField(
    label: String,
    initial: String,
    maxLength: Int,
    supporting: String,
    capitalization: KeyboardCapitalization,
    onChange: (String) -> Unit,
) {
    // The field owns its text while it is shown; every change is saved right away, so nothing is lost
    // when the screen is left mid-edit.
    var text by rememberSaveable { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val cleaned = BrandingRules.cleanText(input, maxLength)
            if (cleaned != text) {
                text = cleaned
                onChange(cleaned)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        label = { Text(label) },
        singleLine = true,
        supportingText = {
            Row {
                Text(supporting, modifier = Modifier.weight(1f))
                if (text.length > maxLength * 3 / 4) Text("${text.length}/$maxLength")
            }
        },
        keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = ImeAction.Done),
    )
}

@Composable
private fun LogoRow(logo: Bitmap?, hasLogo: Boolean, importing: Boolean, onPick: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Logos are printed on white paper, so they are previewed on white too.
        Box(
            Modifier
                .size(width = 112.dp, height = 56.dp)
                .clip(MaterialTheme.shapes.small)
                .background(Color(PlanColors.BACKGROUND))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                importing -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                logo != null -> Image(
                    bitmap = logo.asImageBitmap(),
                    contentDescription = "Company logo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> Icon(
                    Icons.Outlined.Image,
                    contentDescription = null,
                    tint = Color(PlanColors.TEXT_SECONDARY),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Logo", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (hasLogo) "Printed at the top left of each page" else "PNG with a transparent background looks best",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(onClick = onPick, enabled = !importing) {
            Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (hasLogo) "Replace logo" else "Add logo")
        }
        if (hasLogo) {
            TextButton(onClick = onRemove, enabled = !importing) {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Remove")
            }
        }
    }
}

/** The export header band as it will print, drawn by the same painter as the PDF. */
@Composable
private fun HeaderPreview(branding: Branding, logo: Bitmap?) {
    val sheetBranding = SheetBranding.of(branding.companyName, branding.contactLine, logo)
    Text(
        "Preview",
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio((PREVIEW_WIDTH_PT / PREVIEW_HEIGHT_PT).toFloat())
                .background(Color(PlanColors.BACKGROUND))
                .semantics { contentDescription = "Preview of the export header" },
        ) {
            val scale = size.width / PREVIEW_WIDTH_PT
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.scale(scale.toFloat(), scale.toFloat())
                // Crop the page margin above the header band, keeping a little air.
                native.translate(0f, -(SheetLayout.MARGIN - PREVIEW_TOP_AIR_PT).toFloat())
                PlanSheetPainter.drawHeader(native, PREVIEW_WIDTH_PT, PREVIEW_PROJECT, PREVIEW_ADDRESS, sheetBranding)
                native.restore()
            }
        }
    }
}

/**
 * The preview lays the header out on a sheet narrower than Letter (612 pt) so its text stays legible
 * on a phone; the arrangement is the same, only long texts are shortened a little earlier.
 */
private const val PREVIEW_WIDTH_PT = 480.0
private const val PREVIEW_TOP_AIR_PT = 12.0
private const val PREVIEW_HEIGHT_PT = SheetLayout.HEADER_HEIGHT + 2 * PREVIEW_TOP_AIR_PT

/** Decodes the stored logo off the main thread (null while loading, without a logo, or on failure). */
@Composable
private fun rememberLogoBitmap(path: String?, revision: Int): Bitmap? {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path, revision) {
        bitmap = if (path == null) null else withContext(Dispatchers.IO) { SheetBrandings.load(Branding(logoPath = path))?.logo }
    }
    return if (path == null) null else bitmap
}
