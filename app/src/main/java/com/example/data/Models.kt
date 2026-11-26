package com.example.data

import androidx.compose.ui.graphics.Color
import com.example.ui.theme.ChartSeriesPalette
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

enum class ComparePeriod(
    val label: String,
    val months: Int? = null,
    val years: Int? = null,
    val isInception: Boolean = false,
    val isYoungestInception: Boolean = false,
    val isCustom: Boolean = false
) {
    M1("1M", months = 1),
    M3("3M", months = 3),
    M6("6M", months = 6),
    Y1("1Y", years = 1),
    Y2("2Y", years = 2),
    Y3("3Y", years = 3),
    Y5("5Y", years = 5),
    Y10("10Y", years = 10),
    YOUNGEST_INCEPTION("Youngest Inception", isYoungestInception = true),
    INCEPTION("Since Inception", isInception = true),
    CUSTOM("Custom", isCustom = true)
}

data class NavPoint(
    val dateEpochDay: Long,
    val navValue: Double
)

data class YoungestInceptionInfo(
    val youngestSchemeCode: String,
    val youngestSchemeName: String,
    val effectiveInceptionEpochDay: Long,
    val recordedInceptionEpochDay: Long?,
    val actualFirstNavEpochDay: Long?,
    val hasRecordedVsActualMismatch: Boolean
)

data class FundComparisonSeries(
    val schemeCode: String,
    val schemeName: String,
    val amcName: String,
    val isIndex: Boolean,
    val color: Color,
    val isVisible: Boolean,
    val isFavourite: Boolean,
    val requestedStartEpochDay: Long?,
    val actualStartEpochDay: Long,
    val actualEndEpochDay: Long,
    val startingNav: Double,
    val latestNav: Double,
    val returnPercent: Double,
    /** (dateEpochDay, normalizedPercentageReturn) aligned with actual NAV dates only */
    val points: List<Pair<Long, Double>>
)

data class FundRankingEntry(
    val rank: Int,
    val schemeCode: String,
    val schemeName: String,
    val amcName: String,
    val category: String,
    val isIndex: Boolean,
    val startEpochDay: Long,
    val startNav: Double,
    val endEpochDay: Long,
    val endNav: Double,
    val returnPercent: Double,
    val isFavourite: Boolean
)

data class RankingsResult(
    val days: Int,
    val topPerformers: List<FundRankingEntry>,
    val worstPerformers: List<FundRankingEntry>,
    val evaluatedCount: Int,
    val excludedInsufficientCount: Int,
    val effectiveEndEpochDay: Long?,
    val effectiveTargetStartEpochDay: Long?,
    val selectedCategoryCount: Int = 0
)

data class FavouriteFundUiItem(
    val schemeCode: String,
    val schemeName: String,
    val amcName: String,
    val category: String,
    val isIndex: Boolean,
    val latestNav: Double?,
    val latestNavEpochDay: Long?,
    val return1M: Double?,
    val return1Y: Double?,
    val addedAt: Long
)

data class SyncStatusState(
    val isSchemeMasterSyncing: Boolean = false,
    val isNavSyncing: Boolean = false,
    val isUniverseHistorySyncing: Boolean = false,
    val eligibleCatalogCount: Int = 0,
    val inactiveSchemeCount: Int = 0,
    val historiesSyncedCount: Int = 0,
    val indexCount: Int = 0,
    val lastSchemeMasterSyncMillis: Long = 0L,
    val lastNavSyncMillis: Long = 0L,
    val lastDataEpochDay: Long? = null,
    val newSchemesDiscovered: Int = 0,
    val newNavRecordsInserted: Int = 0,
    val databaseSizeBytes: Long = 0L,
    val statusMessage: String = "Initializing...",
    val errorMessage: String? = null
) {
    val isCatalogSyncing: Boolean
        get() = isSchemeMasterSyncing || isNavSyncing
}

object DateFormatUtils {
    private val displayFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)
    private val shortFormatter = DateTimeFormatter.ofPattern("dd MMM yy", Locale.US)
    private val compactAxisFormatter = DateTimeFormatter.ofPattern("MMM yy", Locale.US)
    private val timestampFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.US)
    private val istZone = java.time.ZoneId.of("Asia/Kolkata")

    fun formatDisplay(epochDay: Long?): String {
        if (epochDay == null) return "—"
        return LocalDate.ofEpochDay(epochDay).format(displayFormatter)
    }

    fun formatShort(epochDay: Long?): String {
        if (epochDay == null) return "—"
        return LocalDate.ofEpochDay(epochDay).format(shortFormatter)
    }

    fun formatAxis(epochDay: Long?): String {
        if (epochDay == null) return ""
        return LocalDate.ofEpochDay(epochDay).format(compactAxisFormatter)
    }

    fun formatTimestamp(millis: Long): String {
        if (millis <= 0L) return "Never"
        return java.time.Instant.ofEpochMilli(millis).atZone(istZone).format(timestampFormatter) + " IST"
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 KB"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.2f MB", mb)
    }

    fun colorForSeries(schemeCode: String, fallbackIndex: Int = 0): Color {
        val idx = when (schemeCode) {
            "IDX_NIFTY50" -> 1
            "IDX_NIFTYMIDCAP150" -> 3
            "IDX_NIFTYSMALLCAP250" -> 2
            "IDX_NIFTYNEXT50" -> 4
            "IDX_NIFTY500" -> 5
            "IDX_SENSEX" -> 6
            else -> (abs(schemeCode.hashCode()) + fallbackIndex) % ChartSeriesPalette.size
        }
        return ChartSeriesPalette[idx % ChartSeriesPalette.size]
    }
}
