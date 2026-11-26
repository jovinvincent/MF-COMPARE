package com.example.data

import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import kotlin.math.sqrt

enum class RollingWindow(
    val label: String,
    val shortBadge: String,
    val description: String
) {
    WEEKLY("Weekly", "7D", "7-day rolling windows"),
    BIWEEKLY("Biweekly", "14D", "14-day rolling windows"),
    MONTHLY("Monthly", "1M", "1-month rolling windows"),
    QUARTERLY("Quarterly", "3M", "3-month rolling windows"),
    YEARLY("Yearly", "1Y", "1-year rolling windows")
}

enum class RollingStartMode(val label: String) {
    YOUNGEST_INCEPTION("Youngest Inception"),
    FROM_INCEPTION("From Inception"),
    CUSTOM_DATE("Calendar Date")
}

data class RollingObservation(
    val startEpochDay: Long,
    val endEpochDay: Long,
    val startNav: Double,
    val endNav: Double,
    val returnPercent: Double
)

data class RollingFundResult(
    val schemeCode: String,
    val schemeName: String,
    val amcName: String,
    val isIndex: Boolean,
    val color: Color,
    val window: RollingWindow,
    val effectiveStartEpochDay: Long?,
    val firstWindowEndEpochDay: Long?,
    val lastWindowEndEpochDay: Long?,
    val observationCount: Int,
    val averageReturnPercent: Double,
    val medianReturnPercent: Double,
    val minReturnPercent: Double,
    val maxReturnPercent: Double,
    val latestReturnPercent: Double,
    val positivePercent: Double,
    val stdDevPercent: Double,
    val minObservation: RollingObservation?,
    val maxObservation: RollingObservation?,
    val latestObservation: RollingObservation?,
    /** (endEpochDay, rollingReturnPercent) for chronological plotting */
    val timelinePoints: List<Pair<Long, Double>>,
    val errorNote: String? = null
) {
    val isValid: Boolean
        get() = errorNote == null && observationCount > 0
}

enum class RollingRankMetric(val label: String, val shortLabel: String) {
    AVERAGE("Average Return (%)", "Average"),
    MEDIAN("Median Return (%)", "Median"),
    LATEST("Latest Window (%)", "Latest"),
    POSITIVE_CONSISTENCY("Positive Windows (%)", "Consistency")
}

data class RollingRankingEntry(
    val rank: Int,
    val schemeCode: String,
    val schemeName: String,
    val amcName: String,
    val category: String,
    val isIndex: Boolean,
    val window: RollingWindow,
    val observationCount: Int,
    val averageReturnPercent: Double,
    val medianReturnPercent: Double,
    val minReturnPercent: Double,
    val maxReturnPercent: Double,
    val latestReturnPercent: Double,
    val positivePercent: Double,
    val stdDevPercent: Double,
    val firstWindowEndDay: Long,
    val lastWindowEndDay: Long,
    val isFavourite: Boolean
)

data class RollingRankingsResult(
    val window: RollingWindow,
    val startEpochDay: Long,
    val endEpochDay: Long?,
    val topPerformers: List<RollingRankingEntry>,
    val evaluatedCount: Int,
    val excludedCount: Int,
    val rankMetric: RollingRankMetric,
    val selectedCategoryCount: Int = 0
)

/**
 * Pure financial engine for daily-rolled mutual fund & index Rolling Return analysis.
 * Uses actual published NAV dates only (with holiday lookback tolerance), never fabricating points.
 */
object RollingReturnCalculator {

    private const val HOLIDAY_TOLERANCE_DAYS = 7L

    fun targetStartEpochDay(endEpochDay: Long, window: RollingWindow): Long {
        return when (window) {
            RollingWindow.WEEKLY -> endEpochDay - 7L
            RollingWindow.BIWEEKLY -> endEpochDay - 14L
            RollingWindow.MONTHLY -> LocalDate.ofEpochDay(endEpochDay).minusMonths(1).toEpochDay()
            RollingWindow.QUARTERLY -> LocalDate.ofEpochDay(endEpochDay).minusMonths(3).toEpochDay()
            RollingWindow.YEARLY -> LocalDate.ofEpochDay(endEpochDay).minusYears(1).toEpochDay()
        }
    }

    fun computeRollingResult(
        fund: FundMasterEntity,
        rawHistory: List<NavPoint>,
        window: RollingWindow,
        startMode: RollingStartMode,
        customStartEpochDay: Long?,
        youngestInceptionEpochDay: Long?,
        color: Color
    ): RollingFundResult {
        if (rawHistory.isEmpty()) {
            return emptyResult(fund, window, color, "Historical NAV data not loaded yet")
        }

        val sorted = rawHistory
            .asSequence()
            .filter { it.navValue > 0.0 && it.navValue.isFinite() }
            .sortedBy { it.dateEpochDay }
            .distinctBy { it.dateEpochDay }
            .toList()

        if (sorted.isEmpty()) {
            return emptyResult(fund, window, color, "No valid NAV history available")
        }

        val firstActualDay = sorted.first().dateEpochDay
        val effectiveStartEpochDay = when (startMode) {
            RollingStartMode.FROM_INCEPTION -> firstActualDay
            RollingStartMode.YOUNGEST_INCEPTION -> maxOf(firstActualDay, youngestInceptionEpochDay ?: firstActualDay)
            RollingStartMode.CUSTOM_DATE -> maxOf(firstActualDay, customStartEpochDay ?: firstActualDay)
        }

        val windowPoints = sorted.filter { it.dateEpochDay >= effectiveStartEpochDay }
        if (windowPoints.size < 2) {
            return emptyResult(
                fund,
                window,
                color,
                "Insufficient NAV points from ${DateFormatUtils.formatShort(effectiveStartEpochDay)}"
            )
        }

        val firstWindowPointDay = windowPoints.first().dateEpochDay
        val observations = ArrayList<RollingObservation>(windowPoints.size)

        for (endPt in windowPoints) {
            val targetStart = targetStartEpochDay(endPt.dateEpochDay, window)
            if (targetStart < firstWindowPointDay) continue

            val startPt = PerformanceCalculator.findNearestNavOnOrBefore(
                sortedAscending = windowPoints,
                targetEpochDay = targetStart,
                maxLookbackDays = HOLIDAY_TOLERANCE_DAYS
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

        if (observations.isEmpty()) {
            return emptyResult(
                fund,
                window,
                color,
                "Needs at least ${window.shortBadge} of NAV history from ${DateFormatUtils.formatShort(effectiveStartEpochDay)}"
            )
        }

        var sum = 0.0
        var positiveCount = 0
        var minObs = observations[0]
        var maxObs = observations[0]

        for (obs in observations) {
            sum += obs.returnPercent
            if (obs.returnPercent > 0.0) positiveCount++
            if (obs.returnPercent < minObs.returnPercent) minObs = obs
            if (obs.returnPercent > maxObs.returnPercent) maxObs = obs
        }

        val n = observations.size
        val mean = sum / n

        var varianceSum = 0.0
        for (obs in observations) {
            val diff = obs.returnPercent - mean
            varianceSum += diff * diff
        }
        val stdDev = if (n > 1) sqrt(varianceSum / n) else 0.0

        val sortedReturns = observations.map { it.returnPercent }.sorted()
        val median = if (n % 2 == 1) {
            sortedReturns[n / 2]
        } else {
            0.5 * (sortedReturns[n / 2 - 1] + sortedReturns[n / 2])
        }

        val positivePct = (positiveCount.toDouble() / n) * 100.0
        val latestObs = observations.last()
        val timeline = observations.map { it.endEpochDay to it.returnPercent }

        return RollingFundResult(
            schemeCode = fund.schemeCode,
            schemeName = fund.schemeName,
            amcName = fund.amcName,
            isIndex = fund.isIndex,
            color = color,
            window = window,
            effectiveStartEpochDay = effectiveStartEpochDay,
            firstWindowEndEpochDay = observations.first().endEpochDay,
            lastWindowEndEpochDay = latestObs.endEpochDay,
            observationCount = n,
            averageReturnPercent = PerformanceCalculator.roundPercent(mean),
            medianReturnPercent = PerformanceCalculator.roundPercent(median),
            minReturnPercent = PerformanceCalculator.roundPercent(minObs.returnPercent),
            maxReturnPercent = PerformanceCalculator.roundPercent(maxObs.returnPercent),
            latestReturnPercent = PerformanceCalculator.roundPercent(latestObs.returnPercent),
            positivePercent = PerformanceCalculator.roundPercent(positivePct),
            stdDevPercent = PerformanceCalculator.roundPercent(stdDev),
            minObservation = minObs.copy(returnPercent = PerformanceCalculator.roundPercent(minObs.returnPercent)),
            maxObservation = maxObs.copy(returnPercent = PerformanceCalculator.roundPercent(maxObs.returnPercent)),
            latestObservation = latestObs.copy(returnPercent = PerformanceCalculator.roundPercent(latestObs.returnPercent)),
            timelinePoints = timeline,
            errorNote = null
        )
    }

    private fun emptyResult(
        fund: FundMasterEntity,
        window: RollingWindow,
        color: Color,
        errorNote: String
    ): RollingFundResult = RollingFundResult(
        schemeCode = fund.schemeCode,
        schemeName = fund.schemeName,
        amcName = fund.amcName,
        isIndex = fund.isIndex,
        color = color,
        window = window,
        effectiveStartEpochDay = null,
        firstWindowEndEpochDay = null,
        lastWindowEndEpochDay = null,
        observationCount = 0,
        averageReturnPercent = 0.0,
        medianReturnPercent = 0.0,
        minReturnPercent = 0.0,
        maxReturnPercent = 0.0,
        latestReturnPercent = 0.0,
        positivePercent = 0.0,
        stdDevPercent = 0.0,
        minObservation = null,
        maxObservation = null,
        latestObservation = null,
        timelinePoints = emptyList(),
        errorNote = errorNote
    )
}
