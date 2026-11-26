package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chartScrubGestures
import com.example.data.DateFormatUtils
import com.example.data.FundComparisonSeries
import com.example.data.PerformanceCalculator
import com.example.ui.theme.GainGreenDark
import com.example.ui.theme.GainGreenLight
import com.example.ui.theme.LossRedDark
import com.example.ui.theme.LossRedLight
import com.example.ui.theme.NumericTextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Interactive multi-line normalized percentage return chart.
 * Reuses the high-performance Canvas path-caching & [chartScrubGestures] interaction architecture
 * from the reference comparison screen, enhanced with:
 * - Zero-baseline reference line & Y-axis % scale labels
 * - X-axis date labels
 * - Interactive touch/drag crosshair showing exact date and % return for every visible series
 * - Non-destructive horizontal zoom (1x, 2x, 4x) and pan controls
 */
@Composable
fun PerformanceComparisonChart(
    seriesList: List<FundComparisonSeries>,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val visibleSeries = remember(seriesList) { seriesList.filter { it.isVisible && it.points.isNotEmpty() } }

    // Unified sorted timeline of all actual NAV dates across visible series (no fabricated dates)
    val allDates = remember(visibleSeries) {
        visibleSeries
            .flatMap { s -> s.points.map { it.first } }
            .distinct()
            .sorted()
    }

    var zoomFactor by remember(allDates.size) { mutableFloatStateOf(1f) }
    var panStartFraction by remember(allDates.size) { mutableFloatStateOf(0f) }
    var crosshairDateIndex by remember(allDates.size, zoomFactor, panStartFraction) {
        mutableStateOf<Int?>(null)
    }

    val windowedDates = remember(allDates, zoomFactor, panStartFraction) {
        if (allDates.isEmpty()) return@remember emptyList()
        if (zoomFactor <= 1.01f) return@remember allDates
        val total = allDates.size
        val windowSize = (total / zoomFactor).roundToInt().coerceIn(5, total)
        val maxStart = (total - windowSize).coerceAtLeast(0)
        val startIdx = (panStartFraction * maxStart).roundToInt().coerceIn(0, maxStart)
        val endIdx = (startIdx + windowSize).coerceAtMost(total)
        allDates.subList(startIdx, endIdx)
    }

    // Re-normalize or map values onto windowedDates?
    // Keep percentage returns relative to the selected comparison start date (so zooming in shows exact returns from period start!)
    val chartSnapshot = remember(visibleSeries, windowedDates) {
        if (visibleSeries.isEmpty() || windowedDates.isEmpty()) return@remember null
        var minPct = 0.0
        var maxPct = 0.0

        val mappedSeries = visibleSeries.map { series ->
            val valuesAtDates: List<Double?> = windowedDates.map { epochDay ->
                if (epochDay < series.actualStartEpochDay || epochDay > series.actualEndEpochDay) {
                    null
                } else {
                    PerformanceCalculator.findNearestPointInSeries(series.points, epochDay)?.second
                }
            }
            for (v in valuesAtDates) {
                if (v != null) {
                    if (v < minPct) minPct = v
                    if (v > maxPct) maxPct = v
                }
            }
            series to valuesAtDates
        }

        if (minPct == maxPct) {
            minPct -= 1.0
            maxPct += 1.0
        }
        val span = maxPct - minPct
        val pad = span * 0.12
        ChartSnapshot(
            seriesValues = mappedSeries,
            minPct = minPct - pad,
            maxPct = maxPct + pad
        )
    }

    val activeDates by rememberUpdatedState(windowedDates)
    val gainColor = if (isDark) GainGreenDark else GainGreenLight
    val lossColor = if (isDark) LossRedDark else LossRedLight
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val zeroLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("comparison_chart_card")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Chart Header & Zoom Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (crosshairDateIndex != null && windowedDates.isNotEmpty()) {
                            val idx = crosshairDateIndex!!.coerceIn(0, windowedDates.lastIndex)
                            "Crosshair: ${DateFormatUtils.formatDisplay(windowedDates[idx])}"
                        } else {
                            "Normalized Return (%)"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (crosshairDateIndex == null) "Drag or tap chart for exact date & return" else "Drag horizontally or tap again to inspect",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Zoom & Pan controls
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (zoomFactor > 1.01f) {
                        IconButton(
                            onClick = { panStartFraction = (panStartFraction - 0.15f).coerceIn(0f, 1f) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Pan Left", modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = { panStartFraction = (panStartFraction + 0.15f).coerceIn(0f, 1f) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Pan Right", modifier = Modifier.size(18.dp))
                        }
                    }
                    listOf(1f to "1x", 2f to "2x", 4f to "4x").forEach { (factor, label) ->
                        val active = kotlin.math.abs(zoomFactor - factor) < 0.1f
                        Surface(
                            onClick = {
                                zoomFactor = factor
                                if (factor == 1f) panStartFraction = 0f else panStartFraction = 1f
                            },
                            shape = RoundedCornerShape(6.dp),
                            color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
                            border = BorderStroke(
                                1.dp,
                                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            )
                        ) {
                            Text(
                                text = label,
                                style = NumericTextStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                            )
                        }
                    }
                    if (crosshairDateIndex != null || zoomFactor > 1.01f) {
                        IconButton(
                            onClick = {
                                crosshairDateIndex = null
                                zoomFactor = 1f
                                panStartFraction = 0f
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Filled.ZoomOutMap, contentDescription = "Reset Chart", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // Crosshair Readout Pill Bar
            if (chartSnapshot != null && windowedDates.isNotEmpty()) {
                val activeIdx = crosshairDateIndex?.coerceIn(0, windowedDates.lastIndex)
                if (activeIdx != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        chartSnapshot.seriesValues
                            .mapNotNull { (series, vals) ->
                                val pct = vals.getOrNull(activeIdx) ?: return@mapNotNull null
                                series to pct
                            }
                            .sortedByDescending { it.second }
                            .forEach { (series, pct) ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = series.color.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, series.color.copy(alpha = 0.45f))
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .background(series.color, CircleShape)
                                        )
                                        Spacer(Modifier.width(5.dp))
                                        Text(
                                            text = shortSeriesLabel(series.schemeName),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = String.format(Locale.US, "%+.2f%%", pct),
                                            style = NumericTextStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                            color = if (pct >= 0.0) gainColor else lossColor
                                        )
                                    }
                                }
                            }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            if (visibleSeries.isEmpty() || chartSnapshot == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (seriesList.isEmpty()) "Select funds to plot chart" else "All selected funds are hidden or have no data in this range",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val pathCache = remember(chartSnapshot) {
                    object {
                        var w = -1f
                        var h = -1f
                        val paths = ArrayList<Pair<Path, Color>>()
                    }
                }
                val lineStroke = remember { Stroke(width = 4.5f, join = StrokeJoin.Round, cap = StrokeCap.Round) }
                val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(230.dp)
                ) {
                    // Left Y-axis percentage labels
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxSize()
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.End
                    ) {
                        val maxLabel = String.format(Locale.US, "%+.1f%%", chartSnapshot.maxPct)
                        val midPct = (chartSnapshot.maxPct + chartSnapshot.minPct) / 2.0
                        val midLabel = String.format(Locale.US, "%+.1f%%", midPct)
                        val minLabel = String.format(Locale.US, "%+.1f%%", chartSnapshot.minPct)

                        Text(
                            text = maxLabel,
                            style = NumericTextStyle.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = midLabel,
                            style = NumericTextStyle.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = minLabel,
                            style = NumericTextStyle.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.width(6.dp))

                    // Canvas Chart Area
                    Canvas(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .padding(vertical = 10.dp)
                            .chartScrubGestures(
                                chartSnapshot,
                                enabled = windowedDates.isNotEmpty(),
                                onTap = { offset, canvasSize ->
                                    val w = canvasSize.width.toFloat().coerceAtLeast(1f)
                                    val dates = activeDates
                                    if (dates.isEmpty()) return@chartScrubGestures
                                    val frac = (offset.x / w).coerceIn(0f, 1f)
                                    crosshairDateIndex = (frac * (dates.size - 1)).roundToInt().coerceIn(0, dates.lastIndex)
                                },
                                onScrubStart = { offset, canvasSize ->
                                    val w = canvasSize.width.toFloat().coerceAtLeast(1f)
                                    val dates = activeDates
                                    if (dates.isEmpty()) return@chartScrubGestures
                                    val frac = (offset.x / w).coerceIn(0f, 1f)
                                    crosshairDateIndex = (frac * (dates.size - 1)).roundToInt().coerceIn(0, dates.lastIndex)
                                },
                                onScrubMove = { offset, canvasSize ->
                                    val w = canvasSize.width.toFloat().coerceAtLeast(1f)
                                    val dates = activeDates
                                    if (dates.isEmpty()) return@chartScrubGestures
                                    val frac = (offset.x / w).coerceIn(0f, 1f)
                                    crosshairDateIndex = (frac * (dates.size - 1)).roundToInt().coerceIn(0, dates.lastIndex)
                                }
                            )
                    ) {
                        val w = size.width
                        val h = size.height
                        val range = (chartSnapshot.maxPct - chartSnapshot.minPct).coerceAtLeast(0.01)

                        fun getX(index: Int, total: Int): Float {
                            if (total <= 1) return w / 2f
                            return (index.toFloat() / (total - 1)) * w
                        }

                        fun getY(pct: Double): Float {
                            return (h - (((pct - chartSnapshot.minPct) / range) * h)).toFloat()
                        }

                        // Horizontal reference gridlines (top, mid, bottom)
                        listOf(0f, h / 2f, h).forEach { yPos ->
                            drawLine(
                                color = gridColor,
                                start = Offset(0f, yPos),
                                end = Offset(w, yPos),
                                strokeWidth = 1.5f
                            )
                        }

                        // Zero % baseline
                        if (0.0 in chartSnapshot.minPct..chartSnapshot.maxPct) {
                            val yZero = getY(0.0)
                            drawLine(
                                color = zeroLineColor,
                                start = Offset(0f, yZero),
                                end = Offset(w, yZero),
                                strokeWidth = 2f,
                                pathEffect = dashEffect
                            )
                        }

                        // Build or reuse cached smooth paths
                        val totalDates = windowedDates.size
                        if (pathCache.w != w || pathCache.h != h) {
                            pathCache.w = w
                            pathCache.h = h
                            pathCache.paths.clear()
                            chartSnapshot.seriesValues.forEach { (series, values) ->
                                val path = Path()
                                var started = false
                                values.forEachIndexed { idx, pct ->
                                    if (pct != null) {
                                        val px = getX(idx, totalDates)
                                        val py = getY(pct)
                                        if (!started) {
                                            path.moveTo(px, py)
                                            started = true
                                        } else {
                                            path.lineTo(px, py)
                                        }
                                    }
                                }
                                pathCache.paths.add(path to series.color)
                            }
                        }

                        clipRect {
                            pathCache.paths.forEach { (path, color) ->
                                drawPath(path = path, color = color, style = lineStroke)
                            }
                        }

                        // Interactive Crosshair Needle & Highlight Dots
                        val idx = crosshairDateIndex?.coerceIn(0, totalDates - 1)
                        if (idx != null) {
                            val cx = getX(idx, totalDates)
                            drawLine(
                                color = zeroLineColor.copy(alpha = 0.85f),
                                start = Offset(cx, 0f),
                                end = Offset(cx, h),
                                strokeWidth = 2.5f
                            )
                            chartSnapshot.seriesValues.forEach { (series, values) ->
                                val pct = values.getOrNull(idx) ?: return@forEach
                                val cy = getY(pct)
                                drawCircle(
                                    color = series.color,
                                    radius = 9f,
                                    center = Offset(cx, cy)
                                )
                                drawCircle(
                                    color = if (isDark) Color(0xFF0B0F17) else Color.White,
                                    radius = 4f,
                                    center = Offset(cx, cy)
                                )
                            }
                        }
                    }
                }

                // X-axis date labels
                if (windowedDates.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 54.dp, top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = DateFormatUtils.formatShort(windowedDates.first()),
                            style = NumericTextStyle.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (windowedDates.size >= 3) {
                            Text(
                                text = DateFormatUtils.formatShort(windowedDates[windowedDates.size / 2]),
                                style = NumericTextStyle.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = DateFormatUtils.formatShort(windowedDates.last()),
                            style = NumericTextStyle.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private data class ChartSnapshot(
    val seriesValues: List<Pair<FundComparisonSeries, List<Double?>>>,
    val minPct: Double,
    val maxPct: Double
)

internal fun shortSeriesLabel(name: String): String {
    return name
        .replace(" - Direct Plan - Growth", "", ignoreCase = true)
        .replace(" - Direct Plan - Growth Option", "", ignoreCase = true)
        .replace(" - Growth Option - Direct Plan", "", ignoreCase = true)
        .replace(" Direct Plan Growth", "", ignoreCase = true)
        .replace(" Direct Growth", "", ignoreCase = true)
        .trim()
        .let { if (it.length > 24) it.take(22) + "…" else it }
}
