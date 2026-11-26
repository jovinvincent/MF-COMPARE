package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chartScrubGestures
import com.example.data.DateFormatUtils
import com.example.data.PerformanceCalculator
import com.example.data.RollingFundResult
import com.example.data.RollingStartMode
import com.example.data.RollingWindow
import com.example.data.YoungestInceptionInfo
import com.example.ui.theme.GainGreenDark
import com.example.ui.theme.GainGreenLight
import com.example.ui.theme.IndexAmberDark
import com.example.ui.theme.IndexAmberLight
import com.example.ui.theme.LossRedDark
import com.example.ui.theme.LossRedLight
import com.example.ui.theme.NumericTextStyle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RollingReturnScreen(
    selectedSchemeCodes: List<String>,
    selectedWindow: RollingWindow,
    rollingStartMode: RollingStartMode,
    rollingCustomStartEpochDay: Long?,
    youngestInceptionInfo: YoungestInceptionInfo?,
    rollingResults: List<RollingFundResult>,
    isDark: Boolean,
    onBack: () -> Unit,
    onToggleTheme: () -> Unit,
    onSelectStartMode: (RollingStartMode) -> Unit,
    onCustomStartDateSelected: (Long) -> Unit,
    onSelectWindow: (RollingWindow) -> Unit,
    modifier: Modifier = Modifier
) {
    var showCalendarDialog by remember { mutableStateOf(false) }

    val gainColor = if (isDark) GainGreenDark else GainGreenLight
    val lossColor = if (isDark) LossRedDark else LossRedLight
    val indexBadgeColor = if (isDark) IndexAmberDark else IndexAmberLight

    val effectiveStartMode = if (rollingStartMode == RollingStartMode.YOUNGEST_INCEPTION && youngestInceptionInfo == null) {
        RollingStartMode.FROM_INCEPTION
    } else {
        rollingStartMode
    }

    val startDateSummary = when (effectiveStartMode) {
        RollingStartMode.YOUNGEST_INCEPTION -> {
            val d = youngestInceptionInfo?.effectiveInceptionEpochDay
            if (d != null) "Youngest Inception (${DateFormatUtils.formatDisplay(d)})" else "Youngest Inception"
        }
        RollingStartMode.FROM_INCEPTION -> "Each Fund Inception"
        RollingStartMode.CUSTOM_DATE -> rollingCustomStartEpochDay?.let { DateFormatUtils.formatDisplay(it) } ?: "Select Date"
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("rolling_return_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("rolling_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Compare"
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "Rolling Return Analysis",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${selectedSchemeCodes.size} selected funds • ${selectedWindow.description}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onToggleTheme,
                        modifier = Modifier.testTag("rolling_theme_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (isDark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            contentDescription = "Toggle color theme"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxSize()
                    .testTag("rolling_return_lazy_column"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Start Date Selection Card (Youngest Inception, From Inception, Calendar Pick)
                item(key = "rolling_start_date_card") {
                    RollingStartDateCard(
                        effectiveStartMode = effectiveStartMode,
                        startDateSummary = startDateSummary,
                        youngestInceptionInfo = youngestInceptionInfo,
                        onSelectMode = onSelectStartMode,
                        onOpenCalendar = { showCalendarDialog = true }
                    )
                }

                // 2. Rolling Timeframe Selection (Weekly, Biweekly, Monthly, Quarterly, Yearly)
                item(key = "rolling_timeframe_card") {
                    RollingTimeframeSelectorCard(
                        selectedWindow = selectedWindow,
                        onSelectWindow = onSelectWindow
                    )
                }

                // 3. Graphical & Tabular Results
                val validResults = rollingResults.filter { it.isValid }
                if (validResults.isNotEmpty()) {
                    item(key = "rolling_range_chart_card") {
                        RollingRangeDistributionCard(
                            results = validResults,
                            gainColor = gainColor,
                            lossColor = lossColor
                        )
                    }

                    item(key = "rolling_timeline_chart_card") {
                        RollingTimelineChartCard(
                            results = validResults,
                            selectedWindow = selectedWindow,
                            isDark = isDark,
                            gainColor = gainColor,
                            lossColor = lossColor
                        )
                    }

                    item(key = "rolling_comparison_table_card") {
                        RollingComparisonTableCard(
                            results = rollingResults,
                            selectedWindow = selectedWindow,
                            gainColor = gainColor,
                            lossColor = lossColor,
                            indexBadgeColor = indexBadgeColor
                        )
                    }
                } else if (rollingResults.isNotEmpty()) {
                    item(key = "rolling_insufficient_notice") {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Insufficient History for ${selectedWindow.label} Rolling Window",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = rollingResults.firstOrNull { it.errorNote != null }?.errorNote
                                        ?: "Try picking an earlier start date or a shorter rolling timeframe.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCalendarDialog) {
        val defaultEpochDay = when {
            effectiveStartMode == RollingStartMode.CUSTOM_DATE && rollingCustomStartEpochDay != null -> rollingCustomStartEpochDay
            youngestInceptionInfo != null -> youngestInceptionInfo.effectiveInceptionEpochDay
            else -> LocalDate.now().minusYears(1).toEpochDay()
        }
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = defaultEpochDay * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { showCalendarDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = pickerState.selectedDateMillis
                        if (millis != null) {
                            val day = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                            onCustomStartDateSelected(day)
                        }
                        showCalendarDialog = false
                    },
                    modifier = Modifier.testTag("rolling_apply_calendar_date_button")
                ) { Text("Apply Date") }
            },
            dismissButton = {
                TextButton(onClick = { showCalendarDialog = false }) { Text("Cancel") }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val youngestActive = effectiveStartMode == RollingStartMode.YOUNGEST_INCEPTION
                    Surface(
                        onClick = {
                            if (youngestInceptionInfo != null) {
                                onSelectStartMode(RollingStartMode.YOUNGEST_INCEPTION)
                                showCalendarDialog = false
                            }
                        },
                        enabled = youngestInceptionInfo != null,
                        shape = RoundedCornerShape(12.dp),
                        color = if (youngestActive) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        },
                        border = BorderStroke(
                            1.dp,
                            if (youngestActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("rolling_dialog_youngest_option")
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(
                                text = if (youngestInceptionInfo != null) {
                                    "From Youngest Fund Inception – ${DateFormatUtils.formatDisplay(youngestInceptionInfo.effectiveInceptionEpochDay)}"
                                } else {
                                    "From Youngest Fund Inception"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (youngestInceptionInfo != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (youngestInceptionInfo != null) {
                                Text(
                                    text = "Aligns all funds to ${shortSeriesLabel(youngestInceptionInfo.youngestSchemeName)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    val inceptionActive = effectiveStartMode == RollingStartMode.FROM_INCEPTION
                    Surface(
                        onClick = {
                            onSelectStartMode(RollingStartMode.FROM_INCEPTION)
                            showCalendarDialog = false
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = if (inceptionActive) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        },
                        border = BorderStroke(
                            1.dp,
                            if (inceptionActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("rolling_dialog_inception_option")
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(
                                text = "From Inception (Each Fund)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Starts rolling windows from each fund's earliest actual NAV date",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                DatePicker(state = pickerState, showModeToggle = true)
            }
        }
    }
}

@Composable
private fun RollingStartDateCard(
    effectiveStartMode: RollingStartMode,
    startDateSummary: String,
    youngestInceptionInfo: YoungestInceptionInfo?,
    onSelectMode: (RollingStartMode) -> Unit,
    onOpenCalendar: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("rolling_start_date_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Analysis Start Date",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = startDateSummary,
                    style = NumericTextStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val youngestSelected = effectiveStartMode == RollingStartMode.YOUNGEST_INCEPTION
                val youngestEnabled = youngestInceptionInfo != null
                val youngestLabel = if (youngestInceptionInfo != null) {
                    "Youngest (${DateFormatUtils.formatShort(youngestInceptionInfo.effectiveInceptionEpochDay)})"
                } else {
                    "Youngest Inception"
                }
                Surface(
                    onClick = { if (youngestEnabled) onSelectMode(RollingStartMode.YOUNGEST_INCEPTION) },
                    enabled = youngestEnabled,
                    shape = RoundedCornerShape(10.dp),
                    color = if (youngestSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = if (youngestSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .heightIn(min = 38.dp)
                        .testTag("rolling_mode_youngest_chip")
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            text = youngestLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (youngestSelected) FontWeight.Bold else FontWeight.Medium,
                            color = when {
                                youngestSelected -> MaterialTheme.colorScheme.onPrimary
                                !youngestEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }

                val inceptionSelected = effectiveStartMode == RollingStartMode.FROM_INCEPTION
                Surface(
                    onClick = { onSelectMode(RollingStartMode.FROM_INCEPTION) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (inceptionSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = if (inceptionSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .heightIn(min = 38.dp)
                        .testTag("rolling_mode_inception_chip")
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            text = "From Inception",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (inceptionSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (inceptionSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Surface(
                onClick = onOpenCalendar,
                shape = RoundedCornerShape(10.dp),
                color = if (effectiveStartMode == RollingStartMode.CUSTOM_DATE) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                },
                border = BorderStroke(
                    1.dp,
                    if (effectiveStartMode == RollingStartMode.CUSTOM_DATE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("rolling_calendar_picker_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.DateRange,
                            contentDescription = "Pick Rolling Start Date",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "CALENDAR PICK",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = startDateSummary,
                                style = NumericTextStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    Text(
                        text = "Change",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun RollingTimeframeSelectorCard(
    selectedWindow: RollingWindow,
    onSelectWindow: (RollingWindow) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("rolling_timeframe_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Rolling Return Window",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = selectedWindow.shortBadge,
                    style = NumericTextStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                RollingWindow.entries.forEach { window ->
                    val selected = selectedWindow == window
                    Surface(
                        onClick = { onSelectWindow(window) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .testTag("rolling_window_${window.name}")
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = window.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = window.shortBadge,
                                style = NumericTextStyle.copy(fontSize = 10.sp),
                                color = if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RollingRangeDistributionCard(
    results: List<RollingFundResult>,
    gainColor: Color,
    lossColor: Color
) {
    val globalMin = remember(results) {
        results.minOf { it.minReturnPercent }.coerceAtMost(0.0)
    }
    val globalMax = remember(results) {
        results.maxOf { it.maxReturnPercent }.coerceAtLeast(1.0)
    }
    val span = (globalMax - globalMin).coerceAtLeast(1.0)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
    val zeroColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("rolling_range_chart_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column {
                Text(
                    text = "Rolling Return Spread & Consistency",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Min → Max range bar with Average marker (circle) & % positive periods",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

            results.forEach { res ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .background(res.color, CircleShape)
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                text = shortSeriesLabel(res.schemeName),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = gainColor.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = "${String.format(Locale.US, "%.0f%%", res.positivePercent)} positive",
                                style = NumericTextStyle.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                color = gainColor,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = String.format(Locale.US, "%+.1f%%", res.minReturnPercent),
                            style = NumericTextStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                            color = if (res.minReturnPercent >= 0.0) gainColor else lossColor,
                            modifier = Modifier.width(56.dp)
                        )

                        Canvas(
                            modifier = Modifier
                                .weight(1f)
                                .height(20.dp)
                        ) {
                            val w = size.width
                            val h = size.height
                            fun toX(v: Double): Float = (((v - globalMin) / span) * w).toFloat().coerceIn(0f, w)

                            // Background track
                            drawRoundRect(
                                color = trackColor,
                                topLeft = Offset(0f, h * 0.25f),
                                size = Size(w, h * 0.5f),
                                cornerRadius = CornerRadius(6f, 6f)
                            )

                            // Zero reference tick
                            val zeroX = toX(0.0)
                            drawLine(
                                color = zeroColor,
                                start = Offset(zeroX, 2f),
                                end = Offset(zeroX, h - 2f),
                                strokeWidth = 2f
                            )

                            // Min..Max range bar
                            val minX = toX(res.minReturnPercent)
                            val maxX = toX(res.maxReturnPercent)
                            val rangeW = (maxX - minX).coerceAtLeast(4f)
                            drawRoundRect(
                                color = res.color.copy(alpha = 0.55f),
                                topLeft = Offset(minX, h * 0.22f),
                                size = Size(rangeW, h * 0.56f),
                                cornerRadius = CornerRadius(6f, 6f)
                            )

                            // Average marker dot
                            val avgX = toX(res.averageReturnPercent)
                            drawCircle(
                                color = res.color,
                                radius = h * 0.38f,
                                center = Offset(avgX, h / 2f)
                            )
                            drawCircle(
                                color = Color.White,
                                radius = h * 0.16f,
                                center = Offset(avgX, h / 2f)
                            )
                        }

                        Text(
                            text = String.format(Locale.US, "%+.1f%%", res.maxReturnPercent),
                            style = NumericTextStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                            color = if (res.maxReturnPercent >= 0.0) gainColor else lossColor,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(56.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Avg: ${String.format(Locale.US, "%+.2f%%", res.averageReturnPercent)} • Med: ${String.format(Locale.US, "%+.2f%%", res.medianReturnPercent)}",
                            style = NumericTextStyle.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Latest: ${String.format(Locale.US, "%+.2f%%", res.latestReturnPercent)}",
                            style = NumericTextStyle.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            color = if (res.latestReturnPercent >= 0.0) gainColor else lossColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RollingTimelineChartCard(
    results: List<RollingFundResult>,
    selectedWindow: RollingWindow,
    isDark: Boolean,
    gainColor: Color,
    lossColor: Color
) {
    val allDates = remember(results) {
        results.flatMap { r -> r.timelinePoints.map { it.first } }
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

    val activeDates by rememberUpdatedState(windowedDates)

    val chartSnapshot = remember(results, windowedDates) {
        if (results.isEmpty() || windowedDates.isEmpty()) return@remember null
        var minPct = 0.0
        var maxPct = 0.0
        val mapped = results.map { res ->
            val firstDay = res.timelinePoints.firstOrNull()?.first ?: Long.MAX_VALUE
            val lastDay = res.timelinePoints.lastOrNull()?.first ?: Long.MIN_VALUE
            val vals = windowedDates.map { day ->
                if (day < firstDay || day > lastDay) null
                else PerformanceCalculator.findNearestPointInSeries(res.timelinePoints, day)?.second
            }
            for (v in vals) {
                if (v != null) {
                    if (v < minPct) minPct = v
                    if (v > maxPct) maxPct = v
                }
            }
            res to vals
        }
        if (minPct == maxPct) {
            minPct -= 1.0
            maxPct += 1.0
        }
        val pad = (maxPct - minPct) * 0.12
        RollingTimelineSnapshot(mapped, minPct - pad, maxPct + pad)
    }

    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val zeroLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("rolling_timeline_chart_card")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (crosshairDateIndex != null && windowedDates.isNotEmpty()) {
                            val idx = crosshairDateIndex!!.coerceIn(0, windowedDates.lastIndex)
                            "${selectedWindow.label} Window Ended: ${DateFormatUtils.formatDisplay(windowedDates[idx])}"
                        } else {
                            "${selectedWindow.label} Rolling Return (%)"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Drag or tap chart for exact rolling window return",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

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
                                panStartFraction = if (factor == 1f) 0f else 1f
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
                            .mapNotNull { (res, vals) -> vals.getOrNull(activeIdx)?.let { res to it } }
                            .sortedByDescending { it.second }
                            .forEach { (res, pct) ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = res.color.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, res.color.copy(alpha = 0.45f))
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .background(res.color, CircleShape)
                                        )
                                        Spacer(Modifier.width(5.dp))
                                        Text(
                                            text = shortSeriesLabel(res.schemeName),
                                            style = MaterialTheme.typography.labelSmall,
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

            if (chartSnapshot == null || windowedDates.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Insufficient data to plot rolling chart",
                        style = MaterialTheme.typography.bodySmall,
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
                val stroke = remember { Stroke(width = 4.5f, join = StrokeJoin.Round, cap = StrokeCap.Round) }
                val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .fillMaxSize()
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = String.format(Locale.US, "%+.1f%%", chartSnapshot.maxPct),
                            style = NumericTextStyle.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = String.format(Locale.US, "%+.1f%%", (chartSnapshot.maxPct + chartSnapshot.minPct) / 2.0),
                            style = NumericTextStyle.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = String.format(Locale.US, "%+.1f%%", chartSnapshot.minPct),
                            style = NumericTextStyle.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.width(6.dp))

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
                                    if (dates.isNotEmpty()) {
                                        val frac = (offset.x / w).coerceIn(0f, 1f)
                                        crosshairDateIndex = (frac * (dates.size - 1)).roundToInt().coerceIn(0, dates.lastIndex)
                                    }
                                },
                                onScrubStart = { offset, canvasSize ->
                                    val w = canvasSize.width.toFloat().coerceAtLeast(1f)
                                    val dates = activeDates
                                    if (dates.isNotEmpty()) {
                                        val frac = (offset.x / w).coerceIn(0f, 1f)
                                        crosshairDateIndex = (frac * (dates.size - 1)).roundToInt().coerceIn(0, dates.lastIndex)
                                    }
                                },
                                onScrubMove = { offset, canvasSize ->
                                    val w = canvasSize.width.toFloat().coerceAtLeast(1f)
                                    val dates = activeDates
                                    if (dates.isNotEmpty()) {
                                        val frac = (offset.x / w).coerceIn(0f, 1f)
                                        crosshairDateIndex = (frac * (dates.size - 1)).roundToInt().coerceIn(0, dates.lastIndex)
                                    }
                                }
                            )
                    ) {
                        val w = size.width
                        val h = size.height
                        val range = (chartSnapshot.maxPct - chartSnapshot.minPct).coerceAtLeast(0.01)
                        val totalDates = windowedDates.size

                        fun getX(idx: Int): Float = if (totalDates <= 1) w / 2f else (idx.toFloat() / (totalDates - 1)) * w
                        fun getY(pct: Double): Float = (h - (((pct - chartSnapshot.minPct) / range) * h)).toFloat()

                        listOf(0f, h / 2f, h).forEach { yPos ->
                            drawLine(color = gridColor, start = Offset(0f, yPos), end = Offset(w, yPos), strokeWidth = 1.5f)
                        }

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

                        if (pathCache.w != w || pathCache.h != h) {
                            pathCache.w = w
                            pathCache.h = h
                            pathCache.paths.clear()
                            chartSnapshot.seriesValues.forEach { (res, values) ->
                                val path = Path()
                                var started = false
                                values.forEachIndexed { idx, pct ->
                                    if (pct != null) {
                                        val px = getX(idx)
                                        val py = getY(pct)
                                        if (!started) {
                                            path.moveTo(px, py)
                                            started = true
                                        } else {
                                            path.lineTo(px, py)
                                        }
                                    }
                                }
                                pathCache.paths.add(path to res.color)
                            }
                        }

                        clipRect {
                            pathCache.paths.forEach { (path, color) ->
                                drawPath(path = path, color = color, style = stroke)
                            }
                        }

                        val idx = crosshairDateIndex?.coerceIn(0, totalDates - 1)
                        if (idx != null) {
                            val cx = getX(idx)
                            drawLine(
                                color = zeroLineColor.copy(alpha = 0.85f),
                                start = Offset(cx, 0f),
                                end = Offset(cx, h),
                                strokeWidth = 2.5f
                            )
                            chartSnapshot.seriesValues.forEach { (res, values) ->
                                val pct = values.getOrNull(idx) ?: return@forEach
                                val cy = getY(pct)
                                drawCircle(color = res.color, radius = 9f, center = Offset(cx, cy))
                                drawCircle(
                                    color = if (isDark) Color(0xFF0B0F17) else Color.White,
                                    radius = 4f,
                                    center = Offset(cx, cy)
                                )
                            }
                        }
                    }
                }

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

@Composable
private fun RollingComparisonTableCard(
    results: List<RollingFundResult>,
    selectedWindow: RollingWindow,
    gainColor: Color,
    lossColor: Color,
    indexBadgeColor: Color
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("rolling_comparison_table_card")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Rolling Return Summary",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${selectedWindow.label} (${selectedWindow.shortBadge})",
                    style = NumericTextStyle.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Fund / Windows",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1.75f)
                )
                Text(
                    "Avg / Med",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(0.95f)
                )
                Text(
                    "Min / Max",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(0.95f)
                )
                Text(
                    "Latest",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(0.8f)
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))

            results.forEachIndexed { idx, res ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 9.dp)
                        .testTag("rolling_table_row_${res.schemeCode}"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1.75f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .background(res.color, CircleShape)
                            )
                            Spacer(Modifier.width(7.dp))
                            Column {
                                Text(
                                    text = shortSeriesLabel(res.schemeName),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (res.isValid) {
                                        "${res.observationCount} windows • ${String.format(Locale.US, "%.1f%%", res.positivePercent)} positive"
                                    } else {
                                        if (res.isIndex) "INDEX" else "DIRECT GROWTH"
                                    },
                                    style = NumericTextStyle.copy(fontSize = 10.sp),
                                    color = if (res.isIndex) indexBadgeColor else MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        if (res.isValid) {
                            Column(
                                modifier = Modifier.weight(0.95f),
                                horizontalAlignment = Alignment.End
                            ) {
                                Text(
                                    text = String.format(Locale.US, "%+.2f%%", res.averageReturnPercent),
                                    style = NumericTextStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                    color = if (res.averageReturnPercent >= 0.0) gainColor else lossColor
                                )
                                Text(
                                    text = String.format(Locale.US, "%+.2f%%", res.medianReturnPercent),
                                    style = NumericTextStyle.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(
                                modifier = Modifier.weight(0.95f),
                                horizontalAlignment = Alignment.End
                            ) {
                                Text(
                                    text = String.format(Locale.US, "%+.2f%%", res.minReturnPercent),
                                    style = NumericTextStyle.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                    color = if (res.minReturnPercent >= 0.0) gainColor else lossColor
                                )
                                Text(
                                    text = String.format(Locale.US, "%+.2f%%", res.maxReturnPercent),
                                    style = NumericTextStyle.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                    color = if (res.maxReturnPercent >= 0.0) gainColor else lossColor
                                )
                            }
                            Text(
                                text = String.format(Locale.US, "%+.2f%%", res.latestReturnPercent),
                                style = NumericTextStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                color = if (res.latestReturnPercent >= 0.0) gainColor else lossColor,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(0.8f)
                            )
                        } else {
                            Text(
                                text = res.errorNote ?: "Insufficient history",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(2.7f)
                            )
                        }
                    }

                    if (res.isValid) {
                        val minRange = res.minObservation?.let {
                            "${DateFormatUtils.formatShort(it.startEpochDay)} → ${DateFormatUtils.formatShort(it.endEpochDay)}"
                        } ?: "—"
                        val maxRange = res.maxObservation?.let {
                            "${DateFormatUtils.formatShort(it.startEpochDay)} → ${DateFormatUtils.formatShort(it.endEpochDay)}"
                        } ?: "—"
                        Text(
                            text = "StdDev: ${String.format(Locale.US, "%.2f%%", res.stdDevPercent)} • Worst ($minRange) • Best ($maxRange)",
                            style = NumericTextStyle.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
                if (idx < results.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                }
            }
        }
    }
}

private data class RollingTimelineSnapshot(
    val seriesValues: List<Pair<RollingFundResult, List<Double?>>>,
    val minPct: Double,
    val maxPct: Double
)
