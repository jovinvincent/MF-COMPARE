package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ComparePeriod
import com.example.data.DateFormatUtils
import com.example.data.FavouriteFundUiItem
import com.example.data.FundCompareRepository
import com.example.data.FundComparisonSeries
import com.example.data.FundMasterEntity
import com.example.data.FundSearchEngine
import com.example.data.NavPoint
import com.example.data.PerformanceCalculator
import com.example.data.RankingsResult
import com.example.data.RollingFundResult
import com.example.data.RollingReturnCalculator
import com.example.data.RollingStartMode
import com.example.data.RollingWindow
import com.example.data.SipCalculator
import com.example.data.SipFundConfig
import com.example.data.SipFundResult
import com.example.data.SipStartMode
import com.example.data.SipTenure
import com.example.data.SipRankMetric
import com.example.data.SipRankingEntry
import com.example.data.SipRankingsResult
import com.example.data.RollingRankMetric
import com.example.data.RollingRankingEntry
import com.example.data.RollingRankingsResult
import com.example.data.SyncStatusState
import com.example.data.YoungestInceptionInfo
import com.example.data.BlendTimeframe
import com.example.data.BlendSortMetric
import com.example.data.BlendRankingEntry
import com.example.data.BlendRankingsResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

enum class AppTab(val label: String) {
    COMPARE("Compare"),
    RANKINGS("Rankings"),
    FAVOURITES("Favourites")
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = FundCompareRepository.getInstance(application.applicationContext)

    private val _selectedTab = MutableStateFlow(AppTab.COMPARE)
    val selectedTab: StateFlow<AppTab> = _selectedTab.asStateFlow()

    private val _isDarkTheme = MutableStateFlow(true)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedSchemeCodes = MutableStateFlow<List<String>>(emptyList())
    val selectedSchemeCodes: StateFlow<List<String>> = _selectedSchemeCodes.asStateFlow()

    private val _hiddenSchemeCodes = MutableStateFlow<Set<String>>(emptySet())
    val hiddenSchemeCodes: StateFlow<Set<String>> = _hiddenSchemeCodes.asStateFlow()

    private val _selectedPeriod = MutableStateFlow(ComparePeriod.Y1)
    val selectedPeriod: StateFlow<ComparePeriod> = _selectedPeriod.asStateFlow()

    private val _customStartEpochDay = MutableStateFlow<Long?>(null)
    val customStartEpochDay: StateFlow<Long?> = _customStartEpochDay.asStateFlow()

    private val _customEndEpochDay = MutableStateFlow<Long?>(null)
    val customEndEpochDay: StateFlow<Long?> = _customEndEpochDay.asStateFlow()

    private val _rawHistories = MutableStateFlow<Map<String, List<NavPoint>>>(emptyMap())
    private val activeHistoryLoads = AtomicInteger(0)
    private val _isComparisonLoading = MutableStateFlow(false)
    val isComparisonLoading: StateFlow<Boolean> = _isComparisonLoading.asStateFlow()

    private val _isSipAnalysisOpen = MutableStateFlow(false)
    val isSipAnalysisOpen: StateFlow<Boolean> = _isSipAnalysisOpen.asStateFlow()

    private val _sipStartMode = MutableStateFlow(SipStartMode.YOUNGEST_INCEPTION)
    val sipStartMode: StateFlow<SipStartMode> = _sipStartMode.asStateFlow()

    private val _sipCustomStartEpochDay = MutableStateFlow<Long?>(null)
    val sipCustomStartEpochDay: StateFlow<Long?> = _sipCustomStartEpochDay.asStateFlow()

    private val _sipFundConfigs = MutableStateFlow<Map<String, SipFundConfig>>(emptyMap())
    val sipFundConfigs: StateFlow<Map<String, SipFundConfig>> = _sipFundConfigs.asStateFlow()

    private val _isRollingReturnOpen = MutableStateFlow(false)
    val isRollingReturnOpen: StateFlow<Boolean> = _isRollingReturnOpen.asStateFlow()

    private val _rollingStartMode = MutableStateFlow(RollingStartMode.YOUNGEST_INCEPTION)
    val rollingStartMode: StateFlow<RollingStartMode> = _rollingStartMode.asStateFlow()

    private val _rollingCustomStartEpochDay = MutableStateFlow<Long?>(null)
    val rollingCustomStartEpochDay: StateFlow<Long?> = _rollingCustomStartEpochDay.asStateFlow()

    private val _rollingWindow = MutableStateFlow(RollingWindow.MONTHLY)
    val rollingWindow: StateFlow<RollingWindow> = _rollingWindow.asStateFlow()

    private val _rankingDays = MutableStateFlow(7)
    val rankingDays: StateFlow<Int> = _rankingDays.asStateFlow()

    private val _selectedRankingCategories = MutableStateFlow<Set<String>>(emptySet())
    val selectedRankingCategories: StateFlow<Set<String>> = _selectedRankingCategories.asStateFlow()

    private val _rankingsResult = MutableStateFlow(
        RankingsResult(7, emptyList(), emptyList(), 0, 0, null, null)
    )
    val rankingsResult: StateFlow<RankingsResult> = _rankingsResult.asStateFlow()

    private val _isRankingsLoading = MutableStateFlow(false)
    val isRankingsLoading: StateFlow<Boolean> = _isRankingsLoading.asStateFlow()
    private var rankingsJob: Job? = null

    // SIP Rankings State
    private val _isSipRankingsOpen = MutableStateFlow(false)
    val isSipRankingsOpen: StateFlow<Boolean> = _isSipRankingsOpen.asStateFlow()

    private val _sipRankingTenure = MutableStateFlow(SipTenure.MONTHLY)
    val sipRankingTenure: StateFlow<SipTenure> = _sipRankingTenure.asStateFlow()

    private val _sipRankingAmount = MutableStateFlow(1000.0)
    val sipRankingAmount: StateFlow<Double> = _sipRankingAmount.asStateFlow()

    private val _sipRankingStartEpochDay = MutableStateFlow(LocalDate.now().minusYears(1).toEpochDay())
    val sipRankingStartEpochDay: StateFlow<Long> = _sipRankingStartEpochDay.asStateFlow()

    private val _sipRankingMetric = MutableStateFlow(SipRankMetric.XIRR)
    val sipRankingMetric: StateFlow<SipRankMetric> = _sipRankingMetric.asStateFlow()

    private val _sipRankingCategories = MutableStateFlow<Set<String>>(emptySet())
    val sipRankingCategories: StateFlow<Set<String>> = _sipRankingCategories.asStateFlow()

    private val _sipRankingsResult = MutableStateFlow(
        SipRankingsResult(
            tenure = SipTenure.MONTHLY,
            sipAmount = 1000.0,
            startEpochDay = LocalDate.now().minusYears(1).toEpochDay(),
            endEpochDay = null,
            topPerformers = emptyList(),
            evaluatedCount = 0,
            excludedCount = 0,
            rankMetric = SipRankMetric.XIRR
        )
    )
    val sipRankingsResult: StateFlow<SipRankingsResult> = _sipRankingsResult.asStateFlow()

    private val _isSipRankingsLoading = MutableStateFlow(false)
    val isSipRankingsLoading: StateFlow<Boolean> = _isSipRankingsLoading.asStateFlow()
    private var sipRankingsJob: Job? = null

    // Rolling Return Rankings State
    private val _isRollingReturnRankingsOpen = MutableStateFlow(false)
    val isRollingReturnRankingsOpen: StateFlow<Boolean> = _isRollingReturnRankingsOpen.asStateFlow()

    private val _rollingRankingWindow = MutableStateFlow(RollingWindow.MONTHLY)
    val rollingRankingWindow: StateFlow<RollingWindow> = _rollingRankingWindow.asStateFlow()

    private val _rollingRankingStartEpochDay = MutableStateFlow(LocalDate.now().minusYears(1).toEpochDay())
    val rollingRankingStartEpochDay: StateFlow<Long> = _rollingRankingStartEpochDay.asStateFlow()

    private val _rollingRankingMetric = MutableStateFlow(RollingRankMetric.AVERAGE)
    val rollingRankingMetric: StateFlow<RollingRankMetric> = _rollingRankingMetric.asStateFlow()

    private val _rollingRankingCategories = MutableStateFlow<Set<String>>(emptySet())
    val rollingRankingCategories: StateFlow<Set<String>> = _rollingRankingCategories.asStateFlow()

    private val _rollingRankingsResult = MutableStateFlow(
        RollingRankingsResult(
            window = RollingWindow.MONTHLY,
            startEpochDay = LocalDate.now().minusYears(1).toEpochDay(),
            endEpochDay = null,
            topPerformers = emptyList(),
            evaluatedCount = 0,
            excludedCount = 0,
            rankMetric = RollingRankMetric.AVERAGE
        )
    )
    val rollingRankingsResult: StateFlow<RollingRankingsResult> = _rollingRankingsResult.asStateFlow()

    private val _isRollingRankingsLoading = MutableStateFlow(false)
    val isRollingRankingsLoading: StateFlow<Boolean> = _isRollingRankingsLoading.asStateFlow()
    private var rollingRankingsJob: Job? = null

    // Blend Rankings State
    private val _isBlendRankingsOpen = MutableStateFlow(false)
    val isBlendRankingsOpen: StateFlow<Boolean> = _isBlendRankingsOpen.asStateFlow()

    private val _blendRankingsTimeframe = MutableStateFlow(BlendTimeframe.Y3)
    val blendRankingsTimeframe: StateFlow<BlendTimeframe> = _blendRankingsTimeframe.asStateFlow()

    private val _blendCustomStartEpochDay = MutableStateFlow<Long?>(null)
    val blendCustomStartEpochDay: StateFlow<Long?> = _blendCustomStartEpochDay.asStateFlow()

    private val _blendRollingWindow = MutableStateFlow(RollingWindow.MONTHLY)
    val blendRollingWindow: StateFlow<RollingWindow> = _blendRollingWindow.asStateFlow()

    private val _blendSipTenure = MutableStateFlow(SipTenure.MONTHLY)
    val blendSipTenure: StateFlow<SipTenure> = _blendSipTenure.asStateFlow()

    private val _blendSipAmount = MutableStateFlow(5000.0)
    val blendSipAmount: StateFlow<Double> = _blendSipAmount.asStateFlow()

    private val _blendSipWeight = MutableStateFlow(0.5f)
    val blendSipWeight: StateFlow<Float> = _blendSipWeight.asStateFlow()

    private val _blendPeerGroupAll = MutableStateFlow(false)
    val blendPeerGroupAll: StateFlow<Boolean> = _blendPeerGroupAll.asStateFlow()

    private val _blendCategories = MutableStateFlow<Set<String>>(emptySet())
    val blendCategories: StateFlow<Set<String>> = _blendCategories.asStateFlow()

    private val _blendMinFundAgeDays = MutableStateFlow(0)
    val blendMinFundAgeDays: StateFlow<Int> = _blendMinFundAgeDays.asStateFlow()

    private val _blendMinSipXirr = MutableStateFlow(-999.0)
    val blendMinSipXirr: StateFlow<Double> = _blendMinSipXirr.asStateFlow()

    private val _blendMinRollingAvg = MutableStateFlow(-999.0)
    val blendMinRollingAvg: StateFlow<Double> = _blendMinRollingAvg.asStateFlow()

    private val _blendMinRollingMedian = MutableStateFlow(-999.0)
    val blendMinRollingMedian: StateFlow<Double> = _blendMinRollingMedian.asStateFlow()

    private val _blendConsistencyHurdle = MutableStateFlow(0.0)
    val blendConsistencyHurdle: StateFlow<Double> = _blendConsistencyHurdle.asStateFlow()

    private val _blendMinConsistency = MutableStateFlow(0.0)
    val blendMinConsistency: StateFlow<Double> = _blendMinConsistency.asStateFlow()

    private val _blendSortBy = MutableStateFlow(BlendSortMetric.BLEND_SCORE)
    val blendSortBy: StateFlow<BlendSortMetric> = _blendSortBy.asStateFlow()

    private val _blendRankingsResult = MutableStateFlow(
        BlendRankingsResult(
            timeframeLabel = "",
            rollingWindow = RollingWindow.MONTHLY,
            sipTenure = SipTenure.MONTHLY,
            sipAmount = 5000.0,
            sipWeight = 0.5,
            rollingWeight = 0.5,
            peerGroupAll = false,
            selectedCategoryCount = 0,
            topPerformers = emptyList(),
            evaluatedCount = 0,
            qualifiedCount = 0,
            excludedCount = 0,
            exclusionReasons = emptyMap(),
            sortBy = BlendSortMetric.BLEND_SCORE
        )
    )
    val blendRankingsResult: StateFlow<BlendRankingsResult> = _blendRankingsResult.asStateFlow()

    private val _isBlendRankingsLoading = MutableStateFlow(false)
    val isBlendRankingsLoading: StateFlow<Boolean> = _isBlendRankingsLoading.asStateFlow()
    private var blendRankingsJob: Job? = null

    val catalogFlow: StateFlow<List<FundMasterEntity>> = repository.catalogFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val syncStatus: StateFlow<SyncStatusState> = repository.syncStatusFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SyncStatusState())

    val favouritesUi: StateFlow<List<FavouriteFundUiItem>> = repository.favouritesUiFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favouriteCodes: StateFlow<Set<String>> = combine(repository.favouriteEntriesFlow) { arr ->
        arr[0].map { it.schemeCode }.toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val searchResults: StateFlow<List<FundMasterEntity>> = combine(
        _searchQuery,
        catalogFlow
    ) { query, catalog ->
        withContext(Dispatchers.Default) {
            FundSearchEngine.search(query = query, catalog = catalog, limit = 60)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Dynamically tracks the youngest (most recent inception) among all currently selected funds.
     * Automatically recalculates whenever a newer fund is added or the youngest fund is removed.
     */
    val youngestInceptionInfo: StateFlow<YoungestInceptionInfo?> = combine(
        _selectedSchemeCodes,
        _rawHistories,
        catalogFlow
    ) { codes, histories, catalog ->
        withContext(Dispatchers.Default) {
            val catalogMap = catalog.associateBy { it.schemeCode }
            PerformanceCalculator.computeYoungestInceptionInfo(codes, catalogMap, histories)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val comparisonSeries: StateFlow<List<FundComparisonSeries>> = combine(
        _selectedSchemeCodes,
        _hiddenSchemeCodes,
        _selectedPeriod,
        _customStartEpochDay,
        _customEndEpochDay,
        _rawHistories,
        catalogFlow,
        favouriteCodes,
        youngestInceptionInfo
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val codes = values[0] as List<String>
        @Suppress("UNCHECKED_CAST")
        val hidden = values[1] as Set<String>
        val period = values[2] as ComparePeriod
        val customStart = values[3] as Long?
        val customEnd = values[4] as Long?
        @Suppress("UNCHECKED_CAST")
        val histories = values[5] as Map<String, List<NavPoint>>
        @Suppress("UNCHECKED_CAST")
        val catalog = values[6] as List<FundMasterEntity>
        @Suppress("UNCHECKED_CAST")
        val favs = values[7] as Set<String>
        val youngestInfo = values[8] as YoungestInceptionInfo?

        if (codes.isEmpty()) return@combine emptyList()
        val catalogMap = catalog.associateBy { it.schemeCode }

        val maxReferenceDay = codes.mapNotNull { code ->
            histories[code]?.lastOrNull()?.dateEpochDay
        }.maxOrNull() ?: LocalDate.now().toEpochDay()

        val requestedEnd = if (period == ComparePeriod.CUSTOM || period == ComparePeriod.YOUNGEST_INCEPTION) {
            customEnd
        } else {
            null
        }
        val effectiveReferenceEnd = requestedEnd ?: maxReferenceDay
        val requestedStart = PerformanceCalculator.resolvePeriodStartEpochDay(
            period = period,
            latestReferenceEpochDay = effectiveReferenceEnd,
            customStartEpochDay = customStart,
            youngestInceptionEpochDay = youngestInfo?.effectiveInceptionEpochDay
        )

        withContext(Dispatchers.Default) {
            codes.mapIndexedNotNull { idx, code ->
                val fund = catalogMap[code] ?: return@mapIndexedNotNull null
                val raw = histories[code].orEmpty()
                PerformanceCalculator.buildNormalizedSeries(
                    fund = fund,
                    rawHistory = raw,
                    requestedStartEpochDay = requestedStart,
                    requestedEndEpochDay = requestedEnd,
                    color = DateFormatUtils.colorForSeries(code, idx),
                    isVisible = !hidden.contains(code),
                    isFavourite = favs.contains(code)
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sipAnalysisResults: StateFlow<List<SipFundResult>> = combine(
        _selectedSchemeCodes,
        _sipFundConfigs,
        _sipStartMode,
        _sipCustomStartEpochDay,
        _rawHistories,
        catalogFlow,
        youngestInceptionInfo
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val codes = values[0] as List<String>
        @Suppress("UNCHECKED_CAST")
        val configs = values[1] as Map<String, SipFundConfig>
        val startMode = values[2] as SipStartMode
        val customStart = values[3] as Long?
        @Suppress("UNCHECKED_CAST")
        val histories = values[4] as Map<String, List<NavPoint>>
        @Suppress("UNCHECKED_CAST")
        val catalog = values[5] as List<FundMasterEntity>
        val youngestInfo = values[6] as YoungestInceptionInfo?

        if (codes.isEmpty()) return@combine emptyList()
        val catalogMap = catalog.associateBy { it.schemeCode }
        val resolvedMode = if (startMode == SipStartMode.CUSTOM_DATE && customStart == null) {
            if (youngestInfo != null) SipStartMode.YOUNGEST_INCEPTION else SipStartMode.FROM_INCEPTION
        } else {
            startMode
        }

        withContext(Dispatchers.Default) {
            codes.mapIndexedNotNull { idx, code ->
                val fund = catalogMap[code] ?: return@mapIndexedNotNull null
                val cfg = configs[code] ?: SipFundConfig(schemeCode = code)
                val raw = histories[code].orEmpty()
                SipCalculator.computeSipResult(
                    fund = fund,
                    rawHistory = raw,
                    config = cfg,
                    startMode = resolvedMode,
                    customStartEpochDay = customStart,
                    youngestInceptionEpochDay = youngestInfo?.effectiveInceptionEpochDay,
                    color = DateFormatUtils.colorForSeries(code, idx)
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rollingReturnResults: StateFlow<List<RollingFundResult>> = combine(
        _selectedSchemeCodes,
        _rollingWindow,
        _rollingStartMode,
        _rollingCustomStartEpochDay,
        _rawHistories,
        catalogFlow,
        youngestInceptionInfo
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val codes = values[0] as List<String>
        val window = values[1] as RollingWindow
        val startMode = values[2] as RollingStartMode
        val customStart = values[3] as Long?
        @Suppress("UNCHECKED_CAST")
        val histories = values[4] as Map<String, List<NavPoint>>
        @Suppress("UNCHECKED_CAST")
        val catalog = values[5] as List<FundMasterEntity>
        val youngestInfo = values[6] as YoungestInceptionInfo?

        if (codes.isEmpty()) return@combine emptyList()
        val catalogMap = catalog.associateBy { it.schemeCode }
        val resolvedMode = if (startMode == RollingStartMode.CUSTOM_DATE && customStart == null) {
            if (youngestInfo != null) RollingStartMode.YOUNGEST_INCEPTION else RollingStartMode.FROM_INCEPTION
        } else {
            startMode
        }

        withContext(Dispatchers.Default) {
            codes.mapIndexedNotNull { idx, code ->
                val fund = catalogMap[code] ?: return@mapIndexedNotNull null
                val raw = histories[code].orEmpty()
                RollingReturnCalculator.computeRollingResult(
                    fund = fund,
                    rawHistory = raw,
                    window = window,
                    startMode = resolvedMode,
                    customStartEpochDay = customStart,
                    youngestInceptionEpochDay = youngestInfo?.effectiveInceptionEpochDay,
                    color = DateFormatUtils.colorForSeries(code, idx)
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            repository.refreshCatalogAndIndices(force = false, activeSelectedCodes = _selectedSchemeCodes.value)
        }
        // Automatically recompute rankings & refresh active chart series when catalog/NAV/universe sync advances
        viewModelScope.launch {
            var lastEvaluatedSynced = -1
            var lastEvaluatedNavSync = 0L
            var lastHistoriesNavSync = 0L
            var wasUniverseSyncing = false
            var wasCatalogSyncing = false
            syncStatus.collect { status ->
                val count = status.historiesSyncedCount
                val navSynced = status.lastNavSyncMillis > lastEvaluatedNavSync
                val finishedUniverse = wasUniverseSyncing && !status.isUniverseHistorySyncing
                val finishedCatalog = wasCatalogSyncing && !status.isCatalogSyncing
                wasUniverseSyncing = status.isUniverseHistorySyncing
                wasCatalogSyncing = status.isCatalogSyncing

                val hasNewNavDelta = status.lastNavSyncMillis > lastHistoriesNavSync && status.newNavRecordsInserted > 0
                if (hasNewNavDelta) {
                    lastHistoriesNavSync = status.lastNavSyncMillis
                }
                if ((finishedCatalog || hasNewNavDelta) && _selectedSchemeCodes.value.isNotEmpty()) {
                    loadHistoriesFor(_selectedSchemeCodes.value, forceRefresh = false)
                }

                if (_selectedTab.value == AppTab.RANKINGS && (count > 0 || finishedCatalog)) {
                    val shouldRefresh = lastEvaluatedSynced <= 0 ||
                        finishedUniverse ||
                        finishedCatalog ||
                        (navSynced && status.newNavRecordsInserted > 0) ||
                        abs(count - lastEvaluatedSynced) >= 48
                    if (shouldRefresh && (count != lastEvaluatedSynced || navSynced || finishedCatalog)) {
                        lastEvaluatedSynced = count
                        lastEvaluatedNavSync = status.lastNavSyncMillis
                        refreshRankings(_rankingDays.value)
                    }
                }
            }
        }
    }

    /**
     * Called when app resumes to foreground. Uses repository's 30m (NAV) and 24h (Scheme Master)
     * request throttling so returning to the app retrieves newly published NAVs automatically
     * without wasteful downloads.
     */
    fun onAppResumed() {
        viewModelScope.launch {
            val res = repository.refreshCatalogAndIndices(
                force = false,
                activeSelectedCodes = _selectedSchemeCodes.value
            )
            if (res.isSuccess && _selectedSchemeCodes.value.isNotEmpty()) {
                val refreshed = repository.ensureHistoriesForSchemes(_selectedSchemeCodes.value, forceRefresh = false)
                _rawHistories.update { existing -> existing + refreshed }
            }
            if (_selectedTab.value == AppTab.RANKINGS) {
                refreshRankings(_rankingDays.value)
            }
        }
    }

    fun selectTab(tab: AppTab) {
        _isSipAnalysisOpen.value = false
        _isRollingReturnOpen.value = false
        _isSipRankingsOpen.value = false
        _isRollingReturnRankingsOpen.value = false
        _selectedTab.value = tab
        if (tab == AppTab.RANKINGS) {
            refreshRankings(_rankingDays.value)
            if (syncStatus.value.eligibleCatalogCount > 0 &&
                syncStatus.value.historiesSyncedCount == 0 &&
                !syncStatus.value.isUniverseHistorySyncing
            ) {
                repository.startUniverseHistorySync()
            }
        }
    }

    fun openSipAnalysis() {
        val codes = _selectedSchemeCodes.value
        if (codes.isEmpty()) return
        _isRollingReturnOpen.value = false
        _sipFundConfigs.update { current ->
            val updated = current.toMutableMap()
            codes.forEach { code ->
                if (!updated.containsKey(code)) {
                    updated[code] = SipFundConfig(schemeCode = code)
                }
            }
            updated
        }
        if (codes.any { !_rawHistories.value.containsKey(it) }) {
            loadHistoriesFor(codes)
        }
        _isSipAnalysisOpen.value = true
    }

    fun closeSipAnalysis() {
        _isSipAnalysisOpen.value = false
    }

    fun openRollingReturnAnalysis() {
        val codes = _selectedSchemeCodes.value
        if (codes.isEmpty()) return
        _isSipAnalysisOpen.value = false
        if (codes.any { !_rawHistories.value.containsKey(it) }) {
            loadHistoriesFor(codes)
        }
        _isRollingReturnOpen.value = true
    }

    fun closeRollingReturnAnalysis() {
        _isRollingReturnOpen.value = false
    }

    fun setRollingStartMode(mode: RollingStartMode) {
        _rollingStartMode.value = mode
    }

    fun setRollingCustomStartDate(epochDay: Long) {
        _rollingCustomStartEpochDay.value = epochDay
        _rollingStartMode.value = RollingStartMode.CUSTOM_DATE
    }

    fun setRollingWindow(window: RollingWindow) {
        _rollingWindow.value = window
    }

    fun setSipStartMode(mode: SipStartMode) {
        _sipStartMode.value = mode
    }

    fun setSipCustomStartDate(epochDay: Long) {
        _sipCustomStartEpochDay.value = epochDay
        _sipStartMode.value = SipStartMode.CUSTOM_DATE
    }

    fun updateSipFundAmount(schemeCode: String, amount: Double) {
        _sipFundConfigs.update { current ->
            val existing = current[schemeCode] ?: SipFundConfig(schemeCode = schemeCode)
            current + (schemeCode to existing.copy(amount = amount))
        }
    }

    fun updateSipFundTenure(schemeCode: String, tenure: SipTenure) {
        _sipFundConfigs.update { current ->
            val existing = current[schemeCode] ?: SipFundConfig(schemeCode = schemeCode)
            current + (schemeCode to existing.copy(tenure = tenure))
        }
    }

    fun toggleTheme() {
        _isDarkTheme.update { !it }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        if (catalogFlow.value.none { !it.isIndex } && query.trim().length >= 3) {
            viewModelScope.launch {
                repository.searchRemoteAndCache(query)
            }
        }
    }

    fun triggerCatalogSync(force: Boolean = true) {
        repository.triggerForegroundCatalogRefresh(
            force = force,
            activeSelectedCodes = _selectedSchemeCodes.value
        )
    }

    fun triggerSchemeMasterSync() {
        viewModelScope.launch {
            repository.syncSchemeMaster(force = true)
        }
    }

    fun triggerNavRefreshSync() {
        viewModelScope.launch {
            repository.syncLatestNavs(force = true, schemeCodesHint = _selectedSchemeCodes.value)
            if (_selectedSchemeCodes.value.isNotEmpty()) {
                val loaded = repository.ensureHistoriesForSchemes(_selectedSchemeCodes.value, forceRefresh = false)
                _rawHistories.update { existing -> existing + loaded }
            }
            if (_selectedTab.value == AppTab.RANKINGS) {
                refreshRankings(_rankingDays.value)
            }
        }
    }

    fun startUniverseHistorySync() {
        repository.startUniverseHistorySync()
    }

    fun pauseUniverseHistorySync() {
        repository.pauseUniverseHistorySync()
    }

    fun addSchemeToCompare(schemeCode: String) {
        if (_selectedSchemeCodes.value.contains(schemeCode)) return
        val updated = _selectedSchemeCodes.value + schemeCode
        _selectedSchemeCodes.value = updated
        loadHistoriesFor(updated)
    }

    fun removeSchemeFromCompare(schemeCode: String) {
        _selectedSchemeCodes.update { list -> list.filterNot { it == schemeCode } }
        _hiddenSchemeCodes.update { it - schemeCode }
    }

    fun clearAllComparedSchemes() {
        _selectedSchemeCodes.value = emptyList()
        _hiddenSchemeCodes.value = emptySet()
    }

    fun toggleSchemeVisibility(schemeCode: String) {
        _hiddenSchemeCodes.update { current ->
            if (current.contains(schemeCode)) current - schemeCode else current + schemeCode
        }
    }

    fun compareSchemesFromExternal(schemeCodes: List<String>) {
        val distinct = schemeCodes.distinct()
        if (distinct.isEmpty()) return
        _selectedSchemeCodes.value = distinct
        _hiddenSchemeCodes.value = emptySet()
        _selectedTab.value = AppTab.COMPARE
        loadHistoriesFor(distinct)
    }

    fun selectPeriod(period: ComparePeriod) {
        _selectedPeriod.value = period
    }

    fun selectYoungestInception() {
        _selectedPeriod.value = ComparePeriod.YOUNGEST_INCEPTION
    }

    fun setCustomDateRange(startEpochDay: Long?, endEpochDay: Long?) {
        _customStartEpochDay.value = startEpochDay
        _customEndEpochDay.value = endEpochDay
        _selectedPeriod.value = ComparePeriod.CUSTOM
    }

    fun setCustomEndDateKeepingMode(endEpochDay: Long?) {
        _customEndEpochDay.value = endEpochDay
        if (_selectedPeriod.value != ComparePeriod.YOUNGEST_INCEPTION) {
            _selectedPeriod.value = ComparePeriod.CUSTOM
        }
    }

    fun selectRankingDays(days: Int) {
        if (days <= 0) return
        _rankingDays.value = days
        refreshRankings(days, _selectedRankingCategories.value)
    }

    fun setRankingCategories(categoryIds: Set<String>) {
        _selectedRankingCategories.value = categoryIds
        refreshRankings(_rankingDays.value, categoryIds)
    }

    fun clearRankingCategories() {
        if (_selectedRankingCategories.value.isEmpty()) return
        _selectedRankingCategories.value = emptySet()
        refreshRankings(_rankingDays.value, emptySet())
    }

    fun refreshRankings(
        days: Int = _rankingDays.value,
        categoryIds: Set<String> = _selectedRankingCategories.value
    ) {
        rankingsJob?.cancel()
        rankingsJob = viewModelScope.launch {
            _isRankingsLoading.value = true
            try {
                val result = repository.computeRankingsForDays(
                    days = days,
                    limit = 20,
                    selectedCategoryIds = categoryIds
                )
                _rankingsResult.value = result
            } finally {
                _isRankingsLoading.value = false
            }
        }
    }

    fun openSipRankings() {
        _isSipAnalysisOpen.value = false
        _isRollingReturnOpen.value = false
        _isRollingReturnRankingsOpen.value = false
        _isSipRankingsOpen.value = true
        refreshSipRankings()
        if (syncStatus.value.eligibleCatalogCount > 0 &&
            syncStatus.value.historiesSyncedCount == 0 &&
            !syncStatus.value.isUniverseHistorySyncing
        ) {
            repository.startUniverseHistorySync()
        }
    }

    fun closeSipRankings() {
        _isSipRankingsOpen.value = false
    }

    fun setSipRankingTenure(tenure: SipTenure) {
        _sipRankingTenure.value = tenure
        refreshSipRankings(tenure = tenure)
    }

    fun setSipRankingAmount(amount: Double) {
        _sipRankingAmount.value = amount
        refreshSipRankings(amount = amount)
    }

    fun setSipRankingStartDate(epochDay: Long) {
        _sipRankingStartEpochDay.value = epochDay
        refreshSipRankings(startEpochDay = epochDay)
    }

    fun setSipRankingMetric(metric: SipRankMetric) {
        _sipRankingMetric.value = metric
        refreshSipRankings(metric = metric)
    }

    fun setSipRankingCategories(categoryIds: Set<String>) {
        _sipRankingCategories.value = categoryIds
        refreshSipRankings(categories = categoryIds)
    }

    fun refreshSipRankings(
        startEpochDay: Long = _sipRankingStartEpochDay.value,
        tenure: SipTenure = _sipRankingTenure.value,
        amount: Double = _sipRankingAmount.value,
        metric: SipRankMetric = _sipRankingMetric.value,
        categories: Set<String> = _sipRankingCategories.value
    ) {
        sipRankingsJob?.cancel()
        sipRankingsJob = viewModelScope.launch {
            _isSipRankingsLoading.value = true
            try {
                val res = repository.computeSipRankings(
                    startEpochDay = startEpochDay,
                    tenure = tenure,
                    sipAmount = amount,
                    rankMetric = metric,
                    limit = 20,
                    selectedCategoryIds = categories
                )
                _sipRankingsResult.value = res
            } finally {
                _isSipRankingsLoading.value = false
            }
        }
    }

    fun openRollingReturnRankings() {
        _isSipAnalysisOpen.value = false
        _isRollingReturnOpen.value = false
        _isSipRankingsOpen.value = false
        _isRollingReturnRankingsOpen.value = true
        refreshRollingReturnRankings()
        if (syncStatus.value.eligibleCatalogCount > 0 &&
            syncStatus.value.historiesSyncedCount == 0 &&
            !syncStatus.value.isUniverseHistorySyncing
        ) {
            repository.startUniverseHistorySync()
        }
    }

    fun closeRollingReturnRankings() {
        _isRollingReturnRankingsOpen.value = false
    }

    fun setRollingRankingWindow(window: RollingWindow) {
        _rollingRankingWindow.value = window
        refreshRollingReturnRankings(window = window)
    }

    fun setRollingRankingStartDate(epochDay: Long) {
        _rollingRankingStartEpochDay.value = epochDay
        refreshRollingReturnRankings(startEpochDay = epochDay)
    }

    fun setRollingRankingMetric(metric: RollingRankMetric) {
        _rollingRankingMetric.value = metric
        refreshRollingReturnRankings(metric = metric)
    }

    fun setRollingRankingCategories(categoryIds: Set<String>) {
        _rollingRankingCategories.value = categoryIds
        refreshRollingReturnRankings(categories = categoryIds)
    }

    fun refreshRollingReturnRankings(
        startEpochDay: Long = _rollingRankingStartEpochDay.value,
        window: RollingWindow = _rollingRankingWindow.value,
        metric: RollingRankMetric = _rollingRankingMetric.value,
        categories: Set<String> = _rollingRankingCategories.value
    ) {
        rollingRankingsJob?.cancel()
        rollingRankingsJob = viewModelScope.launch {
            _isRollingRankingsLoading.value = true
            try {
                val res = repository.computeRollingReturnRankings(
                    startEpochDay = startEpochDay,
                    window = window,
                    rankMetric = metric,
                    limit = 20,
                    selectedCategoryIds = categories
                )
                _rollingRankingsResult.value = res
            } finally {
                _isRollingRankingsLoading.value = false
            }
        }
    }

    fun toggleFavourite(schemeCode: String) {
        viewModelScope.launch {
            repository.toggleFavourite(schemeCode)
            if (_selectedTab.value == AppTab.RANKINGS) {
                refreshRankings(_rankingDays.value)
            }
            if (_isSipRankingsOpen.value) {
                refreshSipRankings()
            }
            if (_isRollingReturnRankingsOpen.value) {
                refreshRollingReturnRankings()
            }
        }
    }

    private fun loadHistoriesFor(codes: List<String>, forceRefresh: Boolean = false) {
        if (codes.isEmpty()) return
        viewModelScope.launch {
            activeHistoryLoads.incrementAndGet()
            _isComparisonLoading.value = true
            try {
                val loaded = repository.ensureHistoriesForSchemes(codes, forceRefresh = forceRefresh)
                _rawHistories.update { existing -> existing + loaded }
            } finally {
                if (activeHistoryLoads.decrementAndGet() <= 0) {
                    activeHistoryLoads.set(0)
                    _isComparisonLoading.value = false
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        rankingsJob?.cancel()
        sipRankingsJob?.cancel()
        rollingRankingsJob?.cancel()
        blendRankingsJob?.cancel()
        // Do NOT cancel repository sync onCleared: background WorkManager + ForegroundService keeps
        // catalog & universe sync running reliably when user presses Back or leaves the app.
    }

    fun openBlendRankings() {
        _isSipAnalysisOpen.value = false
        _isRollingReturnOpen.value = false
        _isSipRankingsOpen.value = false
        _isRollingReturnRankingsOpen.value = false
        _isBlendRankingsOpen.value = true
        refreshBlendRankings()
        if (syncStatus.value.eligibleCatalogCount > 0 &&
            syncStatus.value.historiesSyncedCount == 0 &&
            !syncStatus.value.isUniverseHistorySyncing
        ) {
            repository.startUniverseHistorySync()
        }
    }

    fun closeBlendRankings() {
        _isBlendRankingsOpen.value = false
    }

    fun setBlendTimeframe(tf: BlendTimeframe) {
        _blendRankingsTimeframe.value = tf
        refreshBlendRankings()
    }

    fun setBlendCustomStartDate(epochDay: Long) {
        _blendCustomStartEpochDay.value = epochDay
        _blendRankingsTimeframe.value = BlendTimeframe.CUSTOM
        refreshBlendRankings()
    }

    fun setBlendRollingWindow(window: RollingWindow) {
        _blendRollingWindow.value = window
        // Auto-adjust consistency hurdle: 8% for Yearly, 0% for sub-yearly
        _blendConsistencyHurdle.value = if (window == RollingWindow.YEARLY) 8.0 else 0.0
        refreshBlendRankings()
    }

    fun setBlendSipTenure(tenure: SipTenure) {
        _blendSipTenure.value = tenure
        refreshBlendRankings()
    }

    fun setBlendSipAmount(amount: Double) {
        _blendSipAmount.value = amount
        refreshBlendRankings()
    }

    fun setBlendSipWeight(weight: Float) {
        _blendSipWeight.value = weight
        refreshBlendRankings()
    }

    fun setBlendPeerGroupAll(all: Boolean) {
        _blendPeerGroupAll.value = all
        refreshBlendRankings()
    }

    fun setBlendCategories(categories: Set<String>) {
        _blendCategories.value = categories
        refreshBlendRankings()
    }

    fun setBlendMinFundAgeDays(days: Int) {
        _blendMinFundAgeDays.value = days
        refreshBlendRankings()
    }

    fun setBlendMinSipXirr(xirr: Double) {
        _blendMinSipXirr.value = xirr
        refreshBlendRankings()
    }

    fun setBlendMinRollingAvg(avg: Double) {
        _blendMinRollingAvg.value = avg
        refreshBlendRankings()
    }

    fun setBlendMinRollingMedian(median: Double) {
        _blendMinRollingMedian.value = median
        refreshBlendRankings()
    }

    fun setBlendConsistencyHurdle(hurdle: Double) {
        _blendConsistencyHurdle.value = hurdle
        refreshBlendRankings()
    }

    fun setBlendMinConsistency(minC: Double) {
        _blendMinConsistency.value = minC
        refreshBlendRankings()
    }

    fun setBlendSortBy(sort: BlendSortMetric) {
        _blendSortBy.value = sort
        refreshBlendRankings()
    }

    fun refreshBlendRankings() {
        blendRankingsJob?.cancel()
        blendRankingsJob = viewModelScope.launch {
            _isBlendRankingsLoading.value = true
            try {
                val startDay = _blendRankingsTimeframe.value.resolveStartEpochDay(customEpochDay = _blendCustomStartEpochDay.value)
                val res = repository.computeBlendRankings(
                    timeframeStartEpochDay = startDay,
                    rollingWindow = _blendRollingWindow.value,
                    sipTenure = _blendSipTenure.value,
                    sipAmount = _blendSipAmount.value,
                    sipWeight = _blendSipWeight.value.toDouble(),
                    peerGroupAll = _blendPeerGroupAll.value,
                    selectedCategoryIds = _blendCategories.value,
                    minFundAgeDays = _blendMinFundAgeDays.value,
                    minSipXirr = _blendMinSipXirr.value,
                    minRollingAvg = _blendMinRollingAvg.value,
                    minRollingMedian = _blendMinRollingMedian.value,
                    consistencyHurdle = _blendConsistencyHurdle.value,
                    minConsistency = _blendMinConsistency.value,
                    sortBy = _blendSortBy.value,
                    limit = 20
                )
                _blendRankingsResult.value = res
            } finally {
                _isBlendRankingsLoading.value = false
            }
        }
    }
}
