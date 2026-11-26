package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.DailyNavSyncWorker
import com.example.data.DateFormatUtils
import com.example.data.SyncStatusState
import com.example.ui.CompareScreen
import com.example.ui.FavouritesScreen
import com.example.ui.RankingsScreen
import com.example.ui.RollingReturnRankingsScreen
import com.example.ui.RollingReturnScreen
import com.example.ui.SipAnalysisScreen
import com.example.ui.SipRankingsScreen
import com.example.ui.BlendRankingsScreen
import com.example.ui.PullToRefreshBox
import com.example.ui.theme.MfCompareTheme
import com.example.ui.theme.NumericTextStyle

class MainActivity : ComponentActivity() {
    private var mainViewModel: MainViewModel? = null
    private var initialCreateHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DailyNavSyncWorker.schedulePeriodicSync(applicationContext)

        val vm = ViewModelProvider(this)[MainViewModel::class.java]
        mainViewModel = vm
        initialCreateHandled = true

        setContent {
            val isDark by vm.isDarkTheme.collectAsStateWithLifecycle()

            MfCompareTheme(darkTheme = isDark) {
                MfCompareApp(viewModel = vm, isDark = isDark)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (initialCreateHandled) {
            initialCreateHandled = false
        } else {
            mainViewModel?.onAppResumed()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MfCompareApp(
    viewModel: MainViewModel,
    isDark: Boolean
) {
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val selectedSchemeCodes by viewModel.selectedSchemeCodes.collectAsStateWithLifecycle()
    val hiddenSchemeCodes by viewModel.hiddenSchemeCodes.collectAsStateWithLifecycle()
    val favouriteCodes by viewModel.favouriteCodes.collectAsStateWithLifecycle()
    val comparisonSeries by viewModel.comparisonSeries.collectAsStateWithLifecycle()
    val selectedPeriod by viewModel.selectedPeriod.collectAsStateWithLifecycle()
    val customStartEpochDay by viewModel.customStartEpochDay.collectAsStateWithLifecycle()
    val customEndEpochDay by viewModel.customEndEpochDay.collectAsStateWithLifecycle()
    val youngestInceptionInfo by viewModel.youngestInceptionInfo.collectAsStateWithLifecycle()
    val isComparisonLoading by viewModel.isComparisonLoading.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val rankingDays by viewModel.rankingDays.collectAsStateWithLifecycle()
    val selectedRankingCategories by viewModel.selectedRankingCategories.collectAsStateWithLifecycle()
    val rankingsResult by viewModel.rankingsResult.collectAsStateWithLifecycle()
    val isRankingsLoading by viewModel.isRankingsLoading.collectAsStateWithLifecycle()
    val favouritesUi by viewModel.favouritesUi.collectAsStateWithLifecycle()
    val isSipAnalysisOpen by viewModel.isSipAnalysisOpen.collectAsStateWithLifecycle()
    val sipStartMode by viewModel.sipStartMode.collectAsStateWithLifecycle()
    val sipCustomStartEpochDay by viewModel.sipCustomStartEpochDay.collectAsStateWithLifecycle()
    val sipFundConfigs by viewModel.sipFundConfigs.collectAsStateWithLifecycle()
    val sipAnalysisResults by viewModel.sipAnalysisResults.collectAsStateWithLifecycle()
    val isRollingReturnOpen by viewModel.isRollingReturnOpen.collectAsStateWithLifecycle()
    val rollingStartMode by viewModel.rollingStartMode.collectAsStateWithLifecycle()
    val rollingCustomStartEpochDay by viewModel.rollingCustomStartEpochDay.collectAsStateWithLifecycle()
    val rollingWindow by viewModel.rollingWindow.collectAsStateWithLifecycle()
    val rollingReturnResults by viewModel.rollingReturnResults.collectAsStateWithLifecycle()

    val isSipRankingsOpen by viewModel.isSipRankingsOpen.collectAsStateWithLifecycle()
    val sipRankingTenure by viewModel.sipRankingTenure.collectAsStateWithLifecycle()
    val sipRankingAmount by viewModel.sipRankingAmount.collectAsStateWithLifecycle()
    val sipRankingStartEpochDay by viewModel.sipRankingStartEpochDay.collectAsStateWithLifecycle()
    val sipRankingMetric by viewModel.sipRankingMetric.collectAsStateWithLifecycle()
    val sipRankingCategories by viewModel.sipRankingCategories.collectAsStateWithLifecycle()
    val sipRankingsResult by viewModel.sipRankingsResult.collectAsStateWithLifecycle()
    val isSipRankingsLoading by viewModel.isSipRankingsLoading.collectAsStateWithLifecycle()

    val isRollingReturnRankingsOpen by viewModel.isRollingReturnRankingsOpen.collectAsStateWithLifecycle()
    val rollingRankingWindow by viewModel.rollingRankingWindow.collectAsStateWithLifecycle()
    val rollingRankingStartEpochDay by viewModel.rollingRankingStartEpochDay.collectAsStateWithLifecycle()
    val rollingRankingMetric by viewModel.rollingRankingMetric.collectAsStateWithLifecycle()
    val rollingRankingCategories by viewModel.rollingRankingCategories.collectAsStateWithLifecycle()
    val rollingRankingsResult by viewModel.rollingRankingsResult.collectAsStateWithLifecycle()
    val isRollingRankingsLoading by viewModel.isRollingRankingsLoading.collectAsStateWithLifecycle()

    val isBlendRankingsOpen by viewModel.isBlendRankingsOpen.collectAsStateWithLifecycle()
    val blendRankingsTimeframe by viewModel.blendRankingsTimeframe.collectAsStateWithLifecycle()
    val blendCustomStartEpochDay by viewModel.blendCustomStartEpochDay.collectAsStateWithLifecycle()
    val blendRollingWindow by viewModel.blendRollingWindow.collectAsStateWithLifecycle()
    val blendSipTenure by viewModel.blendSipTenure.collectAsStateWithLifecycle()
    val blendSipAmount by viewModel.blendSipAmount.collectAsStateWithLifecycle()
    val blendSipWeight by viewModel.blendSipWeight.collectAsStateWithLifecycle()
    val blendPeerGroupAll by viewModel.blendPeerGroupAll.collectAsStateWithLifecycle()
    val blendCategories by viewModel.blendCategories.collectAsStateWithLifecycle()
    val blendMinFundAgeDays by viewModel.blendMinFundAgeDays.collectAsStateWithLifecycle()
    val blendMinSipXirr by viewModel.blendMinSipXirr.collectAsStateWithLifecycle()
    val blendMinRollingAvg by viewModel.blendMinRollingAvg.collectAsStateWithLifecycle()
    val blendMinRollingMedian by viewModel.blendMinRollingMedian.collectAsStateWithLifecycle()
    val blendConsistencyHurdle by viewModel.blendConsistencyHurdle.collectAsStateWithLifecycle()
    val blendMinConsistency by viewModel.blendMinConsistency.collectAsStateWithLifecycle()
    val blendSortBy by viewModel.blendSortBy.collectAsStateWithLifecycle()
    val blendRankingsResult by viewModel.blendRankingsResult.collectAsStateWithLifecycle()
    val isBlendRankingsLoading by viewModel.isBlendRankingsLoading.collectAsStateWithLifecycle()

    var showSyncDashboardSheet by remember { mutableStateOf(false) }
    var askedNotificationPermission by remember { mutableStateOf(false) }
    val appContext = androidx.compose.ui.platform.LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    val requestNotificationsOnceIfNeeded = remember(appContext) {
        {
            if (!askedNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    askedNotificationPermission = true
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    if (isSipAnalysisOpen) {
        BackHandler {
            viewModel.closeSipAnalysis()
        }
        SipAnalysisScreen(
            selectedSchemeCodes = selectedSchemeCodes,
            sipFundConfigs = sipFundConfigs,
            sipStartMode = sipStartMode,
            sipCustomStartEpochDay = sipCustomStartEpochDay,
            youngestInceptionInfo = youngestInceptionInfo,
            sipResults = sipAnalysisResults,
            isDark = isDark,
            onBack = viewModel::closeSipAnalysis,
            onToggleTheme = viewModel::toggleTheme,
            onSelectStartMode = viewModel::setSipStartMode,
            onCustomStartDateSelected = viewModel::setSipCustomStartDate,
            onUpdateFundAmount = viewModel::updateSipFundAmount,
            onUpdateFundTenure = viewModel::updateSipFundTenure
        )
        return
    }

    if (isRollingReturnOpen) {
        BackHandler {
            viewModel.closeRollingReturnAnalysis()
        }
        RollingReturnScreen(
            selectedSchemeCodes = selectedSchemeCodes,
            selectedWindow = rollingWindow,
            rollingStartMode = rollingStartMode,
            rollingCustomStartEpochDay = rollingCustomStartEpochDay,
            youngestInceptionInfo = youngestInceptionInfo,
            rollingResults = rollingReturnResults,
            isDark = isDark,
            onBack = viewModel::closeRollingReturnAnalysis,
            onToggleTheme = viewModel::toggleTheme,
            onSelectStartMode = viewModel::setRollingStartMode,
            onCustomStartDateSelected = viewModel::setRollingCustomStartDate,
            onSelectWindow = viewModel::setRollingWindow
        )
        return
    }

    if (isSipRankingsOpen) {
        BackHandler {
            viewModel.closeSipRankings()
        }
        SipRankingsScreen(
            result = sipRankingsResult,
            isLoading = isSipRankingsLoading,
            selectedTenure = sipRankingTenure,
            sipAmount = sipRankingAmount,
            startEpochDay = sipRankingStartEpochDay,
            selectedMetric = sipRankingMetric,
            selectedCategories = sipRankingCategories,
            syncStatus = syncStatus,
            isDark = isDark,
            onBack = viewModel::closeSipRankings,
            onToggleTheme = viewModel::toggleTheme,
            onSelectTenure = viewModel::setSipRankingTenure,
            onSelectAmount = viewModel::setSipRankingAmount,
            onSelectStartDate = viewModel::setSipRankingStartDate,
            onSelectMetric = viewModel::setSipRankingMetric,
            onApplyCategories = viewModel::setSipRankingCategories,
            onOpenFundInCompare = { code ->
                viewModel.compareSchemesFromExternal(listOf(code))
                viewModel.closeSipRankings()
            },
            onToggleFavourite = viewModel::toggleFavourite,
            onStartUniverseSync = viewModel::startUniverseHistorySync,
            onPauseUniverseSync = viewModel::pauseUniverseHistorySync
        )
        return
    }

    if (isRollingReturnRankingsOpen) {
            BackHandler {
                viewModel.closeRollingReturnRankings()
            }
            RollingReturnRankingsScreen(
                result = rollingRankingsResult,
                isLoading = isRollingRankingsLoading,
                selectedWindow = rollingRankingWindow,
                startEpochDay = rollingRankingStartEpochDay,
                selectedMetric = rollingRankingMetric,
                selectedCategories = rollingRankingCategories,
                syncStatus = syncStatus,
                isDark = isDark,
                onBack = viewModel::closeRollingReturnRankings,
                onToggleTheme = viewModel::toggleTheme,
                onSelectWindow = viewModel::setRollingRankingWindow,
                onSelectStartDate = viewModel::setRollingRankingStartDate,
                onSelectMetric = viewModel::setRollingRankingMetric,
                onApplyCategories = viewModel::setRollingRankingCategories,
                onOpenFundInCompare = { code ->
                    viewModel.compareSchemesFromExternal(listOf(code))
                    viewModel.closeRollingReturnRankings()
                },
                onToggleFavourite = viewModel::toggleFavourite,
                onStartUniverseSync = viewModel::startUniverseHistorySync,
                onPauseUniverseSync = viewModel::pauseUniverseHistorySync
            )
            return
        }

        if (isBlendRankingsOpen) {
            BackHandler {
                viewModel.closeBlendRankings()
            }
            BlendRankingsScreen(
                result = blendRankingsResult,
                isLoading = isBlendRankingsLoading,
                timeframe = blendRankingsTimeframe,
                customStartEpochDay = blendCustomStartEpochDay,
                rollingWindow = blendRollingWindow,
                sipTenure = blendSipTenure,
                sipAmount = blendSipAmount,
                sipWeight = blendSipWeight,
                peerGroupAll = blendPeerGroupAll,
                selectedCategories = blendCategories,
                minFundAgeDays = blendMinFundAgeDays,
                minSipXirr = blendMinSipXirr,
                minRollingAvg = blendMinRollingAvg,
                minRollingMedian = blendMinRollingMedian,
                consistencyHurdle = blendConsistencyHurdle,
                minConsistency = blendMinConsistency,
                sortBy = blendSortBy,
                syncStatus = syncStatus,
                isDark = isDark,
                onBack = viewModel::closeBlendRankings,
                onToggleTheme = viewModel::toggleTheme,
                onSelectTimeframe = viewModel::setBlendTimeframe,
                onCustomStartDateSelected = viewModel::setBlendCustomStartDate,
                onSelectRollingWindow = viewModel::setBlendRollingWindow,
                onSelectSipTenure = viewModel::setBlendSipTenure,
                onSelectSipAmount = viewModel::setBlendSipAmount,
                onSelectSipWeight = viewModel::setBlendSipWeight,
                onTogglePeerGroupAll = viewModel::setBlendPeerGroupAll,
                onApplyCategories = viewModel::setBlendCategories,
                onUpdateMinFundAge = viewModel::setBlendMinFundAgeDays,
                onUpdateMinSipXirr = viewModel::setBlendMinSipXirr,
                onUpdateMinRollingAvg = viewModel::setBlendMinRollingAvg,
                onUpdateMinRollingMedian = viewModel::setBlendMinRollingMedian,
                onUpdateConsistencyHurdle = viewModel::setBlendConsistencyHurdle,
                onUpdateMinConsistency = viewModel::setBlendMinConsistency,
                onSelectSortBy = viewModel::setBlendSortBy,
                onOpenFundInCompare = { code ->
                    viewModel.compareSchemesFromExternal(listOf(code))
                    viewModel.closeBlendRankings()
                },
                onToggleFavourite = viewModel::toggleFavourite,
                onRefreshSync = { viewModel.triggerCatalogSync(force = true) }
            )
            return
        }

        if (selectedTab != AppTab.COMPARE) {
        BackHandler {
            viewModel.selectTab(AppTab.COMPARE)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        modifier = Modifier
                            .clickable { showSyncDashboardSheet = true }
                            .testTag("sync_dashboard_trigger")
                    ) {
                        Text(
                            text = "MF Compare",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = syncStatus.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (syncStatus.errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    Surface(
                        onClick = {
                            requestNotificationsOnceIfNeeded()
                            viewModel.triggerCatalogSync(force = true)
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
                        modifier = Modifier.testTag("sync_now_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Filled.Sync,
                                contentDescription = "Sync latest NAVs",
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (syncStatus.isCatalogSyncing) "Syncing" else "Sync",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    IconButton(
                        onClick = { viewModel.toggleTheme() },
                        modifier = Modifier.testTag("theme_toggle_button")
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
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                NavigationBarItem(
                    selected = selectedTab == AppTab.COMPARE,
                    onClick = { viewModel.selectTab(AppTab.COMPARE) },
                    icon = { Icon(Icons.Filled.ShowChart, contentDescription = "Compare") },
                    label = { Text(AppTab.COMPARE.label) },
                    modifier = Modifier.testTag("nav_tab_compare")
                )
                NavigationBarItem(
                    selected = selectedTab == AppTab.RANKINGS,
                    onClick = { viewModel.selectTab(AppTab.RANKINGS) },
                    icon = { Icon(Icons.Filled.TrendingUp, contentDescription = "Rankings") },
                    label = { Text(AppTab.RANKINGS.label) },
                    modifier = Modifier.testTag("nav_tab_rankings")
                )
                NavigationBarItem(
                    selected = selectedTab == AppTab.FAVOURITES,
                    onClick = { viewModel.selectTab(AppTab.FAVOURITES) },
                    icon = { Icon(Icons.Filled.Bookmark, contentDescription = "Favourites") },
                    label = { Text(AppTab.FAVOURITES.label) },
                    modifier = Modifier.testTag("nav_tab_favourites")
                )
            }
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = syncStatus.isCatalogSyncing,
            onRefresh = { viewModel.triggerCatalogSync(force = true) },
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            when (selectedTab) {
                AppTab.COMPARE -> CompareScreen(
                    searchQuery = searchQuery,
                    onSearchQueryChange = viewModel::updateSearchQuery,
                    searchResults = searchResults,
                    selectedSchemeCodes = selectedSchemeCodes,
                    hiddenSchemeCodes = hiddenSchemeCodes,
                    favouriteCodes = favouriteCodes,
                    comparisonSeries = comparisonSeries,
                    selectedPeriod = selectedPeriod,
                    customStartEpochDay = customStartEpochDay,
                    customEndEpochDay = customEndEpochDay,
                    youngestInceptionInfo = youngestInceptionInfo,
                    isComparisonLoading = isComparisonLoading,
                    syncStatus = syncStatus,
                    isDark = isDark,
                    onAddScheme = viewModel::addSchemeToCompare,
                    onRemoveScheme = viewModel::removeSchemeFromCompare,
                    onClearAll = viewModel::clearAllComparedSchemes,
                    onToggleVisibility = viewModel::toggleSchemeVisibility,
                    onToggleFavourite = viewModel::toggleFavourite,
                    onSelectPeriod = viewModel::selectPeriod,
                    onSelectYoungestInception = viewModel::selectYoungestInception,
                    onCustomDateRangeSelected = viewModel::setCustomDateRange,
                    onCustomEndDateSelected = viewModel::setCustomEndDateKeepingMode,
                    onOpenSipAnalysis = viewModel::openSipAnalysis,
                    onOpenRollingReturn = viewModel::openRollingReturnAnalysis,
                    onOpenSipRankings = viewModel::openSipRankings,
                    onOpenRollingReturnRankings = viewModel::openRollingReturnRankings,
                    onOpenBlendRankings = viewModel::openBlendRankings,
                    onRefreshSync = { viewModel.triggerCatalogSync(force = true) },
                    modifier = Modifier.fillMaxSize()
                )

                AppTab.RANKINGS -> RankingsScreen(
                    selectedDays = rankingDays,
                    selectedCategories = selectedRankingCategories,
                    rankingsResult = rankingsResult,
                    isLoading = isRankingsLoading,
                    syncStatus = syncStatus,
                    isDark = isDark,
                    onSelectDays = viewModel::selectRankingDays,
                    onApplyCategories = viewModel::setRankingCategories,
                    onStartUniverseSync = {
                        requestNotificationsOnceIfNeeded()
                        viewModel.startUniverseHistorySync()
                    },
                    onPauseUniverseSync = viewModel::pauseUniverseHistorySync,
                    onOpenFundInCompare = { code -> viewModel.compareSchemesFromExternal(listOf(code)) },
                    onToggleFavourite = viewModel::toggleFavourite,
                    modifier = Modifier.fillMaxSize()
                )

                AppTab.FAVOURITES -> FavouritesScreen(
                    favourites = favouritesUi,
                    isDark = isDark,
                    onCompareSelected = viewModel::compareSchemesFromExternal,
                    onToggleFavourite = viewModel::toggleFavourite,
                    onGoToSearch = { viewModel.selectTab(AppTab.COMPARE) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    if (showSyncDashboardSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSyncDashboardSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            SyncDiagnosticsSheetContent(
                syncStatus = syncStatus,
                onRefreshNavs = { viewModel.triggerNavRefreshSync() },
                onCheckNewSchemes = { viewModel.triggerSchemeMasterSync() }
            )
        }
    }
}

@Composable
private fun SyncDiagnosticsSheetContent(
    syncStatus: SyncStatusState,
    onRefreshNavs: () -> Unit,
    onCheckNewSchemes: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("sync_dashboard_sheet"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Synchronization & Storage Status",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

        SyncMetricRow(
            label = "Last Scheme Master Sync",
            value = DateFormatUtils.formatTimestamp(syncStatus.lastSchemeMasterSyncMillis)
        )
        SyncMetricRow(
            label = "Last NAV Sync",
            value = "${DateFormatUtils.formatTimestamp(syncStatus.lastNavSyncMillis)} (Latest NAV: ${DateFormatUtils.formatShort(syncStatus.lastDataEpochDay)})"
        )
        SyncMetricRow(
            label = "Eligible Direct Growth Schemes",
            value = if (syncStatus.inactiveSchemeCount > 0) {
                "${syncStatus.eligibleCatalogCount} active (${syncStatus.inactiveSchemeCount} closed/merged)"
            } else {
                "${syncStatus.eligibleCatalogCount} active"
            }
        )
        SyncMetricRow(
            label = "Schemes With Historical NAV",
            value = "${syncStatus.historiesSyncedCount} / ${syncStatus.eligibleCatalogCount}"
        )
        SyncMetricRow(
            label = "New Schemes Discovered (Last Sync)",
            value = "${syncStatus.newSchemesDiscovered}"
        )
        SyncMetricRow(
            label = "New NAV Records Inserted (Last Sync)",
            value = "${syncStatus.newNavRecordsInserted}"
        )
        SyncMetricRow(
            label = "Current Database Size",
            value = DateFormatUtils.formatBytes(syncStatus.databaseSizeBytes)
        )
        SyncMetricRow(
            label = "Last Synchronization Error",
            value = syncStatus.errorMessage ?: "None (weekends & holidays skipped cleanly)",
            isError = syncStatus.errorMessage != null
        )

        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onRefreshNavs,
                enabled = !syncStatus.isCatalogSyncing,
                modifier = Modifier
                    .weight(1f)
                    .testTag("dashboard_refresh_navs_button")
            ) {
                Text("Refresh Latest NAVs", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = onCheckNewSchemes,
                enabled = !syncStatus.isCatalogSyncing,
                modifier = Modifier
                    .weight(1f)
                    .testTag("dashboard_check_schemes_button")
            ) {
                Text("Check New Schemes", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun SyncMetricRow(
    label: String,
    value: String,
    isError: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = NumericTextStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.25f)
        )
    }
}
