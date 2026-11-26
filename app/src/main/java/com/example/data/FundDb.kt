package com.example.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File
import kotlin.math.abs

@Entity(
    tableName = "fund_master",
    indices = [
        Index(value = ["scheme_id"], unique = true),
        Index(value = ["is_index"]),
        Index(value = ["history_synced_at"])
    ]
)
data class FundMasterEntity(
    @PrimaryKey
    @ColumnInfo(name = "scheme_code")
    val schemeCode: String,
    @ColumnInfo(name = "scheme_id")
    val schemeId: Int = deriveSchemeId(schemeCode),
    @ColumnInfo(name = "scheme_name")
    val schemeName: String,
    @ColumnInfo(name = "search_tokens")
    val searchTokens: String,
    @ColumnInfo(name = "amc_name")
    val amcName: String,
    val category: String,
    val isin: String?,
    @ColumnInfo(name = "dedup_key")
    val dedupKey: String,
    @ColumnInfo(name = "is_index")
    val isIndex: Boolean,
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,
    @ColumnInfo(name = "status_note")
    val statusNote: String? = null,
    @ColumnInfo(name = "recorded_inception_epoch_day")
    val recordedInceptionEpochDay: Long? = null,
    @ColumnInfo(name = "inception_epoch_day")
    val inceptionEpochDay: Long? = null,
    @ColumnInfo(name = "latest_nav")
    val latestNav: Double? = null,
    @ColumnInfo(name = "latest_nav_date_epoch_day")
    val latestNavDateEpochDay: Long? = null,
    @ColumnInfo(name = "return_1m")
    val return1M: Double? = null,
    @ColumnInfo(name = "return_1y")
    val return1Y: Double? = null,
    @ColumnInfo(name = "history_synced_at")
    val historySyncedAt: Long = 0L,
    @ColumnInfo(name = "data_source")
    val dataSource: String,
    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long
) {
    companion object {
        fun deriveSchemeId(schemeCode: String): Int {
            val numeric = schemeCode.toIntOrNull()
            if (numeric != null && numeric > 0) return numeric
            return when (schemeCode) {
                "IDX_NIFTY50" -> -1
                "IDX_NIFTYNEXT50" -> -2
                "IDX_NIFTYMIDCAP150" -> -3
                "IDX_NIFTYSMALLCAP250" -> -4
                "IDX_NIFTY500" -> -5
                "IDX_SENSEX" -> -6
                else -> -(abs(schemeCode.hashCode()).coerceAtLeast(100))
            }
        }
    }
}

/**
 * In-memory parsed NAV point DTO returned by RemoteDataSources.
 * Persisted in compact binary form inside [NavBucketEntity].
 */
data class NavHistoryEntity(
    val schemeCode: String,
    val dateEpochDay: Long,
    val navValue: Double,
    val dataSource: String = "MFAPI",
    val syncedAt: Long = 0L
)

/**
 * Year-partitioned delta-varint NAV storage.
 * Eliminates repeated scheme_code text, data_source, synced_at, and multi-million-row B-tree bloat.
 */
@Entity(
    tableName = "nav_buckets",
    primaryKeys = ["scheme_id", "year"],
    indices = [
        Index(value = ["year", "max_epoch_day"])
    ]
)
data class NavBucketEntity(
    @ColumnInfo(name = "scheme_id")
    val schemeId: Int,
    val year: Int,
    @ColumnInfo(name = "point_count")
    val pointCount: Int,
    @ColumnInfo(name = "min_epoch_day")
    val minEpochDay: Int,
    @ColumnInfo(name = "max_epoch_day")
    val maxEpochDay: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NavBucketEntity) return false
        return schemeId == other.schemeId &&
            year == other.year &&
            pointCount == other.pointCount &&
            minEpochDay == other.minEpochDay &&
            maxEpochDay == other.maxEpochDay &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = schemeId
        result = 31 * result + year
        result = 31 * result + pointCount
        result = 31 * result + minEpochDay
        result = 31 * result + maxEpochDay
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

@Entity(tableName = "favourite_funds")
data class FavouriteFundEntity(
    @PrimaryKey
    @ColumnInfo(name = "scheme_code")
    val schemeCode: String,
    @ColumnInfo(name = "added_at")
    val addedAt: Long
)

@Entity(tableName = "sync_metadata")
data class SyncMetadataEntity(
    @PrimaryKey
    val id: Int = 1,
    @ColumnInfo(name = "last_scheme_master_sync_millis")
    val lastSchemeMasterSyncMillis: Long = 0L,
    @ColumnInfo(name = "last_nav_sync_millis")
    val lastNavSyncMillis: Long = 0L,
    @ColumnInfo(name = "last_universe_sync_millis")
    val lastUniverseSyncMillis: Long = 0L,
    @ColumnInfo(name = "last_data_epoch_day")
    val lastDataEpochDay: Long? = null,
    @ColumnInfo(name = "last_new_schemes_discovered")
    val lastNewSchemesDiscovered: Int = 0,
    @ColumnInfo(name = "last_new_nav_records_inserted")
    val lastNewNavRecordsInserted: Int = 0,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null
)

data class NavMergeResult(
    val insertedPointCount: Int,
    val modifiedBucketCount: Int,
    val minEpochDay: Long?,
    val maxEpochDay: Long?,
    val latestNavValue: Double?
)

@Dao
interface FundDao {

    /**
     * Emits a lightweight 1-row signature whenever [fund_master] membership or latest catalog/NAV sync
     * changes, avoiding reloading 5,000 rows per scheme during bulk historical backfill.
     */
    @Query(
        """
        SELECT COALESCE(COUNT(*), 0) || ':' ||
               COALESCE(SUM(CASE WHEN is_active = 1 THEN 1 ELSE 0 END), 0) || ':' ||
               COALESCE(MAX(last_synced_at), 0) || ':' ||
               COALESCE(MAX(latest_nav_date_epoch_day), 0)
        FROM fund_master
        """
    )
    fun observeCatalogSignature(): Flow<String>

    fun observeAllFunds(): Flow<List<FundMasterEntity>> =
        observeCatalogSignature()
            .distinctUntilChanged()
            .map { getAllFunds() }

    @Query("SELECT * FROM fund_master WHERE is_active = 1 ORDER BY is_index DESC, scheme_name ASC LIMIT :limit OFFSET :offset")
    suspend fun getActiveFundsPage(limit: Int, offset: Int): List<FundMasterEntity>

    @Transaction
    suspend fun getAllFunds(): List<FundMasterEntity> {
        val result = ArrayList<FundMasterEntity>()
        var offset = 0
        val pageSize = 400
        while (true) {
            val page = getActiveFundsPage(pageSize, offset)
            if (page.isEmpty()) break
            result.addAll(page)
            if (page.size < pageSize) break
            offset += pageSize
        }
        return result
    }

    @Query("SELECT * FROM fund_master ORDER BY scheme_code ASC LIMIT :limit OFFSET :offset")
    suspend fun getAllFundsIncludingInactivePage(limit: Int, offset: Int): List<FundMasterEntity>

    @Transaction
    suspend fun getAllFundsIncludingInactive(): List<FundMasterEntity> {
        val result = ArrayList<FundMasterEntity>()
        var offset = 0
        val pageSize = 400
        while (true) {
            val page = getAllFundsIncludingInactivePage(pageSize, offset)
            if (page.isEmpty()) break
            result.addAll(page)
            if (page.size < pageSize) break
            offset += pageSize
        }
        return result
    }

    @Query("SELECT COUNT(*) FROM fund_master WHERE is_active = 1")
    suspend fun getActiveTotalCount(): Int

    @Query("SELECT COUNT(*) FROM fund_master WHERE is_active = 1 AND is_index = 0")
    suspend fun getActiveMutualFundCount(): Int

    @Query("SELECT * FROM fund_master WHERE scheme_code = :schemeCode LIMIT 1")
    suspend fun getFundByCode(schemeCode: String): FundMasterEntity?

    @Query("SELECT * FROM fund_master WHERE scheme_code IN (:schemeCodes)")
    suspend fun getFundsByCodesChunk(schemeCodes: List<String>): List<FundMasterEntity>

    suspend fun getFundsByCodes(schemeCodes: List<String>): List<FundMasterEntity> {
        if (schemeCodes.isEmpty()) return emptyList()
        val distinct = schemeCodes.distinct()
        if (distinct.size <= 300) return getFundsByCodesChunk(distinct)
        val out = ArrayList<FundMasterEntity>(distinct.size)
        for (chunk in distinct.chunked(300)) {
            out.addAll(getFundsByCodesChunk(chunk))
        }
        return out
    }

    @Query("SELECT MAX(scheme_id) FROM fund_master")
    suspend fun getMaxSchemeId(): Int?

    @Query("SELECT COUNT(*) FROM fund_master WHERE is_active = 1 AND is_index = 0")
    fun observeMutualFundCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM fund_master WHERE is_active = 0 AND is_index = 0")
    fun observeInactiveMutualFundCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM fund_master WHERE is_active = 1 AND is_index = 1")
    fun observeIndexCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM fund_master WHERE is_active = 1 AND is_index = 0 AND history_synced_at > 0")
    fun observeSyncedHistoryCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM fund_master WHERE is_active = 1 AND is_index = 0 AND history_synced_at = 0")
    suspend fun getUnsyncedMutualFundCount(): Int

    @Query("SELECT COUNT(*) FROM fund_master WHERE is_active = 1 AND is_index = 0 AND data_source = :dataSource AND history_synced_at > 0")
    suspend fun getSyncedCountByDataSource(dataSource: String): Int

    @Query("SELECT COUNT(*) FROM fund_master WHERE is_active = 1 AND is_index = 0 AND (data_source = :dataSource OR category LIKE '%SIF%') AND history_synced_at = 0")
    suspend fun getUnsyncedCountByDataSource(dataSource: String): Int

    @Query(
        """
        SELECT * FROM fund_master
        WHERE is_active = 1 AND is_index = 0 AND history_synced_at = 0
        ORDER BY
          COALESCE(latest_nav_date_epoch_day, 0) DESC,
          CASE WHEN scheme_name LIKE '%FMP%' OR scheme_name LIKE '%Fixed Maturity%' OR scheme_name LIKE '%Interval%' OR scheme_name LIKE '%Series%' OR scheme_name LIKE '%Capital Protection%' THEN 1 ELSE 0 END ASC,
          scheme_id DESC
        LIMIT :limit
        """
    )
    suspend fun getFundsNeedingHistorySync(limit: Int): List<FundMasterEntity>

    @Query("UPDATE fund_master SET last_synced_at = :timestampMillis WHERE is_active = 1 AND is_index = 0 AND history_synced_at > 0")
    suspend fun touchSyncedCatalogTimestamp(timestampMillis: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFunds(funds: List<FundMasterEntity>)

    @Query(
        """
        UPDATE fund_master
        SET inception_epoch_day = COALESCE(:inceptionEpochDay, inception_epoch_day),
            recorded_inception_epoch_day = COALESCE(:recordedInceptionEpochDay, recorded_inception_epoch_day),
            latest_nav = COALESCE(:latestNav, latest_nav),
            latest_nav_date_epoch_day = COALESCE(:latestNavDateEpochDay, latest_nav_date_epoch_day),
            return_1m = :return1M,
            return_1y = :return1Y,
            amc_name = COALESCE(:amcName, amc_name),
            category = COALESCE(:category, category),
            isin = COALESCE(:isin, isin),
            is_active = COALESCE(:isActive, is_active),
            status_note = :statusNote,
            history_synced_at = CASE WHEN :historySyncedAt > history_synced_at THEN :historySyncedAt ELSE history_synced_at END
        WHERE scheme_code = :schemeCode
        """
    )
    suspend fun updateFundMetrics(
        schemeCode: String,
        inceptionEpochDay: Long?,
        recordedInceptionEpochDay: Long?,
        latestNav: Double?,
        latestNavDateEpochDay: Long?,
        return1M: Double?,
        return1Y: Double?,
        amcName: String?,
        category: String?,
        isin: String?,
        isActive: Boolean?,
        statusNote: String?,
        historySyncedAt: Long
    )

    @Query("SELECT * FROM nav_buckets WHERE scheme_id = :schemeId ORDER BY year ASC")
    suspend fun getBucketsForSchemeId(schemeId: Int): List<NavBucketEntity>

    @Query("SELECT * FROM nav_buckets WHERE scheme_id = :schemeId AND year = :year LIMIT 1")
    suspend fun getBucket(schemeId: Int, year: Int): NavBucketEntity?

    @Query("SELECT * FROM nav_buckets WHERE scheme_id IN (:schemeIds) ORDER BY scheme_id ASC, year ASC")
    suspend fun getBucketsForSchemeIdsChunk(schemeIds: List<Int>): List<NavBucketEntity>

    suspend fun getBucketsForSchemeIds(schemeIds: List<Int>): List<NavBucketEntity> {
        if (schemeIds.isEmpty()) return emptyList()
        val distinct = schemeIds.distinct()
        if (distinct.size <= 100) return getBucketsForSchemeIdsChunk(distinct)
        val out = ArrayList<NavBucketEntity>(distinct.size * 2)
        for (chunk in distinct.chunked(100)) {
            out.addAll(getBucketsForSchemeIdsChunk(chunk))
        }
        return out
    }

    @Query("SELECT * FROM nav_buckets WHERE year IN (:years) AND scheme_id IN (:schemeIds)")
    suspend fun getBucketsForYearsAndSchemeIdsChunk(years: List<Int>, schemeIds: List<Int>): List<NavBucketEntity>

    suspend fun getBucketsForYearsAndSchemeIds(years: List<Int>, schemeIds: List<Int>): List<NavBucketEntity> {
        if (years.isEmpty() || schemeIds.isEmpty()) return emptyList()
        val distinctYears = years.distinct()
        val distinctIds = schemeIds.distinct()
        if (distinctIds.size <= 300) return getBucketsForYearsAndSchemeIdsChunk(distinctYears, distinctIds)
        val out = ArrayList<NavBucketEntity>(distinctIds.size)
        for (chunk in distinctIds.chunked(300)) {
            out.addAll(getBucketsForYearsAndSchemeIdsChunk(distinctYears, chunk))
        }
        return out
    }

    @Query("SELECT * FROM nav_buckets WHERE year >= :minYear AND scheme_id IN (:schemeIds) ORDER BY scheme_id ASC, year ASC")
    suspend fun getBucketsFromYearForSchemeIdsChunk(minYear: Int, schemeIds: List<Int>): List<NavBucketEntity>

    suspend fun getBucketsFromYearForSchemeIds(minYear: Int, schemeIds: List<Int>): List<NavBucketEntity> {
        if (schemeIds.isEmpty()) return emptyList()
        val distinct = schemeIds.distinct()
        if (distinct.size <= 200) return getBucketsFromYearForSchemeIdsChunk(minYear, distinct)
        val out = ArrayList<NavBucketEntity>(distinct.size * 2)
        for (chunk in distinct.chunked(200)) {
            out.addAll(getBucketsFromYearForSchemeIdsChunk(minYear, chunk))
        }
        return out
    }

    @Query("SELECT * FROM nav_buckets WHERE year >= :minYear ORDER BY scheme_id ASC, year ASC LIMIT :limit OFFSET :offset")
    suspend fun getBucketsFromYearPage(minYear: Int, limit: Int, offset: Int): List<NavBucketEntity>

    @Transaction
    suspend fun getBucketsFromYear(minYear: Int): List<NavBucketEntity> {
        val result = ArrayList<NavBucketEntity>()
        var offset = 0
        val pageSize = 400
        while (true) {
            val page = getBucketsFromYearPage(minYear, pageSize, offset)
            if (page.isEmpty()) break
            result.addAll(page)
            if (page.size < pageSize) break
            offset += pageSize
        }
        return result
    }

    @Query("SELECT SUM(point_count) FROM nav_buckets WHERE scheme_id = :schemeId")
    suspend fun getNavCountBySchemeId(schemeId: Int): Int?

    @Query("SELECT MAX(max_epoch_day) FROM nav_buckets WHERE scheme_id = :schemeId")
    suspend fun getMaxNavEpochDayBySchemeId(schemeId: Int): Int?

    @Query(
        """
        SELECT COALESCE(
            (SELECT MAX(nb.max_epoch_day) FROM nav_buckets nb INNER JOIN fund_master fm ON nb.scheme_id = fm.scheme_id WHERE fm.is_index = 0),
            (SELECT MAX(max_epoch_day) FROM nav_buckets WHERE scheme_id > 0),
            (SELECT MAX(max_epoch_day) FROM nav_buckets)
        )
        """
    )
    suspend fun getGlobalMaxNavEpochDay(): Int?

    @Query("SELECT COALESCE(SUM(point_count), 0) FROM nav_buckets")
    suspend fun getTotalStoredNavPoints(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNavBuckets(buckets: List<NavBucketEntity>)

    /**
     * Convenience helper preserving historical-style read API backed by compact [nav_buckets].
     */
    suspend fun getNavHistory(schemeCode: String): List<NavHistoryEntity> {
        val fund = getFundByCode(schemeCode) ?: return emptyList()
        val buckets = getBucketsForSchemeId(fund.schemeId)
        val out = ArrayList<NavHistoryEntity>(buckets.sumOf { it.pointCount })
        for (bucket in buckets) {
            val pts = NavBinaryCodec.decode(bucket.payload)
            for (pt in pts) {
                out.add(NavHistoryEntity(schemeCode, pt.dateEpochDay, pt.navValue, fund.dataSource, fund.lastSyncedAt))
            }
        }
        return out
    }

    suspend fun getNavPointsForSchemes(schemeCodes: List<String>): Map<String, List<NavPoint>> {
        if (schemeCodes.isEmpty()) return emptyMap()
        val funds = getFundsByCodes(schemeCodes)
        if (funds.isEmpty()) return emptyMap()
        val idToCode = funds.associate { it.schemeId to it.schemeCode }
        val buckets = getBucketsForSchemeIds(funds.map { it.schemeId })
        val result = LinkedHashMap<String, ArrayList<NavPoint>>(funds.size)
        for (bucket in buckets) {
            val code = idToCode[bucket.schemeId] ?: continue
            val list = result.getOrPut(code) { ArrayList() }
            list.addAll(NavBinaryCodec.decode(bucket.payload))
        }
        return result
    }

    suspend fun getNavCount(schemeCode: String): Int {
        val fund = getFundByCode(schemeCode) ?: return 0
        return getNavCountBySchemeId(fund.schemeId) ?: 0
    }

    suspend fun getMaxNavEpochDay(schemeCode: String): Long? {
        val fund = getFundByCode(schemeCode) ?: return null
        return getMaxNavEpochDayBySchemeId(fund.schemeId)?.toLong()
    }

    @Transaction
    suspend fun upsertNavRecordsIncremental(
        schemeId: Int,
        records: List<NavPoint>
    ): NavMergeResult {
        if (records.isEmpty()) return NavMergeResult(0, 0, null, null, null)
        val partitioned = NavBinaryCodec.partitionByYear(records)
        if (partitioned.isEmpty()) return NavMergeResult(0, 0, null, null, null)

        val existingBuckets = getBucketsForSchemeId(schemeId).associateBy { it.year }
        val toWrite = ArrayList<NavBucketEntity>(partitioned.size)
        var newlyInserted = 0
        var minDay: Int? = existingBuckets.values.minOfOrNull { it.minEpochDay }
        var maxDay: Int? = existingBuckets.values.maxOfOrNull { it.maxEpochDay }

        for ((year, pts) in partitioned) {
            val existing = existingBuckets[year]
            val outcome = NavBinaryCodec.mergeIntoYearBucket(
                schemeId = schemeId,
                year = year,
                existingPayload = existing?.payload,
                incomingPoints = pts
            )
            newlyInserted += outcome.newlyInsertedCount
            val bucket = outcome.bucket
            if (bucket != null) {
                if (minDay == null || bucket.minEpochDay < minDay) minDay = bucket.minEpochDay
                if (maxDay == null || bucket.maxEpochDay > maxDay) maxDay = bucket.maxEpochDay
                if (outcome.payloadChanged) {
                    toWrite.add(bucket)
                }
            }
        }

        if (toWrite.isNotEmpty()) {
            toWrite.chunked(250).forEach { upsertNavBuckets(it) }
        }

        val latestPt = records.maxByOrNull { it.dateEpochDay }
        return NavMergeResult(
            insertedPointCount = newlyInserted,
            modifiedBucketCount = toWrite.size,
            minEpochDay = minDay?.toLong(),
            maxEpochDay = maxDay?.toLong(),
            latestNavValue = latestPt?.navValue
        )
    }

    @Transaction
    suspend fun upsertNavHistory(records: List<NavHistoryEntity>) {
        if (records.isEmpty()) return
        val grouped = records.groupBy { it.schemeCode }
        val funds = getFundsByCodes(grouped.keys.toList()).associateBy { it.schemeCode }
        for ((code, rows) in grouped) {
            val fund = funds[code] ?: continue
            upsertNavRecordsIncremental(
                schemeId = fund.schemeId,
                records = rows.map { NavPoint(it.dateEpochDay, it.navValue) }
            )
        }
    }

    /**
     * High-speed batch incremental latest-NAV append used during daily AMFI NAVAll.txt refresh.
     * Bulk-fetches ONLY the target year buckets in chunked queries (not thousands of per-scheme queries)
     * and limits trailing 1M/1Y recomputation to favourites (or tiny <= 50 batches) so 5,000 funds finish in < 150ms.
     */
    @Transaction
    suspend fun applyDailyLatestNavBatch(
        entries: List<DailyLatestNavUpdate>,
        nowMillis: Long
    ): Int {
        if (entries.isEmpty()) return 0
        val existingFunds = getAllFundsIncludingInactive().associateBy { it.schemeCode }
        val candidates = ArrayList<Pair<FundMasterEntity, DailyLatestNavUpdate>>(entries.size)
        val targetYears = HashSet<Int>(4)

        for (entry in entries) {
            val existing = existingFunds[entry.schemeCode] ?: continue
            val prevDay = existing.latestNavDateEpochDay
            val isNewerDate = prevDay == null || entry.epochDay > prevDay
            val isSameDateValueChanged = prevDay == entry.epochDay &&
                (existing.latestNav == null || kotlin.math.abs(existing.latestNav - entry.navValue) > 0.0001)
            if (isNewerDate || isSameDateValueChanged) {
                candidates.add(existing to entry)
                targetYears.add(java.time.LocalDate.ofEpochDay(entry.epochDay).year)
            }
        }
        if (candidates.isEmpty()) return 0

        val candidateSchemeIds = candidates.map { it.first.schemeId }
        val existingYearBuckets = getBucketsForYearsAndSchemeIds(targetYears.toList(), candidateSchemeIds)
            .associateBy { it.schemeId.toLong() shl 16 or (it.year.toLong() and 0xFFFFL) }

        val favSet = getFavouriteCodes().toSet()
        val favIdsToRecompute = candidates
            .filter { (fund, _) ->
                fund.historySyncedAt > 0L && (candidates.size <= 50 || favSet.contains(fund.schemeCode))
            }
            .map { it.first.schemeId }
        val minReturnYear = (targetYears.maxOrNull() ?: java.time.LocalDate.now().year) - 1
        val recentReturnBucketsByScheme = if (favIdsToRecompute.isNotEmpty()) {
            getBucketsFromYearForSchemeIds(minReturnYear, favIdsToRecompute).groupBy { it.schemeId }
        } else {
            emptyMap()
        }

        val modifiedFunds = ArrayList<FundMasterEntity>(candidates.size)
        val modifiedBuckets = ArrayList<NavBucketEntity>(candidates.size)
        var totalInsertedPoints = 0

        for ((existing, entry) in candidates) {
            val year = java.time.LocalDate.ofEpochDay(entry.epochDay).year
            val bucketKey = (existing.schemeId.toLong() shl 16) or (year.toLong() and 0xFFFFL)
            val currentYearBucket = existingYearBuckets[bucketKey]
            val outcome = NavBinaryCodec.mergeIntoYearBucket(
                schemeId = existing.schemeId,
                year = year,
                existingPayload = currentYearBucket?.payload,
                incomingPoints = listOf(NavPoint(entry.epochDay, entry.navValue))
            )
            if (outcome.payloadChanged && outcome.bucket != null) {
                modifiedBuckets.add(outcome.bucket)
            }
            totalInsertedPoints += outcome.newlyInsertedCount

            var updated1M = existing.return1M
            var updated1Y = existing.return1Y
            if (existing.historySyncedAt > 0L && (candidates.size <= 50 || favSet.contains(existing.schemeCode))) {
                val buckets = recentReturnBucketsByScheme[existing.schemeId].orEmpty()
                val combined = buckets
                    .filter { it.year != year }
                    .flatMap { NavBinaryCodec.decode(it.payload) }
                    .toMutableList()
                outcome.bucket?.let { combined.addAll(NavBinaryCodec.decode(it.payload)) }
                combined.sortBy { it.dateEpochDay }
                updated1M = PerformanceCalculator.computeTrailingReturn(combined, 30)?.returnPercent ?: updated1M
                updated1Y = PerformanceCalculator.computeTrailingReturn(combined, 365)?.returnPercent ?: updated1Y
            }

            modifiedFunds.add(
                existing.copy(
                    isActive = true,
                    statusNote = null,
                    inceptionEpochDay = existing.inceptionEpochDay ?: entry.epochDay,
                    latestNav = entry.navValue,
                    latestNavDateEpochDay = entry.epochDay,
                    return1M = updated1M,
                    return1Y = updated1Y,
                    lastSyncedAt = nowMillis
                )
            )
        }

        if (modifiedBuckets.isNotEmpty()) {
            modifiedBuckets.chunked(250).forEach { upsertNavBuckets(it) }
        }
        if (modifiedFunds.isNotEmpty()) {
            modifiedFunds.chunked(300).forEach { upsertFunds(it) }
        }
        return totalInsertedPoints
    }

    /**
     * Persists a batch of 32-64 pre-encoded scheme histories in ONE SQLite transaction,
     * cutting SQLite fsync commits & queries by ~30x during Universe History Sync.
     */
    @Transaction
    suspend fun saveUniverseHistoryBatch(
        batch: List<PreparedSchemeHistory>,
        historySyncedAt: Long
    ) {
        if (batch.isEmpty()) return
        val schemeIds = batch.map { it.fund.schemeId }
        val existingBySchemeAndYear = getBucketsForSchemeIds(schemeIds)
            .associateBy { (it.schemeId.toLong() shl 16) or (it.year.toLong() and 0xFFFFL) }

        val bucketsToWrite = ArrayList<NavBucketEntity>(batch.size * 6)
        val fundsToUpdate = ArrayList<FundMasterEntity>(batch.size)

        for (item in batch) {
            for ((year, preEncodedBucket) in item.bucketsByYear) {
                val key = (item.fund.schemeId.toLong() shl 16) or (year.toLong() and 0xFFFFL)
                val existing = existingBySchemeAndYear[key]
                if (existing == null) {
                    bucketsToWrite.add(preEncodedBucket)
                } else {
                    // Year bucket already has data (e.g., today's AMFI point); merge only that year
                    val yearPoints = item.pointsByYear[year].orEmpty()
                    val merged = NavBinaryCodec.mergeIntoYearBucket(
                        schemeId = item.fund.schemeId,
                        year = year,
                        existingPayload = existing.payload,
                        incomingPoints = yearPoints
                    )
                    if (merged.payloadChanged && merged.bucket != null) {
                        bucketsToWrite.add(merged.bucket)
                    }
                }
            }

            val f = item.fund
            val effectiveInception = listOfNotNull(f.inceptionEpochDay, item.inceptionEpochDay).minOrNull()
            val keepExistingLatest = f.latestNavDateEpochDay != null &&
                f.latestNav != null &&
                f.latestNav > 0.0 &&
                f.latestNavDateEpochDay > item.latestNavDateEpochDay
            fundsToUpdate.add(
                f.copy(
                    inceptionEpochDay = effectiveInception,
                    recordedInceptionEpochDay = f.recordedInceptionEpochDay ?: effectiveInception,
                    latestNav = if (keepExistingLatest) f.latestNav else item.latestNav,
                    latestNavDateEpochDay = if (keepExistingLatest) f.latestNavDateEpochDay else item.latestNavDateEpochDay,
                    return1M = item.return1M,
                    return1Y = item.return1Y,
                    amcName = item.amcName?.takeIf { it.isNotBlank() } ?: f.amcName,
                    category = item.category?.takeIf { it.isNotBlank() } ?: f.category,
                    isin = item.isin?.takeIf { it.isNotBlank() } ?: f.isin,
                    isActive = item.isActive,
                    statusNote = item.statusNote,
                    historySyncedAt = maxOf(f.historySyncedAt, historySyncedAt)
                )
            )
        }

        if (bucketsToWrite.isNotEmpty()) {
            bucketsToWrite.chunked(250).forEach { upsertNavBuckets(it) }
        }
        if (fundsToUpdate.isNotEmpty()) {
            fundsToUpdate.chunked(300).forEach { upsertFunds(it) }
        }
    }

    @Transaction
    suspend fun markSchemesNoHistoryAvailable(schemeCodes: List<String>) {
        if (schemeCodes.isEmpty()) return
        val funds = getFundsByCodes(schemeCodes)
        if (funds.isEmpty()) return
        val updated = funds.map { f ->
            f.copy(
                isActive = false,
                statusNote = "No Historical NAV Available",
                historySyncedAt = -1L
            )
        }
        updated.chunked(300).forEach { upsertFunds(it) }
    }

    @Transaction
    suspend fun saveSchemeHistoryAndMetrics(
        schemeCode: String,
        records: List<NavHistoryEntity>,
        inceptionEpochDay: Long?,
        recordedInceptionEpochDay: Long? = null,
        latestNav: Double?,
        latestNavDateEpochDay: Long?,
        return1M: Double?,
        return1Y: Double?,
        amcName: String? = null,
        category: String? = null,
        isin: String? = null,
        isActive: Boolean? = true,
        statusNote: String? = null,
        historySyncedAt: Long
    ): Int {
        val fund = getFundByCode(schemeCode) ?: return 0
        val mergeRes = upsertNavRecordsIncremental(
            schemeId = fund.schemeId,
            records = records.map { NavPoint(it.dateEpochDay, it.navValue) }
        )
        val effectiveInception = listOfNotNull(fund.inceptionEpochDay, inceptionEpochDay, mergeRes.minEpochDay).minOrNull()
        updateFundMetrics(
            schemeCode = schemeCode,
            inceptionEpochDay = effectiveInception,
            recordedInceptionEpochDay = recordedInceptionEpochDay,
            latestNav = latestNav ?: mergeRes.latestNavValue,
            latestNavDateEpochDay = latestNavDateEpochDay ?: mergeRes.maxEpochDay,
            return1M = return1M,
            return1Y = return1Y,
            amcName = amcName,
            category = category,
            isin = isin,
            isActive = isActive,
            statusNote = statusNote,
            historySyncedAt = historySyncedAt
        )
        return mergeRes.insertedPointCount
    }

    /**
     * Backward-compatible test/catalog merge helper that preserves existing metrics and latest NAVs.
     */
    @Transaction
    suspend fun saveFullCatalogAndLatestNavs(
        funds: List<FundMasterEntity>,
        latestPoints: List<NavHistoryEntity>
    ) {
        val existingMap = getAllFundsIncludingInactive().associateBy { it.schemeCode }
        val merged = funds.map { incoming ->
            val old = existingMap[incoming.schemeCode]
            if (old == null) {
                incoming
            } else {
                val keepOldNav = incoming.latestNav == null ||
                    (old.latestNavDateEpochDay != null && incoming.latestNavDateEpochDay != null && old.latestNavDateEpochDay > incoming.latestNavDateEpochDay)
                incoming.copy(
                    schemeId = old.schemeId,
                    amcName = incoming.amcName.takeIf { it.isNotBlank() && it != "Mutual Fund" } ?: old.amcName,
                    category = incoming.category.takeIf { it.isNotBlank() && it != "Mutual Fund" && it != "Direct Growth Mutual Fund" } ?: old.category,
                    isin = incoming.isin ?: old.isin,
                    recordedInceptionEpochDay = incoming.recordedInceptionEpochDay ?: old.recordedInceptionEpochDay,
                    inceptionEpochDay = old.inceptionEpochDay ?: incoming.inceptionEpochDay,
                    latestNav = if (keepOldNav) old.latestNav else incoming.latestNav,
                    latestNavDateEpochDay = if (keepOldNav) old.latestNavDateEpochDay else incoming.latestNavDateEpochDay,
                    return1M = old.return1M ?: incoming.return1M,
                    return1Y = old.return1Y ?: incoming.return1Y,
                    historySyncedAt = maxOf(old.historySyncedAt, incoming.historySyncedAt)
                )
            }
        }
        merged.chunked(400).forEach { upsertFunds(it) }
        if (latestPoints.isNotEmpty()) {
            upsertNavHistory(latestPoints)
        }
    }

    // Favourites
    @Query(
        """
        SELECT f.* FROM fund_master f
        INNER JOIN favourite_funds fav ON f.scheme_code = fav.scheme_code
        ORDER BY fav.added_at DESC
        """
    )
    fun observeFavouriteFunds(): Flow<List<FundMasterEntity>>

    @Query("SELECT scheme_code, added_at FROM favourite_funds")
    fun observeFavouriteEntries(): Flow<List<FavouriteFundEntity>>

    @Query("SELECT scheme_code FROM favourite_funds")
    suspend fun getFavouriteCodes(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavourite(favourite: FavouriteFundEntity)

    @Query("DELETE FROM favourite_funds WHERE scheme_code = :schemeCode")
    suspend fun removeFavourite(schemeCode: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favourite_funds WHERE scheme_code = :schemeCode)")
    suspend fun isFavourite(schemeCode: String): Boolean

    // Sync metadata
    @Query("SELECT * FROM sync_metadata WHERE id = 1")
    fun observeSyncMetadata(): Flow<SyncMetadataEntity?>

    @Query("SELECT * FROM sync_metadata WHERE id = 1")
    suspend fun getSyncMetadata(): SyncMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncMetadata(metadata: SyncMetadataEntity)
}

data class DailyLatestNavUpdate(
    val schemeCode: String,
    val epochDay: Long,
    val navValue: Double
)

data class PreparedSchemeHistory(
    val fund: FundMasterEntity,
    val bucketsByYear: Map<Int, NavBucketEntity>,
    val pointsByYear: Map<Int, List<NavPoint>>,
    val inceptionEpochDay: Long,
    val latestNav: Double,
    val latestNavDateEpochDay: Long,
    val return1M: Double?,
    val return1Y: Double?,
    val amcName: String?,
    val category: String?,
    val isin: String?,
    val isActive: Boolean,
    val statusNote: String?
)

@Database(
    entities = [
        FundMasterEntity::class,
        NavBucketEntity::class,
        FavouriteFundEntity::class,
        SyncMetadataEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class MfCompareDatabase : RoomDatabase() {
    abstract fun fundDao(): FundDao

    fun checkpointAndMeasureBytes(context: Context, vacuumIfLarge: Boolean = false): Long {
        var resolvedPath: String? = null
        try {
            val sqlDb = openHelper.writableDatabase
            resolvedPath = sqlDb.path
            if (vacuumIfLarge) {
                sqlDb.execSQL("VACUUM")
            }
            sqlDb.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
        } catch (_: Exception) {
        }
        if (!resolvedPath.isNullOrBlank() && resolvedPath != ":memory:") {
            val dbFile = File(resolvedPath)
            val walFile = File("$resolvedPath-wal")
            val shmFile = File("$resolvedPath-shm")
            var total = 0L
            if (dbFile.exists()) total += dbFile.length()
            if (walFile.exists()) total += walFile.length()
            if (shmFile.exists()) total += shmFile.length()
            if (total > 0L) return total
        }
        return measureStorageBytes(context, openHelper.databaseName ?: DB_NAME)
    }

    companion object {
        const val DB_NAME = "mf_compare_db"

        @Volatile
        private var INSTANCE: MfCompareDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS nav_history")
                db.execSQL("DROP TABLE IF EXISTS fund_master")
                db.execSQL("DROP TABLE IF EXISTS sync_metadata")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS fund_master (
                        scheme_code TEXT NOT NULL PRIMARY KEY,
                        scheme_id INTEGER NOT NULL,
                        scheme_name TEXT NOT NULL,
                        search_tokens TEXT NOT NULL,
                        amc_name TEXT NOT NULL,
                        category TEXT NOT NULL,
                        isin TEXT,
                        dedup_key TEXT NOT NULL,
                        is_index INTEGER NOT NULL,
                        is_active INTEGER NOT NULL,
                        status_note TEXT,
                        recorded_inception_epoch_day INTEGER,
                        inception_epoch_day INTEGER,
                        latest_nav REAL,
                        latest_nav_date_epoch_day INTEGER,
                        return_1m REAL,
                        return_1y REAL,
                        history_synced_at INTEGER NOT NULL,
                        data_source TEXT NOT NULL,
                        last_synced_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_fund_master_scheme_id ON fund_master(scheme_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_fund_master_is_index ON fund_master(is_index)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_fund_master_history_synced_at ON fund_master(history_synced_at)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS nav_buckets (
                        scheme_id INTEGER NOT NULL,
                        year INTEGER NOT NULL,
                        point_count INTEGER NOT NULL,
                        min_epoch_day INTEGER NOT NULL,
                        max_epoch_day INTEGER NOT NULL,
                        payload BLOB NOT NULL,
                        PRIMARY KEY(scheme_id, year)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_nav_buckets_year_max_epoch_day ON nav_buckets(year, max_epoch_day)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_metadata (
                        id INTEGER NOT NULL PRIMARY KEY,
                        last_scheme_master_sync_millis INTEGER NOT NULL,
                        last_nav_sync_millis INTEGER NOT NULL,
                        last_universe_sync_millis INTEGER NOT NULL,
                        last_data_epoch_day INTEGER,
                        last_new_schemes_discovered INTEGER NOT NULL,
                        last_new_nav_records_inserted INTEGER NOT NULL,
                        last_error TEXT
                    )
                    """.trimIndent()
                )
            }
        }

        fun measureStorageBytes(context: Context, dbName: String = DB_NAME): Long {
            val dbFile = context.getDatabasePath(dbName)
            val walFile = File(dbFile.path + "-wal")
            val shmFile = File(dbFile.path + "-shm")
            var total = 0L
            if (dbFile.exists()) total += dbFile.length()
            if (walFile.exists()) total += walFile.length()
            if (shmFile.exists()) total += shmFile.length()
            return total
        }

        fun getDatabase(context: Context): MfCompareDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MfCompareDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
