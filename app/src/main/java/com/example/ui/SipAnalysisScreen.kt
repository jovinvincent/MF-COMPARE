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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chartScrubGestures
import com.example.data.DateFormatUtils
import com.example.data.PerformanceCalculator
import com.example.data.SipFundConfig
import com.example.data.SipFundResult
import com.example.data.SipStartMode
import com.example.data.SipTenure
import com.example.data.YoungestInceptionInfo
import com.example.ui.theme.GainGreenDark
import com.example.ui.theme.GainGreenLight
import com.example.ui.theme.IndexAmberDark
import com.example.ui.theme.IndexAmberLight
import com.example.ui.theme.LossRedDark
import com.example.ui.theme.LossRedLight
import com.example.ui.theme.NumericTextStyle
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SipAnalysisScreen(
    selectedSchemeCodes: List<String>,
    sipFundConfigs: Map<String, SipFundConfig>,
    sipStartMode: SipStartMode,
    sipCustomStartEpochDay: Long?,
    youngestInceptionInfo: YoungestInceptionInfo?,
    sipResults: List<SipFundResult>,
    isDark: Boolean,
    onBack: () -> Unit,
    onToggleTheme: () -> Unit,
    onSelectStartMode: (SipStartMode) -> Unit,
    onCustomStartDateSelected: (Long) -> Unit,
    onUpdateFundAmount: (String, Double) -> Unit,
    onUpdateFundTenure: (String, SipTenure) -> Unit,
    modifier: Modifier = Modifier
) {
    var showCalendarDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val gainColor = if (isDark) GainGreenDark else GainGreenLight
    val lossColor = if (isDark) LossRedDark else LossRedLight
    val indexBadgeColor = if (isDark) IndexAmberDark else IndexAmberLight

    val effectiveStartMode = if (sipStartMode == SipStartMode.YOUNGEST_INCEPTION && youngestInceptionInfo == null) {
        SipStartMode.FROM_INCEPTION
    } else {
        sipStartMode
    }

    val startDateSummary = when (effectiveStartMode) {
        SipStartMode.YOUNGEST_INCEPTION -> {
            val d = youngestInceptionInfo?.effectiveInceptionEpochDay
            if (d != null) "Youngest Inception (${DateFormatUtils.formatDisplay(d)})" else "Youngest Inception"
        }
        SipStartMode.FROM_INCEPTION -> "Each Fund Inception"
        SipStartMode.CUSTOM_DATE -> sipCustomStartEpochDay?.let { DateFormatUtils.formatDisplay(it) } ?: "Select Date"
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("sip_analysis_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("sip_back_button")
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
                            text = "SIP Analysis",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${selectedSchemeCodes.size} selected funds • FIFO & Worst-Case NAV",
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
                        modifier = Modifier.testTag("sip_theme_toggle_button")
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
                state = listState,
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxSize()
                    .testTag("sip_analysis_lazy_column"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. SIP Start Date Card (Calendar Pick + From Inception + Youngest Inception)
                item(key = "sip_start_date_card") {
                    SipStartDateCard(
                        effectiveStartMode = effectiveStartMode,
                        startDateSummary = startDateSummary,
                        youngestInceptionInfo = youngestInceptionInfo,
                        onSelectMode = onSelectStartMode,
                        onOpenCalendar = { showCalendarDialog = true }
                    )
                }

                // 2. Independent Fund SIP Configurations
                items(sipResults, key = { "cfg_${it.schemeCode}" }) { fundResult ->
                    val config = sipFundConfigs[fundResult.schemeCode]
                        ?: SipFundConfig(schemeCode = fundResult.schemeCode)
                    SipFundConfigCard(
                        fundResult = fundResult,
                        config = config,
                        indexBadgeColor = indexBadgeColor,
                        gainColor = gainColor,
                        onAmountChange = { amount -> onUpdateFundAmount(fundResult.schemeCode, amount) },
                        onTenureChange = { tenure -> onUpdateFundTenure(fundResult.schemeCode, tenure) }
                    )
                }

                // 3. Compare CTA Button
                if (sipResults.isNotEmpty()) {
                    item(key = "sip_compare_cta") {
                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                coroutineScope.launch {
                                    // Scroll directly to Graphical Comparison section
                                    val resultsStartIdx = 2 + sipResults.size
                                    listState.animateScrollToItem(resultsStartIdx)
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("sip_compare_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ShowChart,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Compare SIP Returns & XIRR",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // 4. Graphical Comparison (Absolute Return % & XIRR % Bar Chart + Cumulative Return Timeline)
                val validResults = sipResults.filter { it.isValid }
                if (validResults.isNotEmpty()) {
                    item(key = "sip_graphical_bars") {
                        SipReturnAndXirrBarCard(
                            results = validResults,
                            gainColor = gainColor,
                            lossColor = lossColor
                        )
                    }

                    item(key = "sip_timeline_chart") {
                        SipTimelineChartCard(
                            results = validResults,
                            isDark = isDark,
                            gainColor = gainColor,
                            lossColor = lossColor
                        )
                    }

                    // 5. Tabular Comparison (FIFO Ledger Summary)
                    item(key = "sip_tabular_summary") {
                        SipComparisonTableCard(
                            results = sipResults,
                            gainColor = gainColor,
                            lossColor = lossColor,
                            indexBadgeColor = indexBadgeColor
                        )
                    }
                } else if (sipResults.isNotEmpty()) {
                    item(key = "sip_insufficient_notice") {
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
                                    text = "Adjust SIP Amount or Start Date",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = sipResults.firstOrNull { it.errorNote != null }?.errorNote
                                        ?: "Ensure selected funds have historical NAVs and valid SIP amounts above 0.",
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

    // Material 3 Calendar DatePickerDialog with quick "From Youngest Inception" & "From Inception" options
    if (showCalendarDialog) {
        val defaultEpochDay = when {
            effectiveStartMode == SipStartMode.CUSTOM_DATE && sipCustomStartEpochDay != null -> sipCustomStartEpochDay
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
                    modifier = Modifier.testTag("sip_apply_calendar_date_button")
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
                    val youngestActive = effectiveStartMode == SipStartMode.YOUNGEST_INCEPTION
                    Surface(
                        onClick = {
                            if (youngestInceptionInfo != null) {
                                onSelectStartMode(SipStartMode.YOUNGEST_INCEPTION)
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
                            .testTag("sip_dialog_youngest_option")
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

                    val inceptionActive = effectiveStartMode == SipStartMode.FROM_INCEPTION
                    Surface(
                        onClick = {
                            onSelectStartMode(SipStartMode.FROM_INCEPTION)
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
                            .testTag("sip_dialog_inception_option")
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(
                                text = "From Inception (Each Fund)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Starts each fund's SIP on its own earliest available NAV date",
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
private fun SipStartDateCard(
    effectiveStartMode: SipStartMode,
    startDateSummary: String,
    youngestInceptionInfo: YoungestInceptionInfo?,
    onSelectMode: (SipStartMode) -> Unit,
    onOpenCalendar: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sip_start_date_card")
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
                    text = "SIP Start Date",
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
                val youngestSelected = effectiveStartMode == SipStartMode.YOUNGEST_INCEPTION
                val youngestEnabled = youngestInceptionInfo != null
                val youngestLabel = if (youngestInceptionInfo != null) {
                    "Youngest (${DateFormatUtils.formatShort(youngestInceptionInfo.effectiveInceptionEpochDay)})"
                } else {
                    "Youngest Inception"
                }
                Surface(
                    onClick = { if (youngestEnabled) onSelectMode(SipStartMode.YOUNGEST_INCEPTION) },
                    enabled = youngestEnabled,
                    shape = RoundedCornerShape(10.dp),
                    color = if (youngestSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = if (youngestSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .heightIn(min = 38.dp)
                        .testTag("sip_mode_youngest_chip")
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

                val inceptionSelected = effectiveStartMode == SipStartMode.FROM_INCEPTION
                Surface(
                    onClick = { onSelectMode(SipStartMode.FROM_INCEPTION) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (inceptionSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = if (inceptionSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .heightIn(min = 38.dp)
                        .testTag("sip_mode_inception_chip")
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
                color = if (effectiveStartMode == SipStartMode.CUSTOM_DATE) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                },
                border = BorderStroke(
                    1.dp,
                    if (effectiveStartMode == SipStartMode.CUSTOM_DATE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("sip_calendar_picker_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.DateRange,
                            contentDescription = "Pick SIP Start Date",
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
private fun SipFundConfigCard(
    fundResult: SipFundResult,
    config: SipFundConfig,
    indexBadgeColor: Color,
    gainColor: Color,
    onAmountChange: (Double) -> Unit,
    onTenureChange: (SipTenure) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var amountText by remember(fundResult.schemeCode) {
        mutableStateOf(formatEditableAmount(config.amount))
    }

    LaunchedEffect(config.amount) {
        val currentParsed = amountText.toDoubleOrNull()
        if (currentParsed == null || abs(currentParsed - config.amount) > 1e-4) {
            amountText = formatEditableAmount(config.amount)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, fundResult.color.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sip_fund_config_${fundResult.schemeCode}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Fund Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(fundResult.color, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = shortSeriesLabel(fundResult.schemeName),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (fundResult.isIndex) "INDEX" else "DIRECT GROWTH",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            fontWeight = FontWeight.Bold,
                            color = if (fundResult.isIndex) indexBadgeColor else gainColor
                        )
                        if (fundResult.firstInstallmentEpochDay != null && fundResult.valuationEpochDay != null) {
                            Text(
                                text = "${DateFormatUtils.formatShort(fundResult.firstInstallmentEpochDay)} → ${DateFormatUtils.formatShort(fundResult.valuationEpochDay)} (${fundResult.installmentCount} lots)",
                                style = NumericTextStyle.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Independent SIP Amount Input + Presets
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { raw ->
                        val sanitized = raw.filter { it.isDigit() || it == '.' }
                        if (sanitized.count { it == '.' } <= 1) {
                            amountText = sanitized
                            val parsed = sanitized.toDoubleOrNull()
                            if (parsed != null && parsed > 0.0) {
                                onAmountChange(parsed)
                            }
                        }
                    },
                    label = { Text("SIP Amount (₹)", style = MaterialTheme.typography.labelSmall) },
                    prefix = { Text("₹", style = NumericTextStyle.copy(fontWeight = FontWeight.Bold)) },
                    singleLine = true,
                    textStyle = NumericTextStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = fundResult.color,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier
                        .weight(1.1f)
                        .testTag("sip_amount_input_${fundResult.schemeCode}")
                )

                Row(
                    modifier = Modifier
                        .weight(1.25f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(100.0, 500.0, 1000.0, 5000.0).forEach { preset ->
                        val active = abs(config.amount - preset) < 0.01
                        Surface(
                            onClick = {
                                amountText = formatEditableAmount(preset)
                                onAmountChange(preset)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (active) fundResult.color.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(
                                1.dp,
                                if (active) fundResult.color else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                            ),
                            modifier = Modifier
                                .heightIn(min = 38.dp)
                                .testTag("sip_preset_${fundResult.schemeCode}_${preset.toInt()}")
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)) {
                                Text(
                                    text = "₹${preset.toInt()}",
                                    style = NumericTextStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Independent Tenure Options: Daily, Weekly, Biweekly, Monthly
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SipTenure.entries.forEach { tenure ->
                    val selected = config.tenure == tenure
                    Surface(
                        onClick = { onTenureChange(tenure) },
                        shape = RoundedCornerShape(9.dp),
                        color = if (selected) fundResult.color else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 38.dp)
                            .testTag("sip_tenure_${fundResult.schemeCode}_${tenure.name}")
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)) {
                            Text(
                                text = tenure.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) Color(0xFF071018) else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Text(
                text = if (config.tenure == SipTenure.DAILY) {
                    "Daily SIP: invests on every actual NAV trading day in FIFO order."
                } else {
                    "Worst-case ${config.tenure.label.lowercase()}: invests at the highest NAV inside each ${config.tenure.label.lowercase()} window (FIFO)."
                },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SipReturnAndXirrBarCard(
    results: List<SipFundResult>,
    gainColor: Color,
    lossColor: Color
) {
    val maxMagnitude = remember(results) {
        var maxVal = 1.0
        results.forEach { res ->
            maxVal = max(maxVal, abs(res.absoluteReturnPercent))
            res.xirrPercent?.let { x ->
                maxVal = max(maxVal, abs(x.coerceIn(-99.9, 500.0)))
            }
        }
        maxVal
    }
    val hasNegative = results.any { it.absoluteReturnPercent < 0.0 || (it.xirrPercent ?: 0.0) < 0.0 }
    val hasPositive = results.any { it.absoluteReturnPercent >= 0.0 || (it.xirrPercent ?: 0.0) >= 0.0 }

    val zeroFraction = remember(results, maxMagnitude, hasNegative, hasPositive) {
        if (!hasNegative) 0f
        else if (!hasPositive) 1f
        else {
            val minNeg = results.minOf { min(it.absoluteReturnPercent, it.xirrPercent?.coerceIn(-99.9, 500.0) ?: 0.0) }.coerceAtMost(0.0)
            val maxPos = results.maxOf { max(it.absoluteReturnPercent, it.xirrPercent?.coerceIn(-99.9, 500.0) ?: 0.0) }.coerceAtLeast(0.0)
            val totalSpan = (maxPos - minNeg).coerceAtLeast(1.0)
            (abs(minNeg) / totalSpan).toFloat().coerceIn(0.15f, 0.85f)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sip_bar_comparison_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "SIP Return vs XIRR Comparison",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Graphical comparison of Absolute Return (%) and annualized XIRR (%)",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Legend
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(width = 14.dp, height = 8.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Absolute Return %", style = MaterialTheme.typography.labelSmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(width = 14.dp, height = 8.dp)
                            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f), RoundedCornerShape(3.dp))
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("XIRR (% p.a.)", style = MaterialTheme.typography.labelSmall)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

            val minVal = if (hasNegative) {
                results.minOf { min(it.absoluteReturnPercent, it.xirrPercent?.coerceIn(-99.9, 500.0) ?: 0.0) }.coerceAtMost(0.0)
            } else 0.0
            val maxVal = if (hasPositive) {
                results.maxOf { max(it.absoluteReturnPercent, it.xirrPercent?.coerceIn(-99.9, 500.0) ?: 0.0) }.coerceAtLeast(1.0)
            } else 0.0
            val axisColor = MaterialTheme.colorScheme.outline
            val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)

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
                        Text(
                            text = "${formatInrCompact(res.sipAmount)} • ${res.tenure.shortLabel}",
                            style = NumericTextStyle.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Absolute Return Bar
                    MetricBarRow(
                        label = "Abs Return",
                        value = res.absoluteReturnPercent,
                        displayText = String.format(Locale.US, "%+.2f%%", res.absoluteReturnPercent),
                        barColor = if (res.absoluteReturnPercent >= 0.0) res.color else lossColor,
                        textColor = if (res.absoluteReturnPercent >= 0.0) gainColor else lossColor,
                        minAxis = minVal,
                        maxAxis = maxVal,
                        zeroFraction = zeroFraction,
                        trackColor = trackColor,
                        axisColor = axisColor
                    )

                    // XIRR Bar
                    val xirrVal = res.xirrPercent
                    val xirrColor = when {
                        xirrVal == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        xirrVal >= 0.0 -> gainColor
                        else -> lossColor
                    }
                    MetricBarRow(
                        label = "XIRR p.a.",
                        value = xirrVal?.coerceIn(-99.9, 500.0) ?: 0.0,
                        displayText = xirrVal?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "—",
                        barColor = when {
                            xirrVal == null -> Color.Transparent
                            xirrVal >= 0.0 -> res.color.copy(alpha = 0.62f)
                            else -> lossColor.copy(alpha = 0.72f)
                        },
                        textColor = xirrColor,
                        minAxis = minVal,
                        maxAxis = maxVal,
                        zeroFraction = zeroFraction,
                        trackColor = trackColor,
                        axisColor = axisColor
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricBarRow(
    label: String,
    value: Double,
    displayText: String,
    barColor: Color,
    textColor: Color,
    minAxis: Double,
    maxAxis: Double,
    zeroFraction: Float,
    trackColor: Color,
    axisColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(68.dp)
        )
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(14.dp)
        ) {
            val w = size.width
            val h = size.height
            drawRoundRect(
                color = trackColor,
                topLeft = Offset.Zero,
                size = Size(w, h),
                cornerRadius = CornerRadius(6f, 6f)
            )
            val zeroX = w * zeroFraction
            if (value >= 0.0 && maxAxis > 0.0) {
                val frac = (value / maxAxis).toFloat().coerceIn(0f, 1f)
                val barW = ((w - zeroX) * frac).coerceAtLeast(if (value > 0.0) 4f else 0f)
                if (barW > 0f) {
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(zeroX, 1f),
                        size = Size(barW, h - 2f),
                        cornerRadius = CornerRadius(5f, 5f)
                    )
                }
            } else if (value < 0.0 && minAxis < 0.0) {
                val frac = (abs(value) / abs(minAxis)).toFloat().coerceIn(0f, 1f)
                val barW = (zeroX * frac).coerceAtLeast(4f)
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(zeroX - barW, 1f),
                    size = Size(barW, h - 2f),
                    cornerRadius = CornerRadius(5f, 5f)
                )
            }
            drawLine(
                color = axisColor,
                start = Offset(zeroX, 0f),
                end = Offset(zeroX, h),
                strokeWidth = 2f
            )
        }
        Text(
            text = displayText,
            style = NumericTextStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
            color = textColor,
            textAlign = TextAlign.End,
            modifier = Modifier.width(68.dp)
        )
    }
}

@Composable
private fun SipTimelineChartCard(
    results: List<SipFundResult>,
    isDark: Boolean,
    gainColor: Color,
    lossColor: Color
) {
    val allDates = remember(results) {
        results.flatMap { r -> r.timelinePoints.map { it.first } }
            .distinct()
            .sorted()
    }

    var crosshairDateIndex by remember(allDates.size) { mutableStateOf<Int?>(null) }
    val activeDates by rememberUpdatedState(allDates)

    val chartSnapshot = remember(results, allDates) {
        if (results.isEmpty() || allDates.isEmpty()) return@remember null
        var minPct = 0.0
        var maxPct = 0.0
        val mapped = results.map { res ->
            val firstDay = res.timelinePoints.firstOrNull()?.first ?: Long.MAX_VALUE
            val lastDay = res.timelinePoints.lastOrNull()?.first ?: Long.MIN_VALUE
            val vals = allDates.map { day ->
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
        SipTimelineSnapshot(mapped, minPct - pad, maxPct + pad)
    }

    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val zeroLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sip_timeline_chart_card")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = if (crosshairDateIndex != null && allDates.isNotEmpty()) {
                    "SIP Timeline: ${DateFormatUtils.formatDisplay(allDates[crosshairDateIndex!!.coerceIn(0, allDates.lastIndex)])}"
                } else {
                    "SIP Cumulative Return Trajectory (%)"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Drag or tap chart to inspect FIFO portfolio return (%) over time",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (chartSnapshot != null && allDates.isNotEmpty()) {
                val activeIdx = crosshairDateIndex?.coerceIn(0, allDates.lastIndex)
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

            if (chartSnapshot == null || allDates.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Insufficient NAV points to plot SIP trajectory",
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
                        .height(200.dp)
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
                                enabled = allDates.isNotEmpty(),
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
                        val totalDates = allDates.size

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
                        text = DateFormatUtils.formatShort(allDates.first()),
                        style = NumericTextStyle.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (allDates.size >= 3) {
                        Text(
                            text = DateFormatUtils.formatShort(allDates[allDates.size / 2]),
                            style = NumericTextStyle.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = DateFormatUtils.formatShort(allDates.last()),
                        style = NumericTextStyle.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SipComparisonTableCard(
    results: List<SipFundResult>,
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
            .testTag("sip_comparison_table_card")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SIP Performance Summary",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "FIFO • Highest Period NAV",
                    style = NumericTextStyle.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))

            // Table Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Fund & Plan",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1.7f)
                )
                Text(
                    "Invested",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(0.95f)
                )
                Text(
                    "Value",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(0.95f)
                )
                Text(
                    "Abs / XIRR",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1.05f)
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))

            results.forEachIndexed { idx, res ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 9.dp)
                        .testTag("sip_table_row_${res.schemeCode}"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1.7f),
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
                                    text = "${formatInrCompact(res.sipAmount)} ${res.tenure.shortLabel} • ${res.installmentCount} lots",
                                    style = NumericTextStyle.copy(fontSize = 10.sp),
                                    color = if (res.isIndex) indexBadgeColor else MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        if (res.isValid) {
                            Text(
                                text = formatInrCompact(res.totalInvested),
                                style = NumericTextStyle.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(0.95f)
                            )
                            Text(
                                text = formatInrCompact(res.currentValue),
                                style = NumericTextStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(0.95f)
                            )
                            Column(
                                modifier = Modifier.weight(1.05f),
                                horizontalAlignment = Alignment.End
                            ) {
                                Text(
                                    text = String.format(Locale.US, "%+.2f%%", res.absoluteReturnPercent),
                                    style = NumericTextStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                                    color = if (res.absoluteReturnPercent >= 0.0) gainColor else lossColor
                                )
                                Text(
                                    text = res.xirrPercent?.let { String.format(Locale.US, "%+.2f%% p.a.", it) } ?: "XIRR —",
                                    style = NumericTextStyle.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                    color = when {
                                        res.xirrPercent == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                        res.xirrPercent >= 0.0 -> gainColor
                                        else -> lossColor
                                    }
                                )
                            }
                        } else {
                            Text(
                                text = res.errorNote ?: "Insufficient data",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(2.95f)
                            )
                        }
                    }

                    if (res.isValid) {
                        Text(
                            text = "Units: ${String.format(Locale.US, "%.3f", res.totalUnits)} • Avg NAV: ${
                                res.averagePurchaseNav?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
                            } • Latest: ${
                                res.latestNav?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
                            } (${DateFormatUtils.formatShort(res.firstInstallmentEpochDay)} → ${DateFormatUtils.formatShort(res.lastInstallmentEpochDay)})",
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

private data class SipTimelineSnapshot(
    val seriesValues: List<Pair<SipFundResult, List<Double?>>>,
    val minPct: Double,
    val maxPct: Double
)

private val indianLocale: Locale = Locale.Builder().setLanguage("en").setRegion("IN").build()

internal fun formatInrCompact(amount: Double): String {
    val formatter = NumberFormat.getNumberInstance(indianLocale).apply {
        maximumFractionDigits = if (abs(amount - amount.roundToInt()) < 0.01 || amount >= 100.0) 0 else 2
        minimumFractionDigits = 0
    }
    return "₹${formatter.format(amount)}"
}

private fun formatEditableAmount(amount: Double): String {
    return if (abs(amount - amount.toLong()) < 1e-6) {
        amount.toLong().toString()
    } else {
        String.format(Locale.US, "%.2f", amount)
    }
}
