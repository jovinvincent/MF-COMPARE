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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.material3.Slider
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
import com.example.data.BlendRankingEntry
import com.example.data.BlendRankingsResult
import com.example.data.BlendSortMetric
import com.example.data.BlendTimeframe
import com.example.data.DateFormatUtils
import com.example.data.FundCategoryFilter
import com.example.data.RollingWindow
import com.example.data.SipTenure
import com.example.data.SyncStatusState
import com.example.ui.theme.GainGreenDark
import com.example.ui.theme.GainGreenLight
import com.example.ui.theme.LossRedDark
import com.example.ui.theme.LossRedLight
import com.example.ui.theme.NumericTextStyle
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlendRankingsScreen(
    result: BlendRankingsResult,
    isLoading: Boolean,
    timeframe: BlendTimeframe,
    customStartEpochDay: Long?,
    rollingWindow: RollingWindow,
    sipTenure: SipTenure,
    sipAmount: Double,
    sipWeight: Float,
    peerGroupAll: Boolean,
    selectedCategories: Set<String>,
    minFundAgeDays: Int,
    minSipXirr: Double,
    minRollingAvg: Double,
    minRollingMedian: Double,
    consistencyHurdle: Double,
    minConsistency: Double,
    sortBy: BlendSortMetric,
    syncStatus: SyncStatusState,
    isDark: Boolean,
    onBack: () -> Unit,
    onToggleTheme: () -> Unit,
    onSelectTimeframe: (BlendTimeframe) -> Unit,
    onCustomStartDateSelected: (Long) -> Unit,
    onSelectRollingWindow: (RollingWindow) -> Unit,
    onSelectSipTenure: (SipTenure) -> Unit,
    onSelectSipAmount: (Double) -> Unit,
    onSelectSipWeight: (Float) -> Unit,
    onTogglePeerGroupAll: (Boolean) -> Unit,
    onApplyCategories: (Set<String>) -> Unit,
    onUpdateMinFundAge: (Int) -> Unit,
    onUpdateMinSipXirr: (Double) -> Unit,
    onUpdateMinRollingAvg: (Double) -> Unit,
    onUpdateMinRollingMedian: (Double) -> Unit,
    onUpdateConsistencyHurdle: (Double) -> Unit,
    onUpdateMinConsistency: (Double) -> Unit,
    onSelectSortBy: (BlendSortMetric) -> Unit,
    onOpenFundInCompare: (String) -> Unit,
    onToggleFavourite: (String) -> Unit,
    onRefreshSync: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showCategorySheet by remember { mutableStateOf(false) }
    var showCalendarDialog by remember { mutableStateOf(false) }
    var showFiltersDialog by remember { mutableStateOf(false) }
    var expandedSchemeCode by remember { mutableStateOf<String?>(null) }

    val gainColor = if (isDark) GainGreenDark else GainGreenLight
    val lossColor = if (isDark) LossRedDark else LossRedLight
    val currencyFormat = remember {
        NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).apply {
            maximumFractionDigits = 0
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("blend_rankings_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("blend_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "Top 20 Blend Rankings",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "SIP (${sipTenure.label}, ${currencyFormat.format(sipAmount)}) + Rolling (${rollingWindow.label})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showFiltersDialog = true },
                        modifier = Modifier.testTag("blend_filter_dialog_btn")
                    ) {
                        Icon(Icons.Filled.FilterList, contentDescription = "Advanced Filters")
                    }
                    IconButton(
                        onClick = onToggleTheme,
                        modifier = Modifier.testTag("blend_theme_toggle")
                    ) {
                        Icon(
                            imageVector = if (isDark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            contentDescription = "Toggle theme"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = syncStatus.isCatalogSyncing,
            onRefresh = onRefreshSync,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                LazyColumn(
                    modifier = Modifier
                        .widthIn(max = 840.dp)
                        .fillMaxSize()
                        .testTag("blend_lazy_column"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 1. Horizontally scrollable chip row for Timeframe, Rolling Window, SIP Mode, Peer Group, Sort By
                    item(key = "blend_chips_row") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Evaluation Timeframe", style = MaterialTheme.typography.titleSmall)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                BlendTimeframe.entries.forEach { tf ->
                                    val active = timeframe == tf
                                    Surface(
                                        onClick = {
                                            if (tf == BlendTimeframe.CUSTOM) {
                                                showCalendarDialog = true
                                            } else {
                                                onSelectTimeframe(tf)
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                                    ) {
                                        Text(
                                            text = if (tf == BlendTimeframe.CUSTOM && customStartEpochDay != null) DateFormatUtils.formatShort(customStartEpochDay) else tf.label,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(4.dp))
                            Text("Rolling Window", style = MaterialTheme.typography.titleSmall)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                RollingWindow.entries.forEach { win ->
                                    val active = rollingWindow == win
                                    Surface(
                                        onClick = { onSelectRollingWindow(win) },
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                                    ) {
                                        Text(
                                            text = win.label,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                            color = if (active) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Peer Group", style = MaterialTheme.typography.titleSmall)
                                Surface(
                                    onClick = { showCategorySheet = true },
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (peerGroupAll) "All Funds" else "${selectedCategories.size} Categories",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val peerModes = listOf(false to "Selected Categories", true to "All Funds")
                                peerModes.forEach { (isAll, label) ->
                                    val active = peerGroupAll == isAll
                                    Surface(
                                        onClick = { onTogglePeerGroupAll(isAll) },
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (active) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (active) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(4.dp))
                            Text("Sort Ranking By", style = MaterialTheme.typography.titleSmall)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                BlendSortMetric.entries.forEach { metric ->
                                    val active = sortBy == metric
                                    Surface(
                                        onClick = { onSelectSortBy(metric) },
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                                    ) {
                                        Text(
                                            text = metric.label,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 2. Summary Status Banner
                    item(key = "blend_summary_banner") {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${result.qualifiedCount} funds qualified out of ${result.evaluatedCount} evaluated",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (isLoading) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    }
                                }
                                Text(
                                    text = "SIP Weight: ${(result.sipWeight * 100).toInt()}% | Rolling Weight: ${(result.rollingWeight * 100).toInt()%}% | Hurdle: ${consistencyHurdle}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // 3. Results list
                    if (result.topPerformers.isEmpty() && !isLoading) {
                        item(key = "blend_empty_state") {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.TrendingUp,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        text = "No funds match the current criteria",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "Try adjusting your threshold sliders or selecting more categories.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    } else {
                        items(result.topPerformers, key = { "blend_${it.schemeCode}" }) { entry ->
                            val isExpanded = expandedSchemeCode == entry.schemeCode
                            BlendRankingItemCard(
                                entry = entry,
                                isExpanded = isExpanded,
                                gainColor = gainColor,
                                lossColor = lossColor,
                                onToggleExpand = {
                                    expandedSchemeCode = if (isExpanded) null else entry.schemeCode
                                },
                                onOpenCompare = { onOpenFundInCompare(entry.schemeCode) },
                                onToggleFav = { onToggleFavourite(entry.schemeCode) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCategorySheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showCategorySheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            FundCategoryFilter.CategoryMultiSetSheetContent(
                selectedCategories = selectedCategories,
                onApply = { newCat ->
                    onApplyCategories(newCat)
                    showCategorySheet = false
                },
                onCancel = { showCategorySheet = false }
            )
        }
    }

    if (showFiltersDialog) {
        AlertDialog(
            onDismissRequest = { showFiltersDialog = false },
            title = { Text("Blend Ranking Settings & Thresholds") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("SIP vs Rolling Weight: ${(sipWeight * 100).toInt()} / ${((1f - sipWeight) * 100).toInt()}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = sipWeight,
                        onValueChange = onSelectSipWeight,
                        valueRange = 0f..1f,
                        steps = 9
                    )

                    Text("SIP Amount: ${currencyFormat.format(sipAmount)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1000.0, 2000.0, 5000.0, 10000.0).forEach { amt ->
                            OutlinedButton(
                                onClick = { onSelectSipAmount(amt) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("₹${amt.toInt()}", maxLines = 1)
                            }
                        }
                    }

                    HorizontalDivider()

                    Text("Consistency Hurdle (% applies to window return)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = consistencyHurdle.toString(),
                        onValueChange = { it.toDoubleOrNull()?.let(onUpdateConsistencyHurdle) },
                        label = { Text("Hurdle %") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Min SIP XIRR (%)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = if (minSipXirr == -999.0) "" else minSipXirr.toString(),
                        onValueChange = { onUpdateMinSipXirr(it.toDoubleOrNull() ?: -999.0) },
                        label = { Text("Min XIRR % (optional)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Min Rolling Median (%)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = if (minRollingMedian == -999.0) "" else minRollingMedian.toString(),
                        onValueChange = { onUpdateMinRollingMedian(it.toDoubleOrNull() ?: -999.0) },
                        label = { Text("Min Rolling Median %") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showFiltersDialog = false }) {
                    Text("Apply")
                }
            }
        )
    }

    if (showCalendarDialog) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = customStartEpochDay?.let { LocalDate.ofEpochDay(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        )
        DatePickerDialog(
            onDismissRequest = { showCalendarDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val epochDay = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                            onCustomStartDateSelected(epochDay)
                            onSelectTimeframe(BlendTimeframe.CUSTOM)
                        }
                        showCalendarDialog = false
                    }
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showCalendarDialog = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun BlendRankingItemCard(
    entry: BlendRankingEntry,
    isExpanded: Boolean,
    gainColor: Color,
    lossColor: Color,
    onToggleExpand: () -> Unit,
    onOpenCompare: () -> Unit,
    onToggleFav: () -> Unit
) {
    val rankColor = when (entry.rank) {
        1 -> Color(0xFFFFD700)
        2 -> Color(0xFFC0C0C0)
        3 -> Color(0xFFCD7F32)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    }

    Card(
        onClick = onToggleExpand,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (entry.rank <= 3) rankColor.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("blend_ranking_row_${entry.rank}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = rankColor.copy(alpha = 0.2f),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "#${entry.rank}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (entry.rank <= 3) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.schemeName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(onClick = onOpenCompare)
                        )
                        Text(
                            text = "${entry.amcName} • ${entry.category}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "Blend: ${String.format(Locale.US, "%.1f", entry.blendScore)}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    IconButton(onClick = onToggleFav, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = if (entry.isFavourite) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                            contentDescription = "Bookmark",
                            tint = if (entry.isFavourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricColumn(
                    label = "SIP XIRR",
                    value = entry.sipXirr?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "N/A",
                    valueColor = if ((entry.sipXirr ?: 0.0) >= 0.0) gainColor else lossColor
                )
                MetricColumn(
                    label = "Rolling Mean",
                    value = String.format(Locale.US, "%+.2f%%", entry.rollingMean),
                    valueColor = if (entry.rollingMean >= 0.0) gainColor else lossColor
                )
                MetricColumn(
                    label = "Rolling Median",
                    value = String.format(Locale.US, "%+.2f%%", entry.rollingMedian),
                    valueColor = if (entry.rollingMedian >= 0.0) gainColor else lossColor
                )
                MetricColumn(
                    label = "Consistency",
                    value = String.format(Locale.US, "%.1f%%", entry.consistencyPercent),
                    valueColor = MaterialTheme.colorScheme.onSurface
                )
            }

            // Consistency mini bar
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                LinearProgressIndicator(
                    progress = { (entry.consistencyPercent / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Percentile Breakdown", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("SIP Percentile: ${String.format(Locale.US, "%.1f", entry.sipPercentile)}th", style = MaterialTheme.typography.bodySmall)
                        Text("Rolling Median Pct: ${String.format(Locale.US, "%.1f", entry.rollingMedianPercentile)}th", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Rolling Mean Pct: ${String.format(Locale.US, "%.1f", entry.rollingMeanPercentile)}th", style = MaterialTheme.typography.bodySmall)
                        Text("Consistency Pct: ${String.format(Locale.US, "%.1f", entry.rollingConsistencyPercentile)}th", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    Text("Rolling Observations: ${entry.observationCount} windows (Note: windows overlap)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("NAV Range: ${DateFormatUtils.formatShort(entry.firstWindowEndDay)} to ${DateFormatUtils.formatShort(entry.lastWindowEndDay)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MetricColumn(
    label: String,
    value: String,
    valueColor: Color
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = NumericTextStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
            color = valueColor
        )
    }
}
