package com.example.data

import androidx.compose.ui.graphics.Color
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.abs

/**
 * Pure financial calculation engine.
 * - Normalizes series using actual NAV dates only (never fabricates or interpolates missing NAVs).
 * - Percentage Return = ((Current NAV / Starting NAV) - 1) * 100
 * - Handles staggered inception dates cleanly by plotting each fund from its first actual NAV >= start.
 * - Computes universe-wide Top 20 & Worst 20 performers over N calendar days with holiday tolerance.
 */
object PerformanceCalculator {

    private const val HOLIDAY_TOLERANCE_DAYS = 7L

    fun resolvePeriodStartEpochDay(
        period: ComparePeriod,
        latestReferenceEpochDay: Long,
        customStartEpochDay: Long?,
        youngestInceptionEpochDay: Long? = null
    ): Long? {
        val refDate = LocalDate.ofEpochDay(latestReferenceEpochDay)
        return when {
            period == ComparePeriod.CUSTOM -> customStartEpochDay
            period.isYoungestInception -> youngestInceptionEpochDay
            period.isInception -> null
            period.months != null -> refDate.minusMonths(period.months.toLong()).toEpochDay()
            period.years != null -> refDate.minusYears(period.years.toLong()).toEpochDay()
            else -> null
        }
    }

    /**
     * Determines the youngest (most recent inception) among selected funds/indices.
     * Uses actual earliest available NAV date when present (never fabricating earlier data),
     * falling back to recorded metadata inception while history is loading, and explicitly
     * flags any mismatch between recorded inception metadata and actual first available NAV.
     */
    fun computeYoungestInceptionInfo(
        selectedCodes: List<String>,
        catalogMap: Map<String, FundMasterEntity>,
        histories: Map<String, List<NavPoint>>
    ): YoungestInceptionInfo? {
        if (selectedCodes.isEmpty()) return null
        var youngest: YoungestInceptionInfo? = null

        for (code in selectedCodes) {
            val fund = catalogMap[code] ?: continue
            val pts = histories[code]
            val actualFirst = pts?.minOfOrNull { it.dateEpochDay }
            val recorded = fund.recordedInceptionEpochDay ?: fund.inceptionEpochDay
            val effective = actualFirst ?: recorded ?: continue
            val mismatch = actualFirst != null && recorded != null && actualFirst != recorded

            val current = YoungestInceptionInfo(
                youngestSchemeCode = fund.schemeCode,
                youngestSchemeName = fund.schemeName,
                effectiveInceptionEpochDay = effective,
                recordedInceptionEpochDay = recorded,
                actualFirstNavEpochDay = actualFirst,
                hasRecordedVsActualMismatch = mismatch
            )

            if (youngest == null || current.effectiveInceptionEpochDay > youngest.effectiveInceptionEpochDay) {
                youngest = current
            }
        }
        return youngest
    }

    /**
     * Builds a normalized percentage return series for a single fund/index over [requestedStartEpochDay..requestedEndEpochDay].
     * Each fund starts at 0.00% on its own first actual NAV on or after [requestedStartEpochDay].
     */
    fun buildNormalizedSeries(
        fund: FundMasterEntity,
        rawHistory: List<NavPoint>,
        requestedStartEpochDay: Long?,
        requestedEndEpochDay: Long?,
        color: Color,
        isVisible: Boolean,
        isFavourite: Boolean
    ): FundComparisonSeries? {
        if (rawHistory.isEmpty()) return null
        val sorted = rawHistory
            .asSequence()
            .filter { it.navValue > 0.0 }
            .sortedBy { it.dateEpochDay }
            .distinctBy { it.dateEpochDay }
            .toList()
        if (sorted.isEmpty()) return null

        val effectiveEndCap = requestedEndEpochDay ?: sorted.last().dateEpochDay
        val filtered = sorted.filter { pt ->
            val afterStart = requestedStartEpochDay == null || pt.dateEpochDay >= requestedStartEpochDay
            val beforeEnd = pt.dateEpochDay <= effectiveEndCap
            afterStart && beforeEnd
        }

        if (filtered.isEmpty()) return null

        val startPoint = filtered.first()
        val endPoint = filtered.last()
        if (startPoint.navValue <= 0.0) return null

        val normalizedPoints = filtered.map { pt ->
            val pct = ((pt.navValue / startPoint.navValue) - 1.0) * 100.0
            pt.dateEpochDay to pct
        }

        val totalReturn = ((endPoint.navValue / startPoint.navValue) - 1.0) * 100.0

        return FundComparisonSeries(
            schemeCode = fund.schemeCode,
            schemeName = fund.schemeName,
            amcName = fund.amcName,
            isIndex = fund.isIndex,
            color = color,
            isVisible = isVisible,
            isFavourite = isFavourite,
            requestedStartEpochDay = requestedStartEpochDay,
            actualStartEpochDay = startPoint.dateEpochDay,
            actualEndEpochDay = endPoint.dateEpochDay,
            startingNav = startPoint.navValue,
            latestNav = endPoint.navValue,
            returnPercent = roundPercent(totalReturn),
            points = normalizedPoints
        )
    }

    /**
     * Computes trailing return for a single fund over [days] calendar days before its latest NAV.
     * Uses actual published NAV dates (handling weekends/holidays via nearest prior NAV within [HOLIDAY_TOLERANCE_DAYS]).
     */
    fun computeTrailingReturn(
        historyAscending: List<NavPoint>,
        days: Int,
        referenceLatestEpochDay: Long? = null
    ): TrailingReturnSnapshot? {
        if (historyAscending.size < 2 || days <= 0) return null
        val latest = historyAscending.last()
        if (latest.navValue <= 0.0) return null

        if (referenceLatestEpochDay != null &&
            (referenceLatestEpochDay - latest.dateEpochDay) > HOLIDAY_TOLERANCE_DAYS
        ) {
            return null
        }

        val targetStartDay = latest.dateEpochDay - days.toLong()
        val basePoint = findNearestNavOnOrBefore(
            sortedAscending = historyAscending,
            targetEpochDay = targetStartDay,
            maxLookbackDays = HOLIDAY_TOLERANCE_DAYS
        ) ?: return null

        if (basePoint.navValue <= 0.0 || basePoint.dateEpochDay >= latest.dateEpochDay) return null

        val ret = ((latest.navValue / basePoint.navValue) - 1.0) * 100.0
        return TrailingReturnSnapshot(
            startEpochDay = basePoint.dateEpochDay,
            startNav = basePoint.navValue,
            endEpochDay = latest.dateEpochDay,
            endNav = latest.navValue,
            returnPercent = roundPercent(ret)
        )
    }

    fun computeRankings(
        catalog: Map<String, FundMasterEntity>,
        historiesByScheme: Map<String, List<NavPoint>>,
        favouriteCodes: Set<String>,
        days: Int,
        limit: Int = 20
    ): RankingsResult {
        if (historiesByScheme.isEmpty() || days <= 0) {
            return RankingsResult(
                days = days,
                topPerformers = emptyList(),
                worstPerformers = emptyList(),
                evaluatedCount = 0,
                excludedInsufficientCount = catalog.size,
                effectiveEndEpochDay = null,
                effectiveTargetStartEpochDay = null
            )
        }

        // Determine active market reference date (most recent NAV date present across mutual funds)
        val mfLatestDates = historiesByScheme.entries
            .filter { (code, pts) -> catalog[code]?.isIndex != true && pts.isNotEmpty() }
            .mapNotNull { it.value.lastOrNull()?.dateEpochDay }
        val universeLatestDay = mfLatestDates.maxOrNull()
            ?: historiesByScheme.values.mapNotNull { it.lastOrNull()?.dateEpochDay }.maxOrNull()

        val eligibleEntries = ArrayList<FundRankingEntry>()
        var excluded = 0

        for ((code, fund) in catalog) {
            if (fund.isIndex) continue // Rankings track mutual fund schemes
            val raw = historiesByScheme[code]
            if (raw.isNullOrEmpty()) {
                excluded++
                continue
            }
            val sorted = if (isSortedAscending(raw)) raw else raw.sortedBy { it.dateEpochDay }
            val snapshot = computeTrailingReturn(sorted, days, universeLatestDay)
            if (snapshot == null) {
                excluded++
                continue
            }
            eligibleEntries.add(
                FundRankingEntry(
                    rank = 0,
                    schemeCode = code,
                    schemeName = fund.schemeName,
                    amcName = fund.amcName,
                    category = fund.category,
                    isIndex = false,
                    startEpochDay = snapshot.startEpochDay,
                    startNav = snapshot.startNav,
                    endEpochDay = snapshot.endEpochDay,
                    endNav = snapshot.endNav,
                    returnPercent = snapshot.returnPercent,
                    isFavourite = favouriteCodes.contains(code)
                )
            )
        }

        val top = eligibleEntries
            .sortedByDescending { it.returnPercent }
            .take(limit)
            .mapIndexed { idx, entry -> entry.copy(rank = idx + 1) }

        val worst = eligibleEntries
            .sortedBy { it.returnPercent }
            .take(limit)
            .mapIndexed { idx, entry -> entry.copy(rank = idx + 1) }

        return RankingsResult(
            days = days,
            topPerformers = top,
            worstPerformers = worst,
            evaluatedCount = eligibleEntries.size,
            excludedInsufficientCount = excluded,
            effectiveEndEpochDay = universeLatestDay,
            effectiveTargetStartEpochDay = universeLatestDay?.let { it - days.toLong() }
        )
    }

    /**
     * Binary search for the latest actual NAV point on or before [targetEpochDay], provided it is
     * within [maxLookbackDays] (handles weekends and market holidays without interpolating).
     */
    fun findNearestNavOnOrBefore(
        sortedAscending: List<NavPoint>,
        targetEpochDay: Long,
        maxLookbackDays: Long = Long.MAX_VALUE
    ): NavPoint? {
        if (sortedAscending.isEmpty()) return null
        var low = 0
        var high = sortedAscending.lastIndex
        var ans: NavPoint? = null
        while (low <= high) {
            val mid = (low + high) ushr 1
            val pt = sortedAscending[mid]
            if (pt.dateEpochDay <= targetEpochDay) {
                ans = pt
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (ans == null) return null
        if (maxLookbackDays != Long.MAX_VALUE && abs(targetEpochDay - ans.dateEpochDay) > maxLookbackDays) {
            return null
        }
        return ans
    }

    fun findNearestPointInSeries(
        points: List<Pair<Long, Double>>,
        targetEpochDay: Long
    ): Pair<Long, Double>? {
        if (points.isEmpty()) return null
        var low = 0
        var high = points.lastIndex
        var ans: Pair<Long, Double>? = null
        while (low <= high) {
            val mid = (low + high) ushr 1
            val pt = points[mid]
            if (pt.first <= targetEpochDay) {
                ans = pt
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return ans
    }

    private fun isSortedAscending(list: List<NavPoint>): Boolean {
        for (i in 1 until list.size) {
            if (list[i].dateEpochDay < list[i - 1].dateEpochDay) return false
        }
        return true
    }

    fun roundPercent(value: Double): Double {
        if (value.isNaN() || value.isInfinite()) return 0.0
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toDouble()
    }
}

data class TrailingReturnSnapshot(
    val startEpochDay: Long,
    val startNav: Double,
    val endEpochDay: Long,
    val endNav: Double,
    val returnPercent: Double
)
