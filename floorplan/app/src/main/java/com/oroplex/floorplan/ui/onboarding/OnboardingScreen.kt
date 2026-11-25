package com.oroplex.floorplan.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oroplex.floorplan.core.io.SampleProjects
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.core.render.DrawList
import com.oroplex.floorplan.core.render.PlanColors
import com.oroplex.floorplan.core.render.PlanRenderer
import com.oroplex.floorplan.core.render.RenderOptions
import com.oroplex.floorplan.core.render.ViewTransform
import com.oroplex.floorplan.data.LocaleDefaults
import com.oroplex.floorplan.data.appContainer
import com.oroplex.floorplan.render.PlanPainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Four-page welcome tour (see [Onboarding]): swipe or tap Next; Skip or "Get started" calls [onFinish].
 * System back goes to the previous page, and on the first page leaves as usual.
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pages = Onboarding.pages
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val current = pagerState.currentPage
    // The last page asks for the units. They are the app-wide defaults for NEW projects (existing projects
    // keep their own), so the choice is saved the moment it is tapped, whether the tour ends with
    // "Get started", Back or a swipe away.
    val settingsRepository = appContainer().settings
    val defaults by settingsRepository.defaults.collectAsStateWithLifecycle()
    val selectUnits: (UnitSystem) -> Unit = { system ->
        settingsRepository.update { LocaleDefaults.switchUnits(it, system) }
    }

    BackHandler(enabled = Onboarding.previousPage(current) != null) {
        Onboarding.previousPage(current)?.let { scope.launch { pagerState.animateScrollToPage(it) } }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (Onboarding.showSkip(current)) TextButton(onClick = onFinish) { Text("Skip") }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { index ->
                PageContent(pages[index], units = defaults.unitSystem, onSelectUnits = selectUnits)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PageIndicator(count = pages.size, current = current, modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        val next = Onboarding.nextPage(current)
                        if (next == null) onFinish() else scope.launch { pagerState.animateScrollToPage(next) }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(Onboarding.primaryActionLabel(current)) }
            }
        }
    }
}

@Composable
private fun PageContent(page: OnboardingPage, units: UnitSystem, onSelectUnits: (UnitSystem) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (page.art) {
                OnboardingArt.PLAN -> SamplePlanArt()
                OnboardingArt.SCAN -> IconArt(Icons.Outlined.ViewInAr)
                OnboardingArt.ACCURACY -> IconArt(Icons.Outlined.Straighten)
                OnboardingArt.READY -> IconArt(Icons.Outlined.TaskAlt)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            page.title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            page.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 480.dp),
        )
        if (page.asksUnits) {
            Spacer(Modifier.height(20.dp))
            UnitsChoice(units, onSelectUnits, Modifier.widthIn(max = 480.dp).fillMaxWidth())
        }
        if (page.points.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                page.points.forEachIndexed { i, point ->
                    PointRow(point, number = if (page.art == OnboardingArt.SCAN) i + 1 else null)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * "Measure in: Feet & inches / Metric" (see [Onboarding.UNITS_TITLE]). The selected button is the current
 * app-wide default, which a fresh install takes from the device's country.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitsChoice(selected: UnitSystem, onSelect: (UnitSystem) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            Onboarding.UNITS_TITLE,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        val choices = Onboarding.unitChoices
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            choices.forEachIndexed { index, system ->
                SegmentedButton(
                    selected = system == selected,
                    onClick = { onSelect(system) },
                    shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                ) { Text(Onboarding.unitLabel(system), maxLines = 1) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            Onboarding.UNITS_HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A key point: numbered for steps, checked for features and tips. */
@Composable
private fun PointRow(text: String, number: Int?) {
    Row(verticalAlignment = Alignment.Top) {
        if (number != null) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    number.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        } else {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun IconArt(icon: ImageVector) {
    Box(
        Modifier
            .size(168.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(84.dp))
    }
}

/**
 * The sample house drawn by the real plan renderer, on white paper (plans stay white in dark mode).
 * Falls back to an icon if the sample cannot be rendered.
 */
@Composable
private fun SamplePlanArt() {
    val drawList by produceState<DrawList?>(initialValue = null) {
        value = withContext(Dispatchers.Default) {
            try {
                val project = SampleProjects.demo(0L)
                val rooms = project.floors.firstOrNull()?.rooms.orEmpty()
                if (rooms.isEmpty()) {
                    null
                } else {
                    PlanRenderer.render(
                        rooms,
                        project.settings,
                        RenderOptions(
                            showRoomDimensions = false,
                            showAreas = false,
                            showWallLengths = false,
                            showOverallDimensions = false,
                            textScale = 0.8,
                        ),
                    )
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    val plan = drawList
    if (plan == null || plan.bounds.isEmpty) {
        IconArt(Icons.Outlined.ViewInAr)
        return
    }
    val pxPerPt = with(LocalDensity.current) { 1.dp.toPx() } * 160f / 72f
    Surface(
        shape = MaterialTheme.shapes.large,
        color = Color(PlanColors.BACKGROUND),
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .padding(12.dp)
                .semantics { contentDescription = "Example floor plan" },
        ) {
            val view = ViewTransform.fit(plan.bounds, size.width.toDouble(), size.height.toDouble(), 4.0, pxPerPt.toDouble())
            drawIntoCanvas { PlanPainter.draw(it.nativeCanvas, plan, view) }
        }
    }
}

@Composable
private fun PageIndicator(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.semantics { contentDescription = Onboarding.progressDescription(current) },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val selected = i == current
            val width by animateDpAsState(if (selected) 24.dp else 8.dp, label = "dot-width")
            val color by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                label = "dot-color",
            )
            Box(
                Modifier
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}
