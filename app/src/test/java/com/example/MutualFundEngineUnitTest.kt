package com.example

import com.example.data.ComparePeriod
import com.example.data.DirectGrowthFilter
import com.example.data.FundMasterEntity
import com.example.data.FundSearchEngine
import com.example.data.NavPoint
import com.example.data.PerformanceCalculator
import com.example.data.RemoteDataSources
import com.example.ui.theme.ChartSeriesPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MutualFundEngineUnitTest {

    @Test
    fun `direct growth filter accepts direct growth and rejects regular or idcw`() {
        assertTrue(DirectGrowthFilter.isEligibleDirectGrowthScheme("Quant Small Cap Fund - Direct Plan - Growth"))
        assertTrue(DirectGrowthFilter.isEligibleDirectGrowthScheme("Parag Parikh Flexi Cap Fund - Direct Plan - Growth Option"))
        assertTrue(DirectGrowthFilter.isEligibleDirectGrowthScheme("HDFC Mid-Cap Opportunities Fund - Growth Option - Direct Plan"))
        assertTrue(DirectGrowthFilter.isEligibleDirectGrowthScheme("SBI Nifty Index Fund - Dir Plan - Gr"))

        assertFalse(DirectGrowthFilter.isEligibleDirectGrowthScheme("Quant Small Cap Fund - Regular Plan - Growth"))
        assertFalse(DirectGrowthFilter.isEligibleDirectGrowthScheme("Quant Small Cap Fund - Direct Plan - IDCW"))
        assertFalse(DirectGrowthFilter.isEligibleDirectGrowthScheme("HDFC Mid-Cap Opportunities Fund - Direct Plan - Dividend Payout"))
        assertFalse(DirectGrowthFilter.isEligibleDirectGrowthScheme("Nippon India Small Cap Fund - Direct Plan - Dividend Reinvestment"))
        assertFalse(DirectGrowthFilter.isEligibleDirectGrowthScheme("ICICI Prudential Bluechip Fund - Growth")) // Missing Direct
        assertFalse(DirectGrowthFilter.isEligibleDirectGrowthScheme("SBI Bluechip Fund - Direct Plan - Bonus"))
    }

    @Test
    fun `smart search matches partial keywords extra spaces and ranks closest first`() {
        val catalog = listOf(
            sampleFund("120828", "Quant Small Cap Fund - Direct Plan - Growth", "Quant Mutual Fund"),
            sampleFund("120843", "Quant Mid Cap Fund - Direct Plan - Growth", "Quant Mutual Fund"),
            sampleFund("122639", "Parag Parikh Flexi Cap Fund - Direct Plan - Growth", "PPFAS Mutual Fund"),
            sampleFund("118778", "Nippon India Small Cap Fund - Direct Plan - Growth", "Nippon India Mutual Fund"),
            sampleFund("118989", "HDFC Mid-Cap Opportunities Fund - Direct Plan - Growth", "HDFC Mutual Fund"),
            sampleFund("999001", "HDFC FMP 1120D - Direct Plan - Growth", "HDFC Mutual Fund"),
            sampleFund("IDX_NIFTY50", "Nifty 50", "Market Index", isIndex = true)
        )

        val res1 = FundSearchEngine.search("qu sm", catalog)
        assertEquals("120828", res1.first().schemeCode)

        val res2 = FundSearchEngine.search("  QU   SM   CA ", catalog)
        assertEquals("120828", res2.first().schemeCode)

        val res3 = FundSearchEngine.search("parag par", catalog)
        assertEquals("122639", res3.first().schemeCode)

        val res4 = FundSearchEngine.search("ni sm", catalog)
        assertEquals("118778", res4.first().schemeCode)

        val res5 = FundSearchEngine.search("hd mi", catalog)
        assertEquals("118989", res5.first().schemeCode)

        val resHdfc = FundSearchEngine.search("hdfc", catalog)
        assertEquals("118989", resHdfc.first().schemeCode) // Open-ended ranked above FMP

        val resIdx = FundSearchEngine.search("nifty", catalog)
        assertEquals("IDX_NIFTY50", resIdx.first().schemeCode)
    }

    @Test
    fun `normalized return starts at zero and handles staggered inception dates without fabricating data`() {
        val fundOld = sampleFund("OLD", "Old Fund - Direct Plan - Growth", "AMC A")
        val fundNew = sampleFund("NEW", "New Fund - Direct Plan - Growth", "AMC B")

        val d1 = LocalDate.of(2024, 1, 1).toEpochDay()
        val d2 = LocalDate.of(2024, 6, 1).toEpochDay()
        val d3 = LocalDate.of(2025, 1, 1).toEpochDay()

        val oldHistory = listOf(NavPoint(d1, 100.0), NavPoint(d2, 120.0), NavPoint(d3, 150.0))
        val newHistory = listOf(NavPoint(d2, 10.0), NavPoint(d3, 12.5))

        val sOld = PerformanceCalculator.buildNormalizedSeries(
            fund = fundOld,
            rawHistory = oldHistory,
            requestedStartEpochDay = d1,
            requestedEndEpochDay = d3,
            color = ChartSeriesPalette[0],
            isVisible = true,
            isFavourite = false
        )
        val sNew = PerformanceCalculator.buildNormalizedSeries(
            fund = fundNew,
            rawHistory = newHistory,
            requestedStartEpochDay = d1,
            requestedEndEpochDay = d3,
            color = ChartSeriesPalette[1],
            isVisible = true,
            isFavourite = false
        )

        assertNotNull(sOld)
        assertNotNull(sNew)
        assertEquals(d1, sOld!!.actualStartEpochDay)
        assertEquals(0.0, sOld.points.first().second, 0.0001)
        assertEquals(50.0, sOld.returnPercent, 0.01)

        // New fund starts at 0% on its own inception date d2 rather than fabricating d1
        assertEquals(d2, sNew!!.actualStartEpochDay)
        assertEquals(0.0, sNew.points.first().second, 0.0001)
        assertEquals(25.0, sNew.returnPercent, 0.01)
    }

    @Test
    fun `rankings handle weekend gaps and exclude funds with insufficient history`() {
        val f1 = sampleFund("F1", "Top Fund - Direct Plan - Growth", "AMC")
        val f2 = sampleFund("F2", "Worst Fund - Direct Plan - Growth", "AMC")
        val fShort = sampleFund("F3", "Short Fund - Direct Plan - Growth", "AMC")

        val friday = LocalDate.of(2026, 11, 13).toEpochDay() // Friday
        val monday = LocalDate.of(2026, 11, 16).toEpochDay() // Monday (latest, 1D target = Sunday -> maps to Friday!)
        val prevWeek = LocalDate.of(2026, 11, 9).toEpochDay()

        val catalog = mapOf("F1" to f1, "F2" to f2, "F3" to fShort)
        val histories = mapOf(
            "F1" to listOf(NavPoint(prevWeek, 100.0), NavPoint(friday, 102.0), NavPoint(monday, 107.1)),
            "F2" to listOf(NavPoint(prevWeek, 100.0), NavPoint(friday, 100.0), NavPoint(monday, 96.0)),
            "F3" to listOf(NavPoint(monday, 50.0)) // Insufficient history for 1D or 7D
        )

        val rank1D = PerformanceCalculator.computeRankings(catalog, histories, emptySet(), days = 1)
        assertEquals(2, rank1D.evaluatedCount)
        assertEquals(1, rank1D.excludedInsufficientCount)
        assertEquals("F1", rank1D.topPerformers.first().schemeCode)
        assertEquals(5.0, rank1D.topPerformers.first().returnPercent, 0.01)
        assertEquals("F2", rank1D.worstPerformers.first().schemeCode)
        assertEquals(-4.0, rank1D.worstPerformers.first().returnPercent, 0.01)

        // Insufficient history for 30D should exclude all
        val rank30D = PerformanceCalculator.computeRankings(catalog, histories, emptySet(), days = 30)
        assertEquals(0, rank30D.evaluatedCount)
    }

    @Test
    fun `amfi parser filters non direct growth and deduplicates properly`() {
        val sampleAmfi = """
            Open Ended Schemes(Equity Scheme - Small Cap Fund)
            quant Mutual Fund
            120828;INF966L01689;INF966L01697;quant Small Cap Fund - Growth Option - Direct Plan;250.50;20-Nov-2026
            120829;INF966L01689;-;quant Small Cap Fund - Direct Plan - Growth;249.10;19-Nov-2026
            120827;INF966L01671;-;quant Small Cap Fund - IDCW Option - Direct Plan;45.10;20-Nov-2026
            100111;-;-;quant Small Cap Fund - Growth Option - Regular Plan;230.00;20-Nov-2026
        """.trimIndent()

        val parsed = RemoteDataSources.parseAmfiText(sampleAmfi, nowMillis = 1000L)
        assertEquals(1, parsed.eligibleFunds.size)
        assertEquals("120828", parsed.eligibleFunds.first().schemeCode)
        assertEquals(250.50, parsed.latestNavRecords.first().navValue, 0.001)
    }

    @Test
    fun `amfi 8-column format and tigzig live batch nav json parse accurately for mf and sif`() {
        val sample8Col = """
            Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme Name;Plan;Option;Net Asset Value;Date
            Open Ended Schemes(Hybrid Scheme - Aggressive Hybrid Fund)
            quant Mutual Fund
            120819;INF966L01556;-;quant Aggressive Hybrid Fund;Direct Plan;Growth Option;522.7593;25-Nov-2026
            153705;INF966L01EC8;-;quant Equity Savings Fund;Direct Plan;Growth;11.2186;25-Nov-2026
            120818;INF966L01549;-;quant Aggressive Hybrid Fund;Direct Plan;IDCW Option;45.10;25-Nov-2026
            120817;INF966L01531;-;quant Aggressive Hybrid Fund;Regular Plan;Growth Option;480.00;25-Nov-2026
        """.trimIndent()

        val parsed8 = RemoteDataSources.parseAmfiText(sample8Col, nowMillis = 2000L)
        assertEquals(4, parsed8.totalRawRows)
        assertEquals(2, parsed8.eligibleFunds.size)
        assertEquals(2, parsed8.excludedNonDirectGrowthRows)

        val fund120819 = parsed8.eligibleFunds.first { it.schemeCode == "120819" }
        assertTrue(DirectGrowthFilter.isEligibleDirectGrowthScheme(fund120819.schemeName))
        assertEquals(522.7593, fund120819.latestNav!!, 0.0001)
        assertEquals(LocalDate.of(2026, 11, 25).toEpochDay(), fund120819.latestNavDateEpochDay)

        val fund153705 = parsed8.eligibleFunds.first { it.schemeCode == "153705" }
        assertEquals(11.2186, fund153705.latestNav!!, 0.0001)
        assertEquals(LocalDate.of(2026, 11, 25).toEpochDay(), fund153705.latestNavDateEpochDay)
    }

    @Test
    fun `nav binary codec preserves 6 decimal precision deduplicates dates and compresses compactly`() {
        val pts = listOf(
            NavPoint(LocalDate.of(2026, 1, 1).toEpochDay(), 100.1234),
            NavPoint(LocalDate.of(2026, 1, 2).toEpochDay(), 101.4567),
            NavPoint(LocalDate.of(2026, 1, 2).toEpochDay(), 101.4567), // duplicate date
            NavPoint(LocalDate.of(2026, 1, 5).toEpochDay(), 99.987654)
        )
        val merge1 = com.example.data.NavBinaryCodec.mergeIntoYearBucket(120828, 2026, null, pts)
        assertEquals(3, merge1.newlyInsertedCount)
        assertTrue(merge1.payloadChanged)
        val decoded = com.example.data.NavBinaryCodec.decode(merge1.bucket!!.payload)
        assertEquals(3, decoded.size)
        assertEquals(100.1234, decoded[0].navValue, 1e-6)
        assertEquals(101.4567, decoded[1].navValue, 1e-6)
        assertEquals(99.987654, decoded[2].navValue, 1e-6)

        // Re-merging identical points produces 0 changes and 0 inserts
        val merge2 = com.example.data.NavBinaryCodec.mergeIntoYearBucket(120828, 2026, merge1.bucket!!.payload, pts)
        assertEquals(0, merge2.newlyInsertedCount)
        assertFalse(merge2.payloadChanged)
    }

    @Test
    fun `youngest inception computation tracks youngest fund recalculates and flags recorded mismatch`() {
        val dA = LocalDate.of(2016, 5, 10).toEpochDay()
        val dB = LocalDate.of(2019, 3, 15).toEpochDay()
        val dC = LocalDate.of(2021, 8, 20).toEpochDay()
        val dD = LocalDate.of(2024, 2, 12).toEpochDay()

        val catalog = mapOf(
            "A" to sampleFund("A", "Fund A - Direct Growth", "AMC").copy(recordedInceptionEpochDay = dA),
            "B" to sampleFund("B", "Fund B - Direct Growth", "AMC").copy(recordedInceptionEpochDay = dB),
            "C" to sampleFund("C", "Fund C - Direct Growth", "AMC").copy(recordedInceptionEpochDay = dC),
            "D" to sampleFund("D", "Fund D - Direct Growth", "AMC").copy(recordedInceptionEpochDay = dD)
        )
        val histories = mapOf(
            "A" to listOf(NavPoint(dA, 10.0), NavPoint(dD, 30.0)),
            "B" to listOf(NavPoint(dB, 10.0), NavPoint(dD, 20.0)),
            "C" to listOf(NavPoint(dC, 10.0), NavPoint(dD, 15.0)),
            "D" to listOf(NavPoint(dD + 2, 10.0), NavPoint(dD + 100, 12.0)) // actual NAV starts 2 days after recorded
        )

        val info4 = PerformanceCalculator.computeYoungestInceptionInfo(listOf("A", "B", "C", "D"), catalog, histories)!!
        assertEquals("D", info4.youngestSchemeCode)
        assertEquals(dD + 2, info4.effectiveInceptionEpochDay)
        assertTrue(info4.hasRecordedVsActualMismatch)

        // Remove D -> automatically recalculates to C
        val info3 = PerformanceCalculator.computeYoungestInceptionInfo(listOf("A", "B", "C"), catalog, histories)!!
        assertEquals("C", info3.youngestSchemeCode)
        assertEquals(dC, info3.effectiveInceptionEpochDay)
        assertFalse(info3.hasRecordedVsActualMismatch)
    }

    @Test
    fun `fund category filter covers all 65 categories including 7 sif strategies without false positives`() {
        val filter = com.example.data.FundCategoryFilter
        val grouped = filter.GROUPED_CATEGORIES
        assertEquals(13, grouped[com.example.data.FundCategoryGroup.EQUITY]!!.size)
        assertEquals(16, grouped[com.example.data.FundCategoryGroup.THEMES]!!.size)
        assertEquals(17, grouped[com.example.data.FundCategoryGroup.DEBT]!!.size)
        assertEquals(7, grouped[com.example.data.FundCategoryGroup.HYBRID]!!.size)
        assertEquals(1, grouped[com.example.data.FundCategoryGroup.LIFE_CYCLE]!!.size)
        assertEquals(4, grouped[com.example.data.FundCategoryGroup.OTHER]!!.size)
        assertEquals(7, grouped[com.example.data.FundCategoryGroup.SIF]!!.size)

        val quantSmall = sampleFund("120828", "quant Small Cap Fund - Direct Plan - Growth", "Quant Mutual Fund")
        assertTrue(filter.matchesCategory(quantSmall, "EQ_SMALL_CAP"))
        assertFalse(filter.matchesCategory(quantSmall, "TH_INNOVATION_QUANT"))
        assertFalse(filter.matchesCategory(quantSmall, "EQ_MID_CAP"))

        val bankingPsuDebt = sampleFund("120111", "HDFC Banking and PSU Debt Fund - Direct Plan - Growth", "HDFC Mutual Fund")
            .copy(category = "Debt Scheme - Banking and PSU Fund")
        assertTrue(filter.matchesCategory(bankingPsuDebt, "DT_BANKING_PSU"))
        assertFalse(filter.matchesCategory(bankingPsuDebt, "TH_BFSI"))
        assertFalse(filter.matchesCategory(bankingPsuDebt, "TH_PSU"))

        val creditRisk = sampleFund("120222", "SBI Credit Risk Fund - Direct Plan - Growth", "SBI Mutual Fund")
        assertTrue(filter.matchesCategory(creditRisk, "DT_CREDIT_RISK"))
        assertFalse(filter.matchesCategory(creditRisk, "TH_TECH"))

        val niftyIndex = sampleFund("120333", "ICICI Prudential Nifty 50 Index Fund - Direct Plan - Growth", "ICICI Mutual Fund")
        assertTrue(filter.matchesCategory(niftyIndex, "OT_INDEX"))
        assertFalse(filter.matchesCategory(niftyIndex, "EQ_LARGE_CAP"))

        val nasdaqFof = sampleFund("120444", "Motilal Oswal Nasdaq 100 Fund of Fund - Direct Plan - Growth", "Motilal Oswal")
        assertTrue(filter.matchesCategory(nasdaqFof, "OT_FOF_OVERSEAS"))
        assertFalse(filter.matchesCategory(nasdaqFof, "OT_FOF_DOMESTIC"))

        val hospitalFof = sampleFund("120555", "Groww BSE Hospitals ETF FOF - Direct Plan - Growth", "Groww Mutual Fund")
        assertTrue(filter.matchesCategory(hospitalFof, "OT_FOF_DOMESTIC"))
        assertTrue(filter.matchesCategory(hospitalFof, "TH_PHARMA"))

        val daa = sampleFund("120666", "HDFC Balanced Advantage Fund - Direct Plan - Growth", "HDFC Mutual Fund")
        assertTrue(filter.matchesCategory(daa, "HY_DAA"))

        val defence = sampleFund("120777", "HDFC Defence Fund - Direct Plan - Growth", "HDFC Mutual Fund")
        assertTrue(filter.matchesCategory(defence, "TH_DEFENCE"))
        assertTrue(filter.matchesCategory(defence, "EQ_SECTORAL"))

        // 7 SEBI SIF Specialized Investment Fund categories + strict isolation from MF categories
        val sifEq = sampleFund("SIF_1", "qsif Equity Long-Short Fund - Direct Plan - Growth", "quant SIF")
            .copy(category = "SIF - Equity Long-Short Fund", dataSource = com.example.data.RemoteDataSources.DATA_SOURCE_SIF)
        assertTrue(filter.matchesCategory(sifEq, "SIF_EQUITY_LONG_SHORT"))
        assertFalse(filter.matchesCategory(sifEq, "SIF_EQUITY_EX_TOP_100"))
        assertFalse(filter.matchesCategory(sifEq, "TH_INNOVATION_QUANT"))

        val sifExTop100 = sampleFund("SIF_2", "Edelweiss Altiva Equity Ex-Top 100 Long-Short Fund - Direct Plan - Growth", "Edelweiss SIF")
            .copy(category = "SIF - Equity Ex-Top 100 Long-Short Fund", dataSource = com.example.data.RemoteDataSources.DATA_SOURCE_SIF)
        assertTrue(filter.matchesCategory(sifExTop100, "SIF_EQUITY_EX_TOP_100"))
        assertFalse(filter.matchesCategory(sifExTop100, "SIF_EQUITY_LONG_SHORT"))

        val sifSectorRot = sampleFund("SIF_3", "SBI Magnum Sector Rotation Long-Short Fund - Direct Plan - Growth", "SBI SIF")
            .copy(category = "SIF - Sector Rotation Long-Short Fund", dataSource = com.example.data.RemoteDataSources.DATA_SOURCE_SIF)
        assertTrue(filter.matchesCategory(sifSectorRot, "SIF_SECTOR_ROTATION"))

        val sifDebt = sampleFund("SIF_4", "ICICI Prudential Debt Long-Short Fund - Direct Plan - Growth", "ICICI SIF")
            .copy(category = "SIF - Debt Long-Short Fund", dataSource = com.example.data.RemoteDataSources.DATA_SOURCE_SIF)
        assertTrue(filter.matchesCategory(sifDebt, "SIF_DEBT_LONG_SHORT"))
        assertFalse(filter.matchesCategory(sifDebt, "SIF_SECTORAL_DEBT"))

        val sifSectoralDebt = sampleFund("SIF_5", "Kotak Sectoral Debt Long-Short Fund - Direct Plan - Growth", "Kotak SIF")
            .copy(category = "SIF - Sectoral Debt Long-Short Fund", dataSource = com.example.data.RemoteDataSources.DATA_SOURCE_SIF)
        assertTrue(filter.matchesCategory(sifSectoralDebt, "SIF_SECTORAL_DEBT"))
        assertFalse(filter.matchesCategory(sifSectoralDebt, "DT_SECTORAL_DEBT"))

        val sifHybrid = sampleFund("SIF_6", "qsif Hybrid Long-Short Fund - Direct Plan - Growth", "quant SIF")
            .copy(category = "SIF - Hybrid Long-Short Fund", dataSource = com.example.data.RemoteDataSources.DATA_SOURCE_SIF)
        assertTrue(filter.matchesCategory(sifHybrid, "SIF_HYBRID_LONG_SHORT"))
        assertFalse(filter.matchesCategory(sifHybrid, "HY_AGGRESSIVE"))

        val sifAllocator = sampleFund("SIF_7", "HDFC Active Asset Allocator Long-Short Fund - Direct Plan - Growth", "HDFC SIF")
            .copy(category = "SIF - Active Asset Allocator Long-Short Fund", dataSource = com.example.data.RemoteDataSources.DATA_SOURCE_SIF)
        assertTrue(filter.matchesCategory(sifAllocator, "SIF_ACTIVE_ASSET_ALLOCATOR"))
        assertFalse(filter.matchesCategory(sifAllocator, "HY_DAA"))
    }

    @Test
    fun `fast date parsers parse amfi and mfapi dates accurately without invalid leap or malformed values`() {
        assertEquals(
            LocalDate.of(2026, 11, 24).toEpochDay(),
            RemoteDataSources.parseAmfiDateEpochDay("24-Nov-2026")
        )
        assertEquals(
            LocalDate.of(2024, 2, 29).toEpochDay(),
            RemoteDataSources.parseMfApiDateEpochDay("29-02-2024")
        )
        assertNull(RemoteDataSources.parseMfApiDateEpochDay("31-02-2025"))
        assertNull(RemoteDataSources.parseAmfiDateEpochDay("bad-date"))
    }

    @Test
    fun `sip picks highest nav per weekly biweekly monthly window for worst case and enforces strict fifo order`() {
        val fund = sampleFund("SIP_TEST", "Alpha Equity Fund - Direct Plan - Growth", "AMC")
        val start = LocalDate.of(2025, 1, 1).toEpochDay()
        // Week 1 (days 0..6): NAVs 10.0, 12.5 (highest), 11.0
        // Week 2 (days 7..13): NAVs 11.5, 14.0 (highest), 13.0
        // Valuation day (day 20): NAV 15.0
        val history = listOf(
            NavPoint(start + 0, 10.0),
            NavPoint(start + 2, 12.5),
            NavPoint(start + 5, 11.0),
            NavPoint(start + 7, 11.5),
            NavPoint(start + 9, 14.0),
            NavPoint(start + 12, 13.0),
            NavPoint(start + 14, 13.5),
            NavPoint(start + 20, 15.0)
        )

        val weeklyRes = com.example.data.SipCalculator.computeSipResult(
            fund = fund,
            rawHistory = history,
            config = com.example.data.SipFundConfig("SIP_TEST", amount = 1000.0, tenure = com.example.data.SipTenure.WEEKLY),
            startMode = com.example.data.SipStartMode.FROM_INCEPTION,
            customStartEpochDay = null,
            youngestInceptionEpochDay = null,
            color = ChartSeriesPalette[0]
        )

        assertTrue(weeklyRes.isValid)
        // 3 weekly buckets: [0..6]=12.5, [7..13]=14.0, [14..20]=15.0
        assertEquals(3, weeklyRes.fifoLots.size)
        assertEquals(start + 2, weeklyRes.fifoLots[0].buyEpochDay)
        assertEquals(12.5, weeklyRes.fifoLots[0].navUsed, 1e-6)
        assertEquals(start + 9, weeklyRes.fifoLots[1].buyEpochDay)
        assertEquals(14.0, weeklyRes.fifoLots[1].navUsed, 1e-6)
        assertEquals(start + 20, weeklyRes.fifoLots[2].buyEpochDay)
        assertEquals(15.0, weeklyRes.fifoLots[2].navUsed, 1e-6)

        // Verify strict chronological FIFO lot order & exact unit conservation
        for (i in 1 until weeklyRes.fifoLots.size) {
            assertTrue(weeklyRes.fifoLots[i].buyEpochDay > weeklyRes.fifoLots[i - 1].buyEpochDay)
            assertEquals(i + 1, weeklyRes.fifoLots[i].lotNumber)
        }
        val expectedUnits = (1000.0 / 12.5) + (1000.0 / 14.0) + (1000.0 / 15.0)
        assertEquals(expectedUnits, weeklyRes.totalUnits, 1e-9)
        assertEquals(3000.0, weeklyRes.totalInvested, 1e-9)
        assertEquals(expectedUnits * 15.0, weeklyRes.currentValue, 1e-6)

        // Biweekly: [0..13] picks 14.0 (day +9), [14..20] picks 15.0 (day +20)
        val biweeklyRes = com.example.data.SipCalculator.computeSipResult(
            fund = fund,
            rawHistory = history,
            config = com.example.data.SipFundConfig("SIP_TEST", amount = 1000.0, tenure = com.example.data.SipTenure.BIWEEKLY),
            startMode = com.example.data.SipStartMode.FROM_INCEPTION,
            customStartEpochDay = null,
            youngestInceptionEpochDay = null,
            color = ChartSeriesPalette[0]
        )
        assertEquals(2, biweeklyRes.fifoLots.size)
        assertEquals(14.0, biweeklyRes.fifoLots[0].navUsed, 1e-6)
        assertEquals(15.0, biweeklyRes.fifoLots[1].navUsed, 1e-6)

        // Monthly: [Jan 1..Jan 31] picks 15.0 (day +20)
        val monthlyRes = com.example.data.SipCalculator.computeSipResult(
            fund = fund,
            rawHistory = history,
            config = com.example.data.SipFundConfig("SIP_TEST", amount = 1000.0, tenure = com.example.data.SipTenure.MONTHLY),
            startMode = com.example.data.SipStartMode.FROM_INCEPTION,
            customStartEpochDay = null,
            youngestInceptionEpochDay = null,
            color = ChartSeriesPalette[0]
        )
        assertEquals(1, monthlyRes.fifoLots.size)
        assertEquals(15.0, monthlyRes.fifoLots[0].navUsed, 1e-6)

        // Daily: buys on all 8 days
        val dailyRes = com.example.data.SipCalculator.computeSipResult(
            fund = fund,
            rawHistory = history,
            config = com.example.data.SipFundConfig("SIP_TEST", amount = 100.0, tenure = com.example.data.SipTenure.DAILY),
            startMode = com.example.data.SipStartMode.FROM_INCEPTION,
            customStartEpochDay = null,
            youngestInceptionEpochDay = null,
            color = ChartSeriesPalette[0]
        )
        assertEquals(8, dailyRes.fifoLots.size)
        assertEquals(800.0, dailyRes.totalInvested, 1e-9)
    }

    @Test
    fun `sip xirr computes exact annualized return and supports youngest inception vs custom date`() {
        val fundA = sampleFund("FA", "Fund A - Direct Growth", "AMC")
        val d0 = LocalDate.of(2025, 1, 1).toEpochDay()
        val dMid = d0 + 182L
        val dEnd = d0 + 365L

        val lots = listOf(
            com.example.data.SipFifoLot(1, d0, 100.0, 1000.0, 10.0),
            com.example.data.SipFifoLot(2, dMid, 100.0, 1000.0, 10.0)
        )
        val xirr = com.example.data.SipCalculator.computeXirrPercent(
            lots = lots,
            valuationEpochDay = dEnd,
            terminalValue = 2200.0
        )!!
        // -1000 at y=0, -1000 at y=182/365, +2200 at y=1.0 -> ~13.43% p.a.
        assertEquals(13.43, xirr, 0.2)

        // Test Monthly bucketing across 3 months with youngest inception & custom calendar start
        val jan1 = LocalDate.of(2025, 1, 1).toEpochDay()
        val feb1 = LocalDate.of(2025, 2, 1).toEpochDay()
        val mar1 = LocalDate.of(2025, 3, 1).toEpochDay()
        val apr1 = LocalDate.of(2025, 4, 1).toEpochDay()
        val history = listOf(
            NavPoint(jan1, 10.0),
            NavPoint(feb1, 11.0),
            NavPoint(mar1, 12.0),
            NavPoint(apr1, 15.0)
        )

        val customRes = com.example.data.SipCalculator.computeSipResult(
            fund = fundA,
            rawHistory = history,
            config = com.example.data.SipFundConfig("FA", 1000.0, com.example.data.SipTenure.MONTHLY),
            startMode = com.example.data.SipStartMode.CUSTOM_DATE,
            customStartEpochDay = feb1,
            youngestInceptionEpochDay = jan1,
            color = ChartSeriesPalette[0]
        )
        assertEquals(3, customRes.installmentCount)
        assertEquals(feb1, customRes.firstInstallmentEpochDay)
        assertEquals(3000.0, customRes.totalInvested, 1e-6)
        assertNotNull(customRes.xirrPercent)
    }

    @Test
    fun `rolling return calculator computes weekly biweekly monthly quarterly and yearly rolling stats accurately`() {
        val fund = sampleFund("ROLL_TEST", "Alpha Rolling Fund - Direct Plan - Growth", "AMC")
        val d0 = LocalDate.of(2025, 1, 1).toEpochDay()
        // Build 400 daily points with steady growth + a mild mid-year dip so min/max/avg/median/win-rate are distinct
        val history = (0L..400L).map { offset ->
            val nav = when {
                offset < 100L -> 100.0 + offset * 0.2
                offset < 130L -> 120.0 - (offset - 100L) * 0.3
                else -> 111.0 + (offset - 130L) * 0.25
            }
            NavPoint(d0 + offset, nav)
        }

        val weekly = com.example.data.RollingReturnCalculator.computeRollingResult(
            fund = fund,
            rawHistory = history,
            window = com.example.data.RollingWindow.WEEKLY,
            startMode = com.example.data.RollingStartMode.FROM_INCEPTION,
            customStartEpochDay = null,
            youngestInceptionEpochDay = null,
            color = ChartSeriesPalette[0]
        )
        assertTrue(weekly.isValid)
        // Windows 7..400 => 394 daily-rolled 7D observations
        assertEquals(394, weekly.observationCount)
        assertTrue(weekly.minReturnPercent < 0.0)
        assertTrue(weekly.maxReturnPercent > 0.0)
        assertTrue(weekly.averageReturnPercent in weekly.minReturnPercent..weekly.maxReturnPercent)
        assertTrue(weekly.positivePercent > 50.0)

        val biweekly = com.example.data.RollingReturnCalculator.computeRollingResult(
            fund = fund,
            rawHistory = history,
            window = com.example.data.RollingWindow.BIWEEKLY,
            startMode = com.example.data.RollingStartMode.FROM_INCEPTION,
            customStartEpochDay = null,
            youngestInceptionEpochDay = null,
            color = ChartSeriesPalette[0]
        )
        assertEquals(387, biweekly.observationCount)

        val monthly = com.example.data.RollingReturnCalculator.computeRollingResult(
            fund = fund,
            rawHistory = history,
            window = com.example.data.RollingWindow.MONTHLY,
            startMode = com.example.data.RollingStartMode.FROM_INCEPTION,
            customStartEpochDay = null,
            youngestInceptionEpochDay = null,
            color = ChartSeriesPalette[0]
        )
        assertTrue(monthly.isValid && monthly.observationCount in 365..375)

        val quarterly = com.example.data.RollingReturnCalculator.computeRollingResult(
            fund = fund,
            rawHistory = history,
            window = com.example.data.RollingWindow.QUARTERLY,
            startMode = com.example.data.RollingStartMode.FROM_INCEPTION,
            customStartEpochDay = null,
            youngestInceptionEpochDay = null,
            color = ChartSeriesPalette[0]
        )
        assertTrue(quarterly.isValid && quarterly.observationCount in 305..315)

        val yearly = com.example.data.RollingReturnCalculator.computeRollingResult(
            fund = fund,
            rawHistory = history,
            window = com.example.data.RollingWindow.YEARLY,
            startMode = com.example.data.RollingStartMode.FROM_INCEPTION,
            customStartEpochDay = null,
            youngestInceptionEpochDay = null,
            color = ChartSeriesPalette[0]
        )
        assertTrue(yearly.isValid && yearly.observationCount == 36)

        // Custom start date restricts all windows to >= customStart
        val customStart = LocalDate.of(2025, 11, 1).toEpochDay()
        val yearlyTooShort = com.example.data.RollingReturnCalculator.computeRollingResult(
            fund = fund,
            rawHistory = history,
            window = com.example.data.RollingWindow.YEARLY,
            startMode = com.example.data.RollingStartMode.CUSTOM_DATE,
            customStartEpochDay = customStart,
            youngestInceptionEpochDay = null,
            color = ChartSeriesPalette[0]
        )
        assertFalse(yearlyTooShort.isValid)
        assertNotNull(yearlyTooShort.errorNote)
    }

    @Test
    fun `tigzig sif historical csv and latest snapshot parsers filter direct growth sort dates and tag sif`() {
        val csv = """
            scheme_code,scheme_name,isin,date,nav,amc_name,category
            900001,"qsif Equity Long-Short Fund - Direct Plan - Growth",INF966L30076,2026-11-20,10.25,quant SIF,Equity Long-Short Fund
            900001,"qsif Equity Long-Short Fund - Direct Plan - Growth",INF966L30076,2026-11-18,10.00,quant SIF,Equity Long-Short Fund
            900001,"qsif Equity Long-Short Fund - Direct Plan - Growth",INF966L30076,2026-11-20,10.28,quant SIF,Equity Long-Short Fund
            900002,"qsif Equity Long-Short Fund - Regular Plan - Growth",INF966L30084,2026-11-20,9.95,quant SIF,Equity Long-Short Fund
            900003,"qsif Equity Long-Short Fund - Direct Plan - IDCW",INF966L30092,2026-11-20,10.10,quant SIF,Equity Long-Short Fund
        """.trimIndent()

        val parsedCsv = RemoteDataSources.parseTigzigSifHistoryCsv(csv, nowMillis = 1234L)
        assertEquals(1, parsedCsv.schemes.size)
        val s1 = parsedCsv.schemes.first()
        assertEquals("900001", s1.fund.schemeCode)
        assertEquals("INF966L30076", s1.fund.isin)
        assertEquals(RemoteDataSources.DATA_SOURCE_SIF, s1.fund.dataSource)
        assertEquals("SIF - Equity Long-Short Fund", s1.fund.category)
        assertEquals(2, s1.pointsAscending.size)
        assertEquals(LocalDate.of(2026, 11, 18).toEpochDay(), s1.pointsAscending[0].dateEpochDay)
        assertEquals(LocalDate.of(2026, 11, 20).toEpochDay(), s1.pointsAscending[1].dateEpochDay)
        assertEquals(10.28, s1.pointsAscending[1].navValue, 0.001)
        assertEquals(2, parsedCsv.excludedNonDirectGrowthRows)

        val latestCsv = """
            scheme_code,scheme_name,isin_growth,nav,date,amc,category,source
            120828,"quant Small Cap Fund - Direct Plan - Growth",INF966L01689,262.40,25-Nov-2026,quant Mutual Fund,Small Cap Fund,MF
            900001,"qsif Equity Long-Short Fund - Direct Plan - Growth",INF966L30076,10.42,2026-11-25,quant SIF,Equity Long-Short Fund,SIF
            900002,"qsif Equity Long-Short Fund - Regular Plan - Growth",INF966L30084,10.05,2026-11-25,quant SIF,Equity Long-Short Fund,SIF
        """.trimIndent()
        val parsedLatest = RemoteDataSources.parseTigzigLatestText(latestCsv, nowMillis = 2000L)
        assertEquals(2, parsedLatest.allFunds.size)
        assertEquals(1, parsedLatest.sifFunds.size)
        assertEquals(2, parsedLatest.latestNavRecords.size)
        assertEquals(RemoteDataSources.DATA_SOURCE_TIGZIG_MF, parsedLatest.allFunds[0].dataSource)
        assertEquals(RemoteDataSources.DATA_SOURCE_SIF, parsedLatest.sifFunds.first().dataSource)
        assertEquals(10.42, parsedLatest.sifFunds.first().latestNav!!, 0.001)
        assertEquals(LocalDate.of(2026, 11, 25).toEpochDay(), parsedLatest.sifFunds.first().latestNavDateEpochDay)
    }

    @Test
    fun `sip rankings sort top performers correctly by xirr and absolute return`() {
        val fundA = sampleFund("101", "Alpha Equity Fund - Direct Plan - Growth", "Alpha AMC")
        val fundB = sampleFund("102", "Beta Growth Fund - Direct Plan - Growth", "Beta AMC")
        val dStart = LocalDate.of(2025, 1, 1).toEpochDay()
        val dMid = LocalDate.of(2025, 6, 1).toEpochDay()
        val dEnd = LocalDate.of(2026, 1, 1).toEpochDay()

        // Fund A: 100 -> 120 -> 200 (huge gain)
        // Fund B: 100 -> 110 -> 130 (moderate gain)
        val histA = listOf(NavPoint(dStart, 100.0), NavPoint(dMid, 120.0), NavPoint(dEnd, 200.0))
        val histB = listOf(NavPoint(dStart, 100.0), NavPoint(dMid, 110.0), NavPoint(dEnd, 130.0))

        val configA = com.example.data.SipFundConfig("101", 1000.0, com.example.data.SipTenure.MONTHLY)
        val configB = com.example.data.SipFundConfig("102", 1000.0, com.example.data.SipTenure.MONTHLY)

        val resA = com.example.data.SipCalculator.computeSipResult(fundA, histA, configA, com.example.data.SipStartMode.CUSTOM_DATE, dStart, null, ChartSeriesPalette[0])
        val resB = com.example.data.SipCalculator.computeSipResult(fundB, histB, configB, com.example.data.SipStartMode.CUSTOM_DATE, dStart, null, ChartSeriesPalette[1])

        assertTrue(resA.isValid && resB.isValid)
        assertTrue(resA.xirrPercent!! > resB.xirrPercent!!)
        assertTrue(resA.absoluteReturnPercent > resB.absoluteReturnPercent)

        // Build rankings entries and verify rank sorting
        val entries = listOf(
            com.example.data.SipRankingEntry(
                rank = 0,
                schemeCode = fundB.schemeCode,
                schemeName = fundB.schemeName,
                amcName = fundB.amcName,
                category = fundB.category,
                isIndex = fundB.isIndex,
                totalInvested = resB.totalInvested,
                currentValue = resB.currentValue,
                absoluteReturnPercent = resB.absoluteReturnPercent,
                xirrPercent = resB.xirrPercent,
                installmentCount = resB.installmentCount,
                firstInstallmentDay = resB.firstInstallmentEpochDay!!,
                lastValuationDay = resB.valuationEpochDay!!,
                isFavourite = false
            ),
            com.example.data.SipRankingEntry(
                rank = 0,
                schemeCode = fundA.schemeCode,
                schemeName = fundA.schemeName,
                amcName = fundA.amcName,
                category = fundA.category,
                isIndex = fundA.isIndex,
                totalInvested = resA.totalInvested,
                currentValue = resA.currentValue,
                absoluteReturnPercent = resA.absoluteReturnPercent,
                xirrPercent = resA.xirrPercent,
                installmentCount = resA.installmentCount,
                firstInstallmentDay = resA.firstInstallmentEpochDay!!,
                lastValuationDay = resA.valuationEpochDay!!,
                isFavourite = false
            )
        )

        val sortedByXirr = entries.sortedByDescending { it.xirrPercent }.mapIndexed { idx, e -> e.copy(rank = idx + 1) }
        assertEquals(1, sortedByXirr[0].rank)
        assertEquals("101", sortedByXirr[0].schemeCode)
        assertEquals(2, sortedByXirr[1].rank)
        assertEquals("102", sortedByXirr[1].schemeCode)
    }

    @Test
    fun `rolling return rankings evaluate windows and rank top performers`() {
        val fundHigh = sampleFund("201", "High Consistency Fund - Direct Plan - Growth", "AMC High")
        val fundLow = sampleFund("202", "Low Consistency Fund - Direct Plan - Growth", "AMC Low")

        val start = LocalDate.of(2025, 1, 1).toEpochDay()
        val historyHigh = (0..100).map { i ->
            NavPoint(start + (i * 7), 100.0 + (i * 1.5)) // Strictly increasing -> 100% positive windows
        }
        val historyLow = (0..100).map { i ->
            val sign = if (i % 2 == 0) 1.0 else -1.0
            NavPoint(start + (i * 7), 100.0 + (sign * 2.0)) // Oscillating
        }

        val resHigh = com.example.data.RollingReturnCalculator.computeRollingResult(
            fund = fundHigh,
            rawHistory = historyHigh,
            window = com.example.data.RollingWindow.MONTHLY,
            startMode = com.example.data.RollingStartMode.CUSTOM_DATE,
            customStartEpochDay = start,
            youngestInceptionEpochDay = null,
            color = ChartSeriesPalette[0]
        )
        val resLow = com.example.data.RollingReturnCalculator.computeRollingResult(
            fund = fundLow,
            rawHistory = historyLow,
            window = com.example.data.RollingWindow.MONTHLY,
            startMode = com.example.data.RollingStartMode.CUSTOM_DATE,
            customStartEpochDay = start,
            youngestInceptionEpochDay = null,
            color = ChartSeriesPalette[1]
        )

        assertTrue(resHigh.isValid && resLow.isValid)
        assertTrue(resHigh.positivePercent > resLow.positivePercent)
        assertTrue(resHigh.averageReturnPercent > resLow.averageReturnPercent)
    }

    private fun sampleFund(
        code: String,
        name: String,
        amc: String,
        isIndex: Boolean = false
    ) = FundMasterEntity(
        schemeCode = code,
        schemeName = name,
        searchTokens = DirectGrowthFilter.normalizeSearchTokens("$name $amc"),
        amcName = amc,
        category = if (isIndex) "Index" else "Equity",
        isin = null,
        dedupKey = code,
        isIndex = isIndex,
        isActive = true,
        dataSource = "TEST",
        lastSyncedAt = 1000L
    )
}
