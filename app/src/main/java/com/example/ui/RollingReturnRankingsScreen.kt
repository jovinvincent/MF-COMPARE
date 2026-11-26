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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TrendingUp
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.DateFormatUtils
import com.example.data.FundCategoryFilter
import com.example.data.RollingRankMetric
import com.example.data.RollingRankingEntry
import com.example.data.RollingRankingsResult
import com.example.data.RollingWindow
import com.example.data.SyncStatusState
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

private enum class RollingPresetTimeframe(val label: String, val months: Long) {
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
fun RollingReturnRankingsScreen(
    result: RollingRankingsResult,
    isLoading: Boolean,
    selectedWindow: RollingWindow,
    startEpochDay: Long,
    selectedMetric: RollingRankMetric,
    selectedCategories: Set<String>,
    syncStatus: SyncStatusState,
    isDark: Boolean,
    onBack: () -> Unit,
    onToggleTheme: () -> Unit,
    onSelectWindow: (RollingWindow) -> Unit,
    onSelectStartDate: (Long) -> Unit,
    onSelectMetric: (RollingRankMetric) -> Unit,
    onApplyCategories: (Set<String>) -> Unit,
    onOpenFundInCompare: (String) -> Unit,
    onToggleFavourite: (String) -> Unit,
    onStartUniverseSync: () -> Unit,
    onPauseUniverseSync: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showCalendarDialog by remember { mutableStateOf(false) }
    var showCategorySheet by remember { mutableStateOf(false) }

    val gainColor = if (isDark) GainGreenDark else GainGreenLight
    val lossColor = if (isDark) LossRedDark else LossRedLight

    val referenceToday = result.endEpochDay ?: LocalDate.now().toEpochDay()
    val activePresetTimeframe = remember(startEpochDay, referenceToday) {
        RollingPresetTimeframe.entries.find { preset ->
            val expectedStart = preset.resolveStartEpochDay(referenceToday)
            kotlin.math.abs(expectedStart - startEpochDay) <= 3L
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("rolling_return_rankings_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("rolling_rankings_back_button")
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
                            text = "Rolling Return Rankings",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Universe Top 20 • Multi-Window Consistency",
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
                        modifier = Modifier.testTag("rolling_rankings_theme_toggle")
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
                .padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp)
                    .testTag("rolling_return_rankings_lazy_column"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp)
            ) {
                // 1. Timeframe & Date Picker Selection Card
                item(key = "timeframe_selection_card") {
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
                            Text(
                                text = "START DATE FOR ROLLING PERIOD",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RollingPresetTimeframe.entries.forEach { preset ->
                                    val isSelected = activePresetTimeframe == preset
                                    Surface(
                                        onClick = {
                                            val epoch = preset.resolveStartEpochDay(referenceToday)
                                            onSelectStartDate(epoch)
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        border = BorderStroke(
                                            1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                        ),
                                        modifier = Modifier
                                            .heightIn(min = 38.dp)
                                            .testTag("rolling_timeframe_preset_${preset.name.lowercase()}")
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = preset.label,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }

                                Surface(
                                    onClick = { showCalendarDialog = true },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (activePresetTimeframe == null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    border = BorderStroke(
                                        1.dp,
                                        if (activePresetTimeframe == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                    ),
                                    modifier = Modifier
                                        .heightIn(min = 38.dp)
                                        .testTag("rolling_rankings_date_picker_button")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.DateRange,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (activePresetTimeframe == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = DateFormatUtils.formatDisplay(startEpochDay),
                                            style = NumericTextStyle.copy(fontSize = 12.sp),
                                            fontWeight = if (activePresetTimeframe == null) FontWeight.Bold else FontWeight.Medium,
                                            color = if (activePresetTimeframe == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Rolling Window Selection Card (5 Windows: 7D, 14D, 1M, 3M, 1Y)
                item(key = "rolling_window_card") {
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
                            Text(
                                text = "ROLLING WINDOW TIMEFRAME",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RollingWindow.entries.forEach { window ->
                                    val isSelected = selectedWindow == window
                                    Surface(
                                        onClick = { onSelectWindow(window) },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        border = BorderStroke(
                                            1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline
                                        ),
                                        modifier = Modifier
                                            .heightIn(min = 40.dp)
                                            .testTag("rolling_window_chip_${window.name.lowercase()}")
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                text = window.label,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = window.shortBadge,
                                                style = NumericTextStyle.copy(fontSize = 10.sp),
                                                color = if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Metric Toggle Card & Category Filter
                item(key = "metric_and_filter_card") {
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
                            Text(
                                text = "RANK FUNDS BY",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RollingRankMetric.entries.forEach { metric ->
                                    val isSelected = selectedMetric == metric
                                    Surface(
                                        onClick = { onSelectMetric(metric) },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        border = BorderStroke(
                                            1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                        ),
                                        modifier = Modifier
                                            .heightIn(min = 38.dp)
                                            .testTag("rolling_metric_chip_${metric.name.lowercase()}")
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                        ) {
                                            Text(
                                                text = metric.shortLabel,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

                            // Category filter pill row
                            val isFiltered = selectedCategories.isNotEmpty()
                            val orderedLabels = remember(selectedCategories) {
                                FundCategoryFilter.ALL_CATEGORIES.filter { selectedCategories.contains(it.id) }
                            }
                            val categorySummary = when {
                                orderedLabels.isEmpty() -> "All Categories (Mutual Funds & SIFs)"
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
                                        .testTag("rolling_rankings_category_filter_trigger")
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
                                            .testTag("rolling_rankings_category_clear_button")
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
                                        text = "${result.evaluatedCount} evaluated for ${selectedWindow.shortBadge} • ${syncStatus.historiesSyncedCount}/${syncStatus.eligibleCatalogCount} synced",
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
                                        modifier = Modifier.testTag("rolling_rankings_sync_universe_button")
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
                                    "Evaluating rolling return windows across universe...",
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
                                    text = "Try picking a shorter rolling window (e.g. 7D, 14D) or a more recent start date (1Y, 2Y), or tap \"Sync Universe\" to backfill historical NAVs.",
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
                            Icon(Icons.Filled.ShowChart, contentDescription = null, tint = gainColor, modifier = Modifier.size(20.dp))
                            Text(
                                text = "TOP ${result.topPerformers.size} FUNDS BY ${result.rankMetric.shortLabel.uppercase()} (${selectedWindow.shortBadge})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    items(
                        items = result.topPerformers,
                        key = { it.schemeCode }
                    ) { entry ->
                        RollingRankingFundCard(
                            entry = entry,
                            rankMetric = result.rankMetric,
                            isDark = isDark,
                            gainColor = gainColor,
                            lossColor = lossColor,
                            onClick = { onOpenFundInCompare(entry.schemeCode) },
                            onToggleFavourite = { onToggleFavourite(entry.schemeCode) }
                        )
                    }
                }
            }
        }
    }

    if (showCalendarDialog) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = startEpochDay * 86_400_000L
        )
        DatePickerDialog(
            onDismissRequest = { showCalendarDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = pickerState.selectedDateMillis
                        if (millis != null) {
                            val epochDay = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                            onSelectStartDate(epochDay)
                        }
                        showCalendarDialog = false
                    }
                ) {
                    Text("Apply Date")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCalendarDialog = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = pickerState)
        }
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
                onApply = { newCategories ->
                    onApplyCategories(newCategories)
                    showCategorySheet = false
                },
                onDismiss = { showCategorySheet = false }
            )
        }
    }
}

@Composable
private fun RollingRankingFundCard(
    entry: RollingRankingEntry,
    rankMetric: RollingRankMetric,
    isDark: Boolean,
    gainColor: Color,
    lossColor: Color,
    onClick: () -> Unit,
    onToggleFavourite: () -> Unit
) {
    val medalColor = when (entry.rank) {
        1 -> Color(0xFFFFD700) // Gold
        2 -> Color(0xFFC0C0C0) // Silver
        3 -> Color(0xFFCD7F32) // Bronze
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val medalTextColor = when (entry.rank) {
        1, 2, 3 -> Color.Black
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val primaryVal = when (rankMetric) {
        RollingRankMetric.AVERAGE -> entry.averageReturnPercent
        RollingRankMetric.MEDIAN -> entry.medianReturnPercent
        RollingRankMetric.LATEST -> entry.latestReturnPercent
        RollingRankMetric.POSITIVE_CONSISTENCY -> entry.positivePercent
    }
    val primaryColor = if (primaryVal >= 0.0) gainColor else lossColor

    val indexBadgeColor = if (isDark) IndexAmberDark else IndexAmberLight

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("rolling_ranking_item_${entry.rank}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top Row: Rank Badge, Scheme Name + Badges, Primary Metric, Bookmark
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(medalColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "#${entry.rank}",
                            style = NumericTextStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                            color = medalTextColor
                        )
                    }

                    Spacer(Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.schemeName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(3.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (entry.isIndex) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = indexBadgeColor.copy(alpha = 0.16f),
                                    border = BorderStroke(0.5.dp, indexBadgeColor.copy(alpha = 0.5f))
                                ) {
                                    Text(
                                        text = "INDEX",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                        color = indexBadgeColor,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                                ) {
                                    Text(
                                        text = "DIRECT GROWTH",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            if (entry.category.isNotBlank()) {
                                Text(
                                    text = entry.category,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = when (rankMetric) {
                            RollingRankMetric.POSITIVE_CONSISTENCY -> "${String.format(java.util.Locale.US, "%.1f", primaryVal)}%"
                            else -> "${if (primaryVal >= 0) "+" else ""}${String.format(java.util.Locale.US, "%.2f", primaryVal)}%"
                        },
                        style = NumericTextStyle.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                        color = primaryColor
                    )
                    Text(
                        text = rankMetric.shortLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onToggleFavourite,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("rolling_rank_fav_btn_${entry.schemeCode}")
                ) {
                    Icon(
                        imageVector = if (entry.isFavourite) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        contentDescription = "Toggle favourite",
                        tint = if (entry.isFavourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            // Multi-Metric Grid: Average, Median, Latest, Consistency, Min/Max Range
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Average",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${if (entry.averageReturnPercent >= 0) "+" else ""}${String.format(java.util.Locale.US, "%.2f", entry.averageReturnPercent)}%",
                        style = NumericTextStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        color = if (entry.averageReturnPercent >= 0) gainColor else lossColor
                    )
                }

                Column {
                    Text(
                        text = "Median",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${if (entry.medianReturnPercent >= 0) "+" else ""}${String.format(java.util.Locale.US, "%.2f", entry.medianReturnPercent)}%",
                        style = NumericTextStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        color = if (entry.medianReturnPercent >= 0) gainColor else lossColor
                    )
                }

                Column {
                    Text(
                        text = "Latest Window",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${if (entry.latestReturnPercent >= 0) "+" else ""}${String.format(java.util.Locale.US, "%.2f", entry.latestReturnPercent)}%",
                        style = NumericTextStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        color = if (entry.latestReturnPercent >= 0) gainColor else lossColor
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Consistency",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${String.format(java.util.Locale.US, "%.1f", entry.positivePercent)}% +ve",
                        style = NumericTextStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        color = if (entry.positivePercent >= 70.0) gainColor else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Bottom range & observation count footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Range: ${String.format(java.util.Locale.US, "%.1f", entry.minReturnPercent)}% → ${String.format(java.util.Locale.US, "%.1f", entry.maxReturnPercent)}% (${entry.observationCount} windows)",
                    style = NumericTextStyle.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    onClick = onClick,
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)),
                    modifier = Modifier.testTag("rolling_rank_compare_btn_${entry.schemeCode}")
                ) {
                    Text(
                        text = "Compare in Chart →",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
