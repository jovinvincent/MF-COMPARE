package com.example.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class SchemeMasterSyncOutcome(
    val totalActiveEligible: Int,
    val newSchemesDiscovered: Int,
    val metadataUpdatedCount: Int,
    val markedInactiveCount: Int,
    val newSchemeHistoriesSynced: Int
)

data class NavSyncOutcome(
    val newNavRecordsInserted: Int,
    val updatedSchemesCount: Int,
    val latestDataEpochDay: Long?,
    val databaseSizeBytes: Long,
    val isHolidayOrWeekendUpToDate: Boolean
)

class FundCompareRepository(context: Context) {

    private val appContext = context.applicationContext
    private val db = MfCompareDatabase.getDatabase(appContext)
    private val dao = db.fundDao()
    private val httpCachePrefs = appContext.getSharedPreferences("tigzig_http_cache", Context.MODE_PRIVATE)

    // Injectable remote fetchers (default to live RemoteDataSources; overridden in Robolectric tests)
    internal var amfiFeedFetcher: suspend (Long) -> AmfiParseResult = { now ->
        RemoteDataSources.fetchAmfiMasterAndLatestNavsFast(now)
    }
    internal var mfApiCatalogFetcher: suspend (Long) -> List<FundMasterEntity> = { now ->
        RemoteDataSources.fetchMfApiMasterCatalog(now)
    }
    internal var mfApiHistoryPointsFetcher: suspend (String) -> FastHistoryFetchOutcome = { code ->
        RemoteDataSources.fetchMfApiSchemePointsFast(code)
    }
    internal var mfApiHistoryRecordsFetcher: suspend (String, Long) -> MfApiSchemeHistoryResult = { code, now ->
        RemoteDataSources.fetchMfApiSchemeHistory(code, now)
    }
    internal var mfApiLatestNavFetcher: suspend (String, Long) -> MfApiSchemeHistoryResult = { code, now ->
        RemoteDataSources.fetchMfApiLatestNavOnly(code, now)
    }
    internal var tigzigSifCsvFetcher: suspend (Long, String?, String?) -> TigzigSifHistoryParseResult = { now, etag, lastMod ->
        RemoteDataSources.fetchTigzigSifHistoryCsv(now, etag, lastMod)
    }
    internal var tigzigLatestFetcher: suspend (Long, String?, String?) -> TigzigLatestSnapshotResult = { now, etag, lastMod ->
        RemoteDataSources.fetchTigzigLatestSnapshot(now, etag, lastMod)
    }
    internal var tigzigBatchLatestFetcher: suspend (List<FundMasterEntity>, Long) -> List<DailyLatestNavUpdate> = { funds, now ->
        RemoteDataSources.fetchTigzigBatchLatestNavs(funds, now)
    }
    internal var indexHistoryFetcher: suspend (MarketIndexDefinition, String, Long) -> List<NavHistoryEntity> = { def, range, now ->
        RemoteDataSources.fetchYahooIndexHistory(def, range, now)
    }

    companion object {
        @Volatile
        private var INSTANCE: FundCompareRepository? = null

        fun getInstance(context: Context): FundCompareRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FundCompareRepository(context.applicationContext).also { INSTANCE = it }
            }
        }

        private const val PREF_SIF_CSV_ETAG = "sif_csv_etag"
        private const val PREF_SIF_CSV_LAST_MOD = "sif_csv_last_modified"
        private const val PREF_LATEST_ETAG = "latest_etag"
        private const val PREF_LATEST_LAST_MOD = "latest_last_modified"

        // Process-wide sync coordination state so ViewModel recreation, Back navigation,
        // app backgrounding, and WorkManager foreground workers share a single live pipeline.
        private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val schemeMasterMutex = Mutex()
        private val navSyncMutex = Mutex()
        private val universeSyncMutex = Mutex()
        private val _syncProgress = MutableStateFlow(SyncProgressInternal())
        private val _measuredDbBytes = MutableStateFlow(0L)
        private val universePauseRequested = AtomicBoolean(false)
        @Volatile
        private var catalogSyncJob: Deferred<Result<Int>>? = null
        @Volatile
        private var universeSyncJob: Job? = null
    }

    init {
        if (_measuredDbBytes.value == 0L) {
            _measuredDbBytes.value = MfCompareDatabase.measureStorageBytes(appContext)
        }
    }

    val catalogFlow: Flow<List<FundMasterEntity>> = dao.observeAllFunds().flowOn(Dispatchers.IO)

    val favouriteEntriesFlow: Flow<List<FavouriteFundEntity>> = dao.observeFavouriteEntries()

    val favouritesUiFlow: Flow<List<FavouriteFundUiItem>> = combine(
        dao.observeFavouriteFunds(),
        dao.observeFavouriteEntries()
    ) { funds, entries ->
        val addedMap = entries.associate { it.schemeCode to it.addedAt }
        funds.map { fund ->
            FavouriteFundUiItem(
                schemeCode = fund.schemeCode,
                schemeName = fund.schemeName,
                amcName = fund.amcName,
                category = fund.category,
                isIndex = fund.isIndex,
                latestNav = fund.latestNav,
                latestNavEpochDay = fund.latestNavDateEpochDay,
                return1M = fund.return1M,
                return1Y = fund.return1Y,
                addedAt = addedMap[fund.schemeCode] ?: 0L
            )
        }
    }

    val syncStatusFlow: Flow<SyncStatusState> = combine(
        dao.observeMutualFundCount(),
        dao.observeInactiveMutualFundCount(),
        dao.observeSyncedHistoryCount(),
        dao.observeIndexCount(),
        dao.observeSyncMetadata(),
        _syncProgress,
        _measuredDbBytes
    ) { values ->
        val mfCount = values[0] as Int
        val inactiveCount = values[1] as Int
        val syncedCount = values[2] as Int
        val idxCount = values[3] as Int
        val meta = values[4] as SyncMetadataEntity?
        val prog = values[5] as SyncProgressInternal
        val dbBytes = values[6] as Long

        val message = when {
            prog.isSchemeMasterSyncing && mfCount == 0 -> "Discovering Direct Growth schemes & indices..."
            prog.isSchemeMasterSyncing -> "Updating scheme catalog ($mfCount active)..."
            prog.isNavSyncing -> "Updating latest NAVs ($mfCount funds)..."
            prog.isUniverseSyncing -> "Syncing history ($syncedCount / $mfCount funds)..."
            mfCount == 0 && idxCount == 0 -> prog.errorMessage ?: "Tap Sync to load live funds"
            else -> {
                val dateTxt = if (meta?.lastDataEpochDay != null) {
                    "Latest: ${DateFormatUtils.formatShort(meta.lastDataEpochDay)}"
                } else "No NAVs yet"
                val sizeTxt = DateFormatUtils.formatBytes(dbBytes)
                "$mfCount funds • $sizeTxt • $dateTxt"
            }
        }

        SyncStatusState(
            isSchemeMasterSyncing = prog.isSchemeMasterSyncing,
            isNavSyncing = prog.isNavSyncing,
            isUniverseHistorySyncing = prog.isUniverseSyncing,
            eligibleCatalogCount = mfCount,
            inactiveSchemeCount = inactiveCount,
            historiesSyncedCount = syncedCount,
            indexCount = idxCount,
            lastSchemeMasterSyncMillis = meta?.lastSchemeMasterSyncMillis ?: 0L,
            lastNavSyncMillis = meta?.lastNavSyncMillis ?: 0L,
            lastDataEpochDay = meta?.lastDataEpochDay,
            newSchemesDiscovered = meta?.lastNewSchemesDiscovered ?: 0,
            newNavRecordsInserted = meta?.lastNewNavRecordsInserted ?: 0,
            databaseSizeBytes = dbBytes,
            statusMessage = message,
            errorMessage = if (mfCount == 0 && idxCount == 0) (prog.errorMessage ?: meta?.lastError) else meta?.lastError
        )
    }

    fun refreshMeasuredDbSize(): Long {
        val bytes = db.checkpointAndMeasureBytes(appContext, vacuumIfLarge = false)
        _measuredDbBytes.value = bytes
        return bytes
    }

    fun isUniversePauseRequested(): Boolean = universePauseRequested.get()

    fun isAnySyncRunning(): Boolean {
        val s = _syncProgress.value
        return s.isSchemeMasterSyncing || s.isNavSyncing || s.isUniverseSyncing
    }

    /**
     * Triggers top-right catalog + latest NAV sync immediately in app-scoped [repoScope] AND
     * attaches WorkManager's `dataSync` foreground service so leaving the screen or app never cancels it.
     */
    fun triggerForegroundCatalogRefresh(
        force: Boolean = true,
        activeSelectedCodes: List<String> = emptyList(),
        onFinished: (suspend () -> Unit)? = null
    ) {
        if (catalogSyncJob?.isActive == true) return
        val deferred = CompletableDeferred<Result<Int>>()
        catalogSyncJob = deferred
        UniverseSyncWorker.enqueueCatalogSync(appContext, force = force)
        repoScope.launch {
            try {
                val res = refreshCatalogAndIndices(force = force, activeSelectedCodes = activeSelectedCodes)
                deferred.complete(res)
                onFinished?.invoke()
            } catch (e: CancellationException) {
                deferred.cancel(e)
                throw e
            } catch (e: Exception) {
                deferred.complete(Result.failure(e))
            } finally {
                if (catalogSyncJob === deferred) {
                    catalogSyncJob = null
                }
            }
        }
    }

    suspend fun awaitOrRunCatalogRefresh(force: Boolean): Result<Int> {
        val active = catalogSyncJob
        if (active != null && active.isActive) {
            return active.await()
        }
        return refreshCatalogAndIndices(force = force)
    }

    /**
     * Unified, single-pass startup / foreground refresh:
     * - Races AMFI mirrors in parallel, fetches MFAPI catalog, Tigzig latest MF+SIF snapshot,
     *   Yahoo indices, and conditional SIF CSV (only on first seed or new SIF NFOs) in ONE scope.
     * - Throttles automatic checks to 24h (Scheme Master) and 30m (Latest NAVs) unless [force] = true.
     */
    suspend fun refreshCatalogAndIndices(
        force: Boolean = false,
        activeSelectedCodes: List<String> = emptyList()
    ): Result<Int> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val meta = dao.getSyncMetadata()
        val existingFunds = dao.getAllFundsIncludingInactive()
        val hasMfCatalog = existingFunds.any { !it.isIndex }
        val hasSifCatalog = existingFunds.any { !it.isIndex && FundCategoryFilter.isSifFund(it) }
        val syncedSifCount = existingFunds.count { !it.isIndex && FundCategoryFilter.isSifFund(it) && it.historySyncedAt > 0L }
        val unsyncedSifCount = existingFunds.count { it.isActive && !it.isIndex && FundCategoryFilter.isSifFund(it) && it.historySyncedAt == 0L }

        val twentyFourHoursMs = 24 * 60 * 60 * 1000L
        val thirtyMinutesMs = 30 * 60 * 1000L

        val needMaster = force || !hasMfCatalog || meta == null ||
            (now - meta.lastSchemeMasterSyncMillis) >= twentyFourHoursMs
        val needNav = force || !hasMfCatalog || meta == null || meta.lastDataEpochDay == null ||
            (now - meta.lastNavSyncMillis) >= thirtyMinutesMs
        val needInitialSifCsv = (needMaster || needNav) && (syncedSifCount == 0 || unsyncedSifCount > 0)

        if (!needMaster && !needNav) {
            return@withContext Result.success(existingFunds.count { it.isActive })
        }

        schemeMasterMutex.withLock {
            navSyncMutex.withLock {
                _syncProgress.update {
                    it.copy(
                        isSchemeMasterSyncing = needMaster,
                        isNavSyncing = true,
                        errorMessage = null
                    )
                }

                var amfiResult: AmfiParseResult? = null
                var mfApiCatalog: List<FundMasterEntity>? = null
                var tigzigLatestResult: TigzigLatestSnapshotResult? = null
                var sifCsvResult: TigzigSifHistoryParseResult? = null
                var amfiErr: String? = null
                var mfApiErr: String? = null
                var tigzigLatestErr: String? = null
                var sifCsvErr: String? = null
                var newNavRecordsInserted = 0
                var indexFeedSucceeded = false

                try {
                    ensureIndexCatalogEntriesExist(now)
                    val freshExistingByCode = dao.getAllFundsIncludingInactive().associateBy { it.schemeCode }

                    // Execute AMFI, MFAPI, Tigzig latest (MF+SIF), conditional SIF CSV, and Yahoo indices in parallel!
                    coroutineScope {
                        val amfiJob = async {
                            try {
                                amfiResult = amfiFeedFetcher(now)
                            } catch (e: Exception) {
                                amfiErr = e.message
                            }
                        }
                        val tigzigLatestJob = async {
                            try {
                                tigzigLatestResult = fetchTigzigLatestWithCache(
                                    now = now,
                                    bypassCache = force || !hasMfCatalog || !hasSifCatalog || meta?.lastDataEpochDay == null
                                )
                            } catch (e: Exception) {
                                tigzigLatestErr = e.message
                            }
                        }
                        val mfApiJob = if (needMaster) {
                            async {
                                try {
                                    mfApiCatalog = mfApiCatalogFetcher(now)
                                } catch (e: Exception) {
                                    mfApiErr = e.message
                                }
                            }
                        } else null
                        val sifCsvJob = if (needInitialSifCsv) {
                            async {
                                try {
                                    sifCsvResult = fetchTigzigSifCsvWithCache(
                                        now = now,
                                        bypassCache = (syncedSifCount == 0 || unsyncedSifCount > 0)
                                    )
                                } catch (e: Exception) {
                                    sifCsvErr = e.message
                                }
                            }
                        } else null

                        val indexJobs = if (needNav) {
                            RemoteDataSources.SUPPORTED_INDICES.map { indexDef ->
                                async {
                                    if (syncSingleIndexNav(indexDef, freshExistingByCode[indexDef.schemeCode], now)) {
                                        indexFeedSucceeded = true
                                        newNavRecordsInserted++
                                    }
                                }
                            }
                        } else emptyList()

                        amfiJob.await()
                        tigzigLatestJob.await()
                        mfApiJob?.await()
                        sifCsvJob?.await()
                        indexJobs.awaitAll()
                    }

                    // 1. Reconcile Scheme Master cleanly (MF + SIF + NFOs)
                    var masterOutcome: SchemeMasterSyncOutcome? = null
                    if (needMaster) {
                        val incomingFunds = mergeIncomingCatalogFunds(
                            mfApiCatalog = mfApiCatalog,
                            tigzigLatestFunds = tigzigLatestResult?.allFunds,
                            amfiFunds = amfiResult?.eligibleFunds,
                            sifCsvResult = sifCsvResult
                        )
                        val authoritativeActiveFeed = (amfiResult?.eligibleFunds?.size ?: 0) >= 500

                        if (incomingFunds.isEmpty() && !hasMfCatalog && !hasSifCatalog) {
                            val errText = listOfNotNull(mfApiErr, amfiErr, tigzigLatestErr, sifCsvErr)
                                .distinct()
                                .joinToString(" | ")
                                .ifBlank { "Unable to reach MFAPI/AMFI/Tigzig catalog" }
                            val msg = "Sync failed: $errText"
                            dao.upsertSyncMetadata((meta ?: SyncMetadataEntity()).copy(lastError = msg))
                            _syncProgress.update {
                                it.copy(isSchemeMasterSyncing = false, isNavSyncing = false, errorMessage = msg)
                            }
                            return@withLock Result.failure(IllegalStateException(msg))
                        }

                        if (incomingFunds.isNotEmpty()) {
                            masterOutcome = reconcileSchemeCatalog(
                                incomingFunds = incomingFunds,
                                authoritativeActiveFeed = authoritativeActiveFeed,
                                nowMillis = now,
                                fetchNewSchemeHistories = hasMfCatalog
                            )
                        }
                    } else {
                        // Even when master isn't due, automatically discover & reconcile any new MF/SIF NFOs in latest feeds
                        val latestDiscovered = mergeIncomingCatalogFunds(
                            mfApiCatalog = null,
                            tigzigLatestFunds = tigzigLatestResult?.allFunds,
                            amfiFunds = amfiResult?.eligibleFunds,
                            sifCsvResult = sifCsvResult
                        )
                        if (latestDiscovered.isNotEmpty()) {
                            masterOutcome = discoverAndReconcileLatestNfos(latestDiscovered, now)
                        }
                    }

                    // 2. Hydrate SIF histories ONLY when unseeded or newly discovered SIF NFOs lack history
                    syncPendingSifHistoriesIfNeeded(
                        forceCsv = false,
                        now = now,
                        preFetchedCsv = sifCsvResult
                    )

                    // 3. Apply incremental latest NAV delta (AMFI + Tigzig latest, keeping newest date per scheme)
                    var mfFeedSucceeded = false
                    val mergedLatestNavs = mergeLatestNavUpdates(
                        amfiNavs = amfiResult?.latestNavRecords.orEmpty(),
                        tigzigNavs = tigzigLatestResult?.latestNavRecords.orEmpty()
                    )
                    if (mergedLatestNavs.isNotEmpty()) {
                        newNavRecordsInserted += dao.applyDailyLatestNavBatch(mergedLatestNavs, now)
                        mfFeedSucceeded = true
                    } else if (tigzigLatestResult?.notModified == true && (hasMfCatalog || hasSifCatalog)) {
                        mfFeedSucceeded = true
                    }

                    // If AMFI failed (e.g. blocked from overseas / 502) and Tigzig's cached snapshot is behind
                    // the latest IST trading day (or empty/304 while DB is behind), query live /mf/v1/nav batches!
                    val expectedNavDay = expectedLatestNavEpochDayIst(now)
                    val mergedMaxDay = mergedLatestNavs.maxOfOrNull { it.epochDay }
                    val storedMaxDayBeforeFallback = dao.getGlobalMaxNavEpochDay()?.toLong()
                    val needsLiveBatchFallback = needNav && (
                        !mfFeedSucceeded ||
                            (amfiResult == null && (
                                (mergedMaxDay != null && mergedMaxDay < expectedNavDay) ||
                                    (mergedMaxDay == null && (storedMaxDayBeforeFallback == null || storedMaxDayBeforeFallback < expectedNavDay))
                                ))
                        )
                    if (needsLiveBatchFallback) {
                        val fallbackCount = fetchPriorityLatestNavFallback(activeSelectedCodes, now)
                        if (fallbackCount >= 0) {
                            newNavRecordsInserted += fallbackCount
                            mfFeedSucceeded = true
                        }
                    } else if (needNav) {
                        // AMFI succeeded (MF up-to-date), check live /mf/v1/nav for SIFs if Tigzig snapshot was stale
                        val sifBatchCount = fetchPrioritySifLiveUpdatesIfBehind(activeSelectedCodes, expectedNavDay, now)
                        if (sifBatchCount > 0) {
                            newNavRecordsInserted += sifBatchCount
                        }
                    }

                    val latestMeta = dao.getSyncMetadata() ?: SyncMetadataEntity()
                    val globalMaxDay = dao.getGlobalMaxNavEpochDay()?.toLong() ?: latestMeta.lastDataEpochDay
                    val total = dao.getActiveTotalCount()
                    val hasAnyActive = total > 0

                    val errorToStore = if (!mfFeedSucceeded && !indexFeedSucceeded && mfApiCatalog.isNullOrEmpty() && !hasAnyActive) {
                        amfiErr ?: tigzigLatestErr ?: mfApiErr ?: sifCsvErr ?: "Sync failed: Check internet connection"
                    } else {
                        null
                    }

                    dao.upsertSyncMetadata(
                        latestMeta.copy(
                            lastSchemeMasterSyncMillis = if (needMaster && (amfiResult != null || !mfApiCatalog.isNullOrEmpty() || tigzigLatestResult != null || sifCsvResult != null)) now else latestMeta.lastSchemeMasterSyncMillis,
                            lastNavSyncMillis = if (needNav) now else latestMeta.lastNavSyncMillis,
                            lastDataEpochDay = globalMaxDay,
                            lastNewSchemesDiscovered = masterOutcome?.newSchemesDiscovered ?: latestMeta.lastNewSchemesDiscovered,
                            lastNewNavRecordsInserted = if (newNavRecordsInserted > 0) newNavRecordsInserted else latestMeta.lastNewNavRecordsInserted,
                            lastError = errorToStore
                        )
                    )
                    refreshMeasuredDbSize()
                    _syncProgress.update {
                        it.copy(isSchemeMasterSyncing = false, isNavSyncing = false, errorMessage = errorToStore)
                    }

                    if (hasAnyActive || errorToStore == null) {
                        Result.success(total)
                    } else {
                        Result.failure(IllegalStateException(errorToStore))
                    }
                } catch (e: Exception) {
                    val msg = "Sync failed: ${e.message ?: "Network error"}"
                    dao.upsertSyncMetadata((meta ?: SyncMetadataEntity()).copy(lastError = msg))
                    _syncProgress.update {
                        it.copy(isSchemeMasterSyncing = false, isNavSyncing = false, errorMessage = msg)
                    }
                    Result.failure(e)
                }
            }
        }
    }

    private suspend fun fetchTigzigLatestWithCache(
        now: Long,
        bypassCache: Boolean
    ): TigzigLatestSnapshotResult {
        val etag = if (bypassCache) null else httpCachePrefs.getString(PREF_LATEST_ETAG, null)
        val lastMod = if (bypassCache) null else httpCachePrefs.getString(PREF_LATEST_LAST_MOD, null)
        val res = tigzigLatestFetcher(now, etag, lastMod)
        if (!res.notModified && (res.etag != null || res.lastModified != null)) {
            httpCachePrefs.edit()
                .putString(PREF_LATEST_ETAG, res.etag)
                .putString(PREF_LATEST_LAST_MOD, res.lastModified)
                .apply()
        }
        return res
    }

    private suspend fun fetchTigzigSifCsvWithCache(
        now: Long,
        bypassCache: Boolean
    ): TigzigSifHistoryParseResult {
        val etag = if (bypassCache) null else httpCachePrefs.getString(PREF_SIF_CSV_ETAG, null)
        val lastMod = if (bypassCache) null else httpCachePrefs.getString(PREF_SIF_CSV_LAST_MOD, null)
        val res = tigzigSifCsvFetcher(now, etag, lastMod)
        if (!res.notModified && res.schemes.isNotEmpty() && (res.etag != null || res.lastModified != null)) {
            httpCachePrefs.edit()
                .putString(PREF_SIF_CSV_ETAG, res.etag)
                .putString(PREF_SIF_CSV_LAST_MOD, res.lastModified)
                .apply()
        }
        return res
    }

    private fun mergeIncomingCatalogFunds(
        mfApiCatalog: List<FundMasterEntity>?,
        tigzigLatestFunds: List<FundMasterEntity>?,
        amfiFunds: List<FundMasterEntity>?,
        sifCsvResult: TigzigSifHistoryParseResult?
    ): List<FundMasterEntity> {
        val merged = LinkedHashMap<String, FundMasterEntity>()

        fun putMerged(incoming: FundMasterEntity) {
            val prev = merged[incoming.schemeCode]
            if (prev == null) {
                merged[incoming.schemeCode] = incoming
                return
            }
            val prevIsSif = FundCategoryFilter.isSifFund(prev)
            val incIsSif = FundCategoryFilter.isSifFund(incoming)
            val isSif = prevIsSif || incIsSif
            val preferredAmc = when {
                incoming.amcName.isNotBlank() && incoming.amcName != "Mutual Fund" &&
                    (prev.amcName.isBlank() || prev.amcName == "Mutual Fund" || incoming.amcName.length > prev.amcName.length) -> incoming.amcName
                else -> prev.amcName
            }
            val preferredCat = when {
                isSif -> {
                    val c1 = incoming.category.takeIf { it.isNotBlank() && it != "SIF - Specialized Investment Fund" }
                    val c0 = prev.category.takeIf { it.isNotBlank() && it != "SIF - Specialized Investment Fund" }
                    if (incIsSif && c1 != null) c1 else c0 ?: FundCategoryFilter.inferSifCategoryLabel(incoming.schemeName, incoming.category)
                }
                incoming.category.isNotBlank() && incoming.category != "Mutual Fund" && incoming.category != "Direct Growth Mutual Fund" -> incoming.category
                else -> prev.category
            }
            val preferredIsin = incoming.isin?.takeIf { it.isNotBlank() } ?: prev.isin
            val bestLatestDay = listOfNotNull(prev.latestNavDateEpochDay, incoming.latestNavDateEpochDay).maxOrNull()
            val bestLatestNav = when {
                incoming.latestNavDateEpochDay != null && (prev.latestNavDateEpochDay == null || incoming.latestNavDateEpochDay >= prev.latestNavDateEpochDay) ->
                    incoming.latestNav ?: prev.latestNav
                else -> prev.latestNav ?: incoming.latestNav
            }
            val bestInception = listOfNotNull(prev.inceptionEpochDay, incoming.inceptionEpochDay).minOrNull()
            merged[incoming.schemeCode] = prev.copy(
                schemeName = incoming.schemeName.ifBlank { prev.schemeName },
                searchTokens = if (isSif) {
                    DirectGrowthFilter.normalizeSearchTokens("${incoming.schemeName} $preferredAmc $preferredCat sif specialized investment")
                } else {
                    DirectGrowthFilter.normalizeSearchTokens("${incoming.schemeName} $preferredAmc")
                },
                amcName = preferredAmc,
                category = preferredCat,
                isin = preferredIsin,
                dedupKey = DirectGrowthFilter.deduplicationKey(incoming.schemeName, preferredIsin),
                inceptionEpochDay = bestInception,
                recordedInceptionEpochDay = listOfNotNull(prev.recordedInceptionEpochDay, incoming.recordedInceptionEpochDay).minOrNull(),
                latestNav = bestLatestNav,
                latestNavDateEpochDay = bestLatestDay,
                return1M = incoming.return1M ?: prev.return1M,
                return1Y = incoming.return1Y ?: prev.return1Y,
                dataSource = if (isSif) RemoteDataSources.DATA_SOURCE_SIF else incoming.dataSource.ifBlank { prev.dataSource }
            )
        }

        mfApiCatalog?.forEach(::putMerged)
        amfiFunds?.forEach(::putMerged)
        tigzigLatestFunds?.forEach(::putMerged)
        sifCsvResult?.schemes?.forEach { putMerged(it.fund) }

        return merged.values.toList()
    }

    private fun mergeLatestNavUpdates(
        amfiNavs: List<NavHistoryEntity>,
        tigzigNavs: List<NavHistoryEntity>
    ): List<DailyLatestNavUpdate> {
        if (amfiNavs.isEmpty() && tigzigNavs.isEmpty()) return emptyList()
        val byCode = LinkedHashMap<String, DailyLatestNavUpdate>(amfiNavs.size + tigzigNavs.size)
        for (rec in amfiNavs) {
            val prev = byCode[rec.schemeCode]
            if (prev == null || rec.dateEpochDay > prev.epochDay) {
                byCode[rec.schemeCode] = DailyLatestNavUpdate(rec.schemeCode, rec.dateEpochDay, rec.navValue)
            }
        }
        for (rec in tigzigNavs) {
            val prev = byCode[rec.schemeCode]
            if (prev == null || rec.dateEpochDay >= prev.epochDay) {
                byCode[rec.schemeCode] = DailyLatestNavUpdate(rec.schemeCode, rec.dateEpochDay, rec.navValue)
            }
        }
        return byCode.values.toList()
    }

    private suspend fun discoverAndReconcileLatestNfos(
        incomingFunds: List<FundMasterEntity>,
        now: Long
    ): SchemeMasterSyncOutcome? {
        if (incomingFunds.isEmpty()) return null
        val existingAll = dao.getAllFundsIncludingInactive()
        val existingByCode = existingAll.associateBy { it.schemeCode }
        val existingActiveDedup = existingAll.filter { !it.isIndex && it.isActive }.map { it.dedupKey }.toHashSet()
        val existingActiveIsin = existingAll.filter { !it.isIndex && it.isActive }.mapNotNull { it.isin }.toHashSet()

        val candidates = incomingFunds.filter { inc ->
            if (inc.isIndex) return@filter false
            val existing = existingByCode[inc.schemeCode]
            when {
                existing == null -> {
                    val dupByIsin = inc.isin != null && existingActiveIsin.contains(inc.isin)
                    val dupByDedup = existingActiveDedup.contains(inc.dedupKey)
                    !dupByIsin && !dupByDedup
                }
                !existing.isActive -> true
                FundCategoryFilter.isSifFund(inc) && existing.dataSource != RemoteDataSources.DATA_SOURCE_SIF -> true
                else -> false
            }
        }
        if (candidates.isEmpty()) return null
        return reconcileSchemeCatalog(
            incomingFunds = candidates,
            authoritativeActiveFeed = false,
            nowMillis = now,
            fetchNewSchemeHistories = true
        )
    }

    private suspend fun syncPendingSifHistoriesIfNeeded(
        forceCsv: Boolean,
        now: Long,
        preFetchedCsv: TigzigSifHistoryParseResult? = null
    ): Int {
        val syncedSifCount = dao.getSyncedCountByDataSource(RemoteDataSources.DATA_SOURCE_SIF)
        val allFunds = dao.getAllFundsIncludingInactive()
        val unsyncedSifFunds = allFunds.filter {
            it.isActive && !it.isIndex && FundCategoryFilter.isSifFund(it) && it.historySyncedAt == 0L
        }
        if (!forceCsv && syncedSifCount > 0 && unsyncedSifFunds.isEmpty() && preFetchedCsv == null) {
            return 0
        }

        val csvResult = preFetchedCsv ?: try {
            fetchTigzigSifCsvWithCache(
                now = now,
                bypassCache = forceCsv || syncedSifCount == 0 || unsyncedSifFunds.isNotEmpty()
            )
        } catch (_: Exception) {
            null
        }

        var hydratedCount = if (csvResult != null) {
            persistTigzigSifHistoriesBatch(
                csvResult = csvResult,
                onlyPendingOrNewer = !forceCsv && syncedSifCount > 0,
                now = now
            )
        } else 0

        // Handle brand-new Day-1 SIF NFO present in format=latest before historical sif_nav.csv lists it
        val remainingUnsyncedSifs = dao.getAllFundsIncludingInactive().filter {
            it.isActive && !it.isIndex && FundCategoryFilter.isSifFund(it) && it.historySyncedAt == 0L &&
                it.latestNavDateEpochDay != null && it.latestNav != null && it.latestNav > 0.0
        }
        if (remainingUnsyncedSifs.isNotEmpty()) {
            val dayOnePrepared = remainingUnsyncedSifs.mapNotNull { fund ->
                val day = fund.latestNavDateEpochDay ?: return@mapNotNull null
                val nav = fund.latestNav ?: return@mapNotNull null
                prepareSchemeHistory(
                    fund = fund.copy(dataSource = RemoteDataSources.DATA_SOURCE_SIF, isActive = true),
                    outcome = FastHistoryFetchOutcome.Success(
                        amcName = fund.amcName,
                        category = FundCategoryFilter.inferSifCategoryLabel(fund.schemeName, fund.category),
                        isinGrowth = fund.isin,
                        pointsAscending = listOf(NavPoint(day, nav))
                    )
                )
            }
            if (dayOnePrepared.isNotEmpty()) {
                dayOnePrepared.chunked(48).forEach { dao.saveUniverseHistoryBatch(it, now) }
                hydratedCount += dayOnePrepared.size
            }
        }
        return hydratedCount
    }

    private suspend fun persistTigzigSifHistoriesBatch(
        csvResult: TigzigSifHistoryParseResult,
        onlyPendingOrNewer: Boolean,
        now: Long,
        filterCodes: Set<String>? = null
    ): Int {
        if (csvResult.notModified || csvResult.schemes.isEmpty()) return 0

        val existingAll = dao.getAllFundsIncludingInactive()
        val existingByCode = existingAll.associateBy { it.schemeCode }
        val existingByIsin = existingAll.filter { !it.isIndex && !it.isin.isNullOrBlank() }.associateBy { it.isin!! }
        val existingByDedup = existingAll.filter { !it.isIndex }.associateBy { it.dedupKey }

        val usedIds = existingAll.map { it.schemeId }.toHashSet()
        var nextFallbackId = maxOf((existingAll.maxOfOrNull { it.schemeId } ?: 100000), 200000) + 1
        fun allocateUniqueSchemeId(code: String): Int {
            val preferred = FundMasterEntity.deriveSchemeId(code)
            if (usedIds.add(preferred)) return preferred
            while (usedIds.contains(nextFallbackId)) nextFallbackId++
            return nextFallbackId++.also { usedIds.add(it) }
        }

        val preparedBatch = ArrayList<PreparedSchemeHistory>(csvResult.schemes.size)
        for (item in csvResult.schemes) {
            if (item.pointsAscending.isEmpty()) continue
            val incomingFund = item.fund
            val existing = existingByCode[incomingFund.schemeCode]
                ?: incomingFund.isin?.let { existingByIsin[it] }
                ?: existingByDedup[incomingFund.dedupKey]

            if (filterCodes != null && !filterCodes.contains(incomingFund.schemeCode) &&
                (existing == null || !filterCodes.contains(existing.schemeCode))
            ) {
                continue
            }

            val csvMaxDay = item.pointsAscending.last().dateEpochDay
            if (onlyPendingOrNewer && existing != null && existing.historySyncedAt > 0L) {
                val existingLatestDay = existing.latestNavDateEpochDay
                if (existingLatestDay != null && existingLatestDay >= csvMaxDay) {
                    continue
                }
            }

            val targetCode = existing?.schemeCode ?: incomingFund.schemeCode
            val targetId = existing?.schemeId ?: allocateUniqueSchemeId(targetCode)
            val resolvedCat = FundCategoryFilter.inferSifCategoryLabel(
                incomingFund.schemeName,
                incomingFund.category.takeIf { it != "SIF - Specialized Investment Fund" } ?: existing?.category
            )
            val resolvedAmc = incomingFund.amcName.takeIf { it.isNotBlank() && it != "Mutual Fund" }
                ?: existing?.amcName?.takeIf { it.isNotBlank() }
                ?: "Specialized Investment Fund"
            val resolvedIsin = incomingFund.isin ?: existing?.isin

            // Preserve any newer latest NAV already captured from format=latest
            val mergedPoints = if (existing?.latestNavDateEpochDay != null &&
                existing.latestNav != null &&
                existing.latestNav > 0.0 &&
                existing.latestNavDateEpochDay > csvMaxDay
            ) {
                item.pointsAscending + NavPoint(existing.latestNavDateEpochDay, existing.latestNav)
            } else {
                item.pointsAscending
            }

            val baseFund = (existing ?: incomingFund).copy(
                schemeCode = targetCode,
                schemeId = targetId,
                schemeName = incomingFund.schemeName.ifBlank { existing?.schemeName ?: targetCode },
                searchTokens = DirectGrowthFilter.normalizeSearchTokens(
                    "${incomingFund.schemeName} $resolvedAmc $resolvedCat sif specialized investment"
                ),
                amcName = resolvedAmc,
                category = resolvedCat,
                isin = resolvedIsin,
                dedupKey = DirectGrowthFilter.deduplicationKey(incomingFund.schemeName, resolvedIsin),
                isActive = true,
                statusNote = null,
                dataSource = RemoteDataSources.DATA_SOURCE_SIF,
                lastSyncedAt = now
            )

            val prepared = prepareSchemeHistory(
                fund = baseFund,
                outcome = FastHistoryFetchOutcome.Success(
                    amcName = resolvedAmc,
                    category = resolvedCat,
                    isinGrowth = resolvedIsin,
                    pointsAscending = mergedPoints
                )
            )
            if (prepared != null) {
                preparedBatch.add(prepared)
            }
        }

        if (preparedBatch.isNotEmpty()) {
            preparedBatch.chunked(48).forEach { chunk ->
                dao.saveUniverseHistoryBatch(chunk, now)
            }
        }
        return preparedBatch.size
    }

    private suspend fun syncSingleIndexNav(
        indexDef: MarketIndexDefinition,
        existingIdx: FundMasterEntity?,
        now: Long
    ): Boolean {
        return try {
            val hasHistory = (existingIdx?.historySyncedAt ?: 0L) > 0L
            val range = if (hasHistory) "1mo" else "10y"
            val fetched = indexHistoryFetcher(indexDef, range, now)
            if (fetched.isEmpty()) return false
            val maxExistingDay = existingIdx?.latestNavDateEpochDay
            val missingRecords = if (hasHistory && maxExistingDay != null) {
                fetched.filter { it.dateEpochDay > maxExistingDay }
            } else {
                fetched
            }
            if (missingRecords.isNotEmpty() || !hasHistory) {
                if (missingRecords.isNotEmpty()) {
                    dao.upsertNavRecordsIncremental(
                        schemeId = existingIdx?.schemeId ?: FundMasterEntity.deriveSchemeId(indexDef.schemeCode),
                        records = missingRecords.map { NavPoint(it.dateEpochDay, it.navValue) }
                    )
                }
                val allPoints = dao.getNavPointsForSchemes(listOf(indexDef.schemeCode))[indexDef.schemeCode].orEmpty()
                if (allPoints.isNotEmpty()) {
                    val latest = allPoints.last()
                    val r1M = PerformanceCalculator.computeTrailingReturn(allPoints, 30)?.returnPercent
                    val r1Y = PerformanceCalculator.computeTrailingReturn(allPoints, 365)?.returnPercent
                    dao.updateFundMetrics(
                        schemeCode = indexDef.schemeCode,
                        inceptionEpochDay = allPoints.first().dateEpochDay,
                        recordedInceptionEpochDay = allPoints.first().dateEpochDay,
                        latestNav = latest.navValue,
                        latestNavDateEpochDay = latest.dateEpochDay,
                        return1M = r1M,
                        return1Y = r1Y,
                        amcName = "Market Index",
                        category = indexDef.category,
                        isin = null,
                        isActive = true,
                        statusNote = null,
                        historySyncedAt = now
                    )
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun expectedLatestNavEpochDayIst(nowMillis: Long): Long {
        val istZone = ZoneId.of("Asia/Kolkata")
        val zdt = java.time.Instant.ofEpochMilli(nowMillis).atZone(istZone)
        val today = zdt.toLocalDate()
        val afterPublishWindow = zdt.hour >= 21
        return when (today.dayOfWeek) {
            DayOfWeek.SATURDAY -> today.minusDays(1).toEpochDay()
            DayOfWeek.SUNDAY -> today.minusDays(2).toEpochDay()
            DayOfWeek.MONDAY -> if (afterPublishWindow) today.toEpochDay() else today.minusDays(3).toEpochDay()
            else -> if (afterPublishWindow) today.toEpochDay() else today.minusDays(1).toEpochDay()
        }
    }

    private suspend fun fetchPrioritySifLiveUpdatesIfBehind(
        schemeCodesHint: List<String>,
        expectedDay: Long,
        now: Long
    ): Int {
        return try {
            val allSifs = dao.getAllFunds()
                .filter { !it.isIndex && FundCategoryFilter.isSifFund(it) && (it.latestNavDateEpochDay == null || it.latestNavDateEpochDay < expectedDay) }
            if (allSifs.isEmpty()) return 0
            val favSet = dao.getFavouriteCodes().toSet()
            val hintSet = schemeCodesHint.toSet()
            val prioritized = allSifs.sortedWith(
                compareByDescending<FundMasterEntity> { hintSet.contains(it.schemeCode) }
                    .thenByDescending { favSet.contains(it.schemeCode) }
                    .thenByDescending { it.historySyncedAt > 0L }
            ).take(150)
            val updates = tigzigBatchLatestFetcher(prioritized, now)
            if (updates.isNotEmpty()) dao.applyDailyLatestNavBatch(updates, now) else 0
        } catch (_: Exception) {
            0
        }
    }

    private suspend fun fetchPriorityLatestNavFallback(
        schemeCodesHint: List<String>,
        now: Long
    ): Int {
        return try {
            val expectedDay = expectedLatestNavEpochDayIst(now)
            val allActive = dao.getAllFunds().filter { !it.isIndex && it.isActive }
            if (allActive.isEmpty()) return 0
            val byCode = allActive.associateBy { it.schemeCode }
            val favCodes = dao.getFavouriteCodes()

            val orderedFunds = LinkedHashMap<String, FundMasterEntity>(512)
            for (code in schemeCodesHint) {
                byCode[code]?.let { orderedFunds[code] = it }
            }
            for (code in favCodes) {
                byCode[code]?.let { orderedFunds[code] = it }
            }
            for (f in allActive) {
                if (FundCategoryFilter.isSifFund(f)) {
                    orderedFunds.putIfAbsent(f.schemeCode, f)
                }
            }
            allActive
                .filter { it.historySyncedAt > 0L }
                .sortedWith(
                    compareBy<FundMasterEntity> { (it.latestNavDateEpochDay ?: 0L) >= expectedDay }
                        .thenByDescending { it.latestNavDateEpochDay ?: 0L }
                )
                .forEach { f ->
                    if (orderedFunds.size < 500) {
                        orderedFunds.putIfAbsent(f.schemeCode, f)
                    }
                }
            if (orderedFunds.size < 100) {
                for (f in allActive) {
                    if (orderedFunds.size >= 100) break
                    orderedFunds.putIfAbsent(f.schemeCode, f)
                }
            }

            val targetFunds = orderedFunds.values.toList()
            if (targetFunds.isEmpty()) return 0

            var totalApplied = 0
            var tigzigSucceeded = false
            val freshCodes = HashSet<String>()

            try {
                val batchUpdates = tigzigBatchLatestFetcher(targetFunds, now)
                tigzigSucceeded = true
                if (batchUpdates.isNotEmpty()) {
                    totalApplied += dao.applyDailyLatestNavBatch(batchUpdates, now)
                    batchUpdates.filter { it.epochDay >= expectedDay }.forEach {
                        freshCodes.add(it.schemeCode)
                    }
                }
            } catch (_: Exception) {
            }

            // Secondary fallback via MFAPI /latest if Tigzig live endpoint was unreachable
            var mfApiSucceeded = false
            if (!tigzigSucceeded) {
                val mfPriorityFunds = (schemeCodesHint + favCodes)
                    .distinct()
                    .mapNotNull { byCode[it] }
                    .filter { !FundCategoryFilter.isSifFund(it) && !freshCodes.contains(it.schemeCode) }
                    .take(24)
                if (mfPriorityFunds.isNotEmpty()) {
                    val sem = Semaphore(12)
                    val deltaUpdates = coroutineScope {
                        mfPriorityFunds.map { fund ->
                            async {
                                sem.withPermit {
                                    try {
                                        val res = mfApiLatestNavFetcher(fund.schemeCode, now)
                                        mfApiSucceeded = true
                                        res.records.lastOrNull()?.let { latest ->
                                            DailyLatestNavUpdate(fund.schemeCode, latest.dateEpochDay, latest.navValue)
                                        }
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                            }
                        }.awaitAll().filterNotNull()
                    }
                    if (deltaUpdates.isNotEmpty()) {
                        totalApplied += dao.applyDailyLatestNavBatch(deltaUpdates, now)
                    }
                }
            }

            if (!tigzigSucceeded && !mfApiSucceeded) -1 else totalApplied
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * PROCESS A: Independent Daily Scheme Master Synchronization.
     * Integrates MFAPI, AMFI, Tigzig Latest (MF+SIF+NFO), and conditional SIF CSV seeding.
     */
    suspend fun syncSchemeMaster(force: Boolean = false): Result<SchemeMasterSyncOutcome> = withContext(Dispatchers.IO) {
        schemeMasterMutex.withLock {
            val meta = dao.getSyncMetadata()
            val now = System.currentTimeMillis()
            val twentyFourHoursMs = 24 * 60 * 60 * 1000L
            val existingFunds = dao.getAllFundsIncludingInactive()
            val hasSifCatalog = existingFunds.any { !it.isIndex && FundCategoryFilter.isSifFund(it) }
            val syncedSifCount = existingFunds.count { !it.isIndex && FundCategoryFilter.isSifFund(it) && it.historySyncedAt > 0L }
            val unsyncedSifCount = existingFunds.count { it.isActive && !it.isIndex && FundCategoryFilter.isSifFund(it) && it.historySyncedAt == 0L }

            if (!force && meta != null && (now - meta.lastSchemeMasterSyncMillis) < twentyFourHoursMs &&
                existingFunds.any { !it.isIndex } && hasSifCatalog && unsyncedSifCount == 0
            ) {
                return@withLock Result.success(
                    SchemeMasterSyncOutcome(
                        totalActiveEligible = existingFunds.count { !it.isIndex && it.isActive },
                        newSchemesDiscovered = 0,
                        metadataUpdatedCount = 0,
                        markedInactiveCount = 0,
                        newSchemeHistoriesSynced = 0
                    )
                )
            }

            _syncProgress.update { it.copy(isSchemeMasterSyncing = true, errorMessage = null) }

            var amfiResult: AmfiParseResult? = null
            var mfApiCatalog: List<FundMasterEntity>? = null
            var tigzigLatestResult: TigzigLatestSnapshotResult? = null
            var sifCsvResult: TigzigSifHistoryParseResult? = null
            var amfiErr: String? = null
            var mfApiErr: String? = null
            var tigzigErr: String? = null
            var sifCsvErr: String? = null

            val needInitialSifCsv = syncedSifCount == 0 || unsyncedSifCount > 0

            try {
                coroutineScope {
                    val mfApiJob = async {
                        try {
                            mfApiCatalog = mfApiCatalogFetcher(now)
                        } catch (e: Exception) {
                            mfApiErr = e.message
                        }
                    }
                    val amfiJob = async {
                        try {
                            amfiResult = amfiFeedFetcher(now)
                        } catch (e: Exception) {
                            amfiErr = e.message
                        }
                    }
                    val tigzigLatestJob = async {
                        try {
                            tigzigLatestResult = fetchTigzigLatestWithCache(
                                now = now,
                                bypassCache = force || !hasSifCatalog || existingFunds.none { !it.isIndex }
                            )
                        } catch (e: Exception) {
                            tigzigErr = e.message
                        }
                    }
                    val sifCsvJob = if (needInitialSifCsv) {
                        async {
                            try {
                                sifCsvResult = fetchTigzigSifCsvWithCache(
                                    now = now,
                                    bypassCache = (syncedSifCount == 0 || unsyncedSifCount > 0)
                                )
                            } catch (e: Exception) {
                                sifCsvErr = e.message
                            }
                        }
                    } else null

                    mfApiJob.await()
                    amfiJob.await()
                    tigzigLatestJob.await()
                    sifCsvJob?.await()
                }

                val incomingFunds = mergeIncomingCatalogFunds(
                    mfApiCatalog = mfApiCatalog,
                    tigzigLatestFunds = tigzigLatestResult?.allFunds,
                    amfiFunds = amfiResult?.eligibleFunds,
                    sifCsvResult = sifCsvResult
                )
                val authoritativeActiveFeed = (amfiResult?.eligibleFunds?.size ?: 0) >= 500

                if (incomingFunds.isEmpty() && existingFunds.none { !it.isIndex }) {
                    val errText = listOfNotNull(mfApiErr, amfiErr, tigzigErr, sifCsvErr).distinct().joinToString(" | ")
                        .ifBlank { "Unable to reach MFAPI/AMFI/Tigzig catalog" }
                    val msg = "Scheme sync failed: $errText"
                    dao.upsertSyncMetadata((meta ?: SyncMetadataEntity()).copy(lastError = msg))
                    _syncProgress.update { it.copy(isSchemeMasterSyncing = false, errorMessage = msg) }
                    return@withLock Result.failure(IllegalStateException(msg))
                }

                ensureIndexCatalogEntriesExist(now)

                val isInitialSeed = existingFunds.none { !it.isIndex }
                val outcome = reconcileSchemeCatalog(
                    incomingFunds = incomingFunds,
                    authoritativeActiveFeed = authoritativeActiveFeed,
                    nowMillis = now,
                    fetchNewSchemeHistories = !isInitialSeed
                )

                val sifHydrated = syncPendingSifHistoriesIfNeeded(
                    forceCsv = false,
                    now = now,
                    preFetchedCsv = sifCsvResult
                )

                val mergedNavs = mergeLatestNavUpdates(
                    amfiNavs = amfiResult?.latestNavRecords.orEmpty(),
                    tigzigNavs = tigzigLatestResult?.latestNavRecords.orEmpty()
                )
                var dailyInserted = 0
                if (mergedNavs.isNotEmpty()) {
                    dailyInserted = dao.applyDailyLatestNavBatch(mergedNavs, now)
                }

                val latestMeta = dao.getSyncMetadata() ?: SyncMetadataEntity()
                val globalMaxDay = dao.getGlobalMaxNavEpochDay()?.toLong() ?: latestMeta.lastDataEpochDay
                dao.upsertSyncMetadata(
                    latestMeta.copy(
                        lastSchemeMasterSyncMillis = now,
                        lastDataEpochDay = globalMaxDay,
                        lastNewSchemesDiscovered = outcome.newSchemesDiscovered,
                        lastNewNavRecordsInserted = if (dailyInserted > 0) dailyInserted else latestMeta.lastNewNavRecordsInserted,
                        lastError = null
                    )
                )
                refreshMeasuredDbSize()
                _syncProgress.update { it.copy(isSchemeMasterSyncing = false, errorMessage = null) }
                Result.success(
                    outcome.copy(
                        totalActiveEligible = dao.getActiveMutualFundCount(),
                        newSchemeHistoriesSynced = outcome.newSchemeHistoriesSynced + sifHydrated
                    )
                )
            } catch (e: Exception) {
                val msg = "Scheme sync failed: ${e.message ?: "Network error"}"
                dao.upsertSyncMetadata((meta ?: SyncMetadataEntity()).copy(lastError = msg))
                _syncProgress.update { it.copy(isSchemeMasterSyncing = false, errorMessage = msg) }
                Result.failure(e)
            }
        }
    }

    /**
     * Reconciles [incomingFunds] against local `fund_master` incrementally.
     */
    suspend fun reconcileSchemeCatalog(
        incomingFunds: List<FundMasterEntity>,
        authoritativeActiveFeed: Boolean,
        nowMillis: Long,
        fetchNewSchemeHistories: Boolean = true,
        closureMinCatalogSize: Int = 500,
        newSchemeHistoryFetcher: suspend (FundMasterEntity) -> List<NavHistoryEntity> = { fund ->
            mfApiHistoryRecordsFetcher(fund.schemeCode, nowMillis).records
        }
    ): SchemeMasterSyncOutcome {
        val existingAll = dao.getAllFundsIncludingInactive()
        val existingByCode = existingAll.associateBy { it.schemeCode }
        val existingActiveDedupKeys = existingAll
            .filter { !it.isIndex && it.isActive }
            .associate { it.dedupKey to it.schemeCode }
            .toMutableMap()

        var nextFallbackId = maxOf(
            (existingAll.maxOfOrNull { it.schemeId } ?: 100000),
            200000
        ) + 1
        val usedIds = existingAll.map { it.schemeId }.toHashSet()

        fun allocateUniqueSchemeId(code: String): Int {
            val preferred = FundMasterEntity.deriveSchemeId(code)
            if (!usedIds.contains(preferred)) {
                usedIds.add(preferred)
                return preferred
            }
            while (usedIds.contains(nextFallbackId)) {
                nextFallbackId++
            }
            val allocated = nextFallbackId++
            usedIds.add(allocated)
            return allocated
        }

        val toUpsert = ArrayList<FundMasterEntity>()
        val newlyDiscovered = ArrayList<FundMasterEntity>()
        var metadataUpdatedCount = 0
        var markedInactiveCount = 0

        val incomingByCode = LinkedHashMap<String, FundMasterEntity>(incomingFunds.size)
        for (inc in incomingFunds) {
            if (!inc.isIndex) incomingByCode[inc.schemeCode] = inc
        }

        val incomingActiveDedupKeys = HashSet<String>()
        val incomingActiveIsins = HashSet<String>()

        for (inc in incomingFunds) {
            if (inc.isIndex) continue
            val eligible = DirectGrowthFilter.isEligibleDirectGrowthScheme(inc.schemeName, null, inc.category)
            val existing = existingByCode[inc.schemeCode]

            if (!eligible) {
                if (existing != null && existing.isActive) {
                    toUpsert.add(
                        existing.copy(
                            isActive = false,
                            statusNote = "Eligibility Changed (Non-Direct Growth)",
                            lastSyncedAt = nowMillis
                        )
                    )
                    markedInactiveCount++
                }
                continue
            }

            incomingActiveDedupKeys.add(inc.dedupKey)
            inc.isin?.let { incomingActiveIsins.add(it) }

            if (existing == null) {
                val activeDupCode = existingActiveDedupKeys[inc.dedupKey]
                if (activeDupCode != null && activeDupCode != inc.schemeCode) {
                    continue
                }
                val newEntity = inc.copy(
                    schemeId = allocateUniqueSchemeId(inc.schemeCode),
                    isActive = true,
                    statusNote = null,
                    lastSyncedAt = nowMillis
                )
                toUpsert.add(newEntity)
                newlyDiscovered.add(newEntity)
                existingActiveDedupKeys[newEntity.dedupKey] = newEntity.schemeCode
            } else {
                val incIsSif = FundCategoryFilter.isSifFund(inc)
                val nameChanged = existing.schemeName != inc.schemeName
                val betterAmc = inc.amcName.takeIf { it.isNotBlank() && it != "Mutual Fund" }
                val amcChanged = betterAmc != null && betterAmc != existing.amcName &&
                    (existing.amcName.isBlank() || existing.amcName == "Mutual Fund" || existing.amcName.length < betterAmc.length)
                val betterCat = inc.category.takeIf {
                    it.isNotBlank() &&
                        it != "Mutual Fund" &&
                        it != "Direct Growth Mutual Fund" &&
                        (it != "SIF - Specialized Investment Fund" || existing.category.isBlank())
                }
                val catChanged = betterCat != null && betterCat != existing.category
                val isinChanged = inc.isin != null && inc.isin != existing.isin
                val sourceChanged = incIsSif && existing.dataSource != RemoteDataSources.DATA_SOURCE_SIF
                val reactivated = !existing.isActive

                if (nameChanged || amcChanged || catChanged || isinChanged || sourceChanged || reactivated) {
                    val newCat = betterCat ?: existing.category
                    val newSearchTokens = if (nameChanged || amcChanged || sourceChanged) {
                        if (incIsSif || FundCategoryFilter.isSifFund(existing)) {
                            DirectGrowthFilter.normalizeSearchTokens("${inc.schemeName} ${betterAmc ?: existing.amcName} $newCat sif specialized investment")
                        } else {
                            DirectGrowthFilter.normalizeSearchTokens("${inc.schemeName} ${betterAmc ?: existing.amcName}")
                        }
                    } else existing.searchTokens
                    val newDedup = if (nameChanged || isinChanged) {
                        DirectGrowthFilter.deduplicationKey(inc.schemeName, inc.isin ?: existing.isin)
                    } else existing.dedupKey

                    toUpsert.add(
                        existing.copy(
                            schemeName = inc.schemeName,
                            searchTokens = newSearchTokens,
                            amcName = betterAmc ?: existing.amcName,
                            category = newCat,
                            isin = inc.isin ?: existing.isin,
                            dedupKey = newDedup,
                            isActive = true,
                            statusNote = null,
                            dataSource = if (incIsSif) RemoteDataSources.DATA_SOURCE_SIF else existing.dataSource,
                            lastSyncedAt = nowMillis
                        )
                    )
                    metadataUpdatedCount++
                }
            }
        }

        if (authoritativeActiveFeed && incomingByCode.size >= closureMinCatalogSize) {
            for (existing in existingAll) {
                if (existing.isIndex || !existing.isActive) continue
                // AMFI NAVAll.txt does not list SIF schemes; never close SIFs during an AMFI closure sweep.
                if (FundCategoryFilter.isSifFund(existing)) continue
                if (!incomingByCode.containsKey(existing.schemeCode)) {
                    val merged = (existing.isin != null && incomingActiveIsins.contains(existing.isin)) ||
                        incomingActiveDedupKeys.contains(existing.dedupKey)
                    toUpsert.add(
                        existing.copy(
                            isActive = false,
                            statusNote = if (merged) "Merged / Consolidated" else "Closed / Discontinued",
                            lastSyncedAt = nowMillis
                        )
                    )
                    markedInactiveCount++
                }
            }
        }

        if (toUpsert.isNotEmpty()) {
            toUpsert.chunked(400).forEach { dao.upsertFunds(it) }
        }

        var newHistoriesSynced = 0
        // Only fetch per-scheme MFAPI history for non-SIF MFs; SIF NFOs are hydrated in one bulk CSV pass.
        val mfNewlyDiscovered = newlyDiscovered.filter { !FundCategoryFilter.isSifFund(it) }
        if (fetchNewSchemeHistories && mfNewlyDiscovered.isNotEmpty()) {
            val semaphore = Semaphore(12)
            coroutineScope {
                mfNewlyDiscovered.take(16).map { newFund ->
                    async {
                        semaphore.withPermit {
                            try {
                                val records = newSchemeHistoryFetcher(newFund)
                                if (records.isNotEmpty()) {
                                    val points = records.map { NavPoint(it.dateEpochDay, it.navValue) }.sortedBy { it.dateEpochDay }
                                    val latest = points.last()
                                    val inception = points.first().dateEpochDay
                                    val r1M = PerformanceCalculator.computeTrailingReturn(points, 30)?.returnPercent
                                    val r1Y = PerformanceCalculator.computeTrailingReturn(points, 365)?.returnPercent
                                    dao.saveSchemeHistoryAndMetrics(
                                        schemeCode = newFund.schemeCode,
                                        records = records,
                                        inceptionEpochDay = inception,
                                        latestNav = latest.navValue,
                                        latestNavDateEpochDay = latest.dateEpochDay,
                                        return1M = r1M,
                                        return1Y = r1Y,
                                        historySyncedAt = nowMillis
                                    )
                                    newHistoriesSynced++
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        val activeCount = dao.getActiveMutualFundCount()
        return SchemeMasterSyncOutcome(
            totalActiveEligible = activeCount,
            newSchemesDiscovered = newlyDiscovered.size,
            metadataUpdatedCount = metadataUpdatedCount,
            markedInactiveCount = markedInactiveCount,
            newSchemeHistoriesSynced = newHistoriesSynced
        )
    }

    /**
     * PROCESS B: Independent Incremental Latest NAV & NFO Synchronization.
     * Fetches AMFI + Tigzig Latest (MF & SIF) + Market Indices in parallel, discovers any newly
     * launched MF/SIF NFOs, hydrates new SIF NFO history if needed, and appends only latest NAV deltas.
     */
    suspend fun syncLatestNavs(
        force: Boolean = false,
        schemeCodesHint: List<String> = emptyList()
    ): Result<NavSyncOutcome> = withContext(Dispatchers.IO) {
        navSyncMutex.withLock {
            val meta = dao.getSyncMetadata()
            val now = System.currentTimeMillis()
            val thirtyMinutesMs = 30 * 60 * 1000L
            if (!force && meta != null && (now - meta.lastNavSyncMillis) < thirtyMinutesMs && meta.lastDataEpochDay != null) {
                return@withLock Result.success(
                    NavSyncOutcome(
                        newNavRecordsInserted = 0,
                        updatedSchemesCount = 0,
                        latestDataEpochDay = meta.lastDataEpochDay,
                        databaseSizeBytes = refreshMeasuredDbSize(),
                        isHolidayOrWeekendUpToDate = true
                    )
                )
            }

            _syncProgress.update { it.copy(isNavSyncing = true, errorMessage = null) }

            var newNavRecordsInserted = 0
            var newNfosDiscovered = 0
            var mfFeedSucceeded = false
            var indexFeedSucceeded = false
            var lastFeedError: String? = null

            try {
                ensureIndexCatalogEntriesExist(now)
                val existingIdxMap = dao.getFundsByCodes(RemoteDataSources.SUPPORTED_INDICES.map { it.schemeCode })
                    .associateBy { it.schemeCode }

                var amfiResult: AmfiParseResult? = null
                var tigzigLatestResult: TigzigLatestSnapshotResult? = null
                var amfiErr: String? = null
                var tigzigErr: String? = null

                coroutineScope {
                    val amfiJob = async {
                        try {
                            amfiResult = amfiFeedFetcher(now)
                        } catch (e: Exception) {
                            amfiErr = e.message
                        }
                    }
                    val tigzigJob = async {
                        try {
                            tigzigLatestResult = fetchTigzigLatestWithCache(
                                now = now,
                                bypassCache = force || meta?.lastDataEpochDay == null
                            )
                        } catch (e: Exception) {
                            tigzigErr = e.message
                        }
                    }

                    val indexJobs = RemoteDataSources.SUPPORTED_INDICES.map { indexDef ->
                        async {
                            if (syncSingleIndexNav(indexDef, existingIdxMap[indexDef.schemeCode], now)) {
                                indexFeedSucceeded = true
                            }
                        }
                    }

                    amfiJob.await()
                    tigzigJob.await()
                    indexJobs.awaitAll()
                }

                lastFeedError = listOfNotNull(amfiErr, tigzigErr).distinct().joinToString(" | ").ifBlank { null }

                // Seamlessly register any newly launched MF or SIF NFOs appearing in latest feeds
                val discoveredFunds = mergeIncomingCatalogFunds(
                    mfApiCatalog = null,
                    tigzigLatestFunds = tigzigLatestResult?.allFunds,
                    amfiFunds = amfiResult?.eligibleFunds,
                    sifCsvResult = null
                )
                if (discoveredFunds.isNotEmpty()) {
                    val nfoOutcome = discoverAndReconcileLatestNfos(discoveredFunds, now)
                    newNfosDiscovered = nfoOutcome?.newSchemesDiscovered ?: 0
                }

                // Hydrate SIF historical CSV ONLY if unseeded or a brand-new SIF NFO was just discovered
                syncPendingSifHistoriesIfNeeded(forceCsv = false, now = now)

                val mergedUpdates = mergeLatestNavUpdates(
                    amfiNavs = amfiResult?.latestNavRecords.orEmpty(),
                    tigzigNavs = tigzigLatestResult?.latestNavRecords.orEmpty()
                )
                if (mergedUpdates.isNotEmpty()) {
                    newNavRecordsInserted += dao.applyDailyLatestNavBatch(mergedUpdates, now)
                    mfFeedSucceeded = true
                } else if (tigzigLatestResult?.notModified == true && dao.getActiveMutualFundCount() > 0) {
                    mfFeedSucceeded = true
                }

                val expectedNavDay = expectedLatestNavEpochDayIst(now)
                val mergedMaxDay = mergedUpdates.maxOfOrNull { it.epochDay }
                val storedMaxDayBeforeFallback = dao.getGlobalMaxNavEpochDay()?.toLong()
                val needsLiveBatchFallback = !mfFeedSucceeded ||
                    (amfiResult == null && (
                        (mergedMaxDay != null && mergedMaxDay < expectedNavDay) ||
                            (mergedMaxDay == null && (storedMaxDayBeforeFallback == null || storedMaxDayBeforeFallback < expectedNavDay))
                        ))
                if (needsLiveBatchFallback) {
                    val fallbackCount = fetchPriorityLatestNavFallback(schemeCodesHint, now)
                    if (fallbackCount >= 0) {
                        newNavRecordsInserted += fallbackCount
                        mfFeedSucceeded = true
                    }
                } else {
                    val sifBatchCount = fetchPrioritySifLiveUpdatesIfBehind(schemeCodesHint, expectedNavDay, now)
                    if (sifBatchCount > 0) {
                        newNavRecordsInserted += sifBatchCount
                    }
                }

                val latestMeta = dao.getSyncMetadata() ?: SyncMetadataEntity()
                val globalMaxDay = dao.getGlobalMaxNavEpochDay()?.toLong() ?: latestMeta.lastDataEpochDay
                val dbBytes = refreshMeasuredDbSize()
                val todayIst = LocalDate.now(ZoneId.of("Asia/Kolkata"))
                val isWeekendOrHoliday = (todayIst.dayOfWeek == DayOfWeek.SATURDAY || todayIst.dayOfWeek == DayOfWeek.SUNDAY || newNavRecordsInserted == 0)

                val hasAnyActiveFunds = dao.getActiveTotalCount() > 0
                val succeeded = mfFeedSucceeded || indexFeedSucceeded || (isWeekendOrHoliday && hasAnyActiveFunds)
                val errorToStore = if (!mfFeedSucceeded && !indexFeedSucceeded && !hasAnyActiveFunds) {
                    "NAV sync failed: ${lastFeedError ?: "Check internet connection"}"
                } else {
                    null
                }

                dao.upsertSyncMetadata(
                    latestMeta.copy(
                        lastNavSyncMillis = now,
                        lastDataEpochDay = globalMaxDay,
                        lastNewSchemesDiscovered = if (newNfosDiscovered > 0) newNfosDiscovered else latestMeta.lastNewSchemesDiscovered,
                        lastNewNavRecordsInserted = newNavRecordsInserted,
                        lastError = errorToStore
                    )
                )
                _syncProgress.update { it.copy(isNavSyncing = false, errorMessage = errorToStore) }

                if (succeeded || errorToStore == null) {
                    Result.success(
                        NavSyncOutcome(
                            newNavRecordsInserted = newNavRecordsInserted,
                            updatedSchemesCount = if (newNavRecordsInserted > 0) 1 else 0,
                            latestDataEpochDay = globalMaxDay,
                            databaseSizeBytes = dbBytes,
                            isHolidayOrWeekendUpToDate = isWeekendOrHoliday
                        )
                    )
                } else {
                    Result.failure(IllegalStateException(errorToStore))
                }
            } catch (e: Exception) {
                val msg = "NAV sync failed: ${e.message ?: "Network error"}"
                dao.upsertSyncMetadata((meta ?: SyncMetadataEntity()).copy(lastError = msg))
                _syncProgress.update { it.copy(isNavSyncing = false, errorMessage = msg) }
                Result.failure(e)
            }
        }
    }

    private suspend fun ensureIndexCatalogEntriesExist(nowMillis: Long) {
        val existing = dao.getFundsByCodes(RemoteDataSources.SUPPORTED_INDICES.map { it.schemeCode })
            .associateBy { it.schemeCode }
        val missingIndices = RemoteDataSources.SUPPORTED_INDICES.mapNotNull { indexDef ->
            if (existing.containsKey(indexDef.schemeCode)) {
                null
            } else {
                FundMasterEntity(
                    schemeCode = indexDef.schemeCode,
                    schemeId = FundMasterEntity.deriveSchemeId(indexDef.schemeCode),
                    schemeName = indexDef.displayName,
                    searchTokens = DirectGrowthFilter.normalizeSearchTokens("${indexDef.displayName} index benchmark ${indexDef.category}"),
                    amcName = "Market Index",
                    category = indexDef.category,
                    isin = null,
                    dedupKey = "INDEX:${indexDef.schemeCode}",
                    isIndex = true,
                    isActive = true,
                    dataSource = "YAHOO_FINANCE",
                    lastSyncedAt = nowMillis
                )
            }
        }
        if (missingIndices.isNotEmpty()) {
            dao.upsertFunds(missingIndices)
        }
    }

    suspend fun searchRemoteAndCache(query: String): List<FundMasterEntity> = withContext(Dispatchers.IO) {
        if (query.trim().length < 2) return@withContext emptyList()
        return@withContext try {
            val matches = RemoteDataSources.searchMfApiRemote(query)
            if (matches.isNotEmpty()) {
                reconcileSchemeCatalog(
                    incomingFunds = matches,
                    authoritativeActiveFeed = false,
                    nowMillis = System.currentTimeMillis(),
                    fetchNewSchemeHistories = false
                )
            }
            matches
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun ensureHistoriesForSchemes(
        schemeCodes: List<String>,
        forceRefresh: Boolean = false
    ): Map<String, List<NavPoint>> = withContext(Dispatchers.IO) {
        if (schemeCodes.isEmpty()) return@withContext emptyMap()
        val now = System.currentTimeMillis()
        val funds = dao.getFundsByCodes(schemeCodes).associateBy { it.schemeCode }
        val todayIstEpochDay = LocalDate.now(ZoneId.of("Asia/Kolkata")).toEpochDay()

        val expectedNavDay = expectedLatestNavEpochDayIst(now)

        // Pre-pass: handle any SIF schemes needing full history or latest refresh in a single shared Tigzig request
        val sifCodesNeedingFull = ArrayList<String>()
        val sifCodesNeedingLatest = ArrayList<String>()
        for (code in schemeCodes) {
            val fund = funds[code] ?: continue
            if (!fund.isIndex && FundCategoryFilter.isSifFund(fund)) {
                val existingCount = dao.getNavCount(code)
                val needsFull = existingCount < 2 || fund.historySyncedAt <= 0L
                if (needsFull) {
                    sifCodesNeedingFull.add(code)
                } else if (forceRefresh ||
                    ((fund.latestNavDateEpochDay == null || fund.latestNavDateEpochDay < expectedNavDay) &&
                        ((now - fund.lastSyncedAt) > 120_000L || (now - fund.historySyncedAt) > 3_600_000L))
                ) {
                    sifCodesNeedingLatest.add(code)
                }
            }
        }
        if (sifCodesNeedingFull.isNotEmpty()) {
            try {
                val csv = fetchTigzigSifCsvWithCache(now = now, bypassCache = true)
                persistTigzigSifHistoriesBatch(
                    csvResult = csv,
                    onlyPendingOrNewer = false,
                    now = now,
                    filterCodes = sifCodesNeedingFull.toSet()
                )
            } catch (_: Exception) {
            }
        }
        if (sifCodesNeedingLatest.isNotEmpty()) {
            val sifTargetFunds = sifCodesNeedingLatest.mapNotNull { funds[it] }
            var updatedFromLive = false
            try {
                val liveUpdates = tigzigBatchLatestFetcher(sifTargetFunds, now)
                if (liveUpdates.isNotEmpty()) {
                    dao.applyDailyLatestNavBatch(liveUpdates, now)
                    updatedFromLive = true
                }
            } catch (_: Exception) {
            }
            if (!updatedFromLive) {
                try {
                    val snap = fetchTigzigLatestWithCache(now = now, bypassCache = forceRefresh)
                    if (snap.latestNavRecords.isNotEmpty()) {
                        val targetSet = sifCodesNeedingLatest.toSet()
                        val updates = snap.latestNavRecords
                            .filter { targetSet.contains(it.schemeCode) }
                            .map { DailyLatestNavUpdate(it.schemeCode, it.dateEpochDay, it.navValue) }
                        if (updates.isNotEmpty()) {
                            dao.applyDailyLatestNavBatch(updates, now)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        val semaphore = Semaphore(8)
        coroutineScope {
            schemeCodes.map { code ->
                async {
                    val fund = funds[code] ?: return@async
                    if (!fund.isIndex && FundCategoryFilter.isSifFund(fund)) {
                        return@async
                    }
                    val existingCount = dao.getNavCount(code)
                    val needsFullHistory = existingCount < 2 || fund.historySyncedAt <= 0L
                    val needsLatestCheck = forceRefresh ||
                        (!needsFullHistory &&
                            (fund.latestNavDateEpochDay == null || fund.latestNavDateEpochDay < expectedNavDay) &&
                            ((now - fund.lastSyncedAt) > 120_000L || (now - fund.historySyncedAt) > 3_600_000L))

                    if (needsFullHistory) {
                        semaphore.withPermit {
                            syncSingleSchemeFullHistoryOnce(fund, now)
                        }
                    } else if (needsLatestCheck) {
                        semaphore.withPermit {
                            syncSingleSchemeIncrementalLatest(fund, now)
                        }
                    }
                }
            }.awaitAll()
        }

        refreshMeasuredDbSize()
        dao.getNavPointsForSchemes(schemeCodes)
    }

    private suspend fun syncSingleSchemeFullHistoryOnce(fund: FundMasterEntity, now: Long): Boolean {
        return try {
            if (fund.isIndex) {
                val def = RemoteDataSources.SUPPORTED_INDICES.find { it.schemeCode == fund.schemeCode }
                    ?: return false
                val fetchedRecords = indexHistoryFetcher(def, "10y", now)
                if (fetchedRecords.isEmpty()) return false
                val fullPoints = fetchedRecords.map { NavPoint(it.dateEpochDay, it.navValue) }.sortedBy { it.dateEpochDay }
                val latest = fullPoints.last()
                val inception = fullPoints.first().dateEpochDay
                dao.saveSchemeHistoryAndMetrics(
                    schemeCode = fund.schemeCode,
                    records = fetchedRecords,
                    inceptionEpochDay = inception,
                    recordedInceptionEpochDay = fund.recordedInceptionEpochDay ?: inception,
                    latestNav = latest.navValue,
                    latestNavDateEpochDay = latest.dateEpochDay,
                    return1M = PerformanceCalculator.computeTrailingReturn(fullPoints, 30)?.returnPercent,
                    return1Y = PerformanceCalculator.computeTrailingReturn(fullPoints, 365)?.returnPercent,
                    isActive = true,
                    statusNote = null,
                    historySyncedAt = now
                )
                return true
            }

            if (FundCategoryFilter.isSifFund(fund)) {
                val csv = fetchTigzigSifCsvWithCache(now = now, bypassCache = true)
                val count = persistTigzigSifHistoriesBatch(
                    csvResult = csv,
                    onlyPendingOrNewer = false,
                    now = now,
                    filterCodes = setOf(fund.schemeCode)
                )
                return count > 0
            }

            when (val res = mfApiHistoryPointsFetcher(fund.schemeCode)) {
                is FastHistoryFetchOutcome.Success -> {
                    val prepared = prepareSchemeHistory(fund, res) ?: return false
                    dao.saveUniverseHistoryBatch(listOf(prepared), now)
                    true
                }
                FastHistoryFetchOutcome.NoHistoryAvailable -> {
                    dao.markSchemesNoHistoryAvailable(listOf(fund.schemeCode))
                    false
                }
                is FastHistoryFetchOutcome.TransientError -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun syncSingleSchemeIncrementalLatest(fund: FundMasterEntity, now: Long): Boolean {
        return try {
            if (fund.isIndex) {
                syncSingleIndexNav(
                    RemoteDataSources.SUPPORTED_INDICES.find { it.schemeCode == fund.schemeCode } ?: return false,
                    fund,
                    now
                )
            } else {
                val liveUpdates = try {
                    tigzigBatchLatestFetcher(listOf(fund), now)
                } catch (_: Exception) {
                    emptyList()
                }
                if (liveUpdates.isNotEmpty()) {
                    val inserted = dao.applyDailyLatestNavBatch(liveUpdates, now)
                    if (inserted > 0) return true
                }
                if (FundCategoryFilter.isSifFund(fund)) {
                    val snap = fetchTigzigLatestWithCache(now = now, bypassCache = true)
                    val match = snap.latestNavRecords.firstOrNull { it.schemeCode == fund.schemeCode } ?: return false
                    dao.applyDailyLatestNavBatch(
                        listOf(DailyLatestNavUpdate(match.schemeCode, match.dateEpochDay, match.navValue)),
                        now
                    ) > 0
                } else {
                    val latestRes = mfApiLatestNavFetcher(fund.schemeCode, now)
                    val latestPt = latestRes.records.lastOrNull() ?: return false
                    dao.applyDailyLatestNavBatch(
                        listOf(DailyLatestNavUpdate(fund.schemeCode, latestPt.dateEpochDay, latestPt.navValue)),
                        now
                    ) > 0
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Starts lightning-fast, persistent Universe History Sync immediately on [repoScope] AND
     * attaches WorkManager's `dataSync` Foreground Service so navigating back, switching tabs,
     * locking screen, or leaving the app never stops downloading. Syncs ALL remaining funds.
     */
    fun startUniverseHistorySync(maxBatchCount: Int = Int.MAX_VALUE) {
        universePauseRequested.set(false)
        UniverseSyncWorker.enqueueUniverseSync(appContext, maxBatchCount)
        if (universeSyncJob?.isActive == true) return
        universeSyncJob = repoScope.launch {
            try {
                executeUniverseHistorySyncPass(maxBatchCount)
            } finally {
                universeSyncJob = null
            }
        }
    }

    /**
     * Executes high-concurrency pipelined Universe History Sync:
     * - Hydrates any unsynced SIF schemes in ONE bulk Tigzig CSV pass first
     * - Streams & decodes 20 MF schemes concurrently over pooled HTTP/2 sockets
     * - Encodes year-partitioned binary buckets on CPU threads off the DB lock
     * - Commits 48 schemes per single SQLite transaction
     * - Marks 404/empty legacy schemes as No History Available so counter reaches 100% cleanly
     * - Returns true if 100% finished or paused by user; false if transient network failures remain for auto-retry.
     */
    suspend fun executeUniverseHistorySyncPass(
        maxBatchCount: Int = Int.MAX_VALUE,
        onBatchProgress: (suspend (synced: Int, total: Int) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (universePauseRequested.get()) return@withContext true

        return@withContext universeSyncMutex.withLock {
            _syncProgress.update { it.copy(isUniverseSyncing = true, errorMessage = null) }
            val transientFailedCodes = LinkedHashSet<String>()
            var didProcessAny = false
            var cachedSifCsvForPass: TigzigSifHistoryParseResult? = null

            try {
                if (dao.getActiveMutualFundCount() == 0) {
                    syncSchemeMaster(force = false)
                    didProcessAny = true
                }

                // Pre-pass: hydrate any unsynced SIF schemes in 1 bulk CSV pass (skipped if already synced!)
                if (!universePauseRequested.get()) {
                    val sifSyncedNow = syncPendingSifHistoriesIfNeeded(forceCsv = false, now = System.currentTimeMillis())
                    if (sifSyncedNow > 0) {
                        didProcessAny = true
                    }
                }

                val semaphore = Semaphore(20)
                val batchSize = 48
                var processed = 0

                while (!universePauseRequested.get() && processed < maxBatchCount) {
                    val fetchLimit = (batchSize + transientFailedCodes.size).coerceAtMost(250)
                    val candidates = dao.getFundsNeedingHistorySync(limit = fetchLimit)
                        .filter { !transientFailedCodes.contains(it.schemeCode) }
                        .take(minOf(batchSize, maxBatchCount - processed))
                    if (candidates.isEmpty()) break

                    val batchPrepared = ArrayList<PreparedSchemeHistory>(candidates.size)
                    val batchNoHistory = ArrayList<String>()
                    val batchTransientFailed = ArrayList<String>()

                    coroutineScope {
                        val results = candidates.map { fund ->
                            async {
                                semaphore.withPermit {
                                    if (universePauseRequested.get()) {
                                        null
                                    } else if (FundCategoryFilter.isSifFund(fund)) {
                                        val csv = cachedSifCsvForPass ?: try {
                                            fetchTigzigSifCsvWithCache(System.currentTimeMillis(), bypassCache = true)
                                                .also { cachedSifCsvForPass = it }
                                        } catch (_: Exception) {
                                            null
                                        }
                                        val match = csv?.schemes?.firstOrNull {
                                            it.fund.schemeCode == fund.schemeCode ||
                                                (fund.isin != null && it.fund.isin == fund.isin) ||
                                                it.fund.dedupKey == fund.dedupKey
                                        }
                                        if (match != null && match.pointsAscending.isNotEmpty()) {
                                            val prepared = prepareSchemeHistory(
                                                fund.copy(dataSource = RemoteDataSources.DATA_SOURCE_SIF),
                                                FastHistoryFetchOutcome.Success(
                                                    amcName = match.fund.amcName,
                                                    category = match.fund.category,
                                                    isinGrowth = match.fund.isin,
                                                    pointsAscending = match.pointsAscending
                                                )
                                            )
                                            if (prepared != null) BatchSchemeResult.Ready(prepared)
                                            else BatchSchemeResult.NoHistory(fund.schemeCode)
                                        } else {
                                            BatchSchemeResult.NoHistory(fund.schemeCode)
                                        }
                                    } else {
                                        fetchAndPrepareWithRetry(fund)
                                    }
                                }
                            }
                        }.awaitAll()

                        for (res in results) {
                            when (res) {
                                is BatchSchemeResult.Ready -> batchPrepared.add(res.prepared)
                                is BatchSchemeResult.NoHistory -> batchNoHistory.add(res.schemeCode)
                                is BatchSchemeResult.TransientFailure -> batchTransientFailed.add(res.schemeCode)
                                null -> Unit
                            }
                        }
                    }

                    val now = System.currentTimeMillis()
                    if (batchPrepared.isNotEmpty()) {
                        dao.saveUniverseHistoryBatch(batchPrepared, now)
                        didProcessAny = true
                    }
                    if (batchNoHistory.isNotEmpty()) {
                        dao.markSchemesNoHistoryAvailable(batchNoHistory)
                        didProcessAny = true
                    }
                    transientFailedCodes.addAll(batchTransientFailed)

                    processed += candidates.size
                    _measuredDbBytes.value = MfCompareDatabase.measureStorageBytes(appContext)
                    onBatchProgress?.invoke(
                        dao.getActiveMutualFundCount() - dao.getUnsyncedMutualFundCount(),
                        dao.getActiveMutualFundCount()
                    )
                }

                // Automatic recovery pass for any transient network timeouts before ending
                if (!universePauseRequested.get() && transientFailedCodes.isNotEmpty() && processed < maxBatchCount) {
                    delay(1000L)
                    val retryFunds = dao.getFundsByCodes(transientFailedCodes.toList())
                        .filter { it.isActive && it.historySyncedAt == 0L }
                    if (retryFunds.isNotEmpty()) {
                        transientFailedCodes.clear()
                        for (chunk in retryFunds.chunked(32)) {
                            if (universePauseRequested.get()) break
                            val batchPrepared = ArrayList<PreparedSchemeHistory>(chunk.size)
                            val batchNoHistory = ArrayList<String>()
                            coroutineScope {
                                val results = chunk.map { fund ->
                                    async {
                                        semaphore.withPermit {
                                            if (universePauseRequested.get()) null
                                            else if (FundCategoryFilter.isSifFund(fund)) BatchSchemeResult.NoHistory(fund.schemeCode)
                                            else fetchAndPrepareWithRetry(fund)
                                        }
                                    }
                                }.awaitAll()
                                for (res in results) {
                                    when (res) {
                                        is BatchSchemeResult.Ready -> batchPrepared.add(res.prepared)
                                        is BatchSchemeResult.NoHistory -> batchNoHistory.add(res.schemeCode)
                                        is BatchSchemeResult.TransientFailure -> transientFailedCodes.add(res.schemeCode)
                                        null -> Unit
                                    }
                                }
                            }
                            val now = System.currentTimeMillis()
                            if (batchPrepared.isNotEmpty()) {
                                dao.saveUniverseHistoryBatch(batchPrepared, now)
                                didProcessAny = true
                            }
                            if (batchNoHistory.isNotEmpty()) {
                                dao.markSchemesNoHistoryAvailable(batchNoHistory)
                                didProcessAny = true
                            }
                        }
                    }
                }

                val remaining = dao.getUnsyncedMutualFundCount()
                if (didProcessAny) {
                    val doneNow = System.currentTimeMillis()
                    dao.touchSyncedCatalogTimestamp(doneNow)
                    val meta = dao.getSyncMetadata() ?: SyncMetadataEntity()
                    dao.upsertSyncMetadata(
                        meta.copy(
                            lastUniverseSyncMillis = doneNow,
                            lastDataEpochDay = dao.getGlobalMaxNavEpochDay()?.toLong() ?: meta.lastDataEpochDay,
                            lastError = if (remaining > 0 && transientFailedCodes.isNotEmpty() && !universePauseRequested.get()) {
                                "Waiting for network to auto-resume ($remaining remaining)"
                            } else null
                        )
                    )
                    refreshMeasuredDbSize()
                }

                // Return true when finished or manually paused; false if interrupted by network loss so WorkManager auto-retries!
                universePauseRequested.get() || remaining == 0 || maxBatchCount < Int.MAX_VALUE
            } catch (e: Exception) {
                _syncProgress.update { it.copy(errorMessage = e.message) }
                false
            } finally {
                _syncProgress.update { it.copy(isUniverseSyncing = false) }
            }
        }
    }

    private suspend fun fetchAndPrepareWithRetry(fund: FundMasterEntity): BatchSchemeResult {
        if (FundCategoryFilter.isSifFund(fund)) {
            return BatchSchemeResult.NoHistory(fund.schemeCode)
        }
        var attempt = 0
        var lastErr: String? = null
        while (attempt < 3) {
            if (universePauseRequested.get()) return BatchSchemeResult.TransientFailure(fund.schemeCode)
            when (val outcome = mfApiHistoryPointsFetcher(fund.schemeCode)) {
                is FastHistoryFetchOutcome.Success -> {
                    val prepared = prepareSchemeHistory(fund, outcome)
                    return if (prepared != null) {
                        BatchSchemeResult.Ready(prepared)
                    } else {
                        BatchSchemeResult.NoHistory(fund.schemeCode)
                    }
                }
                FastHistoryFetchOutcome.NoHistoryAvailable -> {
                    return BatchSchemeResult.NoHistory(fund.schemeCode)
                }
                is FastHistoryFetchOutcome.TransientError -> {
                    lastErr = outcome.message
                    attempt++
                    if (attempt < 3) {
                        delay(if (attempt == 1) 250L else 650L)
                    }
                }
            }
        }
        return BatchSchemeResult.TransientFailure(lastErr ?: fund.schemeCode)
    }

    private fun prepareSchemeHistory(
        fund: FundMasterEntity,
        outcome: FastHistoryFetchOutcome.Success
    ): PreparedSchemeHistory? {
        val fullPoints = outcome.pointsAscending
        if (fullPoints.isEmpty()) return null
        val latest = fullPoints.last()
        val inception = fullPoints.first().dateEpochDay
        val staleDays = LocalDate.now(ZoneId.of("Asia/Kolkata")).toEpochDay() - latest.dateEpochDay
        val isSif = FundCategoryFilter.isSifFund(fund)
        val stillActive = fund.isIndex || isSif || staleDays <= 180L
        val statusNote = if (stillActive) null else "Discontinued / Matured"
        val r1M = PerformanceCalculator.computeTrailingReturn(fullPoints, 30)?.returnPercent
        val r1Y = PerformanceCalculator.computeTrailingReturn(fullPoints, 365)?.returnPercent
        val pointsByYear = NavBinaryCodec.partitionByYear(fullPoints)
        val bucketsByYear = LinkedHashMap<Int, NavBucketEntity>(pointsByYear.size)
        for ((year, yearPts) in pointsByYear) {
            val encoded = NavBinaryCodec.mergeIntoYearBucket(fund.schemeId, year, null, yearPts)
            encoded.bucket?.let { bucketsByYear[year] = it }
        }
        return PreparedSchemeHistory(
            fund = fund,
            bucketsByYear = bucketsByYear,
            pointsByYear = pointsByYear,
            inceptionEpochDay = inception,
            latestNav = latest.navValue,
            latestNavDateEpochDay = latest.dateEpochDay,
            return1M = r1M,
            return1Y = r1Y,
            amcName = outcome.amcName,
            category = outcome.category,
            isin = outcome.isinGrowth,
            isActive = stillActive,
            statusNote = statusNote
        )
    }

    fun pauseUniverseHistorySync() {
        universePauseRequested.set(true)
        universeSyncJob?.cancel()
        universeSyncJob = null
        UniverseSyncWorker.cancelUniverseSync(appContext)
        _syncProgress.update { it.copy(isUniverseSyncing = false) }
    }

    suspend fun computeRankingsForDays(
        days: Int,
        limit: Int = 20,
        selectedCategoryIds: Set<String> = emptySet()
    ): RankingsResult = withContext(Dispatchers.IO) {
        val allFunds = dao.getAllFunds()
        if (allFunds.isEmpty()) {
            return@withContext RankingsResult(days, emptyList(), emptyList(), 0, 0, null, null, selectedCategoryIds.size)
        }

        val matchingFunds = if (selectedCategoryIds.isEmpty()) {
            allFunds
        } else {
            allFunds.filter { !it.isIndex && FundCategoryFilter.matchesAnyCategory(it, selectedCategoryIds) }
        }
        if (matchingFunds.isEmpty()) {
            return@withContext RankingsResult(days, emptyList(), emptyList(), 0, 0, null, null, selectedCategoryIds.size)
        }

        val catalogByCode = matchingFunds.associateBy { it.schemeCode }
        val idToCode = matchingFunds.associate { it.schemeId to it.schemeCode }

        val latestGlobal = dao.getGlobalMaxNavEpochDay()?.toLong() ?: LocalDate.now().toEpochDay()
        val cutoffDay = latestGlobal - days.toLong() - 14L
        val minYear = LocalDate.ofEpochDay(cutoffDay).year

        val recentBuckets = dao.getBucketsFromYear(minYear)
        val grouped = LinkedHashMap<String, ArrayList<NavPoint>>(matchingFunds.size)
        for (bucket in recentBuckets) {
            val code = idToCode[bucket.schemeId] ?: continue
            val pts = NavBinaryCodec.decode(bucket.payload)
            val list = grouped.getOrPut(code) { ArrayList() }
            for (pt in pts) {
                if (pt.dateEpochDay >= cutoffDay) {
                    list.add(pt)
                }
            }
        }

        val favs = dao.getFavouriteCodes().toSet()

        PerformanceCalculator.computeRankings(
            catalog = catalogByCode,
            historiesByScheme = grouped,
            favouriteCodes = favs,
            days = days,
            limit = limit
        ).copy(selectedCategoryCount = selectedCategoryIds.size)
    }

    suspend fun computeSipRankings(
        startEpochDay: Long,
        tenure: SipTenure = SipTenure.MONTHLY,
        sipAmount: Double = 1000.0,
        rankMetric: SipRankMetric = SipRankMetric.XIRR,
        limit: Int = 20,
        selectedCategoryIds: Set<String> = emptySet()
    ): SipRankingsResult = withContext(Dispatchers.IO) {
        val allFunds = dao.getAllFunds()
        if (allFunds.isEmpty()) {
            return@withContext SipRankingsResult(
                tenure = tenure,
                sipAmount = sipAmount,
                startEpochDay = startEpochDay,
                endEpochDay = null,
                topPerformers = emptyList(),
                evaluatedCount = 0,
                excludedCount = 0,
                rankMetric = rankMetric,
                selectedCategoryCount = selectedCategoryIds.size
            )
        }

        val matchingFunds = if (selectedCategoryIds.isEmpty()) {
            allFunds.filter { !it.isIndex }
        } else {
            allFunds.filter { !it.isIndex && FundCategoryFilter.matchesAnyCategory(it, selectedCategoryIds) }
        }
        if (matchingFunds.isEmpty()) {
            return@withContext SipRankingsResult(
                tenure = tenure,
                sipAmount = sipAmount,
                startEpochDay = startEpochDay,
                endEpochDay = null,
                topPerformers = emptyList(),
                evaluatedCount = 0,
                excludedCount = allFunds.size,
                rankMetric = rankMetric,
                selectedCategoryCount = selectedCategoryIds.size
            )
        }

        val idToCode = matchingFunds.associate { it.schemeId to it.schemeCode }
        val minYear = LocalDate.ofEpochDay(startEpochDay).year
        val recentBuckets = dao.getBucketsFromYear(minYear)
        val grouped = LinkedHashMap<String, ArrayList<NavPoint>>(matchingFunds.size)
        for (bucket in recentBuckets) {
            val code = idToCode[bucket.schemeId] ?: continue
            val pts = NavBinaryCodec.decode(bucket.payload)
            val list = grouped.getOrPut(code) { ArrayList() }
            for (pt in pts) {
                if (pt.dateEpochDay >= startEpochDay - 14L) {
                    list.add(pt)
                }
            }
        }

        val favs = dao.getFavouriteCodes().toSet()
        val latestGlobal = dao.getGlobalMaxNavEpochDay()?.toLong() ?: LocalDate.now().toEpochDay()
        val minInstallments = when (tenure) {
            SipTenure.DAILY -> 10
            SipTenure.WEEKLY -> 3
            SipTenure.BIWEEKLY -> 2
            SipTenure.MONTHLY -> 2
        }

        val evaluatedEntries = withContext(Dispatchers.Default) {
            val chunks = matchingFunds.chunked(64)
            coroutineScope {
                chunks.map { chunk ->
                    async {
                        val subList = ArrayList<SipRankingEntry>()
                        for (fund in chunk) {
                            val raw = grouped[fund.schemeCode] ?: continue
                            if (raw.size < minInstallments) continue
                            val sorted = raw.sortedBy { it.dateEpochDay }
                            val windowPts = sorted.filter { it.dateEpochDay >= startEpochDay }
                            if (windowPts.size < minInstallments) continue

                            val firstDay = windowPts.first().dateEpochDay
                            if (firstDay > startEpochDay + 35L) continue

                            val selectedNavPoints = SipCalculator.selectHighestNavPointsByTenure(
                                windowPoints = windowPts,
                                anchorEpochDay = startEpochDay,
                                tenure = tenure
                            )
                            if (selectedNavPoints.size < minInstallments) continue

                            var totalInvested = 0.0
                            var totalUnits = 0.0
                            val lots = selectedNavPoints.mapIndexed { idx, pt ->
                                val units = sipAmount / pt.navValue
                                totalInvested += sipAmount
                                totalUnits += units
                                SipFifoLot(
                                    lotNumber = idx + 1,
                                    buyEpochDay = pt.dateEpochDay,
                                    navUsed = pt.navValue,
                                    investedAmount = sipAmount,
                                    unitsAllotted = units
                                )
                            }

                            val valuationPoint = windowPts.last()
                            val latestNav = valuationPoint.navValue
                            val currentValue = totalUnits * latestNav
                            val absoluteReturn = if (totalInvested > 0.0) {
                                PerformanceCalculator.roundPercent(((currentValue - totalInvested) / totalInvested) * 100.0)
                            } else 0.0

                            val xirr = SipCalculator.computeXirrPercent(
                                lots = lots,
                                valuationEpochDay = valuationPoint.dateEpochDay,
                                terminalValue = currentValue
                            )

                            subList.add(
                                SipRankingEntry(
                                    rank = 0,
                                    schemeCode = fund.schemeCode,
                                    schemeName = fund.schemeName,
                                    amcName = fund.amcName,
                                    category = fund.category,
                                    isIndex = false,
                                    totalInvested = totalInvested,
                                    currentValue = currentValue,
                                    absoluteReturnPercent = absoluteReturn,
                                    xirrPercent = xirr,
                                    installmentCount = lots.size,
                                    firstInstallmentDay = lots.first().buyEpochDay,
                                    lastValuationDay = valuationPoint.dateEpochDay,
                                    isFavourite = favs.contains(fund.schemeCode)
                                )
                            )
                        }
                        subList
                    }
                }.awaitAll().flatten()
            }
        }

        val sortedRankings = when (rankMetric) {
            SipRankMetric.XIRR -> {
                evaluatedEntries.sortedWith(
                    compareByDescending<SipRankingEntry> { it.xirrPercent ?: -999.0 }
                        .thenByDescending { it.absoluteReturnPercent }
                )
            }
            SipRankMetric.ABSOLUTE_RETURN -> {
                evaluatedEntries.sortedWith(
                    compareByDescending<SipRankingEntry> { it.absoluteReturnPercent }
                        .thenByDescending { it.xirrPercent ?: -999.0 }
                )
            }
        }.take(limit).mapIndexed { idx, entry -> entry.copy(rank = idx + 1) }

        SipRankingsResult(
            tenure = tenure,
            sipAmount = sipAmount,
            startEpochDay = startEpochDay,
            endEpochDay = latestGlobal,
            topPerformers = sortedRankings,
            evaluatedCount = evaluatedEntries.size,
            excludedCount = matchingFunds.size - evaluatedEntries.size,
            rankMetric = rankMetric,
            selectedCategoryCount = selectedCategoryIds.size
        )
    }

    suspend fun computeRollingReturnRankings(
        startEpochDay: Long,
        window: RollingWindow = RollingWindow.MONTHLY,
        rankMetric: RollingRankMetric = RollingRankMetric.AVERAGE,
        limit: Int = 20,
        selectedCategoryIds: Set<String> = emptySet()
    ): RollingRankingsResult = withContext(Dispatchers.IO) {
        val allFunds = dao.getAllFunds()
        if (allFunds.isEmpty()) {
            return@withContext RollingRankingsResult(
                window = window,
                startEpochDay = startEpochDay,
                endEpochDay = null,
                topPerformers = emptyList(),
                evaluatedCount = 0,
                excludedCount = 0,
                rankMetric = rankMetric,
                selectedCategoryCount = selectedCategoryIds.size
            )
        }

        val matchingFunds = if (selectedCategoryIds.isEmpty()) {
            allFunds.filter { !it.isIndex }
        } else {
            allFunds.filter { !it.isIndex && FundCategoryFilter.matchesAnyCategory(it, selectedCategoryIds) }
        }
        if (matchingFunds.isEmpty()) {
            return@withContext RollingRankingsResult(
                window = window,
                startEpochDay = startEpochDay,
                endEpochDay = null,
                topPerformers = emptyList(),
                evaluatedCount = 0,
                excludedCount = allFunds.size,
                rankMetric = rankMetric,
                selectedCategoryCount = selectedCategoryIds.size
            )
        }

        val idToCode = matchingFunds.associate { it.schemeId to it.schemeCode }
        val minYear = LocalDate.ofEpochDay(startEpochDay).year
        val recentBuckets = dao.getBucketsFromYear(minYear)
        val grouped = LinkedHashMap<String, ArrayList<NavPoint>>(matchingFunds.size)
        for (bucket in recentBuckets) {
            val code = idToCode[bucket.schemeId] ?: continue
            val pts = NavBinaryCodec.decode(bucket.payload)
            val list = grouped.getOrPut(code) { ArrayList() }
            for (pt in pts) {
                if (pt.dateEpochDay >= startEpochDay - 14L) {
                    list.add(pt)
                }
            }
        }

        val favs = dao.getFavouriteCodes().toSet()
        val latestGlobal = dao.getGlobalMaxNavEpochDay()?.toLong() ?: LocalDate.now().toEpochDay()

        val evaluatedEntries = withContext(Dispatchers.Default) {
            val chunks = matchingFunds.chunked(64)
            coroutineScope {
                chunks.map { chunk ->
                    async {
                        val subList = ArrayList<RollingRankingEntry>()
                        for (fund in chunk) {
                            val raw = grouped[fund.schemeCode] ?: continue
                            if (raw.size < 5) continue
                            val sorted = raw.sortedBy { it.dateEpochDay }
                            val windowPts = sorted.filter { it.dateEpochDay >= startEpochDay }
                            if (windowPts.size < 5) continue

                            val firstDay = windowPts.first().dateEpochDay
                            if (firstDay > startEpochDay + 35L) continue

                            val firstWindowPointDay = windowPts.first().dateEpochDay
                            val observations = ArrayList<RollingObservation>(windowPts.size)

                            for (endPt in windowPts) {
                                val targetStart = RollingReturnCalculator.targetStartEpochDay(endPt.dateEpochDay, window)
                                if (targetStart < firstWindowPointDay) continue

                                val startPt = PerformanceCalculator.findNearestNavOnOrBefore(
                                    sortedAscending = windowPts,
                                    targetEpochDay = targetStart,
                                    maxLookbackDays = 7L
                                ) ?: continue

                                if (startPt.dateEpochDay >= endPt.dateEpochDay || startPt.navValue <= 0.0) continue
                                val retPct = ((endPt.navValue / startPt.navValue) - 1.0) * 100.0
                                observations.add(
                                    RollingObservation(
                                        startEpochDay = startPt.dateEpochDay,
                                        endEpochDay = endPt.dateEpochDay,
                                        startNav = startPt.navValue,
                                        endNav = endPt.navValue,
                                        returnPercent = retPct
                                    )
                                )
                            }

                            if (observations.size < 3) continue

                            var sum = 0.0
                            var positiveCount = 0
                            var minObs = observations[0]
                            var maxObs = observations[0]
                            val returns = ArrayList<Double>(observations.size)

                            for (obs in observations) {
                                val r = obs.returnPercent
                                sum += r
                                returns.add(r)
                                if (r >= 0.0) positiveCount++
                                if (r < minObs.returnPercent) minObs = obs
                                if (r > maxObs.returnPercent) maxObs = obs
                            }

                            val avg = sum / observations.size
                            returns.sort()
                            val median = returns[returns.size / 2]
                            val positivePct = (positiveCount.toDouble() / observations.size.toDouble()) * 100.0
                            val variance = returns.fold(0.0) { acc, r -> acc + (r - avg) * (r - avg) } / observations.size
                            val stdDev = kotlin.math.sqrt(variance)

                            subList.add(
                                RollingRankingEntry(
                                    rank = 0,
                                    schemeCode = fund.schemeCode,
                                    schemeName = fund.schemeName,
                                    amcName = fund.amcName,
                                    category = fund.category,
                                    isIndex = false,
                                    window = window,
                                    observationCount = observations.size,
                                    averageReturnPercent = PerformanceCalculator.roundPercent(avg),
                                    medianReturnPercent = PerformanceCalculator.roundPercent(median),
                                    minReturnPercent = PerformanceCalculator.roundPercent(minObs.returnPercent),
                                    maxReturnPercent = PerformanceCalculator.roundPercent(maxObs.returnPercent),
                                    latestReturnPercent = PerformanceCalculator.roundPercent(observations.last().returnPercent),
                                    positivePercent = PerformanceCalculator.roundPercent(positivePct),
                                    stdDevPercent = PerformanceCalculator.roundPercent(stdDev),
                                    firstWindowEndDay = observations.first().endEpochDay,
                                    lastWindowEndDay = observations.last().endEpochDay,
                                    isFavourite = favs.contains(fund.schemeCode)
                                )
                            )
                        }
                        subList
                    }
                }.awaitAll().flatten()
            }
        }

        val sortedRankings = when (rankMetric) {
            RollingRankMetric.AVERAGE -> evaluatedEntries.sortedByDescending { it.averageReturnPercent }
            RollingRankMetric.MEDIAN -> evaluatedEntries.sortedByDescending { it.medianReturnPercent }
            RollingRankMetric.LATEST -> evaluatedEntries.sortedByDescending { it.latestReturnPercent }
            RollingRankMetric.POSITIVE_CONSISTENCY -> evaluatedEntries.sortedWith(
                compareByDescending<RollingRankingEntry> { it.positivePercent }
                    .thenByDescending { it.averageReturnPercent }
            )
        }.take(limit).mapIndexed { idx, entry -> entry.copy(rank = idx + 1) }

        RollingRankingsResult(
            window = window,
            startEpochDay = startEpochDay,
            endEpochDay = latestGlobal,
            topPerformers = sortedRankings,
            evaluatedCount = evaluatedEntries.size,
            excludedCount = matchingFunds.size - evaluatedEntries.size,
            rankMetric = rankMetric,
            selectedCategoryCount = selectedCategoryIds.size
        )
    }

    suspend fun computeBlendRankings(
        timeframeStartEpochDay: Long,
        rollingWindow: RollingWindow = RollingWindow.MONTHLY,
        sipTenure: SipTenure = SipTenure.MONTHLY,
        sipAmount: Double = 5000.0,
        sipWeight: Double = 0.5,
        peerGroupAll: Boolean = false,
        selectedCategoryIds: Set<String> = emptySet(),
        minFundAgeDays: Int = 0,
        minSipXirr: Double = -999.0,
        minRollingAvg: Double = -999.0,
        minRollingMedian: Double = -999.0,
        consistencyHurdle: Double = 0.0,
        minConsistency: Double = 0.0,
        sortBy: BlendSortMetric = BlendSortMetric.BLEND_SCORE,
        limit: Int = 20
    ): BlendRankingsResult = withContext(Dispatchers.IO) {
        val allFunds = dao.getAllFunds()
        if (allFunds.isEmpty()) {
            return@withContext BlendRankingsResult(
                timeframeLabel = DateFormatUtils.formatShort(timeframeStartEpochDay),
                rollingWindow = rollingWindow,
                sipTenure = sipTenure,
                sipAmount = sipAmount,
                sipWeight = sipWeight,
                rollingWeight = 1.0 - sipWeight,
                peerGroupAll = peerGroupAll,
                selectedCategoryCount = selectedCategoryIds.size,
                topPerformers = emptyList(),
                evaluatedCount = 0,
                qualifiedCount = 0,
                excludedCount = 0,
                exclusionReasons = emptyMap(),
                sortBy = sortBy
            )
        }

        val matchingFunds = if (peerGroupAll || selectedCategoryIds.isEmpty()) {
            allFunds.filter { !it.isIndex }
        } else {
            allFunds.filter { !it.isIndex && FundCategoryFilter.matchesAnyCategory(it, selectedCategoryIds) }
        }

        val idToCode = matchingFunds.associate { it.schemeId to it.schemeCode }
        val minYear = LocalDate.ofEpochDay(timeframeStartEpochDay).year
        val recentBuckets = dao.getBucketsFromYear(minYear)
        val grouped = LinkedHashMap<String, ArrayList<NavPoint>>(matchingFunds.size)
        for (bucket in recentBuckets) {
            val code = idToCode[bucket.schemeId] ?: continue
            val pts = NavBinaryCodec.decode(bucket.payload)
            val list = grouped.getOrPut(code) { ArrayList() }
            for (pt in pts) {
                if (pt.dateEpochDay >= timeframeStartEpochDay - 14L) {
                    list.add(pt)
                }
            }
        }

        val favs = dao.getFavouriteCodes().toSet()
        val exclusionReasons = HashMap<String, String>()

        data class IntermediateRaw(
            val fund: FundMasterEntity,
            val sipXirr: Double?,
            val rollingMean: Double,
            val rollingMedian: Double,
            val consistencyPercent: Double,
            val stdDevPercent: Double,
            val observationCount: Int,
            val firstWindowEndDay: Long?,
            val lastWindowEndDay: Long?,
            val effectiveStartDay: Long?,
            val fundAgeDays: Int
        )

        val rawEntries = withContext(Dispatchers.Default) {
            val chunks = matchingFunds.chunked(64)
            coroutineScope {
                chunks.map { chunk ->
                    async {
                        val subList = ArrayList<IntermediateRaw>()
                        for (fund in chunk) {
                            val raw = grouped[fund.schemeCode]
                            if (raw == null || raw.isEmpty()) {
                                exclusionReasons[fund.schemeCode] = "No NAV history loaded"
                                continue
                            }
                            val sorted = raw.sortedBy { it.dateEpochDay }
                            val firstActual = sorted.first().dateEpochDay
                            if (firstActual > timeframeStartEpochDay) {
                                exclusionReasons[fund.schemeCode] = "Fund inception is after timeframe start"
                                continue
                            }

                            val fundAge = (LocalDate.now().toEpochDay() - firstActual).toInt()
                            if (fundAge < minFundAgeDays) {
                                exclusionReasons[fund.schemeCode] = "Fund age below minimum"
                                continue
                            }

                            val windowPtsSip = sorted.filter { it.dateEpochDay >= timeframeStartEpochDay }
                            val minInstallments = when (sipTenure) {
                                SipTenure.DAILY -> 10
                                SipTenure.WEEKLY -> 3
                                SipTenure.BIWEEKLY -> 2
                                SipTenure.MONTHLY -> 2
                            }
                            if (windowPtsSip.size < minInstallments) {
                                exclusionReasons[fund.schemeCode] = "Insufficient SIP installments"
                                continue
                            }
                            val selectedNavPointsSip = SipCalculator.selectHighestNavPointsByTenure(
                                windowPoints = windowPtsSip,
                                anchorEpochDay = timeframeStartEpochDay,
                                tenure = sipTenure
                            )
                            if (selectedNavPointsSip.size < minInstallments) {
                                exclusionReasons[fund.schemeCode] = "Insufficient SIP installments after bucketing"
                                continue
                            }

                            var totalInvested = 0.0
                            var totalUnits = 0.0
                            val lots = selectedNavPointsSip.mapIndexed { idx, pt ->
                                val units = sipAmount / pt.navValue
                                totalInvested += sipAmount
                                totalUnits += units
                                SipFifoLot(idx + 1, pt.dateEpochDay, pt.navValue, sipAmount, units)
                            }
                            val valuationPointSip = windowPtsSip.last()
                            val currentValue = totalUnits * valuationPointSip.navValue
                            val sipXirr = SipCalculator.computeXirrPercent(lots, valuationPointSip.dateEpochDay, currentValue)

                            val windowPtsRoll = sorted.filter { it.dateEpochDay >= timeframeStartEpochDay }
                            val observations = RollingReturnCalculator.computeRollingObservations(
                                rawHistory = windowPtsRoll,
                                window = rollingWindow,
                                startEpochDay = timeframeStartEpochDay
                            )
                            if (observations.size < 30) {
                                exclusionReasons[fund.schemeCode] = "Insufficient rolling observations (${observations.size} < 30)"
                                continue
                            }

                            val returns = observations.map { it.returnPercent }.sorted()
                            val mean = returns.average()
                            val median = if (returns.size % 2 == 1) {
                                returns[returns.size / 2]
                            } else {
                                (returns[returns.size / 2 - 1] + returns[returns.size / 2]) / 2.0
                            }
                            val positiveCount = observations.count { it.returnPercent >= consistencyHurdle }
                            val consistency = (positiveCount.toDouble() / observations.size.toDouble()) * 100.0
                            val variance = returns.fold(0.0) { acc, r -> acc + (r - mean) * (r - mean) } / observations.size
                            val stdDev = kotlin.math.sqrt(variance)

                            subList.add(
                                IntermediateRaw(
                                    fund = fund,
                                    sipXirr = sipXirr,
                                    rollingMean = PerformanceCalculator.roundPercent(mean),
                                    rollingMedian = PerformanceCalculator.roundPercent(median),
                                    consistencyPercent = PerformanceCalculator.roundPercent(consistency),
                                    stdDevPercent = PerformanceCalculator.roundPercent(stdDev),
                                    observationCount = observations.size,
                                    firstWindowEndDay = observations.first().endEpochDay,
                                    lastWindowEndDay = observations.last().endEpochDay,
                                    effectiveStartDay = firstActual,
                                    fundAgeDays = fundAge
                                )
                            )
                        }
                        subList
                    }
                }.awaitAll().flatten()
            }
        }

        if (rawEntries.isEmpty()) {
            return@withContext BlendRankingsResult(
                timeframeLabel = DateFormatUtils.formatShort(timeframeStartEpochDay),
                rollingWindow = rollingWindow,
                sipTenure = sipTenure,
                sipAmount = sipAmount,
                sipWeight = sipWeight,
                rollingWeight = 1.0 - sipWeight,
                peerGroupAll = peerGroupAll,
                selectedCategoryCount = selectedCategoryIds.size,
                topPerformers = emptyList(),
                evaluatedCount = matchingFunds.size,
                qualifiedCount = 0,
                excludedCount = matchingFunds.size,
                exclusionReasons = exclusionReasons,
                sortBy = sortBy
            )
        }

        val sipXirrList = rawEntries.map { it.sipXirr }
        val medianList = rawEntries.map { it.rollingMedian }
        val meanList = rawEntries.map { it.rollingMean }
        val consistencyList = rawEntries.map { it.consistencyPercent }

        val sipPercentileMap = BlendRankingsCalculator.computePercentiles(sipXirrList)
        val medianPercentileMap = BlendRankingsCalculator.computePercentiles(medianList)
        val meanPercentileMap = BlendRankingsCalculator.computePercentiles(meanList)
        val consistencyPercentileMap = BlendRankingsCalculator.computePercentiles(consistencyList)

        val rollingWeight = 1.0 - sipWeight

        val scoredEntries = rawEntries.map { raw ->
            val sipPct = sipPercentileMap[raw.sipXirr] ?: 0.0
            val medPct = medianPercentileMap[raw.rollingMedian] ?: 0.0
            val meanPct = meanPercentileMap[raw.rollingMean] ?: 0.0
            val consPct = consistencyPercentileMap[raw.consistencyPercent] ?: 0.0

            val rollingScore = 0.40 * medPct + 0.30 * meanPct + 0.30 * consPct
            val blendScore = (sipWeight * sipPct) + (rollingWeight * rollingScore)

            BlendRankingEntry(
                rank = 0,
                schemeCode = raw.fund.schemeCode,
                schemeName = raw.fund.schemeName,
                amcName = raw.fund.amcName,
                category = raw.fund.category,
                isIndex = false,
                blendScore = PerformanceCalculator.roundPercent(blendScore),
                sipXirr = raw.sipXirr,
                rollingMean = raw.rollingMean,
                rollingMedian = raw.rollingMedian,
                consistencyPercent = raw.consistencyPercent,
                sipPercentile = PerformanceCalculator.roundPercent(sipPct),
                rollingScore = PerformanceCalculator.roundPercent(rollingScore),
                rollingMedianPercentile = PerformanceCalculator.roundPercent(medPct),
                rollingMeanPercentile = PerformanceCalculator.roundPercent(meanPct),
                rollingConsistencyPercentile = PerformanceCalculator.roundPercent(consPct),
                observationCount = raw.observationCount,
                stdDevPercent = raw.stdDevPercent,
                firstWindowEndDay = raw.firstWindowEndDay,
                lastWindowEndDay = raw.lastWindowEndDay,
                effectiveStartDay = raw.effectiveStartDay,
                isFavourite = favs.contains(raw.fund.schemeCode)
            )
        }

        val qualified = scoredEntries.filter { entry ->
            val xirrOk = entry.sipXirr == null || entry.sipXirr >= minSipXirr
            val avgOk = entry.rollingMean >= minRollingAvg
            val medOk = entry.rollingMedian >= minRollingMedian
            val consOk = entry.consistencyPercent >= minConsistency
            xirrOk && avgOk && medOk && consOk
        }

        val sorted = when (sortBy) {
            BlendSortMetric.BLEND_SCORE -> qualified.sortedWith(
                compareByDescending<BlendRankingEntry> { it.blendScore }
                    .thenBy { it.stdDevPercent }
            )
            BlendSortMetric.SIP_XIRR -> qualified.sortedWith(
                compareByDescending<BlendRankingEntry> { it.sipXirr ?: -999.0 }
                    .thenByDescending { it.blendScore }
                    .thenBy { it.stdDevPercent }
            )
            BlendSortMetric.ROLLING_MEDIAN -> qualified.sortedWith(
                compareByDescending<BlendRankingEntry> { it.rollingMedian }
                    .thenByDescending { it.blendScore }
                    .thenBy { it.stdDevPercent }
            )
            BlendSortMetric.CONSISTENCY -> qualified.sortedWith(
                compareByDescending<BlendRankingEntry> { it.consistencyPercent }
                    .thenByDescending { it.blendScore }
                    .thenBy { it.stdDevPercent }
            )
        }.take(limit).mapIndexed { idx, entry -> entry.copy(rank = idx + 1) }

        BlendRankingsResult(
            timeframeLabel = DateFormatUtils.formatShort(timeframeStartEpochDay),
            rollingWindow = rollingWindow,
            sipTenure = sipTenure,
            sipAmount = sipAmount,
            sipWeight = sipWeight,
            rollingWeight = rollingWeight,
            peerGroupAll = peerGroupAll,
            selectedCategoryCount = selectedCategoryIds.size,
            topPerformers = sorted,
            evaluatedCount = rawEntries.size,
            qualifiedCount = qualified.size,
            excludedCount = matchingFunds.size - rawEntries.size,
            exclusionReasons = exclusionReasons,
            sortBy = sortBy
        )
    }

    suspend fun toggleFavourite(schemeCode: String) = withContext(Dispatchers.IO) {
        if (dao.isFavourite(schemeCode)) {
            dao.removeFavourite(schemeCode)
        } else {
            dao.addFavourite(FavouriteFundEntity(schemeCode, System.currentTimeMillis()))
            dao.getFundByCode(schemeCode)?.let { fund ->
                if (fund.historySyncedAt == 0L) {
                    syncSingleSchemeFullHistoryOnce(fund, System.currentTimeMillis())
                    refreshMeasuredDbSize()
                }
            }
        }
    }
}

private sealed interface BatchSchemeResult {
    data class Ready(val prepared: PreparedSchemeHistory) : BatchSchemeResult
    data class NoHistory(val schemeCode: String) : BatchSchemeResult
    data class TransientFailure(val schemeCode: String) : BatchSchemeResult
}

private data class SyncProgressInternal(
    val isSchemeMasterSyncing: Boolean = false,
    val isNavSyncing: Boolean = false,
    val isUniverseSyncing: Boolean = false,
    val errorMessage: String? = null
)
