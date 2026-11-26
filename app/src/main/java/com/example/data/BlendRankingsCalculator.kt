package com.example.data

import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import java.util.Locale

enum class BlendTimeframe(val label: String, val months: Long?, val years: Long?) {
    M6("6M", 6L, null),
    Y1("1Y", null, 1L),
    Y3("3Y", null, 3L),
    Y5("5Y", null, 5L),
    Y7("7Y", null, 7L),
    Y10("10Y", null, 10L),
    CUSTOM("Custom", null, null);

    fun resolveStartEpochDay(referenceDay: Long = LocalDate.now().toEpochDay(), customEpochDay: Long? = null): Long {
        val refDate = LocalDate.ofEpochDay(referenceDay)
        return when (this) {
            M6 -> refDate.minusMonths(6).toEpochDay()
            Y1 -> refDate.minusYears(1).toEpochDay()
            Y3 -> refDate.minusYears(3).toEpochDay()
            Y5 -> refDate.minusYears(5).toEpochDay()
            Y7 -> refDate.minusYears(7).toEpochDay()
            Y10 -> refDate.minusYears(10).toEpochDay()
            CUSTOM -> customEpochDay ?: refDate.minusYears(1).toEpochDay()
        }
    }
}

enum class BlendSortMetric(val label: String) {
    BLEND_SCORE("Blend Score"),
    SIP_XIRR("SIP XIRR"),
    ROLLING_MEDIAN("Rolling Median"),
    CONSISTENCY("Consistency")
}

data class BlendRankingEntry(
    val rank: Int,
    val schemeCode: String,
    val schemeName: String,
    val amcName: String,
    val category: String,
    val isIndex: Boolean,
    val blendScore: Double,
    val sipXirr: Double?,
    val rollingMean: Double,
    val rollingMedian: Double,
    val consistencyPercent: Double,
    val sipPercentile: Double,
    val rollingScore: Double,
    val rollingMedianPercentile: Double,
    val rollingMeanPercentile: Double,
    val rollingConsistencyPercentile: Double,
    val observationCount: Int,
    val stdDevPercent: Double,
    val firstWindowEndDay: Long?,
    val lastWindowEndDay: Long?,
    val effectiveStartDay: Long?,
    val isFavourite: Boolean
)

data class BlendRankingsResult(
    val timeframeLabel: String,
    val rollingWindow: RollingWindow,
    val sipTenure: SipTenure,
    val sipAmount: Double,
    val sipWeight: Double,
    val rollingWeight: Double,
    val peerGroupAll: Boolean,
    val selectedCategoryCount: Int,
    val topPerformers: List<BlendRankingEntry>,
    val evaluatedCount: Int,
    val qualifiedCount: Int,
    val excludedCount: Int,
    val exclusionReasons: Map<String, String>,
    val sortBy: BlendSortMetric
)

object BlendRankingsCalculator {

    fun computePercentiles(values: List<Double?>): Map<Double?, Double> {
        val valid = values.filterNotNull().sorted()
        if (valid.isEmpty()) return emptyMap()
        val result = HashMap<Double?, Double>()
        for (v in values) {
            if (v == null) {
                result[null] = 0.0
                continue
            }
            val countBelowOrEqual = valid.count { it <= v }
            val percentile = (countBelowOrEqual.toDouble() / valid.size.toDouble()) * 100.0
            result[v] = percentile
        }
        return result
    }
}
