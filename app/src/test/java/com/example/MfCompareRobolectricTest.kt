package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.DirectGrowthFilter
import com.example.data.FavouriteFundEntity
import com.example.data.FundMasterEntity
import com.example.data.MfCompareDatabase
import com.example.data.NavHistoryEntity
import com.example.data.SyncMetadataEntity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithText
import com.example.data.DailyLatestNavUpdate
import com.example.data.FundCompareRepository
import com.example.data.NavPoint
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MfCompareRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: MfCompareDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MfCompareDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        FundCompareRepository.getInstance(context).pauseUniverseHistorySync()
        try {
            androidx.work.WorkManager.getInstance(context).cancelAllWork()
        } catch (_: Throwable) {
        }
        db.close()
    }

    @Test
    fun `room database deduplicates nav history by schemeCode and dateEpochDay and persists favourites`() = runTest {
        val dao = db.fundDao()
        val fund = FundMasterEntity(
            schemeCode = "122639",
            schemeName = "Parag Parikh Flexi Cap Fund - Direct Plan - Growth",
            searchTokens = DirectGrowthFilter.normalizeSearchTokens("Parag Parikh Flexi Cap Fund Direct Plan Growth PPFAS"),
            amcName = "PPFAS Mutual Fund",
            category = "Flexi Cap Fund",
            isin = "INF879O01027",
            dedupKey = "ISIN:INF879O01027",
            isIndex = false,
            isActive = true,
            latestNav = 82.45,
            latestNavDateEpochDay = LocalDate.of(2026, 11, 20).toEpochDay(),
            dataSource = "AMFI",
            lastSyncedAt = 1000L
        )
        dao.upsertFunds(listOf(fund))

        val day = LocalDate.of(2026, 11, 20).toEpochDay()
        dao.upsertNavHistory(
            listOf(
                NavHistoryEntity("122639", day, 82.40, "MFAPI", 1000L),
                NavHistoryEntity("122639", day, 82.45, "AMFI", 2000L)
            )
        )

        val history = dao.getNavHistory("122639")
        assertEquals(1, history.size)
        assertEquals(82.45, history.first().navValue, 0.0001)

        dao.addFavourite(FavouriteFundEntity("122639", 3000L))
        assertTrue(dao.isFavourite("122639"))
        assertEquals(listOf("122639"), dao.getFavouriteCodes())
    }

    @Test
    fun `main screen renders strictly three bottom navigation tabs and switches cleanly`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val realDb = MfCompareDatabase.getDatabase(appContext)
        val now = System.currentTimeMillis()
        val day = LocalDate.of(2026, 11, 20).toEpochDay()
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            realDb.clearAllTables()
            realDb.fundDao().upsertFunds(
                listOf(
                    FundMasterEntity(
                        schemeCode = "122639",
                        schemeName = "Parag Parikh Flexi Cap Fund - Direct Plan - Growth",
                        searchTokens = DirectGrowthFilter.normalizeSearchTokens("Parag Parikh Flexi Cap Fund Direct Plan Growth"),
                        amcName = "PPFAS Mutual Fund",
                        category = "Flexi Cap Fund",
                        isin = "INF879O01027",
                        dedupKey = "ISIN:INF879O01027",
                        isIndex = false,
                        isActive = true,
                        latestNav = 82.45,
                        latestNavDateEpochDay = day,
                        historySyncedAt = now,
                        dataSource = "TEST",
                        lastSyncedAt = now
                    )
                )
            )
            realDb.fundDao().upsertNavRecordsIncremental(
                FundMasterEntity.deriveSchemeId("122639"),
                listOf(NavPoint(day - 7L, 80.0), NavPoint(day, 82.45))
            )
            realDb.fundDao().upsertSyncMetadata(
                SyncMetadataEntity(
                    id = 1,
                    lastSchemeMasterSyncMillis = now,
                    lastNavSyncMillis = now,
                    lastUniverseSyncMillis = now,
                    lastDataEpochDay = day
                )
            )
        }
        val vm = MainViewModel(appContext as android.app.Application)

        composeRule.setContent {
            com.example.ui.theme.MfCompareTheme(darkTheme = true) {
                MfCompareApp(viewModel = vm, isDark = true)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_tab_compare").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_tab_rankings").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_tab_favourites").assertIsDisplayed()
        composeRule.onNodeWithTag("fund_search_input").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_tab_rankings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ranking_day_7d").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_tab_favourites").performClick()
        composeRule.waitForIdle()
        vm.pauseUniverseHistorySync()
    }

    @Test
    fun `mfapi and yahoo json parsers extract sorted deduplicated historical points`() {
        val mfApiJson = """
            {
              "meta": {"scheme_code": 120828},
              "data": [
                {"date": "20-11-2026", "nav": "250.50"},
                {"date": "20-11-2026", "nav": "250.50"},
                {"date": "19-11-2026", "nav": "248.00"}
              ]
            }
        """.trimIndent()
        val parsedHistory = com.example.data.RemoteDataSources.parseMfApiHistoryJson("120828", mfApiJson, nowMillis = 1000L)
        assertEquals(2, parsedHistory.size)
        assertEquals(248.00, parsedHistory[0].navValue, 0.001)
        assertEquals(250.50, parsedHistory[1].navValue, 0.001)

        val yahooJson = """
            {
              "chart": {
                "result": [{
                  "timestamp": [1795065600, 1795152000],
                  "indicators": {
                    "quote": [{"close": [24100.25, 24350.75]}]
                  }
                }]
              }
            }
        """.trimIndent()
        val parsedIdx = com.example.data.RemoteDataSources.parseYahooChartJson("IDX_NIFTY50", yahooJson, nowMillis = 1000L)
        assertEquals(2, parsedIdx.size)
        assertEquals(24100.25, parsedIdx[0].navValue, 0.001)
        assertEquals(24350.75, parsedIdx[1].navValue, 0.001)

        val masterJson = """
            [
              {"schemeCode": 120828, "schemeName": "quant Small Cap Fund - Growth Option - Direct Plan", "isinGrowth": "INF966L01689"},
              {"schemeCode": 120827, "schemeName": "quant Small Cap Fund - IDCW Option - Direct Plan", "isinGrowth": null},
              {"schemeCode": 100111, "schemeName": "quant Small Cap Fund - Growth Option - Regular Plan"},
              {"schemeCode": 154658, "schemeName": "quant Income Plus Arbitrage Active FOF - Direct Plan - Growth Option", "isinGrowth": "INF966L01EJ3"}
            ]
        """.trimIndent()
        val masterFunds = com.example.data.RemoteDataSources.parseMfApiMasterCatalogJson(masterJson, nowMillis = 2000L)
        assertEquals(2, masterFunds.size)
        assertEquals("120828", masterFunds[0].schemeCode)
        assertEquals("154658", masterFunds[1].schemeCode)

        val fullMetaJson = """
            {
              "meta": {
                "fund_house": "quant Mutual Fund",
                "scheme_category": "Equity Scheme - Small Cap Fund",
                "isin_growth": "INF966L01689"
              },
              "data": [
                {"date": "23-11-2026", "nav": "252.10"}
              ]
            }
        """.trimIndent()
        val withMeta = com.example.data.RemoteDataSources.parseMfApiHistoryWithMetaJson("120828", fullMetaJson, nowMillis = 3000L)
        assertEquals("quant Mutual Fund", withMeta.amcName)
        assertEquals("Equity Scheme - Small Cap Fund", withMeta.category)
        assertEquals("INF966L01689", withMeta.isinGrowth)
        assertEquals(1, withMeta.records.size)

        val tigzigBatchJson = """
            {
              "count": 3,
              "schemes": [
                {"scheme_code": 120819, "isin": "INF966L01556", "data": [{"date": "2026-11-25", "nav": 522.7593}]},
                {"scheme_code": 153705, "isin": "INF966L01EC8", "data": [{"date": "2026-11-25", "nav": 11.2186}]},
                {"scheme_code": 9, "source": "SIF", "sif_code": "SIF-9", "isin": "INF754K30011", "data": [{"date": "2026-11-23", "nav": 11.1261}]}
              ],
              "not_found": []
            }
        """.trimIndent()
        val queryMap = mapOf(
            "120819" to listOf("120819"),
            "153705" to listOf("153705"),
            "SIF-9" to listOf("9")
        )
        val batchUpdates = com.example.data.RemoteDataSources.parseTigzigBatchNavLatestJson(tigzigBatchJson, queryMap)
        assertEquals(3, batchUpdates.size)
        val u1 = batchUpdates.first { it.schemeCode == "120819" }
        assertEquals(LocalDate.of(2026, 11, 25).toEpochDay(), u1.epochDay)
        assertEquals(522.7593, u1.navValue, 0.0001)
        val uSif = batchUpdates.first { it.schemeCode == "9" }
        assertEquals(LocalDate.of(2026, 11, 23).toEpochDay(), uSif.epochDay)
        assertEquals(11.1261, uSif.navValue, 0.0001)
    }

    @Test
    fun `catalog refresh preserves existing synced metrics and latest nav when incoming catalog lacks nav`() = runTest {
        val dao = db.fundDao()
        val day = LocalDate.of(2026, 11, 23).toEpochDay()
        val existing = FundMasterEntity(
            schemeCode = "120828",
            schemeName = "quant Small Cap Fund - Growth Option - Direct Plan",
            searchTokens = DirectGrowthFilter.normalizeSearchTokens("quant Small Cap Fund Growth Option Direct Plan"),
            amcName = "quant Mutual Fund",
            category = "Equity Scheme - Small Cap Fund",
            isin = "INF966L01689",
            dedupKey = "ISIN:INF966L01689",
            isIndex = false,
            isActive = true,
            inceptionEpochDay = LocalDate.of(2013, 1, 1).toEpochDay(),
            latestNav = 252.10,
            latestNavDateEpochDay = day,
            return1M = 3.5,
            return1Y = 18.2,
            historySyncedAt = 5000L,
            dataSource = "AMFI",
            lastSyncedAt = 5000L
        )
        dao.upsertFunds(listOf(existing))

        // Subsequent MFAPI catalog refresh without latestNav or historySyncedAt should NOT wipe cached metrics
        val incomingMfApi = FundMasterEntity(
            schemeCode = "120828",
            schemeName = "quant Small Cap Fund - Growth Option - Direct Plan",
            searchTokens = DirectGrowthFilter.normalizeSearchTokens("quant Small Cap Fund Growth Option Direct Plan"),
            amcName = "quant Small",
            category = "Direct Growth Mutual Fund",
            isin = null,
            dedupKey = "ISIN:INF966L01689",
            isIndex = false,
            isActive = true,
            latestNav = null,
            latestNavDateEpochDay = null,
            historySyncedAt = 0L,
            dataSource = "MFAPI",
            lastSyncedAt = 6000L
        )
        dao.saveFullCatalogAndLatestNavs(listOf(incomingMfApi), emptyList())

        val reloaded = dao.getFundByCode("120828")!!
        assertEquals(252.10, reloaded.latestNav!!, 0.001)
        assertEquals(5000L, reloaded.historySyncedAt)
        assertEquals(3.5, reloaded.return1M!!, 0.001)
        assertEquals(18.2, reloaded.return1Y!!, 0.001)
    }

    @Test
    fun `end to end cuj search select compare chart period switch rankings and favourites work cleanly`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val realDb = MfCompareDatabase.getDatabase(appContext)
        val dao = realDb.fundDao()
        val now = System.currentTimeMillis()
        val endDay = LocalDate.of(2026, 11, 20).toEpochDay()
        val midDay = endDay - 7L
        val monthDay = endDay - 30L
        val yearDay = endDay - 365L

        val fund1 = FundMasterEntity(
            schemeCode = "120828",
            schemeName = "Quant Small Cap Fund - Direct Plan - Growth",
            searchTokens = DirectGrowthFilter.normalizeSearchTokens("Quant Small Cap Fund Direct Plan Growth Quant Mutual Fund"),
            amcName = "Quant Mutual Fund",
            category = "Small Cap Fund",
            isin = "INF966L01689",
            dedupKey = "ISIN:INF966L01689",
            isIndex = false,
            isActive = true,
            inceptionEpochDay = yearDay,
            latestNav = 260.0,
            latestNavDateEpochDay = endDay,
            return1M = 8.33,
            return1Y = 30.0,
            historySyncedAt = now,
            dataSource = "MFAPI",
            lastSyncedAt = now
        )
        val idx1 = FundMasterEntity(
            schemeCode = "IDX_NIFTY50",
            schemeName = "Nifty 50",
            searchTokens = DirectGrowthFilter.normalizeSearchTokens("Nifty 50 index benchmark Broad Market Index"),
            amcName = "Market Index",
            category = "Broad Market Index",
            isin = null,
            dedupKey = "INDEX:IDX_NIFTY50",
            isIndex = true,
            isActive = true,
            inceptionEpochDay = yearDay,
            latestNav = 24500.0,
            latestNavDateEpochDay = endDay,
            return1M = 2.08,
            return1Y = 12.5,
            historySyncedAt = now,
            dataSource = "YAHOO_FINANCE",
            lastSyncedAt = now
        )

        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            realDb.clearAllTables()
            dao.upsertFunds(listOf(fund1, idx1))
            dao.upsertNavHistory(
                listOf(
                    NavHistoryEntity("120828", yearDay, 200.0, "MFAPI", now),
                    NavHistoryEntity("120828", monthDay, 240.0, "MFAPI", now),
                    NavHistoryEntity("120828", midDay, 245.0, "MFAPI", now),
                    NavHistoryEntity("120828", endDay, 260.0, "MFAPI", now),
                    NavHistoryEntity("IDX_NIFTY50", yearDay, 21777.0, "YAHOO_FINANCE", now),
                    NavHistoryEntity("IDX_NIFTY50", monthDay, 24000.0, "YAHOO_FINANCE", now),
                    NavHistoryEntity("IDX_NIFTY50", midDay, 24200.0, "YAHOO_FINANCE", now),
                    NavHistoryEntity("IDX_NIFTY50", endDay, 24500.0, "YAHOO_FINANCE", now)
                )
            )
            dao.addFavourite(FavouriteFundEntity("120828", now))
            dao.upsertSyncMetadata(
                SyncMetadataEntity(
                    id = 1,
                    lastSchemeMasterSyncMillis = now,
                    lastNavSyncMillis = now,
                    lastUniverseSyncMillis = now,
                    lastDataEpochDay = endDay
                )
            )
        }

        val vm = MainViewModel(appContext as android.app.Application)
        composeRule.setContent {
            com.example.ui.theme.MfCompareTheme(darkTheme = true) {
                MfCompareApp(viewModel = vm, isDark = true)
            }
        }
        composeRule.waitForIdle()

        // Search "qu sm" and select Quant Small Cap
        composeRule.onNodeWithTag("fund_search_input").performTextInput("qu sm")
        composeRule.waitUntil(timeoutMillis = 3000) {
            composeRule.onAllNodesWithTag("search_result_120828").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("search_result_120828").performClick()
        composeRule.onNodeWithTag("done_search_button").performClick()

        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithTag("comparison_chart_card").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("comparison_chart_card").assertIsDisplayed()

        // Switch period chips while period row is visible in viewport
        composeRule.onNodeWithTag("period_chip_M1").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("period_chip_INCEPTION").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("compare_lazy_column")
            .performScrollToNode(hasTestTag("comparison_summary_table"))
        composeRule.onNodeWithTag("comparison_summary_table").assertIsDisplayed()

        // Navigate to Rankings and verify 7D row renders
        composeRule.onNodeWithTag("nav_tab_rankings").performClick()
        composeRule.waitForIdle()
        assertEquals(AppTab.RANKINGS, vm.selectedTab.value)
        try {
            composeRule.waitUntil(timeoutMillis = 5000) {
                vm.rankingsResult.value.topPerformers.isNotEmpty()
            }
        } catch (t: Throwable) {
            throw AssertionError(
                "Rankings did not populate: tab=${vm.selectedTab.value}, loading=${vm.isRankingsLoading.value}, " +
                    "result=${vm.rankingsResult.value}, sync=${vm.syncStatus.value}",
                t
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rankings_lazy_column")
            .performScrollToNode(hasTestTag("ranking_top_row_120828"))
        composeRule.onNodeWithTag("ranking_top_row_120828").assertIsDisplayed()

        // Navigate to Favourites and verify saved bookmark card renders
        composeRule.onNodeWithTag("nav_tab_favourites").performClick()
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithTag("favourite_card_120828").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("favourite_card_120828").assertIsDisplayed()
        vm.pauseUniverseHistorySync()
    }

    @Test
    fun `storage audit verifies compact binary buckets deduplicate dates and scale cleanly across incremental syncs`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "mf_compare_storage_audit_test"
        val dbFile = context.getDatabasePath(dbName)
        listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm")).forEach { if (it.exists()) it.delete() }

        val fileDb = Room.databaseBuilder(context, MfCompareDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = fileDb.fundDao()
            val emptyBytes = fileDb.checkpointAndMeasureBytes(context, vacuumIfLarge = false)

            val schemeCount = 100
            val daysPerScheme = 1500 // ~6 years of trading days per fund => 150,000 points
            val baseStartDay = LocalDate.of(2020, 1, 1).toEpochDay()
            val funds = (1..schemeCount).map { idx ->
                val code = (110000 + idx).toString()
                FundMasterEntity(
                    schemeCode = code,
                    schemeId = 110000 + idx,
                    schemeName = "Benchmark Audit Direct Fund $idx - Direct Plan - Growth",
                    searchTokens = "benchmark audit direct fund $idx growth",
                    amcName = "Audit AMC",
                    category = "Flexi Cap Fund",
                    isin = "INF999A01%03d".format(idx),
                    dedupKey = "ISIN:INF999A01%03d".format(idx),
                    isIndex = false,
                    isActive = true,
                    historySyncedAt = 1000L,
                    dataSource = "MFAPI",
                    lastSyncedAt = 1000L
                )
            }

            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                dao.upsertFunds(funds)
                val endDayForSeries = baseStartDay + (daysPerScheme - 1).toLong()
                for (fund in funds) {
                    val pts = ArrayList<NavPoint>(daysPerScheme)
                    var nav = 100.0 + (fund.schemeId % 50)
                    for (d in 0 until daysPerScheme) {
                        val day = baseStartDay + d
                        nav += ((d % 7) - 2) * 0.08
                        pts.add(NavPoint(day, nav))
                    }
                    // Also include duplicate points to ensure strict deduplication
                    pts.add(pts.first())
                    pts.add(pts.last())
                    dao.upsertNavRecordsIncremental(fund.schemeId, pts)
                }
                dao.applyDailyLatestNavBatch(
                    funds.map { DailyLatestNavUpdate(it.schemeCode, endDayForSeries, 150.0) },
                    nowMillis = 2000L
                )
            }

            val totalPointsAfterInitial = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                dao.getTotalStoredNavPoints()
            }
            assertEquals(schemeCount * daysPerScheme, totalPointsAfterInitial)

            val postInitialBytes = fileDb.checkpointAndMeasureBytes(context, vacuumIfLarge = true)
            val bytesPerPoint = postInitialBytes.toDouble() / totalPointsAfterInitial.toDouble()
            val projected3000FundsMb = (bytesPerPoint * daysPerScheme * 3000.0) / (1024.0 * 1024.0)

            // Capture snapshot of 2020 bucket before incremental updates
            val sample2020Before = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                dao.getBucket(110001, 2020)!!
            }

            // Simulate 5 incremental daily NAV publications + 2 redundant weekend/duplicate runs
            val lastSeededDay = baseStartDay + (daysPerScheme - 1).toLong()
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                for (step in 1..5) {
                    val nextDay = lastSeededDay + step
                    val inserted = dao.applyDailyLatestNavBatch(
                        funds.mapIndexed { idx, f ->
                            DailyLatestNavUpdate(f.schemeCode, nextDay, 155.0 + step + idx * 0.01)
                        },
                        nowMillis = 3000L + step
                    )
                    assertEquals(schemeCount, inserted)
                }
                // Re-running with same date must insert 0 rows
                val dupInserted = dao.applyDailyLatestNavBatch(
                    funds.mapIndexed { idx, f ->
                        DailyLatestNavUpdate(f.schemeCode, lastSeededDay + 5, 160.0 + idx * 0.01)
                    },
                    nowMillis = 9000L
                )
                assertEquals(0, dupInserted)
            }

            val totalPointsAfterIncremental = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                dao.getTotalStoredNavPoints()
            }
            assertEquals(schemeCount * (daysPerScheme + 5), totalPointsAfterIncremental)

            val sample2020After = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                dao.getBucket(110001, 2020)!!
            }
            assertTrue(sample2020Before.payload.contentEquals(sample2020After.payload))

            val postIncrementalBytes = fileDb.checkpointAndMeasureBytes(context, vacuumIfLarge = false)
            val incrementalDeltaBytes = postIncrementalBytes - postInitialBytes

            println(
                String.format(
                    Locale.US,
                    "STORAGE_AUDIT_REPORT|empty=%dB|points=%d|postInitial=%dB(%.2fB/pt)|projected3000Funds=%.2fMB|post5DailySyncs=%dB(delta=%dB)",
                    emptyBytes,
                    totalPointsAfterInitial,
                    postInitialBytes,
                    bytesPerPoint,
                    projected3000FundsMb,
                    postIncrementalBytes,
                    incrementalDeltaBytes
                )
            )

            // Verify high compression and real non-zero SQLite measurement:
            assertTrue("Expected real DB size > 50 KB, actual=$postInitialBytes", postInitialBytes > 50_000L)
            assertTrue("Expected < 6.0 bytes/point, actual=$bytesPerPoint", bytesPerPoint < 6.0)
            assertTrue("Expected incremental delta < 150 KB, actual=$incrementalDeltaBytes", incrementalDeltaBytes < 150_000L)
        } finally {
            fileDb.close()
            listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm")).forEach { if (it.exists()) it.delete() }
        }
    }

    @Test
    fun `incremental nav sync appends latest nav updates rankings and handles holidays gracefully`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val realDb = MfCompareDatabase.getDatabase(appContext)
        val dao = realDb.fundDao()
        val repo = FundCompareRepository(appContext)
        val now = System.currentTimeMillis()
        val dayNov17 = LocalDate.of(2026, 11, 17).toEpochDay()
        val dayNov23 = LocalDate.of(2026, 11, 23).toEpochDay()
        val dayNov24 = LocalDate.of(2026, 11, 24).toEpochDay()
        val day2025 = LocalDate.of(2025, 6, 10).toEpochDay()

        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            realDb.clearAllTables()
            val fund = FundMasterEntity(
                schemeCode = "122639",
                schemeName = "Parag Parikh Flexi Cap Fund - Direct Plan - Growth",
                searchTokens = DirectGrowthFilter.normalizeSearchTokens("Parag Parikh Flexi Cap Fund Direct Plan Growth"),
                amcName = "PPFAS Mutual Fund",
                category = "Flexi Cap Fund",
                isin = "INF879O01027",
                dedupKey = "ISIN:INF879O01027",
                isIndex = false,
                isActive = true,
                inceptionEpochDay = day2025,
                latestNav = 105.0,
                latestNavDateEpochDay = dayNov23,
                historySyncedAt = 1000L,
                dataSource = "MFAPI",
                lastSyncedAt = 1000L
            )
            dao.upsertFunds(listOf(fund))
            dao.upsertNavRecordsIncremental(
                fund.schemeId,
                listOf(
                    NavPoint(day2025, 85.0),
                    NavPoint(dayNov17, 100.0),
                    NavPoint(dayNov23, 105.0)
                )
            )
            val bucket2025Before = dao.getBucket(fund.schemeId, 2025)!!

            // New daily NAV on Nov 24 pushes 7D return from +5.0% to +12.0%
            val inserted = dao.applyDailyLatestNavBatch(
                listOf(DailyLatestNavUpdate("122639", dayNov24, 112.0)),
                nowMillis = now
            )
            assertEquals(1, inserted)

            // 2025 historical bucket remains bit-for-bit untouched
            val bucket2025After = dao.getBucket(fund.schemeId, 2025)!!
            assertTrue(bucket2025Before.payload.contentEquals(bucket2025After.payload))

            val updatedFund = dao.getFundByCode("122639")!!
            assertEquals(112.0, updatedFund.latestNav!!, 0.0001)
            assertEquals(dayNov24, updatedFund.latestNavDateEpochDay)

            val rankings = repo.computeRankingsForDays(days = 7, limit = 20)
            assertEquals(1, rankings.topPerformers.size)
            assertEquals(12.0, rankings.topPerformers.first().returnPercent, 0.05)

            // Weekend / market holiday sync with repeated date -> 0 inserts, 0 errors
            val holidayInsert = dao.applyDailyLatestNavBatch(
                listOf(DailyLatestNavUpdate("122639", dayNov24, 112.0)),
                nowMillis = now + 1000L
            )
            assertEquals(0, holidayInsert)
        }
    }

    @Test
    fun `scheme master reconciliation detects new schemes closures name changes and eligibility shifts`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val realDb = MfCompareDatabase.getDatabase(appContext)
        val dao = realDb.fundDao()
        val repo = FundCompareRepository(appContext)
        val t0 = 5000L
        val t1 = 10000L
        val day = LocalDate.of(2026, 11, 20).toEpochDay()

        fun makeFund(code: String, name: String, isin: String?, syncedAt: Long = 0L): FundMasterEntity =
            FundMasterEntity(
                schemeCode = code,
                schemeName = name,
                searchTokens = DirectGrowthFilter.normalizeSearchTokens(name),
                amcName = "Sample AMC",
                category = "Equity",
                isin = isin,
                dedupKey = DirectGrowthFilter.deduplicationKey(name, isin),
                isIndex = false,
                isActive = true,
                historySyncedAt = syncedAt,
                dataSource = "AMFI",
                lastSyncedAt = t0
            )

        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            realDb.clearAllTables()
            val schemeA = makeFund("100001", "Alpha Flexi Cap Fund - Direct Plan - Growth", "INF000000001", syncedAt = t0)
            val schemeB = makeFund("100002", "Beta Old Name Fund - Direct Plan - Growth", "INF000000002", syncedAt = t0)
            val schemeC = makeFund("100003", "Gamma Closing Fund - Direct Plan - Growth", "INF000000003", syncedAt = t0)
            val schemeD = makeFund("100004", "Delta Reclassified Fund - Direct Plan - Growth", "INF000000004", syncedAt = t0)
            dao.upsertFunds(listOf(schemeA, schemeB, schemeC, schemeD))
            dao.upsertNavRecordsIncremental(schemeA.schemeId, listOf(NavPoint(day, 100.0)))

            val incoming = listOf(
                schemeA, // Unchanged
                makeFund("100002", "Beta Renamed Large Cap Fund - Direct Plan - Growth", "INF000000002"), // Renamed
                makeFund("100004", "Delta Reclassified Fund - Direct Plan - IDCW", "INF000000004"), // Eligibility shifted out of Direct Growth
                // schemeC absent => closed/discontinued
                makeFund("155999", "Zeta Newly Launched NFO Fund - Direct Plan - Growth", "INF000000999") // Genuninely new scheme!
            )

            var historyFetchCount = 0
            val outcome = repo.reconcileSchemeCatalog(
                incomingFunds = incoming,
                authoritativeActiveFeed = true,
                nowMillis = t1,
                fetchNewSchemeHistories = true,
                closureMinCatalogSize = 2,
                newSchemeHistoryFetcher = { newFund ->
                    historyFetchCount++
                    assertEquals("155999", newFund.schemeCode)
                    listOf(
                        NavHistoryEntity(newFund.schemeCode, day - 5, 10.0, "MFAPI", t1),
                        NavHistoryEntity(newFund.schemeCode, day, 10.45, "MFAPI", t1)
                    )
                }
            )

            assertEquals(1, outcome.newSchemesDiscovered)
            assertEquals(1, outcome.newSchemeHistoriesSynced)
            assertEquals(1, historyFetchCount) // ONLY the new scheme fetched historical NAV!
            assertEquals(1, outcome.metadataUpdatedCount)
            assertEquals(2, outcome.markedInactiveCount) // C closed + D eligibility shifted

            val reloadedA = dao.getFundByCode("100001")!!
            assertEquals(t0, reloadedA.historySyncedAt) // Existing scheme history preserved untouched
            assertEquals(1, dao.getNavCount("100001"))

            val reloadedB = dao.getFundByCode("100002")!!
            assertEquals("Beta Renamed Large Cap Fund - Direct Plan - Growth", reloadedB.schemeName)
            assertTrue(reloadedB.isActive)

            val reloadedC = dao.getFundByCode("100003")!!
            assertFalse(reloadedC.isActive)
            assertEquals("Closed / Discontinued", reloadedC.statusNote)

            val reloadedD = dao.getFundByCode("100004")!!
            assertFalse(reloadedD.isActive)

            val reloadedE = dao.getFundByCode("155999")!!
            assertTrue(reloadedE.isActive)
            assertEquals(2, dao.getNavCount("155999"))
            assertEquals(10.45, reloadedE.latestNav!!, 0.001)
        }
    }

    @Test
    fun `four fund youngest inception comparison recalculates dynamically and dashboard opens cleanly`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val realDb = MfCompareDatabase.getDatabase(appContext)
        val dao = realDb.fundDao()
        val now = System.currentTimeMillis()

        val incA = LocalDate.of(2016, 5, 10).toEpochDay()
        val incB = LocalDate.of(2019, 3, 15).toEpochDay()
        val incC = LocalDate.of(2021, 8, 20).toEpochDay()
        val incD = LocalDate.of(2024, 2, 12).toEpochDay()
        val incE = LocalDate.of(2025, 1, 15).toEpochDay()
        val endDay = LocalDate.of(2026, 11, 20).toEpochDay()

        fun seedFund(code: String, name: String, inception: Long): Pair<FundMasterEntity, List<NavPoint>> {
            val fund = FundMasterEntity(
                schemeCode = code,
                schemeName = name,
                searchTokens = DirectGrowthFilter.normalizeSearchTokens(name),
                amcName = "Sample AMC",
                category = "Equity",
                isin = "INF123456$code",
                dedupKey = "ISIN:INF123456$code",
                isIndex = false,
                isActive = true,
                recordedInceptionEpochDay = inception,
                inceptionEpochDay = inception,
                latestNav = 160.0,
                latestNavDateEpochDay = endDay,
                historySyncedAt = now,
                dataSource = "MFAPI",
                lastSyncedAt = now
            )
            val mid2026 = LocalDate.of(2026, 5, 20).toEpochDay()
            val points = listOf(incA, incB, incC, incD, incE, mid2026, endDay)
                .filter { it >= inception }
                .mapIndexed { i, d -> NavPoint(d, 100.0 + i * 12.0) }
            return fund to points
        }

        val (fA, pA) = seedFund("111111", "Fund A 2016 - Direct Plan - Growth", incA)
        val (fB, pB) = seedFund("222222", "Fund B 2019 - Direct Plan - Growth", incB)
        val (fC, pC) = seedFund("333333", "Fund C 2021 - Direct Plan - Growth", incC)
        val (fD, pD) = seedFund("444444", "Fund D 2024 Youngest - Direct Plan - Growth", incD)
        val (fE, pE) = seedFund("555555", "Fund E 2025 Newest - Direct Plan - Growth", incE)

        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            realDb.clearAllTables()
            dao.upsertFunds(listOf(fA, fB, fC, fD, fE))
            dao.upsertNavRecordsIncremental(fA.schemeId, pA)
            dao.upsertNavRecordsIncremental(fB.schemeId, pB)
            dao.upsertNavRecordsIncremental(fC.schemeId, pC)
            dao.upsertNavRecordsIncremental(fD.schemeId, pD)
            dao.upsertNavRecordsIncremental(fE.schemeId, pE)
            dao.upsertSyncMetadata(SyncMetadataEntity(1, now, now, now, endDay, 1, 5, null))
        }

        val vm = MainViewModel(appContext as android.app.Application)
        composeRule.setContent {
            com.example.ui.theme.MfCompareTheme(darkTheme = true) {
                MfCompareApp(viewModel = vm, isDark = true)
            }
        }
        composeRule.waitForIdle()

        // Add 4 funds (A, B, C, D)
        vm.compareSchemesFromExternal(listOf("111111", "222222", "333333", "444444"))
        composeRule.waitForIdle()
        try {
            composeRule.waitUntil(timeoutMillis = 8000) {
                vm.comparisonSeries.value.size == 4
            }
        } catch (t: Throwable) {
            throw AssertionError(
                "4-fund series timeout: selected=${vm.selectedSchemeCodes.value}, " +
                    "catalog=${vm.catalogFlow.value.size}, series=${vm.comparisonSeries.value.size}, " +
                    "loading=${vm.isComparisonLoading.value}, youngest=${vm.youngestInceptionInfo.value}, sync=${vm.syncStatus.value}",
                t
            )
        }

        // Open FROM DATE picker & select "Since Youngest Fund Inception – 12 Feb 2024"
        composeRule.onNodeWithTag("from_date_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("since_youngest_inception_option")
            .assertIsDisplayed()
            .assertTextContains("Since Youngest Fund Inception – 12 Feb 2024", substring = true)
        composeRule.onNodeWithTag("since_youngest_inception_option").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("from_date_button")
            .assertTextContains("Since Youngest Fund Inception – 12 Feb 2024", substring = true)

        assertTrue(vm.comparisonSeries.value.all { it.actualStartEpochDay >= incD })

        // Remove youngest fund D (2024) -> automatically recalculates to C (20 Aug 2021)
        composeRule.onNodeWithTag("remove_chip_444444").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5000) {
            vm.youngestInceptionInfo.value?.effectiveInceptionEpochDay == incC
        }
        composeRule.onNodeWithTag("from_date_button")
            .assertTextContains("Since Youngest Fund Inception – 20 Aug 2021", substring = true)

        // Add newer fund E (2025) -> automatically updates to 15 Jan 2025
        vm.addSchemeToCompare("555555")
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5000) {
            vm.youngestInceptionInfo.value?.effectiveInceptionEpochDay == incE &&
                vm.comparisonSeries.value.any { it.schemeCode == "555555" }
        }
        composeRule.onNodeWithTag("from_date_button")
            .assertTextContains("Since Youngest Fund Inception – 15 Jan 2025", substring = true)
        assertTrue(vm.comparisonSeries.value.all { it.actualStartEpochDay >= incE })

        // Open compact Sync Status & Storage Dashboard modal sheet
        composeRule.onNodeWithTag("sync_dashboard_trigger").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sync_dashboard_sheet").assertIsDisplayed()
        composeRule.onNodeWithText("Current Database Size", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("dashboard_refresh_navs_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("dashboard_check_schemes_button").performScrollTo().assertIsDisplayed()
        vm.pauseUniverseHistorySync()
    }

    @Test
    fun `large catalog of 5000 direct growth schemes paginates cleanly without CursorWindow crash`() {
        val dao = db.fundDao()
        val now = System.currentTimeMillis()
        val largeFunds = (1..5000).map { i ->
            val code = (200000 + i).toString()
            val name = "Sample Mutual Fund Large & Midcap Direct Plan Growth Option Scheme Variant $i"
            FundMasterEntity(
                schemeCode = code,
                schemeId = 200000 + i,
                schemeName = name,
                searchTokens = DirectGrowthFilter.normalizeSearchTokens("$name Sample Mutual Fund Equity Scheme"),
                amcName = "Sample Mutual Fund Asset Management Pvt Ltd",
                category = "Equity Scheme - Large & Mid Cap Fund",
                isin = "INF888K0%05d".format(i),
                dedupKey = "ISIN:INF888K0%05d".format(i),
                isIndex = false,
                isActive = true,
                latestNav = 100.0 + (i % 50),
                latestNavDateEpochDay = LocalDate.of(2026, 11, 24).toEpochDay(),
                dataSource = "AMFI",
                lastSyncedAt = now
            )
        }

        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            largeFunds.chunked(400).forEach { dao.upsertFunds(it) }

            val observed = dao.observeAllFunds().first()
            assertEquals(5000, observed.size)

            val allActive = dao.getAllFunds()
            assertEquals(5000, allActive.size)

            val allInc = dao.getAllFundsIncludingInactive()
            assertEquals(5000, allInc.size)

            val byCodes = dao.getFundsByCodes(largeFunds.map { it.schemeCode })
            assertEquals(5000, byCodes.size)
        }
    }

    @Test
    fun `rankings category dropdown supports multi-ticking across groups and filters top and worst results`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val realDb = MfCompareDatabase.getDatabase(appContext)
        val dao = realDb.fundDao()
        val now = System.currentTimeMillis()
        val dayEnd = LocalDate.of(2026, 11, 20).toEpochDay()
        val dayStart = dayEnd - 7L

        fun mkFund(code: String, name: String, category: String) = FundMasterEntity(
            schemeCode = code,
            schemeName = name,
            searchTokens = DirectGrowthFilter.normalizeSearchTokens(name),
            amcName = "Sample AMC",
            category = category,
            isin = "INF777A0$code",
            dedupKey = "ISIN:INF777A0$code",
            isIndex = false,
            isActive = true,
            latestNav = 100.0,
            latestNavDateEpochDay = dayEnd,
            historySyncedAt = now,
            dataSource = "AMFI",
            lastSyncedAt = now
        )

        val smallCap = mkFund("300001", "Alpha Small Cap Fund - Direct Plan - Growth", "Equity Scheme - Small Cap Fund")
        val liquid = mkFund("300002", "Beta Liquid Fund - Direct Plan - Growth", "Debt Scheme - Liquid Fund")
        val indexFund = mkFund("300003", "Gamma Nifty 50 Index Fund - Direct Plan - Growth", "Other Scheme - Index Funds")

        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            realDb.clearAllTables()
            dao.upsertFunds(listOf(smallCap, liquid, indexFund))
            dao.upsertNavRecordsIncremental(smallCap.schemeId, listOf(NavPoint(dayStart, 100.0), NavPoint(dayEnd, 112.0)))
            dao.upsertNavRecordsIncremental(liquid.schemeId, listOf(NavPoint(dayStart, 100.0), NavPoint(dayEnd, 100.2)))
            dao.upsertNavRecordsIncremental(indexFund.schemeId, listOf(NavPoint(dayStart, 100.0), NavPoint(dayEnd, 105.0)))
            dao.upsertSyncMetadata(SyncMetadataEntity(1, now, now, now, dayEnd, 0, 3, null))
        }

        val vm = MainViewModel(appContext as android.app.Application)
        composeRule.setContent {
            com.example.ui.theme.MfCompareTheme(darkTheme = true) {
                MfCompareApp(viewModel = vm, isDark = true)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_tab_rankings").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5000) {
            vm.rankingsResult.value.evaluatedCount == 3
        }

        // Open Category Dropdown Sheet
        composeRule.onNodeWithTag("category_filter_dropdown_trigger").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("category_filter_sheet").assertIsDisplayed()

        // Tick Small Cap Fund (under expanded Equity group)
        composeRule.onNodeWithTag("category_filter_lazy_list")
            .performScrollToNode(hasTestTag("category_checkbox_row_EQ_SMALL_CAP"))
        composeRule.onNodeWithTag("category_checkbox_row_EQ_SMALL_CAP").performClick()

        // Search "Liquid" to auto-expand Debt and tick Liquid Fund too
        composeRule.onNodeWithTag("category_filter_search_input").performTextInput("Liquid")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("category_checkbox_row_DT_LIQUID").performClick()

        // Apply multi-selection (2 categories)
        composeRule.onNodeWithTag("category_filter_apply_button").performClick()
        composeRule.waitForIdle()

        composeRule.waitUntil(timeoutMillis = 5000) {
            vm.rankingsResult.value.evaluatedCount == 2
        }
        val filteredCodes = vm.rankingsResult.value.topPerformers.map { it.schemeCode }
        assertTrue(filteredCodes.contains("300001"))
        assertTrue(filteredCodes.contains("300002"))
        assertFalse(filteredCodes.contains("300003"))

        // Remove Liquid chip directly from active chips row -> only Small Cap remains
        composeRule.onNodeWithTag("selected_category_chip_DT_LIQUID").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5000) {
            vm.rankingsResult.value.evaluatedCount == 1
        }
        assertEquals(listOf("300001"), vm.rankingsResult.value.topPerformers.map { it.schemeCode })

        // Reset filter -> all 3 evaluated schemes restored
        composeRule.onNodeWithTag("category_filter_clear_button").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5000) {
            vm.rankingsResult.value.evaluatedCount == 3
        }
        vm.pauseUniverseHistorySync()
    }

    @Test
    fun `streaming parser and batched universe persistence complete cleanly without leaving stuck unsynced rows`() = runTest {
        val dao = db.fundDao()
        val now = System.currentTimeMillis()
        val d1 = LocalDate.of(2026, 11, 10).toEpochDay()
        val d2 = LocalDate.of(2026, 11, 20).toEpochDay()
        val d3 = LocalDate.of(2026, 11, 24).toEpochDay()

        val jsonValid = """
            {
              "meta": {
                "fund_house": "quant Mutual Fund",
                "scheme_category": "Equity Scheme - Small Cap Fund",
                "isin_growth": "INF966L01689"
              },
              "data": [
                {"date": "20-11-2026", "nav": "250.50"},
                {"date": "10-11-2026", "nav": "240.00"}
              ]
            }
        """.trimIndent()
        val parsedValid = com.example.data.RemoteDataSources.parseMfApiHistoryStreamFast(
            jsonValid.byteInputStream()
        ) as com.example.data.FastHistoryFetchOutcome.Success
        assertEquals("quant Mutual Fund", parsedValid.amcName)
        assertEquals(2, parsedValid.pointsAscending.size)
        assertEquals(d1, parsedValid.pointsAscending[0].dateEpochDay)
        assertEquals(d2, parsedValid.pointsAscending[1].dateEpochDay)

        val jsonEmpty = """{"meta": {"scheme_code": 999999}, "data": []}"""
        val parsedEmpty = com.example.data.RemoteDataSources.parseMfApiHistoryStreamFast(
            jsonEmpty.byteInputStream()
        )
        assertEquals(com.example.data.FastHistoryFetchOutcome.NoHistoryAvailable, parsedEmpty)

        val f1 = FundMasterEntity(
            schemeCode = "120828",
            schemeName = "quant Small Cap Fund - Direct Plan - Growth",
            searchTokens = "quant small cap direct growth",
            amcName = "Quant",
            category = "Direct Growth Mutual Fund",
            isin = null,
            dedupKey = "120828",
            isIndex = false,
            isActive = true,
            latestNav = 255.0,
            latestNavDateEpochDay = d3,
            dataSource = "AMFI",
            lastSyncedAt = now
        )
        val fEmpty = f1.copy(
            schemeCode = "999999",
            schemeId = 999999,
            schemeName = "Legacy Empty Scheme - Direct Plan - Growth",
            dedupKey = "999999"
        )
        dao.upsertFunds(listOf(f1, fEmpty))
        // Simulate pre-existing AMFI latest NAV point in 2026 before historical backfill runs
        dao.upsertNavRecordsIncremental(f1.schemeId, listOf(NavPoint(d3, 255.0)))
        assertEquals(2, dao.getUnsyncedMutualFundCount())

        val ptsByYear = com.example.data.NavBinaryCodec.partitionByYear(parsedValid.pointsAscending)
        val bucketsByYear = com.example.data.NavBinaryCodec.encodePointsIntoBuckets(
            f1.schemeId,
            parsedValid.pointsAscending
        )
        val prepared = com.example.data.PreparedSchemeHistory(
            fund = f1,
            bucketsByYear = bucketsByYear,
            pointsByYear = ptsByYear,
            inceptionEpochDay = d1,
            latestNav = 250.50,
            latestNavDateEpochDay = d2,
            return1M = null,
            return1Y = null,
            amcName = parsedValid.amcName,
            category = parsedValid.category,
            isin = parsedValid.isinGrowth,
            isActive = true,
            statusNote = null
        )

        dao.saveUniverseHistoryBatch(listOf(prepared), now)
        dao.markSchemesNoHistoryAvailable(listOf(fEmpty.schemeCode))

        // Counter hits 0 unsynced cleanly & merged pre-existing d3 AMFI NAV point is preserved!
        assertEquals(0, dao.getUnsyncedMutualFundCount())
        val reloadedPts = dao.getNavHistory("120828")
        assertEquals(3, reloadedPts.size)
        assertTrue(reloadedPts.any { it.dateEpochDay == d3 && it.navValue == 255.0 })

        // Verify ViewModel destruction (Back press / screen exit) does NOT set universePauseRequested
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val repo = FundCompareRepository.getInstance(appContext)
        repo.pauseUniverseHistorySync() // ensure clean state first
        val store = androidx.lifecycle.ViewModelStore()
        val provider = androidx.lifecycle.ViewModelProvider(
            store,
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(appContext as android.app.Application)
        )
        provider[MainViewModel::class.java]
        assertTrue(repo.isUniversePauseRequested()) // true from explicit pause above
        // Start / clear pause flag via simulate active flag check: clearing ViewModel must NOT re-trigger pause
        val field = FundCompareRepository::class.java.getDeclaredField("universePauseRequested")
        field.isAccessible = true
        (field.get(null) as java.util.concurrent.atomic.AtomicBoolean).set(false)
        store.clear()
        assertFalse(repo.isUniversePauseRequested())
    }

    @Test
    fun `sip analysis card opens sip page with independent fund tenures and renders graphical and tabular results`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val realDb = MfCompareDatabase.getDatabase(appContext)
        val dao = realDb.fundDao()
        val now = System.currentTimeMillis()
        val dStart = LocalDate.of(2025, 10, 1).toEpochDay()
        val dYoungest = LocalDate.of(2026, 1, 1).toEpochDay()
        val dEnd = LocalDate.of(2026, 4, 15).toEpochDay()

        fun buildSeries(baseDay: Long, endDay: Long, startNav: Double, step: Double): List<NavPoint> {
            val pts = ArrayList<NavPoint>()
            var d = baseDay
            var nav = startNav
            while (d <= endDay) {
                pts.add(NavPoint(d, nav))
                nav += step
                d += 3L
            }
            return pts
        }

        fun mkFund(code: String, name: String, inc: Long, latestNav: Double) = FundMasterEntity(
            schemeCode = code,
            schemeId = code.toInt(),
            schemeName = name,
            searchTokens = DirectGrowthFilter.normalizeSearchTokens(name),
            amcName = "Sample AMC",
            category = "Equity Scheme",
            isin = "INF999A$code",
            dedupKey = "ISIN:INF999A$code",
            isIndex = false,
            isActive = true,
            inceptionEpochDay = inc,
            recordedInceptionEpochDay = inc,
            latestNav = latestNav,
            latestNavDateEpochDay = dEnd,
            historySyncedAt = now,
            dataSource = "AMFI",
            lastSyncedAt = now
        )

        val pA = buildSeries(dStart, dEnd, 10.0, 0.08)
        val pB = buildSeries(dStart, dEnd, 20.0, 0.12)
        val pC = buildSeries(dYoungest, dEnd, 50.0, 0.25)

        val fA = mkFund("400001", "Fund Alpha Equity - Direct Plan - Growth", dStart, pA.last().navValue)
        val fB = mkFund("400002", "Fund Beta Hybrid - Direct Plan - Growth", dStart, pB.last().navValue)
        val fC = mkFund("400003", "Fund Gamma Index - Direct Plan - Growth", dYoungest, pC.last().navValue)

        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            realDb.clearAllTables()
            dao.upsertFunds(listOf(fA, fB, fC))
            dao.upsertNavRecordsIncremental(fA.schemeId, pA)
            dao.upsertNavRecordsIncremental(fB.schemeId, pB)
            dao.upsertNavRecordsIncremental(fC.schemeId, pC)
            dao.upsertSyncMetadata(SyncMetadataEntity(1, now, now, now, dEnd, 0, 3, null))
        }

        val vm = MainViewModel(appContext as android.app.Application)
        composeRule.setContent {
            com.example.ui.theme.MfCompareTheme(darkTheme = true) {
                MfCompareApp(viewModel = vm, isDark = true)
            }
        }
        composeRule.waitForIdle()

        vm.compareSchemesFromExternal(listOf("400001", "400002", "400003"))
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 8000) {
            vm.comparisonSeries.value.size == 3
        }

        // Scroll to Performance Summary & SIP Analysis card right underneath
        composeRule.onNodeWithTag("compare_lazy_column")
            .performScrollToNode(hasTestTag("sip_analysis_entry_card"))
        composeRule.onNodeWithTag("sip_analysis_entry_card").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // Landed on SIP Analysis page showing all 3 selected funds
        composeRule.onNodeWithTag("sip_analysis_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("sip_start_date_card").assertIsDisplayed()

        // Configure independently: Fund A = 100 Daily, Fund B = 1000 Weekly, Fund C = 1000 Monthly
        composeRule.onNodeWithTag("sip_analysis_lazy_column")
            .performScrollToNode(hasTestTag("sip_fund_config_400001"))
        composeRule.onNodeWithTag("sip_preset_400001_100").performClick()
        composeRule.onNodeWithTag("sip_tenure_400001_DAILY").performClick()

        composeRule.onNodeWithTag("sip_analysis_lazy_column")
            .performScrollToNode(hasTestTag("sip_fund_config_400002"))
        composeRule.onNodeWithTag("sip_preset_400002_1000").performClick()
        composeRule.onNodeWithTag("sip_tenure_400002_WEEKLY").performClick()

        composeRule.onNodeWithTag("sip_analysis_lazy_column")
            .performScrollToNode(hasTestTag("sip_fund_config_400003"))
        composeRule.onNodeWithTag("sip_preset_400003_1000").performClick()
        composeRule.onNodeWithTag("sip_tenure_400003_MONTHLY").performClick()

        composeRule.waitForIdle()
        val configs = vm.sipFundConfigs.value
        assertEquals(100.0, configs["400001"]!!.amount, 0.01)
        assertEquals(com.example.data.SipTenure.DAILY, configs["400001"]!!.tenure)
        assertEquals(1000.0, configs["400002"]!!.amount, 0.01)
        assertEquals(com.example.data.SipTenure.WEEKLY, configs["400002"]!!.tenure)
        assertEquals(1000.0, configs["400003"]!!.amount, 0.01)
        assertEquals(com.example.data.SipTenure.MONTHLY, configs["400003"]!!.tenure)

        // Click Compare -> scrolls to graphical & tabular comparison
        composeRule.onNodeWithTag("sip_analysis_lazy_column")
            .performScrollToNode(hasTestTag("sip_compare_button"))
        composeRule.onNodeWithTag("sip_compare_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("sip_analysis_lazy_column")
            .performScrollToNode(hasTestTag("sip_bar_comparison_card"))
        composeRule.onNodeWithTag("sip_bar_comparison_card").assertIsDisplayed()

        composeRule.onNodeWithTag("sip_analysis_lazy_column")
            .performScrollToNode(hasTestTag("sip_timeline_chart_card"))
        composeRule.onNodeWithTag("sip_timeline_chart_card").assertIsDisplayed()

        composeRule.onNodeWithTag("sip_analysis_lazy_column")
            .performScrollToNode(hasTestTag("sip_comparison_table_card"))
        composeRule.onNodeWithTag("sip_comparison_table_card").assertIsDisplayed()
        composeRule.onNodeWithTag("sip_table_row_400001").assertIsDisplayed()

        // Test From Inception toggle & Calendar picker dialog quick options
        composeRule.onNodeWithTag("sip_analysis_lazy_column")
            .performScrollToNode(hasTestTag("sip_start_date_card"))
        composeRule.onNodeWithTag("sip_mode_inception_chip").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5000) {
            vm.sipAnalysisResults.value.first { it.schemeCode == "400001" }.firstInstallmentEpochDay == dStart
        }

        composeRule.onNodeWithTag("sip_calendar_picker_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sip_dialog_youngest_option").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5000) {
            val resA = vm.sipAnalysisResults.value.first { it.schemeCode == "400001" }
            vm.sipStartMode.value == com.example.data.SipStartMode.YOUNGEST_INCEPTION &&
                (resA.firstInstallmentEpochDay ?: 0L) >= dYoungest
        }

        // Back button returns cleanly to home CompareScreen
        composeRule.onNodeWithTag("sip_back_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("compare_lazy_column").assertIsDisplayed()
        vm.pauseUniverseHistorySync()
    }

    @Test
    fun `rolling return analysis card under sip opens rolling screen with all 5 timeframes and start date options`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val realDb = MfCompareDatabase.getDatabase(appContext)
        val dao = realDb.fundDao()
        val now = System.currentTimeMillis()
        val dStart = LocalDate.of(2024, 1, 1).toEpochDay()
        val dYoungest = LocalDate.of(2024, 6, 1).toEpochDay()
        val dEnd = LocalDate.of(2026, 4, 15).toEpochDay()

        fun buildDailySeries(baseDay: Long, endDay: Long, startNav: Double, step: Double): List<NavPoint> {
            val pts = ArrayList<NavPoint>()
            var d = baseDay
            var nav = startNav
            while (d <= endDay) {
                pts.add(NavPoint(d, nav))
                nav += step
                d += 2L
            }
            return pts
        }

        fun mkFund(code: String, name: String, inc: Long, latestNav: Double) = FundMasterEntity(
            schemeCode = code,
            schemeId = code.toInt(),
            schemeName = name,
            searchTokens = DirectGrowthFilter.normalizeSearchTokens(name),
            amcName = "Sample AMC",
            category = "Equity Scheme",
            isin = "INF888R$code",
            dedupKey = "ISIN:INF888R$code",
            isIndex = false,
            isActive = true,
            inceptionEpochDay = inc,
            recordedInceptionEpochDay = inc,
            latestNav = latestNav,
            latestNavDateEpochDay = dEnd,
            historySyncedAt = now,
            dataSource = "AMFI",
            lastSyncedAt = now
        )

        val pA = buildDailySeries(dStart, dEnd, 100.0, 0.12)
        val pB = buildDailySeries(dYoungest, dEnd, 50.0, 0.08)

        val fA = mkFund("500001", "Rolling Alpha Equity - Direct Plan - Growth", dStart, pA.last().navValue)
        val fB = mkFund("500002", "Rolling Beta Flexi - Direct Plan - Growth", dYoungest, pB.last().navValue)

        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            realDb.clearAllTables()
            dao.upsertFunds(listOf(fA, fB))
            dao.upsertNavRecordsIncremental(fA.schemeId, pA)
            dao.upsertNavRecordsIncremental(fB.schemeId, pB)
            dao.upsertSyncMetadata(SyncMetadataEntity(1, now, now, now, dEnd, 0, 2, null))
        }

        val vm = MainViewModel(appContext as android.app.Application)
        composeRule.setContent {
            com.example.ui.theme.MfCompareTheme(darkTheme = true) {
                MfCompareApp(viewModel = vm, isDark = true)
            }
        }
        composeRule.waitForIdle()

        vm.compareSchemesFromExternal(listOf("500001", "500002"))
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 8000) {
            vm.comparisonSeries.value.size == 2
        }

        // Verify Rolling Return Analysis card sits under SIP Analysis and opens dedicated screen
        composeRule.onNodeWithTag("compare_lazy_column")
            .performScrollToNode(hasTestTag("rolling_return_analysis_entry_card"))
        composeRule.onNodeWithTag("rolling_return_analysis_entry_card").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("rolling_return_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("rolling_start_date_card").assertIsDisplayed()
        composeRule.onNodeWithTag("rolling_timeframe_card").assertIsDisplayed()

        // Switch through all 5 timeframes: Weekly, Biweekly, Monthly, Quarterly, Yearly
        com.example.data.RollingWindow.entries.forEach { window ->
            composeRule.onNodeWithTag("rolling_window_${window.name}").performScrollTo().performClick()
            composeRule.waitForIdle()
            composeRule.waitUntil(timeoutMillis = 5000) {
                vm.rollingWindow.value == window &&
                    vm.rollingReturnResults.value.size == 2 &&
                    vm.rollingReturnResults.value.all { it.isValid && it.window == window }
            }
        }

        // Verify Graphical & Tabular components render
        composeRule.onNodeWithTag("rolling_return_lazy_column")
            .performScrollToNode(hasTestTag("rolling_range_chart_card"))
        composeRule.onNodeWithTag("rolling_range_chart_card").assertIsDisplayed()

        composeRule.onNodeWithTag("rolling_return_lazy_column")
            .performScrollToNode(hasTestTag("rolling_timeline_chart_card"))
        composeRule.onNodeWithTag("rolling_timeline_chart_card").assertIsDisplayed()

        composeRule.onNodeWithTag("rolling_return_lazy_column")
            .performScrollToNode(hasTestTag("rolling_comparison_table_card"))
        composeRule.onNodeWithTag("rolling_comparison_table_card").assertIsDisplayed()
        composeRule.onNodeWithTag("rolling_table_row_500001").assertIsDisplayed()

        // Switch Start Date to From Inception and back to Youngest Inception via Calendar Dialog
        composeRule.onNodeWithTag("rolling_return_lazy_column")
            .performScrollToNode(hasTestTag("rolling_start_date_card"))
        composeRule.onNodeWithTag("rolling_mode_inception_chip").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5000) {
            vm.rollingStartMode.value == com.example.data.RollingStartMode.FROM_INCEPTION
        }

        composeRule.onNodeWithTag("rolling_calendar_picker_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rolling_dialog_youngest_option").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5000) {
            vm.rollingStartMode.value == com.example.data.RollingStartMode.YOUNGEST_INCEPTION
        }

        // Back button returns cleanly to home CompareScreen
        composeRule.onNodeWithTag("rolling_back_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("compare_lazy_column").assertIsDisplayed()
        vm.pauseUniverseHistorySync()
    }

    @Test
    fun `rankings category dropdown displays 6th sif group filters long-short schemes cleanly`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val realDb = MfCompareDatabase.getDatabase(appContext)
        val dao = realDb.fundDao()
        val now = System.currentTimeMillis()
        val dayEnd = LocalDate.of(2026, 11, 24).toEpochDay()
        val dayStart = dayEnd - 7L

        fun mkFund(code: String, name: String, category: String, source: String) = FundMasterEntity(
            schemeCode = code,
            schemeName = name,
            searchTokens = DirectGrowthFilter.normalizeSearchTokens("$name $category"),
            amcName = "Sample AMC",
            category = category,
            isin = "INF666S0$code",
            dedupKey = "ISIN:INF666S0$code",
            isIndex = false,
            isActive = true,
            latestNav = 110.0,
            latestNavDateEpochDay = dayEnd,
            historySyncedAt = now,
            dataSource = source,
            lastSyncedAt = now
        )

        val sifEquity = mkFund(
            "600001",
            "qsif Equity Long-Short Fund - Direct Plan - Growth",
            "SIF - Equity Long-Short Fund",
            com.example.data.RemoteDataSources.DATA_SOURCE_SIF
        )
        val sifHybrid = mkFund(
            "600002",
            "Edelweiss Hybrid Long-Short Fund - Direct Plan - Growth",
            "SIF - Hybrid Long-Short Fund",
            com.example.data.RemoteDataSources.DATA_SOURCE_SIF
        )
        val regularSectoralDebtMf = mkFund(
            "600003",
            "SBI Magnum Sectoral Debt Fund - Direct Plan - Growth",
            "Debt Scheme - Sectoral Debt Fund",
            "AMFI"
        )

        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            realDb.clearAllTables()
            dao.upsertFunds(listOf(sifEquity, sifHybrid, regularSectoralDebtMf))
            dao.upsertNavRecordsIncremental(sifEquity.schemeId, listOf(NavPoint(dayStart, 100.0), NavPoint(dayEnd, 114.0)))
            dao.upsertNavRecordsIncremental(sifHybrid.schemeId, listOf(NavPoint(dayStart, 100.0), NavPoint(dayEnd, 106.0)))
            dao.upsertNavRecordsIncremental(regularSectoralDebtMf.schemeId, listOf(NavPoint(dayStart, 100.0), NavPoint(dayEnd, 101.0)))
            dao.upsertSyncMetadata(SyncMetadataEntity(1, now, now, now, dayEnd, 0, 3, null))
        }

        val vm = MainViewModel(appContext as android.app.Application)
        composeRule.setContent {
            com.example.ui.theme.MfCompareTheme(darkTheme = true) {
                MfCompareApp(viewModel = vm, isDark = true)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_tab_rankings").performClick()
        composeRule.waitForIdle()
        try {
            composeRule.waitUntil(timeoutMillis = 5000) {
                vm.rankingsResult.value.evaluatedCount == 3
            }
        } catch (t: Throwable) {
            val allInDb = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { dao.getAllFunds() }
            val bucketsInDb = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { dao.getBucketsFromYear(2026) }
            throw AssertionError(
                "SIF rankings timeout: tab=${vm.selectedTab.value}, loading=${vm.isRankingsLoading.value}, " +
                    "result=${vm.rankingsResult.value}, sync=${vm.syncStatus.value}, " +
                    "dbFunds=${allInDb.size}, dbBuckets=${bucketsInDb.size}",
                t
            )
        }

        // Open category sheet & verify 6. SIF Schemes (7) group header exists
        composeRule.onNodeWithTag("category_filter_dropdown_trigger").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("category_filter_lazy_list")
            .performScrollToNode(hasTestTag("category_group_header_SIF"))
        composeRule.onNodeWithTag("category_group_header_SIF").assertIsDisplayed()

        // Search "Long-Short" -> auto-expands SIF categories with strategy subtitles
        composeRule.onNodeWithTag("category_filter_search_input").performTextInput("Long-Short")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("category_filter_lazy_list")
            .performScrollToNode(hasTestTag("category_checkbox_row_SIF_EQUITY_LONG_SHORT"))
        composeRule.onNodeWithTag("category_checkbox_row_SIF_EQUITY_LONG_SHORT").performClick()
        composeRule.onNodeWithTag("category_filter_lazy_list")
            .performScrollToNode(hasTestTag("category_checkbox_row_SIF_HYBRID_LONG_SHORT"))
        composeRule.onNodeWithTag("category_checkbox_row_SIF_HYBRID_LONG_SHORT").performClick()

        composeRule.onNodeWithTag("category_filter_apply_button").performClick()
        composeRule.waitForIdle()

        composeRule.waitUntil(timeoutMillis = 5000) {
            vm.rankingsResult.value.evaluatedCount == 2
        }
        val filteredCodes = vm.rankingsResult.value.topPerformers.map { it.schemeCode }
        assertTrue(filteredCodes.contains("600001"))
        assertTrue(filteredCodes.contains("600002"))
        assertFalse(filteredCodes.contains("600003"))
        vm.pauseUniverseHistorySync()
    }

    @Test
    fun `seamless sif and nfo incremental sync pipeline seeds once updates via latest only and discovers new nfos`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val realDb = MfCompareDatabase.getDatabase(appContext)
        val dao = realDb.fundDao()
        val repo = FundCompareRepository(appContext)
        val cachePrefs = appContext.getSharedPreferences("tigzig_http_cache", Context.MODE_PRIVATE)

        val dHist1 = LocalDate.of(2025, 11, 20).toEpochDay()
        val dHist2 = LocalDate.of(2026, 11, 20).toEpochDay()
        val dLatest1 = LocalDate.of(2026, 11, 24).toEpochDay()
        val dLatest2 = LocalDate.of(2026, 11, 25).toEpochDay()
        val dLatest3 = LocalDate.of(2026, 11, 26).toEpochDay()

        val initialCsv = """
            scheme_code,scheme_name,isin,date,nav,amc_name,category
            SIF_EQ_1,"qsif Equity Long-Short Fund - Direct Plan - Growth",INF966L30076,2025-11-20,10.00,quant SIF,Equity Long-Short Fund
            SIF_EQ_1,"qsif Equity Long-Short Fund - Direct Plan - Growth",INF966L30076,2026-11-20,12.00,quant SIF,Equity Long-Short Fund
        """.trimIndent()

        val csvWithNewNfo = """
            scheme_code,scheme_name,isin,date,nav,amc_name,category
            SIF_EQ_1,"qsif Equity Long-Short Fund - Direct Plan - Growth",INF966L30076,2025-11-20,10.00,quant SIF,Equity Long-Short Fund
            SIF_EQ_1,"qsif Equity Long-Short Fund - Direct Plan - Growth",INF966L30076,2026-11-20,12.00,quant SIF,Equity Long-Short Fund
            SIF_NFO_2,"Edelweiss Active Asset Allocator Long-Short Fund - Direct Plan - Growth",INF754K30011,2026-11-24,10.05,Edelweiss SIF,Active Asset Allocator Long-Short Fund
            SIF_NFO_2,"Edelweiss Active Asset Allocator Long-Short Fund - Direct Plan - Growth",INF754K30011,2026-11-25,10.12,Edelweiss SIF,Active Asset Allocator Long-Short Fund
        """.trimIndent()

        fun latestCsvForDay(dayStr: String, mfNav: Double, sifNav: Double, includeNfos: Boolean): String = buildString {
            appendLine("scheme_code,scheme_name,isin_growth,nav,date,amc,category,source")
            appendLine("120828,\"quant Small Cap Fund - Direct Plan - Growth\",INF966L01689,$mfNav,$dayStr,quant Mutual Fund,Small Cap Fund,MF")
            appendLine("SIF_EQ_1,\"qsif Equity Long-Short Fund - Direct Plan - Growth\",INF966L30076,$sifNav,$dayStr,quant SIF,Equity Long-Short Fund,SIF")
            if (includeNfos) {
                appendLine("155888,\"PPFAS Dynamic Asset NFO - Direct Plan - Growth\",INF879O01999,10.30,$dayStr,PPFAS Mutual Fund,Dynamic Asset Allocation Fund,MF")
                appendLine("SIF_NFO_2,\"Edelweiss Active Asset Allocator Long-Short Fund - Direct Plan - Growth\",INF754K30011,10.22,$dayStr,Edelweiss SIF,Active Asset Allocator Long-Short Fund,SIF")
            }
        }

        var csvFetchCount = 0
        var latestFetchCount = 0
        var currentLatestPayload = latestCsvForDay("2026-11-24", mfNav = 250.0, sifNav = 12.15, includeNfos = false)
        var currentCsvPayload = initialCsv

        // Simulate official AMFI returning 502 Bad Gateway while Tigzig + MFAPI work seamlessly
        repo.amfiFeedFetcher = { throw IllegalStateException("AMFI HTTP 502 Bad Gateway") }
        repo.mfApiCatalogFetcher = { now ->
            listOf(
                FundMasterEntity(
                    schemeCode = "120828",
                    schemeName = "quant Small Cap Fund - Direct Plan - Growth",
                    searchTokens = DirectGrowthFilter.normalizeSearchTokens("quant Small Cap Fund Direct Plan Growth"),
                    amcName = "Mutual Fund",
                    category = "Direct Growth Mutual Fund",
                    isin = "INF966L01689",
                    dedupKey = "ISIN:INF966L01689",
                    isIndex = false,
                    isActive = true,
                    dataSource = "MFAPI",
                    lastSyncedAt = now
                )
            )
        }
        repo.mfApiHistoryRecordsFetcher = { code, now ->
            com.example.data.MfApiSchemeHistoryResult(
                amcName = "PPFAS Mutual Fund",
                category = "Hybrid Scheme - Dynamic Asset Allocation",
                isinGrowth = "INF879O01999",
                records = listOf(
                    NavHistoryEntity(code, dLatest2, 10.00, "MFAPI", now),
                    NavHistoryEntity(code, dLatest3, 10.30, "MFAPI", now)
                )
            )
        }
        repo.indexHistoryFetcher = { _, _, _ -> emptyList() }
        repo.tigzigSifCsvFetcher = { now, _, _ ->
            csvFetchCount++
            com.example.data.RemoteDataSources.parseTigzigSifHistoryCsv(currentCsvPayload, nowMillis = now)
        }
        repo.tigzigLatestFetcher = { now, _, _ ->
            latestFetchCount++
            com.example.data.RemoteDataSources.parseTigzigLatestText(currentLatestPayload, nowMillis = now)
        }

        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            realDb.clearAllTables()
            cachePrefs.edit().clear().commit()

            // 1. Initial sync: downloads sif_nav.csv ONCE and succeeds even when AMFI returns 502!
            val res1 = repo.refreshCatalogAndIndices(force = true)
            assertTrue(res1.isSuccess)
            assertEquals(1, csvFetchCount)
            assertEquals(1, latestFetchCount)

            val sifAfterSeed = dao.getFundByCode("SIF_EQ_1")!!
            assertEquals(com.example.data.RemoteDataSources.DATA_SOURCE_SIF, sifAfterSeed.dataSource)
            assertEquals("SIF - Equity Long-Short Fund", sifAfterSeed.category)
            assertEquals(12.15, sifAfterSeed.latestNav!!, 0.001)
            assertEquals(dLatest1, sifAfterSeed.latestNavDateEpochDay)
            assertEquals(3, dao.getNavCount("SIF_EQ_1")) // dHist1, dHist2, dLatest1
            val sif2025BucketBefore = dao.getBucket(sifAfterSeed.schemeId, 2025)!!

            // 2. Subsequent daily latest NAV sync: MUST NOT re-download sif_nav.csv!
            currentLatestPayload = latestCsvForDay("2026-11-25", mfNav = 252.5, sifNav = 12.40, includeNfos = false)
            val res2 = repo.syncLatestNavs(force = true)
            assertTrue(res2.isSuccess)
            assertEquals(1, csvFetchCount) // ZERO extra CSV downloads!
            assertEquals(2, latestFetchCount)

            val sifAfterLatest = dao.getFundByCode("SIF_EQ_1")!!
            assertEquals(12.40, sifAfterLatest.latestNav!!, 0.001)
            assertEquals(dLatest2, sifAfterLatest.latestNavDateEpochDay)
            assertEquals(4, dao.getNavCount("SIF_EQ_1"))
            val sif2025BucketAfter = dao.getBucket(sifAfterSeed.schemeId, 2025)!!
            assertTrue(sif2025BucketBefore.payload.contentEquals(sif2025BucketAfter.payload))

            // 3. New MF NFO + New SIF NFO appear in format=latest -> auto-discovered & hydrated incrementally!
            currentCsvPayload = csvWithNewNfo
            currentLatestPayload = latestCsvForDay("2026-11-26", mfNav = 254.0, sifNav = 12.55, includeNfos = true)
            val res3 = repo.syncLatestNavs(force = true)
            assertTrue(res3.isSuccess)
            assertEquals(2, csvFetchCount) // Fetched CSV ONLY because new unsynced SIF NFO appeared!

            val newSifNfo = dao.getFundByCode("SIF_NFO_2")!!
            assertTrue(newSifNfo.isActive)
            assertEquals("SIF - Active Asset Allocator Long-Short Fund", newSifNfo.category)
            assertEquals(10.22, newSifNfo.latestNav!!, 0.001)
            assertEquals(dLatest3, newSifNfo.latestNavDateEpochDay)
            assertEquals(3, dao.getNavCount("SIF_NFO_2")) // dLatest1 (10.05), dLatest2 (10.12), dLatest3 (10.22)

            val newMfNfo = dao.getFundByCode("155888")!!
            assertTrue(newMfNfo.isActive)
            assertEquals(10.30, newMfNfo.latestNav!!, 0.001)
            assertEquals(2, dao.getNavCount("155888"))

            // Existing SIF history stayed intact + updated cleanly to dLatest3
            assertEquals(5, dao.getNavCount("SIF_EQ_1"))
            assertEquals(12.55, dao.getFundByCode("SIF_EQ_1")!!.latestNav!!, 0.001)
        }
    }

    @Test
    fun `sip rankings and rolling return rankings cards open dedicated screens with filters and return via back`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val realDb = MfCompareDatabase.getDatabase(appContext)
        val dao = realDb.fundDao()
        val now = System.currentTimeMillis()
        val dStart = LocalDate.of(2025, 1, 1).toEpochDay()
        val dMid = LocalDate.of(2025, 6, 1).toEpochDay()
        val dEnd = LocalDate.of(2026, 1, 1).toEpochDay()

        fun makeFund(code: String, name: String) = FundMasterEntity(
            schemeCode = code,
            schemeName = name,
            searchTokens = DirectGrowthFilter.normalizeSearchTokens(name),
            amcName = "Test AMC",
            category = "Flexi Cap Fund",
            isin = "INF00$code",
            dedupKey = "ISIN:INF00$code",
            isIndex = false,
            isActive = true,
            latestNav = 150.0,
            latestNavDateEpochDay = dEnd,
            historySyncedAt = now,
            dataSource = "MFAPI",
            lastSyncedAt = now
        )

        val fund1 = makeFund("700001", "Fund 1 Direct Plan Growth")
        val fund2 = makeFund("700002", "Fund 2 Direct Plan Growth")

        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            realDb.clearAllTables()
            dao.upsertFunds(listOf(fund1, fund2))
            dao.upsertNavRecordsIncremental(fund1.schemeId, listOf(NavPoint(dStart, 100.0), NavPoint(dMid, 120.0), NavPoint(dEnd, 150.0)))
            dao.upsertNavRecordsIncremental(fund2.schemeId, listOf(NavPoint(dStart, 100.0), NavPoint(dMid, 110.0), NavPoint(dEnd, 130.0)))
            dao.upsertSyncMetadata(SyncMetadataEntity(1, now, now, now, dEnd, 0, 2, null))
        }

        val vm = MainViewModel(appContext as android.app.Application)
        composeRule.setContent {
            com.example.ui.theme.MfCompareTheme(darkTheme = true) {
                MfCompareApp(viewModel = vm, isDark = true)
            }
        }
        composeRule.waitForIdle()

        // 1. In Empty Compare Screen, verify SIP Rankings entry card exists and click it
        composeRule.onNodeWithTag("compare_lazy_column")
            .performScrollToNode(hasTestTag("sip_rankings_entry_card"))
        composeRule.onNodeWithTag("sip_rankings_entry_card").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // Verify Sip Rankings screen is displayed with its controls
        composeRule.onNodeWithTag("sip_rankings_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("sip_rankings_lazy_column")
            .performScrollToNode(hasTestTag("sip_rankings_tenure_MONTHLY"))
        composeRule.onNodeWithTag("sip_rankings_tenure_MONTHLY").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("sip_rankings_lazy_column")
            .performScrollToNode(hasTestTag("sip_rankings_period_12m"))
        composeRule.onNodeWithTag("sip_rankings_period_12m").assertIsDisplayed()

        // Back button returns to CompareScreen
        composeRule.onNodeWithTag("sip_rankings_back_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("compare_lazy_column").assertIsDisplayed()

        // 2. Open Rolling Return Rankings card and verify screen
        composeRule.onNodeWithTag("compare_lazy_column")
            .performScrollToNode(hasTestTag("rolling_return_rankings_entry_card"))
        composeRule.onNodeWithTag("rolling_return_rankings_entry_card").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // Verify Rolling Return Rankings screen is displayed with its 5 window chips
        composeRule.onNodeWithTag("rolling_return_rankings_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("rolling_return_rankings_lazy_column")
            .performScrollToNode(hasTestTag("rolling_window_chip_weekly"))
        composeRule.onNodeWithTag("rolling_window_chip_weekly").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(com.example.data.RollingWindow.WEEKLY, vm.rollingRankingWindow.value)

        // Back button returns to CompareScreen
        composeRule.onNodeWithTag("rolling_rankings_back_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("compare_lazy_column").assertIsDisplayed()

        vm.pauseUniverseHistorySync()
    }

    @Test
    fun `stale tigzig snapshot with amfi 502 triggers live batch nav updates and header excludes index date`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val realDb = MfCompareDatabase.getDatabase(appContext)
        val dao = realDb.fundDao()
        val repo = FundCompareRepository(appContext)
        val cachePrefs = appContext.getSharedPreferences("tigzig_http_cache", Context.MODE_PRIVATE)

        val dayNov23 = LocalDate.of(2026, 11, 23).toEpochDay()
        val dayNov25 = LocalDate.of(2026, 11, 25).toEpochDay()
        // Simulate 26 Nov 2026 00:15 IST (= 25 Nov 2026 18:45 UTC)
        val nowNov26Ist0015 = LocalDate.of(2026, 11, 26)
            .atTime(0, 15)
            .atZone(java.time.ZoneId.of("Asia/Kolkata"))
            .toInstant()
            .toEpochMilli()

        val idxNifty = FundMasterEntity(
            schemeCode = "IDX_NIFTY50",
            schemeId = FundMasterEntity.deriveSchemeId("IDX_NIFTY50"),
            schemeName = "Nifty 50",
            searchTokens = "nifty 50",
            amcName = "Market Index",
            category = "Broad Market Index",
            isin = null,
            dedupKey = "INDEX:IDX_NIFTY50",
            isIndex = true,
            isActive = true,
            latestNav = 25800.0,
            latestNavDateEpochDay = dayNov25,
            historySyncedAt = nowNov26Ist0015,
            dataSource = "YAHOO_FINANCE",
            lastSyncedAt = nowNov26Ist0015
        )
        val mfFav = FundMasterEntity(
            schemeCode = "153705",
            schemeName = "quant Equity Savings Fund - Growth Option - Direct Plan",
            searchTokens = DirectGrowthFilter.normalizeSearchTokens("quant Equity Savings Fund Direct Plan Growth"),
            amcName = "quant Mutual Fund",
            category = "Hybrid Scheme - Equity Savings Fund",
            isin = "INF966L01EC8",
            dedupKey = "ISIN:INF966L01EC8",
            isIndex = false,
            isActive = true,
            latestNav = 11.14,
            latestNavDateEpochDay = dayNov23,
            historySyncedAt = nowNov26Ist0015,
            dataSource = "MFAPI",
            lastSyncedAt = nowNov26Ist0015
        )
        val mfSelected = mfFav.copy(
            schemeCode = "120819",
            schemeId = 120819,
            schemeName = "quant Aggressive Hybrid Fund - Growth Option - Direct Plan",
            isin = "INF966L01556",
            dedupKey = "ISIN:INF966L01556",
            latestNav = 517.81
        )
        val sifFav = mfFav.copy(
            schemeCode = "9",
            schemeId = 9,
            schemeName = "Altiva Hybrid Long-Short Fund - Direct Plan - Growth",
            amcName = "Altiva SIF",
            category = "SIF - Hybrid Long-Short Fund",
            isin = "INF754K30011",
            dedupKey = "ISIN:INF754K30011",
            latestNav = 11.13,
            dataSource = com.example.data.RemoteDataSources.DATA_SOURCE_SIF
        )

        var batchCalled = false
        val staleSnapshotCsv = """
            scheme_code,scheme_name,isin_growth,nav,date,amc,category,source
            153705,"quant Equity Savings Fund - Growth Option - Direct Plan",INF966L01EC8,11.14,2026-11-23,quant Mutual Fund,Equity Savings Fund,MF
            120819,"quant Aggressive Hybrid Fund - Growth Option - Direct Plan",INF966L01556,517.81,2026-11-23,quant Mutual Fund,Aggressive Hybrid Fund,MF
            9,"Altiva Hybrid Long-Short Fund - Direct Plan - Growth",INF754K30011,11.13,2026-11-23,Altiva SIF,Hybrid Long-Short Fund,SIF
        """.trimIndent()

        repo.amfiFeedFetcher = { throw IllegalStateException("AMFI HTTP 502 Bad Gateway") }
        repo.tigzigLatestFetcher = { now, _, _ ->
            com.example.data.RemoteDataSources.parseTigzigLatestText(staleSnapshotCsv, nowMillis = now)
        }
        repo.tigzigBatchLatestFetcher = { funds, _ ->
            batchCalled = true
            funds.mapNotNull { f ->
                when (f.schemeCode) {
                    "153705" -> DailyLatestNavUpdate("153705", dayNov25, 11.2186)
                    "120819" -> DailyLatestNavUpdate("120819", dayNov25, 522.7593)
                    "9" -> DailyLatestNavUpdate("9", dayNov23, 11.1261)
                    else -> null
                }
            }
        }
        repo.indexHistoryFetcher = { _, _, _ -> emptyList() }
        repo.mfApiCatalogFetcher = { emptyList() }

        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            realDb.clearAllTables()
            cachePrefs.edit().clear().commit()
            dao.upsertFunds(listOf(idxNifty, mfFav, mfSelected, sifFav))
            dao.upsertNavRecordsIncremental(idxNifty.schemeId, listOf(NavPoint(dayNov23, 25600.0), NavPoint(dayNov25, 25800.0)))
            dao.upsertNavRecordsIncremental(mfFav.schemeId, listOf(NavPoint(dayNov23, 11.14)))
            dao.upsertNavRecordsIncremental(mfSelected.schemeId, listOf(NavPoint(dayNov23, 517.81)))
            dao.upsertNavRecordsIncremental(sifFav.schemeId, listOf(NavPoint(dayNov23, 11.13)))
            dao.addFavourite(FavouriteFundEntity("153705", 1000L))
            dao.addFavourite(FavouriteFundEntity("9", 1001L))

            // Even though Nifty index is on 25 Nov, MF/SIF global max NAV date must report 23 Nov!
            assertEquals(dayNov23.toInt(), dao.getGlobalMaxNavEpochDay())

            // Run incremental NAV sync while AMFI returns 502 and Tigzig snapshot returns stale 23 Nov
            val res = repo.syncLatestNavs(force = true, schemeCodesHint = listOf("120819"))
            assertTrue(res.isSuccess)
            assertTrue("Expected live Tigzig batch endpoint to fire when snapshot was stale on 23 Nov", batchCalled)

            val updated153705 = dao.getFundByCode("153705")!!
            assertEquals(dayNov25, updated153705.latestNavDateEpochDay)
            assertEquals(11.2186, updated153705.latestNav!!, 0.0001)

            val updated120819 = dao.getFundByCode("120819")!!
            assertEquals(dayNov25, updated120819.latestNavDateEpochDay)
            assertEquals(522.7593, updated120819.latestNav!!, 0.0001)

            assertEquals(dayNov25.toInt(), dao.getGlobalMaxNavEpochDay())
        }
    }
}
