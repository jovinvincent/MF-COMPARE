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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.DateFormatUtils
import com.example.data.FundCategoryFilter
import com.example.data.FundCategoryGroup
import com.example.data.FundCategoryOption
import com.example.data.FundRankingEntry
import com.example.data.RankingsResult
import com.example.data.SyncStatusState
import com.example.ui.theme.GainGreenDark
import com.example.ui.theme.GainGreenLight
import com.example.ui.theme.LossRedDark
import com.example.ui.theme.LossRedLight
import com.example.ui.theme.NumericTextStyle
import java.util.Locale

private val PRESET_RANKING_DAYS = listOf(1, 2, 3, 5, 7, 10, 15, 20, 30, 60, 90)

private enum class RankingViewMode(val label: String) {
    BOTH("Top & Worst"),
    TOP_ONLY("Top 20"),
    WORST_ONLY("Worst 20")
}

private val FilterListIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "FilterListIcon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(10f, 18f)
            horizontalLineTo(14f)
            verticalLineTo(16f)
            horizontalLineTo(10f)
            verticalLineTo(18f)
            close()
            moveTo(3f, 6f)
            verticalLineTo(8f)
            horizontalLineTo(21f)
            verticalLineTo(6f)
            horizontalLineTo(3f)
            close()
            moveTo(6f, 13f)
            horizontalLineTo(18f)
            verticalLineTo(11f)
            horizontalLineTo(6f)
            verticalLineTo(13f)
            close()
        }
    }.build()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankingsScreen(
    selectedDays: Int,
    selectedCategories: Set<String>,
    rankingsResult: RankingsResult,
    isLoading: Boolean,
    syncStatus: SyncStatusState,
    isDark: Boolean,
    onSelectDays: (Int) -> Unit,
    onApplyCategories: (Set<String>) -> Unit,
    onStartUniverseSync: () -> Unit,
    onPauseUniverseSync: () -> Unit,
    onOpenFundInCompare: (String) -> Unit,
    onToggleFavourite: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showCustomDaysDialog by remember { mutableStateOf(false) }
    var showCategorySheet by remember { mutableStateOf(false) }
    var customDaysInput by remember { mutableStateOf(selectedDays.toString()) }
    var viewMode by remember { mutableStateOf(RankingViewMode.BOTH) }

    val gainColor = if (isDark) GainGreenDark else GainGreenLight
    val lossColor = if (isDark) LossRedDark else LossRedLight

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxSize()
                .testTag("rankings_lazy_column"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Day Selector Chips + View Mode + Category Multi-Select Dropdown
            item(key = "day_selector") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Performance Window (Days)", style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PRESET_RANKING_DAYS.forEach { d ->
                            val active = selectedDays == d
                            Surface(
                                onClick = { onSelectDays(d) },
                                shape = RoundedCornerShape(10.dp),
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                border = if (active) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier
                                    .heightIn(min = 36.dp)
                                    .testTag("ranking_day_${d}d")
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                ) {
                                    Text(
                                        text = "${d}D",
                                        style = NumericTextStyle.copy(
                                            fontSize = 12.sp,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                                        ),
                                        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        val isCustom = !PRESET_RANKING_DAYS.contains(selectedDays)
                        Surface(
                            onClick = {
                                customDaysInput = selectedDays.toString()
                                showCustomDaysDialog = true
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isCustom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            border = if (isCustom) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier
                                .heightIn(min = 36.dp)
                                .testTag("ranking_day_custom")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = if (isCustom) "${selectedDays}D (Custom)" else "Custom",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isCustom) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // View Mode Segmented Pills
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RankingViewMode.entries.forEach { mode ->
                            val active = viewMode == mode
                            Surface(
                                onClick = { viewMode = mode },
                                shape = RoundedCornerShape(8.dp),
                                color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                border = BorderStroke(
                                    1.dp,
                                    if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                )
                            ) {
                                Text(
                                    text = mode.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    // Multi-Tickable Category Filter Dropdown Trigger
                    CategoryDropdownBar(
                        selectedCategories = selectedCategories,
                        onOpenDropdown = { showCategorySheet = true },
                        onClearAll = { onApplyCategories(emptySet()) },
                        onRemoveCategory = { idToRemove ->
                            onApplyCategories(selectedCategories - idToRemove)
                        }
                    )
                }
            }

            // Universe Sync Coverage Card
            item(key = "universe_coverage") {
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
                                val filterNote = if (selectedCategories.isNotEmpty()) {
                                    " (${selectedCategories.size} categories)"
                                } else {
                                    ""
                                }
                                Text(
                                    text = "${rankingsResult.evaluatedCount} evaluated for ${selectedDays}D$filterNote • ${syncStatus.historiesSyncedCount}/${syncStatus.eligibleCatalogCount} synced",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (rankingsResult.effectiveEndEpochDay != null && rankingsResult.effectiveTargetStartEpochDay != null) {
                                    Text(
                                        text = "Reference window: ${DateFormatUtils.formatDisplay(rankingsResult.effectiveTargetStartEpochDay)} → ${DateFormatUtils.formatDisplay(rankingsResult.effectiveEndEpochDay)}",
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
                                    modifier = Modifier.testTag("sync_universe_button")
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
                            } else {
                                0f
                            }
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(5.dp)
                            )
                        }
                    }
                }
            }

            if (isLoading && rankingsResult.evaluatedCount == 0) {
                item(key = "rankings_loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (rankingsResult.topPerformers.isEmpty() && rankingsResult.worstPerformers.isEmpty()) {
                item(key = "rankings_empty") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (selectedCategories.isNotEmpty()) {
                                Text(
                                    text = "No Evaluated Schemes in Selected Categories",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Try selecting additional categories or wait for universe history sync to complete for these schemes.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(12.dp))
                                OutlinedButton(onClick = { onApplyCategories(emptySet()) }) {
                                    Text("Clear Category Filter")
                                }
                            } else {
                                Text(
                                    text = "No Historical Coverage Yet for ${selectedDays}D",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Tap \"Sync Universe\" above to fetch verified historical NAVs for eligible Direct Growth Mutual Fund & SIF schemes.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } else {
                // TOP 20 PERFORMERS
                if (viewMode == RankingViewMode.BOTH || viewMode == RankingViewMode.TOP_ONLY) {
                    item(key = "header_top20") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Icon(Icons.Filled.TrendingUp, contentDescription = null, tint = gainColor, modifier = Modifier.size(20.dp))
                            Text(
                                text = "TOP ${rankingsResult.topPerformers.size} PERFORMERS (${selectedDays}D)",
                                style = MaterialTheme.typography.titleSmall,
                                color = gainColor
                            )
                        }
                    }
                    items(rankingsResult.topPerformers, key = { "top_${it.schemeCode}" }) { entry ->
                        RankingRowCard(
                            entry = entry,
                            accentColor = if (entry.returnPercent >= 0) gainColor else lossColor,
                            onOpenCompare = { onOpenFundInCompare(entry.schemeCode) },
                            onToggleFav = { onToggleFavourite(entry.schemeCode) },
                            rowTag = "ranking_top_row_${entry.schemeCode}"
                        )
                    }
                }

                // WORST 20 PERFORMERS
                if (viewMode == RankingViewMode.BOTH || viewMode == RankingViewMode.WORST_ONLY) {
                    item(key = "header_worst20") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Icon(Icons.Filled.TrendingDown, contentDescription = null, tint = lossColor, modifier = Modifier.size(20.dp))
                            Text(
                                text = "WORST ${rankingsResult.worstPerformers.size} PERFORMERS (${selectedDays}D)",
                                style = MaterialTheme.typography.titleSmall,
                                color = lossColor
                            )
                        }
                    }
                    items(rankingsResult.worstPerformers, key = { "worst_${it.schemeCode}" }) { entry ->
                        RankingRowCard(
                            entry = entry,
                            accentColor = if (entry.returnPercent >= 0) gainColor else lossColor,
                            onOpenCompare = { onOpenFundInCompare(entry.schemeCode) },
                            onToggleFav = { onToggleFavourite(entry.schemeCode) },
                            rowTag = "ranking_worst_row_${entry.schemeCode}"
                        )
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

    if (showCustomDaysDialog) {
        AlertDialog(
            onDismissRequest = { showCustomDaysDialog = false },
            title = { Text("Custom Ranking Period") },
            text = {
                OutlinedTextField(
                    value = customDaysInput,
                    onValueChange = { input -> customDaysInput = input.filter { it.isDigit() }.take(4) },
                    label = { Text("Number of recent days (e.g. 45, 180)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val d = customDaysInput.toIntOrNull()?.coerceIn(1, 3650)
                    if (d != null) onSelectDays(d)
                    showCustomDaysDialog = false
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDaysDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun CategoryDropdownBar(
    selectedCategories: Set<String>,
    onOpenDropdown: () -> Unit,
    onClearAll: () -> Unit,
    onRemoveCategory: (String) -> Unit
) {
    val isFiltered = selectedCategories.isNotEmpty()
    val orderedLabels = remember(selectedCategories) {
        selectedCategories.mapNotNull { FundCategoryFilter.CATEGORIES_BY_ID[it] }
    }
    val summaryText = when {
        orderedLabels.isEmpty() -> "Category Filter: All Funds (MF & SIF)"
        orderedLabels.size == 1 -> "Category: ${orderedLabels.first().label}"
        else -> "Categories (${orderedLabels.size}): ${orderedLabels.first().label} +${orderedLabels.size - 1} more"
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = onOpenDropdown,
                shape = RoundedCornerShape(10.dp),
                color = if (isFiltered) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
                border = BorderStroke(
                    1.dp,
                    if (isFiltered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("category_filter_dropdown_trigger")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = FilterListIcon,
                        contentDescription = "Category filter",
                        tint = if (isFiltered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = summaryText,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isFiltered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Open category list",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (isFiltered) {
                Surface(
                    onClick = onClearAll,
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("category_filter_clear_button")
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
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

        if (orderedLabels.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                orderedLabels.forEach { catOption ->
                    Surface(
                        onClick = { onRemoveCategory(catOption.id) },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                        modifier = Modifier.testTag("selected_category_chip_${catOption.id}")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp)
                        ) {
                            Text(
                                text = catOption.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Remove ${catOption.label}",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CategoryMultiSelectSheetContent(
    initialSelectedIds: Set<String>,
    onApply: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var pendingSelected by remember { mutableStateOf(initialSelectedIds) }
    var filterQuery by remember { mutableStateOf("") }
    var expandedGroups by remember {
        mutableStateOf(
            FundCategoryGroup.entries.filter { grp ->
                grp == FundCategoryGroup.EQUITY ||
                    FundCategoryFilter.GROUPED_CATEGORIES[grp].orEmpty().any { initialSelectedIds.contains(it.id) }
            }.toSet()
        )
    }

    val trimmedQuery = filterQuery.trim().lowercase()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.88f)
            .padding(horizontal = 16.dp)
            .testTag("category_filter_sheet")
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Filter by Fund Category",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (pendingSelected.isEmpty()) {
                        "Multi-select across Equity, Debt, Hybrid, SIF & More"
                    } else {
                        "${pendingSelected.size} categories ticked"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = { pendingSelected = emptySet() },
                enabled = pendingSelected.isNotEmpty(),
                modifier = Modifier.testTag("category_filter_sheet_clear")
            ) {
                Text("Clear All")
            }
        }

        Spacer(Modifier.height(8.dp))

        // Quick Category Search Box
        OutlinedTextField(
            value = filterQuery,
            onValueChange = { filterQuery = it },
            placeholder = {
                Text("Search category (e.g. Small Cap, Long-Short, Gilt)", maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search categories") },
            trailingIcon = {
                if (filterQuery.isNotEmpty()) {
                    IconButton(onClick = { filterQuery = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("category_filter_search_input")
        )

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

        // Grouped Multi-Tickable Category Accordion List
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("category_filter_lazy_list"),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FundCategoryGroup.entries.forEach { group ->
                val allInGroup = FundCategoryFilter.GROUPED_CATEGORIES[group].orEmpty()
                val visibleInGroup = if (trimmedQuery.isEmpty()) {
                    allInGroup
                } else {
                    allInGroup.filter {
                        it.label.lowercase().contains(trimmedQuery) ||
                            (it.subtitle?.lowercase()?.contains(trimmedQuery) == true) ||
                            group.title.lowercase().contains(trimmedQuery)
                    }
                }

                if (visibleInGroup.isNotEmpty()) {
                    val isExpanded = trimmedQuery.isNotEmpty() || expandedGroups.contains(group)
                    val selectedInGroupCount = allInGroup.count { pendingSelected.contains(it.id) }
                    val groupToggleState = when (selectedInGroupCount) {
                        0 -> ToggleableState.Off
                        allInGroup.size -> ToggleableState.On
                        else -> ToggleableState.Indeterminate
                    }

                    item(key = "group_${group.name}") {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("category_group_header_${group.name}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expandedGroups = if (expandedGroups.contains(group)) {
                                            expandedGroups - group
                                        } else {
                                            expandedGroups + group
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TriStateCheckbox(
                                    state = groupToggleState,
                                    onClick = {
                                        val ids = allInGroup.map { it.id }
                                        pendingSelected = if (groupToggleState == ToggleableState.On) {
                                            pendingSelected - ids.toSet()
                                        } else {
                                            pendingSelected + ids
                                        }
                                    },
                                    modifier = Modifier.testTag("category_group_checkbox_${group.name}")
                                )
                                Text(
                                    text = group.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                if (selectedInGroupCount > 0) {
                                    Text(
                                        text = "$selectedInGroupCount/${allInGroup.size}",
                                        style = NumericTextStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(end = 6.dp)
                                    )
                                }
                                Icon(
                                    imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                            }
                        }
                    }

                    if (isExpanded) {
                        items(visibleInGroup, key = { "cat_${it.id}" }) { option ->
                            val isChecked = pendingSelected.contains(option.id)
                            CategoryTickRow(
                                option = option,
                                checked = isChecked,
                                onToggle = {
                                    pendingSelected = if (isChecked) {
                                        pendingSelected - option.id
                                    } else {
                                        pendingSelected + option.id
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

        // Sticky Bottom Action Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(0.8f)
            ) {
                Text("Cancel")
            }
            Button(
                onClick = { onApply(pendingSelected) },
                modifier = Modifier
                    .weight(1.4f)
                    .testTag("category_filter_apply_button")
            ) {
                val applyLabel = if (pendingSelected.isEmpty()) {
                    "Show All Categories"
                } else {
                    "Apply Filter (${pendingSelected.size})"
                }
                Text(applyLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CategoryTickRow(
    option: FundCategoryOption,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
            .testTag("category_checkbox_row_${option.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!option.subtitle.isNullOrBlank()) {
                Text(
                    text = option.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun RankingRowCard(
    entry: FundRankingEntry,
    accentColor: Color,
    onOpenCompare: () -> Unit,
    onToggleFav: () -> Unit,
    rowTag: String
) {
    Card(
        onClick = onOpenCompare,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(rowTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(accentColor.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${entry.rank}",
                    style = NumericTextStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    color = accentColor
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
                    text = "${DateFormatUtils.formatShort(entry.startEpochDay)} (${String.format(Locale.US, "%.2f", entry.startNav)}) → ${DateFormatUtils.formatShort(entry.endEpochDay)} (${String.format(Locale.US, "%.2f", entry.endNav)})",
                    style = NumericTextStyle.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = String.format(Locale.US, "%+.2f%%", entry.returnPercent),
                style = NumericTextStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                color = accentColor
            )
            IconButton(
                onClick = onToggleFav,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (entry.isFavourite) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    contentDescription = if (entry.isFavourite) "Remove bookmark" else "Bookmark fund",
                    tint = if (entry.isFavourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

