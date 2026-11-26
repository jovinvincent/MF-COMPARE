package com.example.data

import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.exp

enum class SipTenure(val label: String, val shortLabel: String) {
    DAILY("Daily", "Daily"),
    WEEKLY("Weekly", "Weekly"),
    BIWEEKLY("Biweekly", "Biweekly"),
    MONTHLY("Monthly", "Monthly")
}

enum class SipStartMode(val label: String) {
    YOUNGEST_INCEPTION("Youngest Inception"),
    FROM_INCEPTION("From Inception"),
    CUSTOM_DATE("Calendar Date")
}

data class SipFundConfig(
    val schemeCode: String,
    val amount: Double = 1000.0,
    val tenure: SipTenure = SipTenure.MONTHLY
)

/**
 * Single chronological SIP installment lot tracked in strict FIFO order.
 */
data class SipFifoLot(
    val lotNumber: Int,
    val buyEpochDay: Long,
    val navUsed: Double,
    val investedAmount: Double,
    val unitsAllotted: Double
)

data class SipFundResult(
    val schemeCode: String,
    val schemeName: String,
    val amcName: String,
    val isIndex: Boolean,
    val color: Color,
    val sipAmount: Double,
    val tenure: SipTenure,
    val effectiveStartEpochDay: Long?,
    val firstInstallmentEpochDay: Long?,
    val lastInstallmentEpochDay: Long?,
    val valuationEpochDay: Long?,
    val latestNav: Double?,
    val fifoLots: List<SipFifoLot>,
    val installmentCount: Int,
    val totalInvested: Double,
    val totalUnits: Double,
    val currentValue: Double,
    val absoluteReturnPercent: Double,
    val xirrPercent: Double?,
    val averagePurchaseNav: Double?,
    /** (dateEpochDay, cumulative FIFO absolute return %) across all NAV dates from first lot to valuation */
    val timelinePoints: List<Pair<Long, Double>>,
    val errorNote: String? = null
) {
    val isValid: Boolean
        get() = errorNote == null && installmentCount > 0 && totalInvested > 0.0
}

enum class SipRankMetric(val label: String, val shortLabel: String) {
    XIRR("XIRR (Annualized %)", "XIRR"),
    ABSOLUTE_RETURN("Absolute Return (%)", "Absolute Return")
}

data class SipRankingEntry(
    val rank: Int,
    val schemeCode: String,
    val schemeName: String,
    val amcName: String,
    val category: String,
    val isIndex: Boolean,
    val totalInvested: Double,
    val currentValue: Double,
    val absoluteReturnPercent: Double,
    val xirrPercent: Double?,
    val installmentCount: Int,
    val firstInstallmentDay: Long,
    val lastValuationDay: Long,
    val isFavourite: Boolean
)

data class SipRankingsResult(
    val tenure: SipTenure,
    val sipAmount: Double,
    val startEpochDay: Long,
    val endEpochDay: Long?,
    val topPerformers: List<SipRankingEntry>,
    val evaluatedCount: Int,
    val excludedCount: Int,
    val rankMetric: SipRankMetric,
    val selectedCategoryCount: Int = 0
)

/**
 * Pure SIP, FIFO Lot Ledger, Worst-Case (Highest NAV in Window) Bucketing, and XIRR Engine.
 */
object SipCalculator {

    private const val DAYS_PER_YEAR = 365.0

    fun computeSipResult(
        fund: FundMasterEntity,
        rawHistory: List<NavPoint>,
        config: SipFundConfig,
        startMode: SipStartMode,
        customStartEpochDay: Long?,
        youngestInceptionEpochDay: Long?,
        color: Color
    ): SipFundResult {
        val amount = config.amount
        if (amount.isNaN() || amount.isInfinite() || amount <= 0.0) {
            return emptyResult(fund, config, color, "Enter a valid SIP amount above 0")
        }

        if (rawHistory.isEmpty()) {
            return emptyResult(fund, config, color, "Historical NAV data not loaded yet")
        }

        val sorted = rawHistory
            .asSequence()
            .filter { it.navValue > 0.0 && it.navValue.isFinite() }
            .sortedBy { it.dateEpochDay }
            .distinctBy { it.dateEpochDay }
            .toList()

        if (sorted.isEmpty()) {
            return emptyResult(fund, config, color, "No valid NAV history available")
        }

        val firstActualDay = sorted.first().dateEpochDay
        val effectiveStartEpochDay = when (startMode) {
            SipStartMode.FROM_INCEPTION -> firstActualDay
            SipStartMode.YOUNGEST_INCEPTION -> maxOf(firstActualDay, youngestInceptionEpochDay ?: firstActualDay)
            SipStartMode.CUSTOM_DATE -> maxOf(firstActualDay, customStartEpochDay ?: firstActualDay)
        }

        val anchorEpochDay = when (startMode) {
            SipStartMode.FROM_INCEPTION -> firstActualDay
            SipStartMode.YOUNGEST_INCEPTION -> youngestInceptionEpochDay ?: firstActualDay
            SipStartMode.CUSTOM_DATE -> customStartEpochDay ?: firstActualDay
        }

        val windowPoints = sorted.filter { it.dateEpochDay >= effectiveStartEpochDay }
        if (windowPoints.isEmpty()) {
            return emptyResult(fund, config, color, "No NAV records on or after ${DateFormatUtils.formatShort(effectiveStartEpochDay)}")
        }

        // Select installment NAVs per timeframe:
        // - Daily: every trading day's actual NAV
        // - Weekly / Biweekly / Monthly: HIGHEST NAV within each timeframe window (worst-case return)
        val selectedNavPoints = selectHighestNavPointsByTenure(
            windowPoints = windowPoints,
            anchorEpochDay = anchorEpochDay,
            tenure = config.tenure
        )

        if (selectedNavPoints.isEmpty()) {
            return emptyResult(fund, config, color, "No eligible SIP dates found in selected range")
        }

        // Build chronological FIFO lot queue
        val fifoQueue = ArrayDeque<SipFifoLot>(selectedNavPoints.size)
        var prevBuyDay = Long.MIN_VALUE
        selectedNavPoints.forEachIndexed { idx, pt ->
            check(pt.dateEpochDay > prevBuyDay) { "FIFO ordering invariant violated for ${fund.schemeCode}" }
            prevBuyDay = pt.dateEpochDay
            val units = amount / pt.navValue
            fifoQueue.addLast(
                SipFifoLot(
                    lotNumber = idx + 1,
                    buyEpochDay = pt.dateEpochDay,
                    navUsed = pt.navValue,
                    investedAmount = amount,
                    unitsAllotted = units
                )
            )
        }

        val lots = fifoQueue.toList()
        val valuationPoint = windowPoints.last()
        val latestNav = valuationPoint.navValue

        // Verify FIFO redemption / mark-to-market unit & cost-basis accounting in strict FIFO order
        var totalInvested = 0.0
        var totalUnits = 0.0
        var totalRedemptionProceeds = 0.0
        val fifoDrainQueue = ArrayDeque(lots)
        while (fifoDrainQueue.isNotEmpty()) {
            val lot = fifoDrainQueue.removeFirst()
            totalInvested += lot.investedAmount
            totalUnits += lot.unitsAllotted
            totalRedemptionProceeds += lot.unitsAllotted * latestNav
        }

        val currentValue = totalRedemptionProceeds
        val absoluteReturn = if (totalInvested > 0.0) {
            PerformanceCalculator.roundPercent(((currentValue - totalInvested) / totalInvested) * 100.0)
        } else {
            0.0
        }

        val averagePurchaseNav = if (totalUnits > 0.0) totalInvested / totalUnits else null

        val xirr = computeXirrPercent(
            lots = lots,
            valuationEpochDay = valuationPoint.dateEpochDay,
            terminalValue = currentValue
        )

        val timeline = buildCumulativeReturnTimeline(
            windowPoints = windowPoints,
            lots = lots
        )

        return SipFundResult(
            schemeCode = fund.schemeCode,
            schemeName = fund.schemeName,
            amcName = fund.amcName,
            isIndex = fund.isIndex,
            color = color,
            sipAmount = amount,
            tenure = config.tenure,
            effectiveStartEpochDay = anchorEpochDay,
            firstInstallmentEpochDay = lots.first().buyEpochDay,
            lastInstallmentEpochDay = lots.last().buyEpochDay,
            valuationEpochDay = valuationPoint.dateEpochDay,
            latestNav = latestNav,
            fifoLots = lots,
            installmentCount = lots.size,
            totalInvested = totalInvested,
            totalUnits = totalUnits,
            currentValue = currentValue,
            absoluteReturnPercent = absoluteReturn,
            xirrPercent = xirr,
            averagePurchaseNav = averagePurchaseNav,
            timelinePoints = timeline,
            errorNote = null
        )
    }

    /**
     * Groups chronologically sorted [windowPoints] into timeframe buckets anchored at [anchorEpochDay]
     * and chooses the highest NAV inside each Weekly, Biweekly, or Monthly window to model the
     * worst-case purchase price. Ties preserve the earliest date deterministically.
     */
    fun selectHighestNavPointsByTenure(
        windowPoints: List<NavPoint>,
        anchorEpochDay: Long,
        tenure: SipTenure
    ): List<NavPoint> {
        if (windowPoints.isEmpty()) return emptyList()
        if (tenure == SipTenure.DAILY) return windowPoints

        val selected = ArrayList<NavPoint>()
        val anchorDate = LocalDate.ofEpochDay(anchorEpochDay)

        when (tenure) {
            SipTenure.DAILY -> return windowPoints

            SipTenure.WEEKLY, SipTenure.BIWEEKLY -> {
                val stepDays = if (tenure == SipTenure.WEEKLY) 7L else 14L
                var currentBucket = Long.MIN_VALUE
                var bestInBucket: NavPoint? = null

                for (pt in windowPoints) {
                    val offset = (pt.dateEpochDay - anchorEpochDay).coerceAtLeast(0L)
                    val bucket = offset / stepDays
                    if (bucket != currentBucket) {
                        if (bestInBucket != null) selected.add(bestInBucket)
                        currentBucket = bucket
                        bestInBucket = pt
                    } else if (bestInBucket == null || pt.navValue > bestInBucket.navValue) {
                        bestInBucket = pt
                    }
                }
                if (bestInBucket != null) selected.add(bestInBucket)
            }

            SipTenure.MONTHLY -> {
                var periodIndex = 0L
                var nextBoundaryEpochDay = anchorDate.plusMonths(1).toEpochDay()
                var bestInBucket: NavPoint? = null

                for (pt in windowPoints) {
                    if (pt.dateEpochDay >= nextBoundaryEpochDay) {
                        if (bestInBucket != null) {
                            selected.add(bestInBucket)
                            bestInBucket = null
                        }
                        while (pt.dateEpochDay >= nextBoundaryEpochDay) {
                            periodIndex++
                            nextBoundaryEpochDay = anchorDate.plusMonths(periodIndex + 1L).toEpochDay()
                        }
                    }
                    if (bestInBucket == null || pt.navValue > bestInBucket.navValue) {
                        bestInBucket = pt
                    }
                }
                if (bestInBucket != null) selected.add(bestInBucket)
            }
        }

        return selected
    }

    /**
     * Computes annualized XIRR (%) using log-rate substitution x = ln(1 + r) so (1 + r) = exp(x) > 0
     * with terminal future-value normalization g(x) = sum(c_i * exp(x * (y_end - y_i))), solved via
     * safeguarded Newton-Raphson + bisection. Returns null if duration <= 0 days.
     */
    fun computeXirrPercent(
        lots: List<SipFifoLot>,
        valuationEpochDay: Long,
        terminalValue: Double
    ): Double? {
        if (lots.isEmpty() || terminalValue.isNaN() || terminalValue.isInfinite()) return null
        val firstDay = lots.first().buyEpochDay
        if (valuationEpochDay <= firstDay) return null
        if (terminalValue <= 0.0) return -100.0

        val aggregatedByDay = TreeMap<Long, Double>()
        for (lot in lots) {
            aggregatedByDay[lot.buyEpochDay] = (aggregatedByDay[lot.buyEpochDay] ?: 0.0) - lot.investedAmount
        }
        aggregatedByDay[valuationEpochDay] = (aggregatedByDay[valuationEpochDay] ?: 0.0) + terminalValue

        val cashflows = aggregatedByDay.entries
            .filter { abs(it.value) > 1e-9 }
            .map { (day, amount) -> day to amount }

        if (cashflows.size < 2) return null

        val hasPositive = cashflows.any { it.second > 0.0 }
        val hasNegative = cashflows.any { it.second < 0.0 }
        if (!hasPositive || !hasNegative) return null

        val endYears = (valuationEpochDay - firstDay) / DAYS_PER_YEAR
        if (endYears <= 0.0) return null

        val termPairs = cashflows.map { (day, amount) ->
            val tau = endYears - ((day - firstDay) / DAYS_PER_YEAR)
            tau to amount
        }

        fun evalG(x: Double): Pair<Double, Double> {
            var g = 0.0
            var dg = 0.0
            for ((tau, amount) in termPairs) {
                if (tau == 0.0) {
                    g += amount
                } else {
                    val exponent = (x * tau).coerceIn(-60.0, 60.0)
                    val factor = exp(exponent)
                    g += amount * factor
                    dg += amount * tau * factor
                }
            }
            return g to dg
        }

        var lowX = -15.0
        var highX = 15.0
        var gLow = evalG(lowX).first
        var gHigh = evalG(highX).first

        var expandCount = 0
        while (gLow < 0.0 && expandCount < 10) {
            lowX -= 5.0
            gLow = evalG(lowX).first
            expandCount++
        }
        expandCount = 0
        while (gHigh > 0.0 && expandCount < 10) {
            highX += 5.0
            gHigh = evalG(highX).first
            expandCount++
        }

        if (gLow * gHigh > 0.0) return null

        var x = 0.1.coerceIn(lowX + 1e-6, highX - 1e-6)
        for (iter in 0 until 100) {
            val (g, dg) = evalG(x)
            if (abs(g) <= 1e-8) break
            if (g > 0.0) lowX = x else highX = x

            val newton = if (abs(dg) > 1e-12) x - g / dg else Double.NaN
            x = if (newton.isFinite() && newton > lowX && newton < highX) {
                newton
            } else {
                0.5 * (lowX + highX)
            }
            if (abs(highX - lowX) <= 1e-10) break
        }

        val rate = exp(x) - 1.0
        if (!rate.isFinite()) return null
        return PerformanceCalculator.roundPercent(rate * 100.0)
    }

    private fun buildCumulativeReturnTimeline(
        windowPoints: List<NavPoint>,
        lots: List<SipFifoLot>
    ): List<Pair<Long, Double>> {
        if (windowPoints.isEmpty() || lots.isEmpty()) return emptyList()
        val timeline = ArrayList<Pair<Long, Double>>(windowPoints.size)
        var lotIdx = 0
        var cumInvested = 0.0
        var cumUnits = 0.0

        for (pt in windowPoints) {
            while (lotIdx < lots.size && lots[lotIdx].buyEpochDay <= pt.dateEpochDay) {
                cumInvested += lots[lotIdx].investedAmount
                cumUnits += lots[lotIdx].unitsAllotted
                lotIdx++
            }
            if (cumInvested > 0.0) {
                val marketVal = cumUnits * pt.navValue
                val retPct = ((marketVal - cumInvested) / cumInvested) * 100.0
                timeline.add(pt.dateEpochDay to retPct)
            }
        }
        return timeline
    }

    private fun emptyResult(
        fund: FundMasterEntity,
        config: SipFundConfig,
        color: Color,
        errorNote: String
    ): SipFundResult = SipFundResult(
        schemeCode = fund.schemeCode,
        schemeName = fund.schemeName,
        amcName = fund.amcName,
        isIndex = fund.isIndex,
        color = color,
        sipAmount = config.amount,
        tenure = config.tenure,
        effectiveStartEpochDay = null,
        firstInstallmentEpochDay = null,
        lastInstallmentEpochDay = null,
        valuationEpochDay = null,
        latestNav = null,
        fifoLots = emptyList(),
        installmentCount = 0,
        totalInvested = 0.0,
        totalUnits = 0.0,
        currentValue = 0.0,
        absoluteReturnPercent = 0.0,
        xirrPercent = null,
        averagePurchaseNav = null,
        timelinePoints = emptyList(),
        errorNote = errorNote
    )
}
