package com.example.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Addchart
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import com.example.data.ComparePeriod
import com.example.data.DateFormatUtils
import com.example.data.FundComparisonSeries
import com.example.data.FundMasterEntity
import com.example.data.SyncStatusState
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searchResults: List<FundMasterEntity>,
    selectedSchemeCodes: List<String>,
    hiddenSchemeCodes: Set<String>,
    favouriteCodes: Set<String>,
    comparisonSeries: List<FundComparisonSeries>,
    selectedPeriod: ComparePeriod,
    customStartEpochDay: Long?,
    customEndEpochDay: Long?,
    youngestInceptionInfo: YoungestInceptionInfo?,
    isComparisonLoading: Boolean,
    syncStatus: SyncStatusState,
    isDark: Boolean,
    onAddScheme: (String) -> Unit,
    onRemoveScheme: (String) -> Unit,
    onClearAll: () -> Unit,
    onToggleVisibility: (String) -> Unit,
    onToggleFavourite: (String) -> Unit,
    onSelectPeriod: (ComparePeriod) -> Unit,
    onSelectYoungestInception: () -> Unit,
    onCustomDateRangeSelected: (Long?, Long?) -> Unit,
    onCustomEndDateSelected: (Long?) -> Unit,
    onRefreshSync: () -> Unit,
    onOpenSipAnalysis: () -> Unit = {},
    onOpenRollingReturn: () -> Unit = {},
    onOpenSipRankings: () -> Unit = {},
    onOpenRollingReturnRankings: () -> Unit = {},
    onOpenBlendRankings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showSearchResultsPanel by remember { mutableStateOf(false) }
    var showFromDatePicker by remember { mutableStateOf(false) }
    var showToDatePicker by remember { mutableStateOf(false) }

    val gainColor = if (isDark) GainGreenDark else GainGreenLight
    val lossColor = if (isDark) LossRedDark else LossRedLight
    val indexBadgeColor = if (isDark) IndexAmberDark else IndexAmberLight

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxSize()
                .testTag("compare_lazy_column"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Smart Partial-Word Mutual Fund & Index Search Bar
            item(key = "search_bar") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            onSearchQueryChange(it)
                            showSearchResultsPanel = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("fund_search_input"),
                        placeholder = {
                            Text(
                                "Search Direct Growth funds or indices (e.g. qu sm, parag par, nifty 50)",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = { onSearchQueryChange("") },
                                        modifier = Modifier.testTag("clear_search_button")
                                    ) {
                                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                                    }
                                } else {
                                    TextButton(
                                        onClick = { showSearchResultsPanel = !showSearchResultsPanel },
                                        modifier = Modifier.testTag("browse_catalog_button")
                                    ) {
                                        Text(if (showSearchResultsPanel) "Hide" else "Browse")
                                    }
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    if (syncStatus.isCatalogSyncing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
                    }
                }
            }

            // Instant Local Search Results Card
            item(key = "search_results_panel") {
                AnimatedVisibility(visible = showSearchResultsPanel || searchQuery.isNotBlank()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("search_results_panel")
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (searchQuery.isBlank()) {
                                        "Indices & Active Direct Growth Schemes (${syncStatus.eligibleCatalogCount + syncStatus.indexCount})"
                                    } else {
                                        "Matches (${searchResults.size})"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(
                                    onClick = {
                                        showSearchResultsPanel = false
                                        if (searchQuery.isNotBlank()) {
                                            onSearchQueryChange("")
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.testTag("done_search_button")
                                ) {
                                    Text("Done", style = MaterialTheme.typography.labelMedium)
                                }
                            }

                            if (searchResults.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 20.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (syncStatus.eligibleCatalogCount == 0 && syncStatus.indexCount == 0) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                syncStatus.errorMessage ?: "Catalog is empty. Connect to internet to sync AMFI schemes.",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                textAlign = TextAlign.Center
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            Button(onClick = onRefreshSync) {
                                                Text("Retry Sync")
                                            }
                                        }
                                    } else {
                                        Text(
                                            "No Direct Growth schemes or indices match \"$searchQuery\"",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 270.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    items(searchResults, key = { it.schemeCode }) { item ->
                                        val isSelected = selectedSchemeCodes.contains(item.schemeCode)
                                        val isFav = favouriteCodes.contains(item.schemeCode)
                                        SearchResultRow(
                                            item = item,
                                            isSelected = isSelected,
                                            isFavourite = isFav,
                                            indexBadgeColor = indexBadgeColor,
                                            gainColor = gainColor,
                                            onSelectClick = {
                                                if (isSelected) {
                                                    onRemoveScheme(item.schemeCode)
                                                } else {
                                                    onAddScheme(item.schemeCode)
                                                }
                                            },
                                            onToggleFav = { onToggleFavourite(item.schemeCode) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. Selected Funds & Indices Chips
            if (selectedSchemeCodes.isNotEmpty()) {
                item(key = "selected_chips") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selectedSchemeCodes.size >= 2) {
                            Surface(
                                onClick = onClearAll,
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                                modifier = Modifier.testTag("clear_all_selected_button")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.LayersClear,
                                        contentDescription = "Clear all",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "Clear (${selectedSchemeCodes.size})",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }

                        selectedSchemeCodes.forEachIndexed { idx, code ->
                            val series = comparisonSeries.find { it.schemeCode == code }
                            val color = series?.color ?: DateFormatUtils.colorForSeries(code, idx)
                            val isVisible = !hiddenSchemeCodes.contains(code)
                            val label = series?.let { shortSeriesLabel(it.schemeName) } ?: code

                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = if (isVisible) color.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, if (isVisible) color.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(if (isVisible) color else Color.Gray, CircleShape)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { onToggleVisibility(code) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                            contentDescription = if (isVisible) "Hide line" else "Show line",
                                            modifier = Modifier.size(15.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = { onRemoveScheme(code) },
                                        modifier = Modifier
                                            .size(28.dp)
                                            .testTag("remove_chip_${code}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "Remove fund",
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Time Period Selectors (1M..10Y, Since Inception) + From/To Calendar Buttons
            item(key = "period_selectors") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ComparePeriod.entries.filter { !it.isCustom }.forEach { period ->
                            val active = selectedPeriod == period
                            val enabled = !period.isYoungestInception || youngestInceptionInfo != null
                            val chipLabel = if (period.isYoungestInception && youngestInceptionInfo != null) {
                                "Youngest (${DateFormatUtils.formatShort(youngestInceptionInfo.effectiveInceptionEpochDay)})"
                            } else {
                                period.label
                            }
                            Surface(
                                onClick = {
                                    if (period.isYoungestInception) {
                                        if (youngestInceptionInfo != null) onSelectYoungestInception()
                                    } else {
                                        onSelectPeriod(period)
                                    }
                                },
                                enabled = enabled,
                                shape = RoundedCornerShape(10.dp),
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                border = if (active) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier
                                    .heightIn(min = 36.dp)
                                    .testTag("period_chip_${period.name}")
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                ) {
                                    Text(
                                        text = chipLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                        color = when {
                                            active -> MaterialTheme.colorScheme.onPrimary
                                            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // From Date & To Date Calendar Cards
                    val isYoungestMode = selectedPeriod == ComparePeriod.YOUNGEST_INCEPTION && youngestInceptionInfo != null
                    val fromDisplayText = when {
                        isYoungestMode -> "Since Youngest Fund Inception – ${DateFormatUtils.formatDisplay(youngestInceptionInfo!!.effectiveInceptionEpochDay)}"
                        selectedPeriod == ComparePeriod.CUSTOM && customStartEpochDay != null -> DateFormatUtils.formatDisplay(customStartEpochDay)
                        else -> "Select From Date"
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CalendarDateCard(
                            label = "FROM DATE",
                            dateText = fromDisplayText,
                            isActive = isYoungestMode || (selectedPeriod == ComparePeriod.CUSTOM && customStartEpochDay != null),
                            onClick = { showFromDatePicker = true },
                            modifier = Modifier
                                .weight(1.35f)
                                .testTag("from_date_button")
                        )
                        Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                        CalendarDateCard(
                            label = "TO DATE",
                            dateText = customEndEpochDay?.let { DateFormatUtils.formatDisplay(it) } ?: "Latest Available",
                            isActive = (selectedPeriod == ComparePeriod.CUSTOM || selectedPeriod == ComparePeriod.YOUNGEST_INCEPTION) && customEndEpochDay != null,
                            onClick = { showToDatePicker = true },
                            modifier = Modifier
                                .weight(0.95f)
                                .testTag("to_date_button")
                        )
                    }
                }
            }

            // 4. Main Comparison Content: Empty State / Loading / Chart + Table
            if (selectedSchemeCodes.isEmpty()) {
                item(key = "empty_compare_state") {
                    EmptyCompareCard(
                        catalogCount = syncStatus.eligibleCatalogCount,
                        indexCount = syncStatus.indexCount,
                        onOpenBrowse = { showSearchResultsPanel = true },
                        quickPicks = searchResults.take(5),
                        onQuickPick = onAddScheme
                    )
                }
                item(key = "empty_sip_rankings_card") {
                    SipRankingsEntryCard(
                        onClick = onOpenSipRankings
                    )
                }
                item(key = "empty_rolling_rankings_card") {
                    RollingReturnRankingsEntryCard(
                        onClick = onOpenRollingReturnRankings
                    )
                }
                item(key = "empty_blend_rankings_card") {
                    BlendRankingsEntryCard(
                        onClick = onOpenBlendRankings
                    )
                }
            } else {
                if (isComparisonLoading && comparisonSeries.isEmpty()) {
                    item(key = "loading_chart") {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(Modifier.height(10.dp))
                                    Text("Fetching verified historical NAVs...", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                } else if (comparisonSeries.isEmpty()) {
                    item(key = "no_data_compare_state") {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "No NAV History in Selected Range",
                                    style = MaterialTheme.typography.titleSmall,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Try switching timeframe (e.g. Since Inception), or retry fetching NAV history from MFAPI/Yahoo.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(14.dp))
                                Button(onClick = onRefreshSync) {
                                    Text("Retry Fetching NAVs")
                                }
                            }
                        }
                    }
                } else {
                    // Check for staggered inception / differing start dates / recorded-vs-actual inception mismatch
                    val distinctStarts = comparisonSeries.map { it.actualStartEpochDay }.distinct()
                    val showMismatchNote = youngestInceptionInfo?.hasRecordedVsActualMismatch == true &&
                        (selectedPeriod == ComparePeriod.YOUNGEST_INCEPTION || selectedPeriod == ComparePeriod.INCEPTION)
                    if (distinctStarts.size > 1 || showMismatchNote) {
                        item(key = "different_starts_notice") {
                            val noteText = buildString {
                                if (showMismatchNote) {
                                    val info = youngestInceptionInfo!!
                                    append("Disclosed NAV start: ${shortSeriesLabel(info.youngestSchemeName)} actual first available NAV is ${DateFormatUtils.formatDisplay(info.actualFirstNavEpochDay)} (recorded inception: ${DateFormatUtils.formatDisplay(info.recordedInceptionEpochDay)}). ")
                                }
                                if (distinctStarts.size > 1) {
                                    append("Each scheme begins at 0.00% from its own first actual NAV on or after the selected start date (no earlier NAV or synthetic data used).")
                                }
                            }.trim()
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = noteText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }

                    // Interactive Chart
                    item(key = "comparison_chart") {
                        PerformanceComparisonChart(
                            seriesList = comparisonSeries,
                            isDark = isDark
                        )
                    }

                    // Compact Summary Table
                    item(key = "summary_table") {
                        ComparisonSummaryTable(
                            seriesList = comparisonSeries,
                            gainColor = gainColor,
                            lossColor = lossColor,
                            indexBadgeColor = indexBadgeColor,
                            onToggleVisibility = onToggleVisibility,
                            onToggleFavourite = onToggleFavourite
                        )
                    }

                    // SIP Analysis Card right under Performance Summary
                    item(key = "sip_analysis_card") {
                        SipAnalysisEntryCard(
                            selectedCount = comparisonSeries.size,
                            onClick = onOpenSipAnalysis
                        )
                    }

                    // Rolling Return Analysis Card right under SIP Analysis
                    item(key = "rolling_return_analysis_card") {
                        RollingReturnEntryCard(
                            selectedCount = comparisonSeries.size,
                            onClick = onOpenRollingReturn
                        )
                    }

                    // SIP Rankings Card directly below Rolling Return Analysis Card
                    item(key = "sip_rankings_card") {
                        SipRankingsEntryCard(
                            onClick = onOpenSipRankings
                        )
                    }

                    // Rolling Return Rankings Card directly below SIP Rankings Card
                    item(key = "rolling_return_rankings_card") {
                        RollingReturnRankingsEntryCard(
                            onClick = onOpenRollingReturnRankings
                        )
                    }

                    // Top 20 Blend Rankings Card directly below Rolling Return Rankings Card
                    item(key = "blend_rankings_card") {
                        BlendRankingsEntryCard(
                            onClick = onOpenBlendRankings
                        )
                    }
                }
            }
        }
    }

    // Material 3 DatePickerDialog for FROM DATE with "Since Youngest Fund Inception" Quick Option
    if (showFromDatePicker) {
        val defaultStart = when {
            selectedPeriod == ComparePeriod.YOUNGEST_INCEPTION && youngestInceptionInfo != null ->
                youngestInceptionInfo.effectiveInceptionEpochDay
            customStartEpochDay != null -> customStartEpochDay
            else -> LocalDate.now().minusYears(1).toEpochDay()
        }
        val initMillis = defaultStart * 86_400_000L
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initMillis)
        DatePickerDialog(
            onDismissRequest = { showFromDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = pickerState.selectedDateMillis
                        if (millis != null) {
                            val day = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                            onCustomDateRangeSelected(day, customEndEpochDay)
                        }
                        showFromDatePicker = false
                    },
                    modifier = Modifier.testTag("apply_custom_from_date_button")
                ) { Text("Apply Calendar Date") }
            },
            dismissButton = {
                TextButton(onClick = { showFromDatePicker = false }) { Text("Cancel") }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                val youngestActive = selectedPeriod == ComparePeriod.YOUNGEST_INCEPTION
                Surface(
                    onClick = {
                        if (youngestInceptionInfo != null) {
                            onSelectYoungestInception()
                            showFromDatePicker = false
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
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .testTag("since_youngest_inception_option")
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(
                            text = if (youngestInceptionInfo != null) {
                                "Since Youngest Fund Inception – ${DateFormatUtils.formatDisplay(youngestInceptionInfo.effectiveInceptionEpochDay)}"
                            } else {
                                "Since Youngest Fund Inception"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (youngestInceptionInfo != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Spacer(Modifier.height(2.dp))
                        val endLabel = customEndEpochDay?.let { DateFormatUtils.formatDisplay(it) } ?: "Latest Available"
                        Text(
                            text = if (youngestInceptionInfo != null) {
                                "Youngest: ${shortSeriesLabel(youngestInceptionInfo.youngestSchemeName)} • To: $endLabel"
                            } else {
                                "Select 1 or more funds first to align to the youngest inception date"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                DatePicker(state = pickerState, showModeToggle = true)
            }
        }
    }

    // Material 3 DatePickerDialog for TO DATE
    if (showToDatePicker) {
        val initMillis = (customEndEpochDay ?: LocalDate.now().toEpochDay()) * 86_400_000L
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initMillis)
        DatePickerDialog(
            onDismissRequest = { showToDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = pickerState.selectedDateMillis
                    if (millis != null) {
                        val day = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                        onCustomEndDateSelected(day)
                    }
                    showToDatePicker = false
                }) { Text("Apply") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        onCustomEndDateSelected(null)
                        showToDatePicker = false
                    }) { Text("Latest") }
                    TextButton(onClick = { showToDatePicker = false }) { Text("Cancel") }
                }
            }
        ) {
            DatePicker(state = pickerState, showModeToggle = true)
        }
    }
}

@Composable
private fun SearchResultRow(
    item: FundMasterEntity,
    isSelected: Boolean,
    isFavourite: Boolean,
    indexBadgeColor: Color,
    gainColor: Color,
    onSelectClick: () -> Unit,
    onToggleFav: () -> Unit
) {
    Surface(
        onClick = onSelectClick,
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("search_result_${item.schemeCode}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = if (item.isIndex) indexBadgeColor.copy(alpha = 0.18f) else gainColor.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (item.isIndex) "INDEX" else "DIRECT GROWTH",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (item.isIndex) indexBadgeColor else gainColor,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        text = item.amcName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = item.schemeName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.latestNav != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${if (item.isIndex) "Level" else "NAV"} ${String.format(Locale.US, "%.2f", item.latestNav)} • ${DateFormatUtils.formatShort(item.latestNavDateEpochDay)}",
                        style = NumericTextStyle.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(
                onClick = onToggleFav,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (isFavourite) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    contentDescription = if (isFavourite) "Remove favourite" else "Save favourite",
                    tint = if (isFavourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isSelected) Icons.Filled.Check else Icons.Filled.Add,
                    contentDescription = if (isSelected) "Selected" else "Add to compare",
                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun CalendarDateCard(
    label: String,
    dateText: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        modifier = modifier.heightIn(min = 48.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.DateRange,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = dateText,
                    style = NumericTextStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ComparisonSummaryTable(
    seriesList: List<FundComparisonSeries>,
    gainColor: Color,
    lossColor: Color,
    indexBadgeColor: Color,
    onToggleVisibility: (String) -> Unit,
    onToggleFavourite: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("comparison_summary_table")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Performance Summary", style = MaterialTheme.typography.titleSmall)
                Text(
                    "((End / Start) - 1) × 100",
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
                Text("Fund / Index", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.9f))
                Text("Start", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(0.95f))
                Text("Latest", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(0.95f))
                Text("Return", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(0.9f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))

            seriesList.forEachIndexed { idx, s ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1.9f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .background(s.color, CircleShape)
                                .clickable { onToggleVisibility(s.schemeCode) }
                        )
                        Spacer(Modifier.width(7.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = shortSeriesLabel(s.schemeName),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (s.isVisible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = if (s.isIndex) "INDEX" else "DIRECT GROWTH",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    color = if (s.isIndex) indexBadgeColor else gainColor
                                )
                                Icon(
                                    imageVector = if (s.isFavourite) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                    contentDescription = "Toggle bookmark",
                                    tint = if (s.isFavourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { onToggleFavourite(s.schemeCode) }
                                )
                            }
                        }
                    }

                    Text(
                        text = DateFormatUtils.formatShort(s.actualStartEpochDay),
                        style = NumericTextStyle.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(0.95f)
                    )
                    Text(
                        text = DateFormatUtils.formatShort(s.actualEndEpochDay),
                        style = NumericTextStyle.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(0.95f)
                    )
                    Text(
                        text = String.format(Locale.US, "%+.2f%%", s.returnPercent),
                        style = NumericTextStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        color = if (s.returnPercent >= 0.0) gainColor else lossColor,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(0.9f)
                    )
                }
                if (idx < seriesList.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                }
            }
        }
    }
}

@Composable
private fun SipAnalysisEntryCard(
    selectedCount: Int,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sip_analysis_entry_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                ) {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.TrendingUp,
                            contentDescription = "SIP Analysis",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "SIP Analysis",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Compare Daily, Weekly, Biweekly & Monthly SIP XIRR across $selectedCount selected funds",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Open SIP Analysis",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun RollingReturnEntryCard(
    selectedCount: Int,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("rolling_return_analysis_entry_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
                ) {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ShowChart,
                            contentDescription = "Rolling Return Analysis",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Rolling Return Analysis",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Explore Weekly, Biweekly, Monthly, Quarterly & Yearly rolling returns for $selectedCount selected funds",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Open Rolling Return Analysis",
                tint = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun SipRankingsEntryCard(
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sip_rankings_entry_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                ) {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.TrendingUp,
                            contentDescription = "SIP Rankings",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "SIP Rankings",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Top 20 funds by XIRR & Absolute Return across Daily, Weekly, Biweekly & Monthly SIP",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Open SIP Rankings",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun RollingReturnRankingsEntryCard(
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("rolling_return_rankings_entry_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)
                ) {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ShowChart,
                            contentDescription = "Rolling Return Rankings",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Rolling Return Rankings",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Top 20 funds evaluated across Weekly, Biweekly, Monthly, Quarterly & Yearly rolling windows",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Open Rolling Return Rankings",
                tint = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun EmptyCompareCard(
    catalogCount: Int,
    indexCount: Int,
    onOpenBrowse: () -> Unit,
    quickPicks: List<FundMasterEntity>,
    onQuickPick: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.ShowChart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Compare Direct Growth Mutual Funds, SIFs & Indices",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Search and select 2 or more schemes above to plot normalized percentage returns from 0% across any timeframe.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpenBrowse, modifier = Modifier.testTag("empty_browse_button")) {
                Icon(Icons.Filled.Addchart, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Browse ${catalogCount + indexCount} Live Schemes & Indices")
            }

            if (quickPicks.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text(
                    text = "Quick Add from Live Catalog",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    quickPicks.forEach { item ->
                        Surface(
                            onClick = { onQuickPick(item.schemeCode) },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = shortSeriesLabel(item.schemeName),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlendRankingsEntryCard(
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("blend_rankings_entry_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                ) {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.TrendingUp,
                            contentDescription = "Top 20 Blend Rankings",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Top 20 Blend Rankings",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Top 20 funds evaluated via multi-metric SIP & Rolling Return blend scoring model",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Open Blend Rankings",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
