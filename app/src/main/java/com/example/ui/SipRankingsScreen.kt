package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.DateFormatUtils
import com.example.data.FundCategoryFilter
import com.example.data.SipRankMetric
import com.example.data.SipRankingEntry
import com.example.data.SipRankingsResult
import com.example.data.SipTenure
import com.example.data.SyncStatusState
import com.example.ui.theme.GainGreenDark
import com.example.ui.theme.GainGreenLight
import com.example.ui.theme.IndexAmberDark
import com.example.ui.theme.IndexAmberLight
import com.example.ui.theme.LossRedDark
import com.example.ui.theme.LossRedLight
import com.example.ui.theme.NumericTextStyle
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

private val PRESET_AMOUNTS = listOf(500.0, 1000.0, 2000.0, 5000.0)

private enum class SipPresetTimeframe(val label: String, val months: Long) {
    M6("6M", 6L),
    Y1("1Y", 12L),
    Y2("2Y", 24L),
    Y3("3Y", 36L),
    Y5("5Y", 60L);

    fun resolveStartEpochDay(referenceDay: Long = LocalDate.now().toEpochDay()): Long {
        return LocalDate.ofEpochDay(referenceDay).minusMonths(months).toEpochDay()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SipRankingsScreen(
    result: SipRankingsResult,
    isLoading: Boolean,
    selectedTenure: SipTenure,
    sipAmount: Double,
    startEpochDay: Long,
    selectedMetric: SipRankMetric,
    selectedCategories: Set<String>,
    syncStatus: SyncStatusState,
    isDark: Boolean,
    onBack: () -> Unit,
    onToggleTheme: () -> Unit,
    onSelectTenure: (SipTenure) -> Unit,
    onSelectAmount: (Double) -> Unit,
    onSelectStartDate: (Long) -> Unit,
    onSelectMetric: (SipRankMetric) -> Unit,
    onApplyCategories: (Set<String>) -> Unit,
    onOpenFundInCompare: (String) -> Unit,
    onToggleFavourite: (String) -> Unit,
    onStartUniverseSync: () -> Unit,
    onPauseUniverseSync: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showCalendarDialog by remember { mutableStateOf(false) }
    var showCategorySheet by remember { mutableStateOf(false) }
    var showCustomAmountDialog by remember { mutableStateOf(false) }
    var customAmountInput by remember { mutableStateOf(sipAmount.toInt().toString()) }

    val gainColor = if (isDark) GainGreenDark else GainGreenLight
    val lossColor = if (isDark) LossRedDark else LossRedLight
    val sCurrencyFormat = remember {
        NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).apply {
            maximumFractionDigits = 0
        }
    }

    val referenceToday = result.endEpochDay ?: LocalDate.now().toEpochDay()
    val activePresetTimeframe = remember(startEpochDay, referenceToday) {
        SipPresetTimeframe.entries.find { preset ->
            val expectedStart = preset.resolveStartEpochDay(referenceToday)
            kotlin.math.abs(expectedStart - startEpochDay) <= 3L
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("sip_rankings_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("sip_rankings_back_button")
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
                            text = "SIP Rankings",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Top 20 • ${selectedTenure.label} SIP • ${sCurrencyFormat.format(sipAmount)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onToggleTheme,
                        modifier = Modifier.testTag("sip_rankings_theme_toggle")
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
                .padding(innerPadding)
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxSize()
                    .testTag("sip_rankings_lazy_column"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. SIP Mode & Tenure Card
                item(key = "tenure_card") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
                        modifier = Modifier.fillMaxWidth()
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
                                    text = "SIP Mode & Tenure",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Worst-case return (highest NAV)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SipTenure.entries.forEach { tenure ->
                                    val active = selectedTenure == tenure
                                    Surface(
                                        onClick = { onSelectTenure(tenure) },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                        border = if (active) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 38.dp)
                                            .testTag("sip_rankings_tenure_${tenure.name}")
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)
                                        ) {
                                            Text(
                                                text = tenure.label,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                                color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                            // SIP Installment Amount
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Installment Amount",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = sCurrencyFormat.format(sipAmount),
                                    style = NumericTextStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                PRESET_AMOUNTS.forEach { amt ->
                                    val active = amt == sipAmount
                                    Surface(
                                        onClick = { onSelectAmount(amt) },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(
                                            1.dp,
                                            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 34.dp)
                                            .testTag("sip_rankings_preset_amount_${amt.toInt()}")
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.padding(vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = "₹${amt.toInt()}",
                                                style = NumericTextStyle.copy(fontSize = 11.sp),
                                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                                Surface(
                                    onClick = {
                                        customAmountInput = sipAmount.toInt().toString()
                                        showCustomAmountDialog = true
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                    modifier = Modifier
                                        .weight(1.1f)
                                        .heightIn(min = 34.dp)
                                        .testTag("sip_rankings_custom_amount_button")
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = "Custom",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Start Date / Investment Timeframe Card
                item(key = "timeframe_card") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "SIP Start Date & Timeframe",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SipPresetTimeframe.entries.forEach { preset ->
                                    val active = activePresetTimeframe == preset
                                    Surface(
                                        onClick = {
                                            onSelectStartDate(preset.resolveStartEpochDay(referenceToday))
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                        border = if (active) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                        modifier = Modifier
                                            .heightIn(min = 36.dp)
                                            .testTag("sip_rankings_period_${preset.months}m")
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                        ) {
                                            Text(
                                                text = preset.label,
                                                style = NumericTextStyle.copy(
                                                    fontSize = 12.sp,
                                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                                                ),
                                                color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                val isCustomDate = activePresetTimeframe == null
                                Surface(
                                    onClick = { showCalendarDialog = true },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isCustomDate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                    border = if (isCustomDate) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                    modifier = Modifier
                                        .heightIn(min = 36.dp)
                                        .testTag("sip_rankings_calendar_button")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.DateRange,
                                            contentDescription = "Pick start date",
                                            modifier = Modifier.size(16.dp),
                                            tint = if (isCustomDate) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = if (isCustomDate) DateFormatUtils.formatDisplay(startEpochDay) else "Calendar Pick",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (isCustomDate) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Text(
                                text = "Installments begin on or after: ${DateFormatUtils.formatDisplay(startEpochDay)}",
                                style = NumericTextStyle.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 3. Metric Toggle & Category Filter
                item(key = "metric_and_category_card") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SipRankMetric.entries.forEach { metric ->
                                val active = selectedMetric == metric
                                Surface(
                                    onClick = { onSelectMetric(metric) },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(
                                        1.dp,
                                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 40.dp)
                                        .testTag("sip_rankings_metric_${metric.name}")
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = "Rank by ${metric.shortLabel}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }

                        // Category Filter Dropdown Trigger
                        val isFiltered = selectedCategories.isNotEmpty()
                        val orderedLabels = remember(selectedCategories) {
                            selectedCategories.mapNotNull { FundCategoryFilter.CATEGORIES_BY_ID[it] }
                        }
                        val categorySummary = when {
                            orderedLabels.isEmpty() -> "Category Filter: All Funds (MF & SIF)"
                            orderedLabels.size == 1 -> "Category: ${orderedLabels.first().label}"
                            else -> "Categories (${orderedLabels.size}): ${orderedLabels.first().label} +${orderedLabels.size - 1} more"
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                onClick = { showCategorySheet = true },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isFiltered) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, if (isFiltered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 44.dp)
                                    .testTag("sip_rankings_category_filter_trigger")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.TrendingUp,
                                        contentDescription = null,
                                        tint = if (isFiltered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = categorySummary,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isFiltered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        imageVector = Icons.Filled.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            if (isFiltered) {
                                Surface(
                                    onClick = { onApplyCategories(emptySet()) },
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                                    modifier = Modifier
                                        .heightIn(min = 44.dp)
                                        .testTag("sip_rankings_category_clear_button")
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = "Reset",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. Universe Coverage Card
                item(key = "universe_coverage_card") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Direct Growth Universe Coverage",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = "${result.evaluatedCount} evaluated • ${syncStatus.historiesSyncedCount}/${syncStatus.eligibleCatalogCount} synced",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (result.endEpochDay != null) {
                                        Text(
                                            text = "Reference window: ${DateFormatUtils.formatDisplay(result.startEpochDay)} → ${DateFormatUtils.formatDisplay(result.endEpochDay)}",
                                            style = NumericTextStyle.copy(fontSize = 10.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                if (syncStatus.isUniverseHistorySyncing) {
                                    OutlinedButton(
                                        onClick = onPauseUniverseSync,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("Pause", style = MaterialTheme.typography.labelMedium)
                                    }
                                } else if (syncStatus.historiesSyncedCount < syncStatus.eligibleCatalogCount || syncStatus.eligibleCatalogCount == 0) {
                                    Button(
                                        onClick = onStartUniverseSync,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.testTag("sip_rankings_sync_universe_button")
                                    ) {
                                        Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Sync Universe", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }

                            if (syncStatus.isUniverseHistorySyncing || (syncStatus.eligibleCatalogCount > 0 && syncStatus.historiesSyncedCount < syncStatus.eligibleCatalogCount)) {
                                val progress = if (syncStatus.eligibleCatalogCount > 0) {
                                    (syncStatus.historiesSyncedCount.toFloat() / syncStatus.eligibleCatalogCount.toFloat()).coerceIn(0f, 1f)
                                } else 0f
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth().height(5.dp)
                                )
                            }
                        }
                    }
                }

                // 5. Loading / Empty / Results List
                if (isLoading && result.topPerformers.isEmpty()) {
                    item(key = "loading_state") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 36.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "Evaluating SIP XIRR & Worst-Case NAVs across universe...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else if (result.topPerformers.isEmpty()) {
                    item(key = "empty_state") {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "No Eligible Schemes Evaluated in This Range",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Try picking a more recent start date (e.g. 1Y, 2Y) or tap \"Sync Universe\" to backfill historical NAVs.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    item(key = "results_header") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Icon(Icons.Filled.TrendingUp, contentDescription = null, tint = gainColor, modifier = Modifier.size(20.dp))
                            Text(
                                text = "TOP ${result.topPerformers.size} FUNDS BY ${result.rankMetric.shortLabel.uppercase()}",
                                style = MaterialTheme.typography.titleSmall,
                                color = gainColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    items(result.topPerformers, key = { it.schemeCode }) { entry ->
                        SipRankingRowCard(
                            entry = entry,
                            selectedMetric = selectedMetric,
                            currencyFormat = sCurrencyFormat,
                            gainColor = gainColor,
                            lossColor = lossColor,
                            onOpenCompare = { onOpenFundInCompare(entry.schemeCode) },
                            onToggleFav = { onToggleFavourite(entry.schemeCode) }
                        )
                    }
                }
            }
        }
    }

    if (showCalendarDialog) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = Instant.ofEpochSecond(startEpochDay * 86400L).toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showCalendarDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = datePickerState.selectedDateMillis
                        if (millis != null) {
                            val pickedDay = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                            onSelectStartDate(pickedDay)
                        }
                        showCalendarDialog = false
                    }
                ) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showCalendarDialog = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showCustomAmountDialog) {
        AlertDialog(
            onDismissRequest = { showCustomAmountDialog = false },
            title = { Text("Custom SIP Installment") },
            text = {
                OutlinedTextField(
                    value = customAmountInput,
                    onValueChange = { input -> customAmountInput = input.filter { it.isDigit() }.take(7) },
                    label = { Text("Amount in INR (₹)") },
                    prefix = { Text("₹ ") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val amt = customAmountInput.toDoubleOrNull()
                    if (amt != null && amt > 0.0) {
                        onSelectAmount(amt)
                    }
                    showCustomAmountDialog = false
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomAmountDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showCategorySheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showCategorySheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            CategoryMultiSelectSheetContent(
                initialSelectedIds = selectedCategories,
                onApply = { updated ->
                    onApplyCategories(updated)
                    showCategorySheet = false
                },
                onDismiss = { showCategorySheet = false }
            )
        }
    }
}

@Composable
private fun SipRankingRowCard(
    entry: SipRankingEntry,
    selectedMetric: SipRankMetric,
    currencyFormat: NumberFormat,
    gainColor: Color,
    lossColor: Color,
    onOpenCompare: () -> Unit,
    onToggleFav: () -> Unit
) {
    val rankBadgeBg = when (entry.rank) {
        1 -> Color(0xFFFFD700)
        2 -> Color(0xFFC0C0C0)
        3 -> Color(0xFFCD7F32)
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val rankBadgeText = when (entry.rank) {
        1, 2, 3 -> Color.Black
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    Card(
        onClick = onOpenCompare,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sip_rankings_row_${entry.schemeCode}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Rank Badge + Name + Bookmark Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(rankBadgeBg, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "#${entry.rank}",
                        style = NumericTextStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        color = rankBadgeText
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.schemeName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${entry.amcName} • ${entry.category}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onToggleFav,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (entry.isFavourite) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        contentDescription = "Bookmark fund",
                        tint = if (entry.isFavourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            // Metrics 2x2 Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Column: Primary Return metrics
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val isXirrPrimary = selectedMetric == SipRankMetric.XIRR
                    val primaryLabel = if (isXirrPrimary) "XIRR" else "Absolute Return"
                    val primaryVal = if (isXirrPrimary) {
                        entry.xirrPercent?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "N/A"
                    } else {
                        String.format(Locale.US, "%+.2f%%", entry.absoluteReturnPercent)
                    }
                    val primaryColor = if (entry.absoluteReturnPercent >= 0.0) gainColor else lossColor

                    Text(
                        text = primaryLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = primaryVal,
                        style = NumericTextStyle.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                        color = primaryColor
                    )

                    // Secondary Metric
                    val secVal = if (isXirrPrimary) {
                        "Absolute: ${String.format(Locale.US, "%+.2f%%", entry.absoluteReturnPercent)}"
                    } else {
                        "XIRR: ${entry.xirrPercent?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "N/A"}"
                    }
                    Text(
                        text = secVal,
                        style = NumericTextStyle.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Right Column: Valuation & Invested capital
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Valuation",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = currencyFormat.format(entry.currentValue),
                        style = NumericTextStyle.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Invested: ${currencyFormat.format(entry.totalInvested)} (${entry.installmentCount} lots)",
                        style = NumericTextStyle.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
