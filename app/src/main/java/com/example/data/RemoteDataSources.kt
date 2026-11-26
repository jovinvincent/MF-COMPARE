package com.example.data

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.io.StringReader
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class AmfiParseResult(
    val eligibleFunds: List<FundMasterEntity>,
    val latestNavRecords: List<NavHistoryEntity>,
    val totalRawRows: Int,
    val excludedNonDirectGrowthRows: Int,
    val deduplicatedRows: Int
)

data class TigzigSifHistoryScheme(
    val fund: FundMasterEntity,
    val pointsAscending: List<NavPoint>
)

data class TigzigSifHistoryParseResult(
    val schemes: List<TigzigSifHistoryScheme>,
    val totalRawRows: Int,
    val excludedNonDirectGrowthRows: Int,
    val etag: String? = null,
    val lastModified: String? = null,
    val notModified: Boolean = false
)

data class TigzigLatestSnapshotResult(
    val allFunds: List<FundMasterEntity>,
    val sifFunds: List<FundMasterEntity>,
    val latestNavRecords: List<NavHistoryEntity>,
    val totalRawRows: Int,
    val etag: String? = null,
    val lastModified: String? = null,
    val notModified: Boolean = false
)

data class MfApiSchemeHistoryResult(
    val amcName: String?,
    val category: String?,
    val isinGrowth: String?,
    val records: List<NavHistoryEntity>
)

sealed interface FastHistoryFetchOutcome {
    data class Success(
        val amcName: String?,
        val category: String?,
        val isinGrowth: String?,
        val pointsAscending: List<NavPoint>
    ) : FastHistoryFetchOutcome

    data object NoHistoryAvailable : FastHistoryFetchOutcome
    data class TransientError(val message: String?) : FastHistoryFetchOutcome
}

data class MarketIndexDefinition(
    val schemeCode: String,
    val displayName: String,
    val category: String,
    val yahooSymbols: List<String>
)

object RemoteDataSources {

    val AMFI_NAV_URLS = listOf(
        "https://www.amfiindia.com/spages/NAVAll.txt",
        "https://portal.amfiindia.com/spages/NAVAll.txt"
    )
    const val MFAPI_BASE_URL = "https://api.mfapi.in/mf"
    const val TIGZIG_SIF_HISTORY_CSV_URL = "https://sif.tigzig.com/sif_nav.csv"
    const val TIGZIG_LATEST_NAV_URL = "https://api.tigzig.com/mf/v1/download?format=latest"
    const val TIGZIG_NAV_BATCH_URL = "https://api.tigzig.com/mf/v1/nav"
    const val TIGZIG_NAV_BATCH_LIMIT = 50
    const val DATA_SOURCE_SIF = "TIGZIG_SIF"
    const val DATA_SOURCE_TIGZIG_MF = "TIGZIG_MF"
    private val IST_ZONE = ZoneId.of("Asia/Kolkata")

    val SUPPORTED_INDICES = listOf(
        MarketIndexDefinition(
            schemeCode = "IDX_NIFTY50",
            displayName = "Nifty 50",
            category = "Broad Market Index",
            yahooSymbols = listOf("^NSEI")
        ),
        MarketIndexDefinition(
            schemeCode = "IDX_NIFTYNEXT50",
            displayName = "Nifty Next 50",
            category = "Large Cap Index",
            yahooSymbols = listOf("^NSMIDCP", "^CNXJR")
        ),
        MarketIndexDefinition(
            schemeCode = "IDX_NIFTYMIDCAP150",
            displayName = "Nifty Midcap 150",
            category = "Mid Cap Index",
            yahooSymbols = listOf("NIFTYMIDCAP150.NS", "^CNXMIDCAP")
        ),
        MarketIndexDefinition(
            schemeCode = "IDX_NIFTYSMALLCAP250",
            displayName = "Nifty Smallcap 250",
            category = "Small Cap Index",
            yahooSymbols = listOf("NIFTYSMLCAP250.NS", "^CNXSC")
        ),
        MarketIndexDefinition(
            schemeCode = "IDX_NIFTY500",
            displayName = "Nifty 500",
            category = "Broad Market Index",
            yahooSymbols = listOf("^CRSLDX")
        ),
        MarketIndexDefinition(
            schemeCode = "IDX_SENSEX",
            displayName = "BSE Sensex",
            category = "Broad Market Index",
            yahooSymbols = listOf("^BSESN")
        )
    )

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * High-concurrency HTTP/2 pooled client for rapid bulk history syncing (~20-24 parallel streams
     * to api.mfapi.in instead of OkHttp's conservative default maxRequestsPerHost = 5).
     */
    private val bulkHistoryClient: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 48
            maxRequestsPerHost = 24
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(24, 5, TimeUnit.MINUTES))
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val fastAmfiClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(4, 2, TimeUnit.MINUTES))
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val fastIndexClient: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 16
            maxRequestsPerHost = 12
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(8, 2, TimeUnit.MINUTES))
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    fun fetchAmfiMasterAndLatestNavs(nowMillis: Long = System.currentTimeMillis()): AmfiParseResult {
        var lastException: Exception? = null
        for (url in AMFI_NAV_URLS) {
            try {
                return fetchAmfiFromUrl(url, nowMillis)
            } catch (e: Exception) {
                lastException = e
            }
        }
        throw lastException ?: IllegalStateException("AMFI feed unreachable")
    }

    /**
     * Races AMFI mirrors in parallel and returns immediately on the first valid response,
     * cancelling the slower mirror instead of waiting sequentially on timeouts.
     */
    suspend fun fetchAmfiMasterAndLatestNavsFast(
        nowMillis: Long = System.currentTimeMillis()
    ): AmfiParseResult = coroutineScope {
        val winner = CompletableDeferred<AmfiParseResult>()
        val jobs = AMFI_NAV_URLS.map { url ->
            launch {
                try {
                    val res = fetchAmfiFromUrl(url, nowMillis)
                    winner.complete(res)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
        val watcher = launch {
            jobs.forEach { it.join() }
            if (!winner.isCompleted) {
                winner.completeExceptionally(IllegalStateException("AMFI feed unreachable"))
            }
        }
        try {
            winner.await()
        } finally {
            jobs.forEach { it.cancel() }
            watcher.cancel()
        }
    }

    private fun fetchAmfiFromUrl(url: String, nowMillis: Long): AmfiParseResult {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .build()
        fastAmfiClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("AMFI feed HTTP ${response.code}")
            }
            val stream = response.body?.byteStream()
                ?: throw IllegalStateException("AMFI feed returned empty body")
            val parsed = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8), 32 * 1024).use { reader ->
                parseAmfiStream(reader, nowMillis)
            }
            if (parsed.eligibleFunds.isNotEmpty()) {
                return parsed
            }
            throw IllegalStateException("AMFI feed returned 0 eligible funds")
        }
    }

    /**
     * Global Cloudflare-backed master catalog from https://api.mfapi.in/mf.
     * Works worldwide even when AMFI India servers time out from overseas networks.
     */
    fun fetchMfApiMasterCatalog(nowMillis: Long = System.currentTimeMillis()): List<FundMasterEntity> {
        val request = Request.Builder()
            .url(MFAPI_BASE_URL)
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Android; MFCompare/1.0)")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("MFAPI master HTTP ${response.code}")
            }
            val stream = response.body?.byteStream()
                ?: throw IllegalStateException("MFAPI master returned empty body")
            return parseMfApiMasterCatalogStream(stream, nowMillis)
        }
    }

    fun searchMfApiRemote(
        query: String,
        nowMillis: Long = System.currentTimeMillis()
    ): List<FundMasterEntity> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val request = Request.Builder()
            .url("$MFAPI_BASE_URL/search?q=$encoded")
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Android; MFCompare/1.0)")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val stream = response.body?.byteStream() ?: return emptyList()
            return parseMfApiMasterCatalogStream(stream, nowMillis)
        }
    }

    fun parseMfApiMasterCatalogJson(
        jsonText: String,
        nowMillis: Long = System.currentTimeMillis()
    ): List<FundMasterEntity> {
        return parseMfApiMasterCatalogStream(
            ByteArrayInputStream(jsonText.toByteArray(StandardCharsets.UTF_8)),
            nowMillis
        )
    }

    fun parseMfApiMasterCatalogStream(
        inputStream: InputStream,
        nowMillis: Long = System.currentTimeMillis()
    ): List<FundMasterEntity> {
        val dedupMap = LinkedHashMap<String, FundMasterEntity>(2500)
        JsonReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
            reader.beginArray()
            while (reader.hasNext()) {
                var schemeCode: String? = null
                var schemeName: String? = null
                var isinGrowth: String? = null

                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "schemeCode" -> {
                            schemeCode = if (reader.peek() == JsonToken.NUMBER) {
                                reader.nextLong().toString()
                            } else if (reader.peek() == JsonToken.STRING) {
                                reader.nextString().trim()
                            } else {
                                reader.skipValue()
                                null
                            }
                        }
                        "schemeName" -> {
                            schemeName = if (reader.peek() == JsonToken.STRING) {
                                reader.nextString().trim().replace(Regex("\\s+"), " ")
                            } else {
                                reader.skipValue()
                                null
                            }
                        }
                        "isinGrowth" -> {
                            isinGrowth = if (reader.peek() == JsonToken.STRING) {
                                reader.nextString().trim().takeIf { it.isNotEmpty() && it != "null" }
                            } else {
                                reader.skipValue()
                                null
                            }
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()

                if (!schemeCode.isNullOrBlank() && !schemeName.isNullOrBlank()) {
                    if (DirectGrowthFilter.isEligibleDirectGrowthScheme(schemeName)) {
                        val dedupKey = DirectGrowthFilter.deduplicationKey(schemeName, isinGrowth)
                        if (!dedupMap.containsKey(dedupKey)) {
                            val amc = inferAmcFromName(schemeName)
                            dedupMap[dedupKey] = FundMasterEntity(
                                schemeCode = schemeCode,
                                schemeName = schemeName,
                                searchTokens = DirectGrowthFilter.normalizeSearchTokens("$schemeName $amc"),
                                amcName = amc,
                                category = "Direct Growth Mutual Fund",
                                isin = isinGrowth,
                                dedupKey = dedupKey,
                                isIndex = false,
                                isActive = true,
                                dataSource = "MFAPI",
                                lastSyncedAt = nowMillis
                            )
                        }
                    }
                }
            }
            reader.endArray()
        }
        return dedupMap.values.toList()
    }

    fun parseAmfiText(rawText: String, nowMillis: Long = System.currentTimeMillis()): AmfiParseResult {
        return parseAmfiStream(BufferedReader(StringReader(rawText)), nowMillis)
    }

    fun parseAmfiStream(reader: BufferedReader, nowMillis: Long = System.currentTimeMillis()): AmfiParseResult {
        var currentCategory = "Mutual Fund"
        var currentAmc = ""
        var totalRawRows = 0
        var excluded = 0
        var deduplicated = 0

        // Dynamic column indices updated if a "Scheme Code;..." header row is encountered.
        var headerMapped = false
        var codeIdx = 0
        var isinIdx = 1
        var nameIdx = 3
        var planIdx = -1
        var optionIdx = -1
        var navIdx = 4
        var dateIdx = 5

        val dedupMap = LinkedHashMap<String, Pair<FundMasterEntity, NavHistoryEntity?>>(2500)

        reader.forEachLine { rawLine ->
            val line = rawLine.removePrefix("\uFEFF").trim()
            if (line.isEmpty()) return@forEachLine

            if (!line.contains(";")) {
                if (line.startsWith("Open Ended", ignoreCase = true) ||
                    line.startsWith("Close Ended", ignoreCase = true) ||
                    line.startsWith("Interval Fund", ignoreCase = true)
                ) {
                    currentCategory = line
                        .replace("Open Ended Schemes(", "")
                        .replace("Close Ended Schemes(", "")
                        .removeSuffix(")")
                        .trim()
                } else if (!line.startsWith("Scheme Code", ignoreCase = true)) {
                    currentAmc = line
                }
                return@forEachLine
            }

            val parts = line.split(';')
            if (parts.size < 6) return@forEachLine
            val firstCol = parts[0].trim()
            if (firstCol.isEmpty() || firstCol.equals("Scheme Code", ignoreCase = true)) {
                if (firstCol.equals("Scheme Code", ignoreCase = true)) {
                    val colMap = buildHeaderColumnMap(parts)
                    codeIdx = colMap.findAny("schemecode", "code").takeIf { it >= 0 } ?: 0
                    isinIdx = colMap.findAny("isindivpayoutisingrowth", "isingrowth", "isin", "isindivpayout").takeIf { it >= 0 } ?: 1
                    nameIdx = colMap.findAny("schemename", "name").takeIf { it >= 0 } ?: 3
                    planIdx = colMap.findAny("plan", "plantype", "schemeplan")
                    optionIdx = colMap.findAny("option", "optiontype", "schemeoption")
                    navIdx = colMap.findAny("netassetvalue", "nav", "navvalue").takeIf { it >= 0 }
                        ?: if (parts.size >= 8) 6 else 4
                    dateIdx = colMap.findAny("date", "navdate").takeIf { it >= 0 }
                        ?: if (parts.size >= 8) 7 else 5
                    headerMapped = true
                }
                return@forEachLine
            }
            if (!firstCol.all { it.isDigit() }) return@forEachLine

            totalRawRows++

            val resolvedCodeIdx: Int
            val resolvedIsinIdx: Int
            val resolvedNameIdx: Int
            val resolvedPlanIdx: Int
            val resolvedOptionIdx: Int
            val resolvedNavIdx: Int
            val resolvedDateIdx: Int

            if (headerMapped) {
                resolvedCodeIdx = codeIdx
                resolvedIsinIdx = isinIdx
                resolvedNameIdx = nameIdx
                resolvedPlanIdx = planIdx
                resolvedOptionIdx = optionIdx
                resolvedNavIdx = navIdx
                resolvedDateIdx = dateIdx
            } else if (parts.size >= 8) {
                // Headerless 8-column AMFI format: Scheme Code;ISIN1;ISIN2;Scheme Name;Plan;Option;Net Asset Value;Date
                resolvedCodeIdx = 0
                resolvedIsinIdx = 1
                resolvedNameIdx = 3
                resolvedPlanIdx = 4
                resolvedOptionIdx = 5
                resolvedNavIdx = 6
                resolvedDateIdx = 7
            } else {
                // Legacy 6-column AMFI format: Scheme Code;ISIN1;ISIN2;Scheme Name;Net Asset Value;Date
                resolvedCodeIdx = 0
                resolvedIsinIdx = 1
                resolvedNameIdx = 3
                resolvedPlanIdx = -1
                resolvedOptionIdx = -1
                resolvedNavIdx = 4
                resolvedDateIdx = 5
            }

            val schemeCode = parts.valueAt(resolvedCodeIdx) ?: return@forEachLine
            val isinGrowthOrPayout = parts.valueAt(resolvedIsinIdx)?.takeIf { it != "-" }
            val rawName = parts.valueAt(resolvedNameIdx)?.replace(Regex("\\s+"), " ")
            if (rawName.isNullOrBlank()) {
                excluded++
                return@forEachLine
            }
            val plan = parts.valueAt(resolvedPlanIdx)
            val option = parts.valueAt(resolvedOptionIdx)
            val schemeName = if (plan != null || option != null) {
                buildEffectiveSchemeName(rawName, plan, option)
            } else {
                rawName
            }

            val navDouble = parts.valueAt(resolvedNavIdx)?.toDoubleOrNull()
            val dateStr = parts.valueAt(resolvedDateIdx)
            val dateEpochDay = dateStr?.let { parseAmfiDateEpochDay(it) ?: parseFlexibleEpochDay(it) }

            if (!DirectGrowthFilter.isEligibleDirectGrowthScheme(schemeName, currentCategory)) {
                excluded++
                return@forEachLine
            }

            if (navDouble == null || navDouble <= 0.0 || dateEpochDay == null) {
                excluded++
                return@forEachLine
            }

            val dedupKey = DirectGrowthFilter.deduplicationKey(schemeName, isinGrowthOrPayout)
            val amc = currentAmc.ifBlank { inferAmcFromName(schemeName) }
            val entity = FundMasterEntity(
                schemeCode = schemeCode,
                schemeName = schemeName,
                searchTokens = DirectGrowthFilter.normalizeSearchTokens("$schemeName $amc"),
                amcName = amc,
                category = currentCategory,
                isin = isinGrowthOrPayout,
                dedupKey = dedupKey,
                isIndex = false,
                isActive = true,
                latestNav = navDouble,
                latestNavDateEpochDay = dateEpochDay,
                dataSource = "AMFI",
                lastSyncedAt = nowMillis
            )
            val navPoint = NavHistoryEntity(
                schemeCode = schemeCode,
                dateEpochDay = dateEpochDay,
                navValue = navDouble,
                dataSource = "AMFI",
                syncedAt = nowMillis
            )

            val existing = dedupMap[dedupKey]
            if (existing != null) {
                deduplicated++
                val existingDate = existing.first.latestNavDateEpochDay ?: Long.MIN_VALUE
                if (dateEpochDay > existingDate) {
                    dedupMap[dedupKey] = entity to navPoint
                }
            } else {
                dedupMap[dedupKey] = entity to navPoint
            }
        }

        val funds = ArrayList<FundMasterEntity>(dedupMap.size)
        val navs = ArrayList<NavHistoryEntity>(dedupMap.size)
        dedupMap.values.forEach { (f, n) ->
            funds.add(f)
            if (n != null) navs.add(n)
        }

        return AmfiParseResult(
            eligibleFunds = funds,
            latestNavRecords = navs,
            totalRawRows = totalRawRows,
            excludedNonDirectGrowthRows = excluded,
            deduplicatedRows = deduplicated
        )
    }

    /**
     * High-speed streaming history fetch for bulk sync & single scheme loading.
     * Uses HTTP/2 pooled sockets and [JsonReader] directly over the stream without constructing
     * intermediate DOM JSONObject trees.
     */
    fun fetchMfApiSchemePointsFast(schemeCode: String): FastHistoryFetchOutcome {
        val request = Request.Builder()
            .url("$MFAPI_BASE_URL/$schemeCode")
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Android; MFCompare/1.0)")
            .build()
        return try {
            bulkHistoryClient.newCall(request).execute().use { response ->
                val code = response.code
                if (code == 404 || code == 400 || code == 410) {
                    return FastHistoryFetchOutcome.NoHistoryAvailable
                }
                if (!response.isSuccessful) {
                    return FastHistoryFetchOutcome.TransientError("MFAPI HTTP $code for $schemeCode")
                }
                val stream = response.body?.byteStream()
                    ?: return FastHistoryFetchOutcome.TransientError("Empty response body for $schemeCode")
                parseMfApiHistoryStreamFast(stream)
            }
        } catch (e: Exception) {
            FastHistoryFetchOutcome.TransientError(e.message ?: "Network error")
        }
    }

    fun parseMfApiHistoryStreamFast(inputStream: InputStream): FastHistoryFetchOutcome {
        var amc: String? = null
        var category: String? = null
        var isinGrowth: String? = null
        val dedup = LinkedHashMap<Long, Double>(1536)

        JsonReader(BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8), 16 * 1024)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "meta" -> {
                        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "fund_house" -> amc = nextOptionalString(reader)
                                    "scheme_category" -> category = nextOptionalString(reader)
                                    "isin_growth" -> isinGrowth = nextOptionalString(reader)
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                        } else {
                            reader.skipValue()
                        }
                    }
                    "data" -> {
                        if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                var dateEpoch: Long? = null
                                var navVal = -1.0
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "date" -> {
                                            if (reader.peek() == JsonToken.STRING) {
                                                dateEpoch = parseMfApiDateEpochDay(reader.nextString())
                                            } else {
                                                reader.skipValue()
                                            }
                                        }
                                        "nav" -> {
                                            if (reader.peek() == JsonToken.STRING) {
                                                navVal = reader.nextString().trim().toDoubleOrNull() ?: -1.0
                                            } else if (reader.peek() == JsonToken.NUMBER) {
                                                navVal = reader.nextDouble()
                                            } else {
                                                reader.skipValue()
                                            }
                                        }
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                                if (dateEpoch != null && navVal > 0.0 && !dedup.containsKey(dateEpoch)) {
                                    dedup[dateEpoch] = navVal
                                }
                            }
                            reader.endArray()
                        } else {
                            reader.skipValue()
                        }
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }

        if (dedup.isEmpty()) {
            return FastHistoryFetchOutcome.NoHistoryAvailable
        }
        val points = ArrayList<NavPoint>(dedup.size)
        for ((d, v) in dedup) {
            points.add(NavPoint(d, v))
        }
        // MFAPI returns newest-first; if already descending, reverse in O(N) instead of O(N log N)
        if (!isSortedAscending(points)) {
            if (isSortedDescending(points)) {
                points.reverse()
            } else {
                points.sortBy { it.dateEpochDay }
            }
        }
        return FastHistoryFetchOutcome.Success(
            amcName = amc,
            category = category,
            isinGrowth = isinGrowth,
            pointsAscending = points
        )
    }

    private fun nextOptionalString(reader: JsonReader): String? {
        return if (reader.peek() == JsonToken.STRING) {
            reader.nextString().trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        } else {
            reader.skipValue()
            null
        }
    }

    private fun isSortedAscending(points: List<NavPoint>): Boolean {
        for (i in 1 until points.size) {
            if (points[i].dateEpochDay < points[i - 1].dateEpochDay) return false
        }
        return true
    }

    private fun isSortedDescending(points: List<NavPoint>): Boolean {
        for (i in 1 until points.size) {
            if (points[i].dateEpochDay > points[i - 1].dateEpochDay) return false
        }
        return true
    }

    fun fetchMfApiSchemeHistory(
        schemeCode: String,
        nowMillis: Long = System.currentTimeMillis()
    ): MfApiSchemeHistoryResult {
        return when (val res = fetchMfApiSchemePointsFast(schemeCode)) {
            is FastHistoryFetchOutcome.Success -> MfApiSchemeHistoryResult(
                amcName = res.amcName,
                category = res.category,
                isinGrowth = res.isinGrowth,
                records = res.pointsAscending.map {
                    NavHistoryEntity(schemeCode, it.dateEpochDay, it.navValue, "MFAPI", nowMillis)
                }
            )
            FastHistoryFetchOutcome.NoHistoryAvailable ->
                MfApiSchemeHistoryResult(null, null, null, emptyList())
            is FastHistoryFetchOutcome.TransientError ->
                throw IllegalStateException(res.message ?: "MFAPI error for scheme $schemeCode")
        }
    }

    /**
     * Lightweight endpoint fetching ONLY the most recent published NAV (~300 bytes)
     * without downloading full historical data. Used for incremental latest-NAV checks
     * when AMFI bulk feed is unreachable.
     */
    fun fetchMfApiLatestNavOnly(
        schemeCode: String,
        nowMillis: Long = System.currentTimeMillis()
    ): MfApiSchemeHistoryResult {
        val request = Request.Builder()
            .url("$MFAPI_BASE_URL/$schemeCode/latest")
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Android; MFCompare/1.0)")
            .build()
        bulkHistoryClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("MFAPI /latest HTTP ${response.code} for scheme $schemeCode")
            }
            val stream = response.body?.byteStream()
                ?: return MfApiSchemeHistoryResult(null, null, null, emptyList())
            return when (val parsed = parseMfApiHistoryStreamFast(stream)) {
                is FastHistoryFetchOutcome.Success -> MfApiSchemeHistoryResult(
                    amcName = parsed.amcName,
                    category = parsed.category,
                    isinGrowth = parsed.isinGrowth,
                    records = parsed.pointsAscending.map {
                        NavHistoryEntity(schemeCode, it.dateEpochDay, it.navValue, "MFAPI", nowMillis)
                    }
                )
                else -> MfApiSchemeHistoryResult(null, null, null, emptyList())
            }
        }
    }

    fun parseMfApiHistoryJson(
        schemeCode: String,
        jsonText: String,
        nowMillis: Long = System.currentTimeMillis()
    ): List<NavHistoryEntity> {
        return parseMfApiHistoryWithMetaJson(schemeCode, jsonText, nowMillis).records
    }

    fun parseMfApiHistoryWithMetaJson(
        schemeCode: String,
        jsonText: String,
        nowMillis: Long = System.currentTimeMillis()
    ): MfApiSchemeHistoryResult {
        val root = JSONObject(jsonText)
        val meta = root.optJSONObject("meta")
        val amc = meta?.optString("fund_house")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        val category = meta?.optString("scheme_category")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        val isinGrowth = meta?.optString("isin_growth")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

        val dataArray = root.optJSONArray("data")
            ?: return MfApiSchemeHistoryResult(amc, category, isinGrowth, emptyList())
        val dedup = LinkedHashMap<Long, NavHistoryEntity>(dataArray.length())

        for (i in 0 until dataArray.length()) {
            val obj = dataArray.optJSONObject(i) ?: continue
            val dateStr = obj.optString("date").trim()
            val navStr = obj.optString("nav").trim()
            val navVal = navStr.toDoubleOrNull() ?: continue
            if (navVal <= 0.0) continue
            val epochDay = parseMfApiDateEpochDay(dateStr) ?: continue

            if (!dedup.containsKey(epochDay)) {
                dedup[epochDay] = NavHistoryEntity(
                    schemeCode = schemeCode,
                    dateEpochDay = epochDay,
                    navValue = navVal,
                    dataSource = "MFAPI",
                    syncedAt = nowMillis
                )
            }
        }

        return MfApiSchemeHistoryResult(
            amcName = amc,
            category = category,
            isinGrowth = isinGrowth,
            records = dedup.values.sortedBy { it.dateEpochDay }
        )
    }

    fun fetchYahooIndexHistory(
        indexDef: MarketIndexDefinition,
        range: String = "10y",
        nowMillis: Long = System.currentTimeMillis()
    ): List<NavHistoryEntity> {
        val hosts = listOf("query1.finance.yahoo.com", "query2.finance.yahoo.com")
        for (symbol in indexDef.yahooSymbols) {
            val encoded = URLEncoder.encode(symbol, "UTF-8")
            for (host in hosts) {
                val url = "https://$host/v8/finance/chart/$encoded?interval=1d&range=$range"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()
                try {
                    fastIndexClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use
                        val body = response.body?.string() ?: return@use
                        val parsed = parseYahooChartJson(indexDef.schemeCode, body, nowMillis)
                        if (parsed.isNotEmpty()) {
                            return parsed
                        }
                    }
                } catch (_: Exception) {
                    // Try next host or symbol
                }
            }
        }
        return emptyList()
    }

    fun parseYahooChartJson(
        schemeCode: String,
        jsonText: String,
        nowMillis: Long = System.currentTimeMillis()
    ): List<NavHistoryEntity> {
        val root = JSONObject(jsonText)
        val chart = root.optJSONObject("chart") ?: return emptyList()
        val resultArr = chart.optJSONArray("result") ?: return emptyList()
        if (resultArr.length() == 0) return emptyList()
        val result = resultArr.optJSONObject(0) ?: return emptyList()
        val timestamps = result.optJSONArray("timestamp") ?: return emptyList()
        val indicators = result.optJSONObject("indicators") ?: return emptyList()
        val quotes = indicators.optJSONArray("quote") ?: return emptyList()
        if (quotes.length() == 0) return emptyList()
        val closes = quotes.optJSONObject(0)?.optJSONArray("close") ?: return emptyList()

        val dedup = LinkedHashMap<Long, NavHistoryEntity>(timestamps.length())
        for (i in 0 until minOf(timestamps.length(), closes.length())) {
            val ts = timestamps.optLong(i, -1L)
            if (ts <= 0L) continue
            if (closes.isNull(i)) continue
            val closeVal = closes.optDouble(i, Double.NaN)
            if (closeVal.isNaN() || closeVal <= 0.0) continue

            val epochDay = Instant.ofEpochSecond(ts).atZone(IST_ZONE).toLocalDate().toEpochDay()
            dedup[epochDay] = NavHistoryEntity(
                schemeCode = schemeCode,
                dateEpochDay = epochDay,
                navValue = closeVal,
                dataSource = "YAHOO_FINANCE",
                syncedAt = nowMillis
            )
        }
        return dedup.values.sortedBy { it.dateEpochDay }
    }

    /**
     * Downloads and streams https://sif.tigzig.com/sif_nav.csv into deduplicated per-scheme
     * ascending NAV histories without loading the entire CSV into memory.
     */
    fun fetchTigzigSifHistoryCsv(
        nowMillis: Long = System.currentTimeMillis(),
        ifNoneMatch: String? = null,
        ifModifiedSince: String? = null
    ): TigzigSifHistoryParseResult {
        val reqBuilder = Request.Builder()
            .url(TIGZIG_SIF_HISTORY_CSV_URL)
            .header("Accept", "text/csv,text/plain,application/zip,*/*")
            .header("User-Agent", "Mozilla/5.0 (Android; MFCompare/1.0)")
        if (!ifNoneMatch.isNullOrBlank()) {
            reqBuilder.header("If-None-Match", ifNoneMatch)
        }
        if (!ifModifiedSince.isNullOrBlank()) {
            reqBuilder.header("If-Modified-Since", ifModifiedSince)
        }
        httpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (response.code == 304) {
                return TigzigSifHistoryParseResult(
                    schemes = emptyList(),
                    totalRawRows = 0,
                    excludedNonDirectGrowthRows = 0,
                    etag = ifNoneMatch,
                    lastModified = ifModifiedSince,
                    notModified = true
                )
            }
            if (!response.isSuccessful) {
                throw IllegalStateException("Tigzig SIF CSV HTTP ${response.code}")
            }
            val etag = response.header("ETag")
            val lastMod = response.header("Last-Modified")
            val stream = response.body?.byteStream()
                ?: throw IllegalStateException("Tigzig SIF CSV returned empty body")
            return parseTigzigSifHistoryCsvStream(stream, nowMillis, etag, lastMod)
        }
    }

    fun parseTigzigSifHistoryCsv(
        csvText: String,
        nowMillis: Long = System.currentTimeMillis()
    ): TigzigSifHistoryParseResult {
        return parseTigzigSifHistoryCsvStream(
            ByteArrayInputStream(csvText.toByteArray(StandardCharsets.UTF_8)),
            nowMillis = nowMillis,
            etag = null,
            lastModified = null
        )
    }

    fun parseTigzigSifHistoryCsvStream(
        inputStream: InputStream,
        nowMillis: Long = System.currentTimeMillis(),
        etag: String? = null,
        lastModified: String? = null
    ): TigzigSifHistoryParseResult {
        val unwrapped = unwrapZipStreamIfNeeded(inputStream)
        val accumulators = LinkedHashMap<String, SifSchemeAccumulator>(64)
        var totalRawRows = 0
        var excluded = 0

        BufferedReader(InputStreamReader(unwrapped, StandardCharsets.UTF_8), 32 * 1024).use { reader ->
            var headerLine: String? = null
            while (true) {
                val raw = reader.readLine() ?: break
                val clean = raw.removePrefix("\uFEFF").trim()
                if (clean.isNotEmpty()) {
                    headerLine = clean
                    break
                }
            }
            if (headerLine == null) {
                return TigzigSifHistoryParseResult(emptyList(), 0, 0, etag, lastModified)
            }

            val delimiter = detectDelimiter(headerLine)
            val headerCols = splitDelimitedLine(headerLine, delimiter)
            val colMap = buildHeaderColumnMap(headerCols)

            val codeIdx = colMap.findAny("schemecode", "amficode", "sifcode", "code")
            val nameIdx = colMap.findAny("schemename", "schemenavname", "name", "scheme")
            val isinIdx = colMap.findAny("isin", "isingrowth", "isindivpayoutisingrowth", "isindivpayout")
            val navIdx = colMap.findAny("nav", "netassetvalue", "navvalue", "closingnav")
            val dateIdx = colMap.findAny("date", "navdate", "asofdate", "valuationdate")
            val amcIdx = colMap.findAny("amc", "amcname", "fundhouse", "mutualfund")
            val catIdx = colMap.findAny("category", "schemecategory", "subcategory", "strategy", "investmentstrategy", "sifcategory", "schemetype")
            val planIdx = colMap.findAny("plan", "plantype", "schemeplan")
            val optionIdx = colMap.findAny("option", "optiontype", "schemeoption")

            while (true) {
                val rawLine = reader.readLine() ?: break
                if (rawLine.isBlank()) continue
                totalRawRows++

                val cols = splitDelimitedLine(rawLine, delimiter)
                val rawName = cols.valueAt(nameIdx) ?: continue
                val plan = cols.valueAt(planIdx)
                val option = cols.valueAt(optionIdx)
                val effectiveName = buildEffectiveSchemeName(rawName, plan, option)

                if (!DirectGrowthFilter.isEligibleDirectGrowthScheme(effectiveName)) {
                    excluded++
                    continue
                }

                val isin = cols.valueAt(isinIdx)?.takeIf { it != "-" && !it.equals("null", true) && !it.equals("na", true) }
                val rawCode = cols.valueAt(codeIdx)?.takeIf { it != "-" && !it.equals("null", true) }
                val schemeCode = rawCode ?: isin ?: continue

                val navVal = cols.valueAt(navIdx)?.toDoubleOrNull()
                val epochDay = cols.valueAt(dateIdx)?.let { parseFlexibleEpochDay(it) }
                if (navVal == null || navVal <= 0.0 || epochDay == null) {
                    excluded++
                    continue
                }

                val amc = cols.valueAt(amcIdx)?.takeIf { !it.equals("null", true) } ?: inferAmcFromName(effectiveName)
                val rawCat = cols.valueAt(catIdx)?.takeIf { !it.equals("null", true) }
                val dedupKey = DirectGrowthFilter.deduplicationKey(effectiveName, isin)

                val acc = accumulators.getOrPut(dedupKey) {
                    SifSchemeAccumulator(
                        schemeCode = schemeCode,
                        schemeName = effectiveName,
                        amcName = amc,
                        rawCategory = rawCat,
                        isin = isin,
                        dedupKey = dedupKey
                    )
                }
                if (rawCode != null && rawCode.all { it.isDigit() } && !acc.schemeCode.all { it.isDigit() }) {
                    acc.schemeCode = rawCode
                }
                if (isin != null && acc.isin == null) acc.isin = isin
                if (rawCat != null && acc.rawCategory == null) acc.rawCategory = rawCat
                acc.pointsByDay[epochDay] = navVal
            }
        }

        val schemes = accumulators.values.mapNotNull { acc ->
            if (acc.pointsByDay.isEmpty()) return@mapNotNull null
            val pts = ArrayList<NavPoint>(acc.pointsByDay.size)
            for ((day, v) in acc.pointsByDay) {
                pts.add(NavPoint(day, v))
            }
            if (!isSortedAscending(pts)) {
                if (isSortedDescending(pts)) pts.reverse() else pts.sortBy { it.dateEpochDay }
            }
            val latest = pts.last()
            val inception = pts.first().dateEpochDay
            val catLabel = FundCategoryFilter.inferSifCategoryLabel(acc.schemeName, acc.rawCategory)
            val fund = FundMasterEntity(
                schemeCode = acc.schemeCode,
                schemeName = acc.schemeName,
                searchTokens = DirectGrowthFilter.normalizeSearchTokens("${acc.schemeName} ${acc.amcName} $catLabel sif specialized investment"),
                amcName = acc.amcName,
                category = catLabel,
                isin = acc.isin,
                dedupKey = acc.dedupKey,
                isIndex = false,
                isActive = true,
                inceptionEpochDay = inception,
                recordedInceptionEpochDay = inception,
                latestNav = latest.navValue,
                latestNavDateEpochDay = latest.dateEpochDay,
                return1M = PerformanceCalculator.computeTrailingReturn(pts, 30)?.returnPercent,
                return1Y = PerformanceCalculator.computeTrailingReturn(pts, 365)?.returnPercent,
                historySyncedAt = nowMillis,
                dataSource = DATA_SOURCE_SIF,
                lastSyncedAt = nowMillis
            )
            TigzigSifHistoryScheme(fund = fund, pointsAscending = pts)
        }

        return TigzigSifHistoryParseResult(
            schemes = schemes,
            totalRawRows = totalRawRows,
            excludedNonDirectGrowthRows = excluded,
            etag = etag,
            lastModified = lastModified
        )
    }

    /**
     * Fetches the lightweight latest NAV snapshot from https://api.tigzig.com/mf/v1/download?format=latest
     * covering both Mutual Funds and Specialized Investment Funds (SIFs), supporting JSON, CSV, and ZIP.
     */
    fun fetchTigzigLatestSnapshot(
        nowMillis: Long = System.currentTimeMillis(),
        ifNoneMatch: String? = null,
        ifModifiedSince: String? = null
    ): TigzigLatestSnapshotResult {
        val reqBuilder = Request.Builder()
            .url(TIGZIG_LATEST_NAV_URL)
            .header("Accept", "application/json,text/csv,text/plain,application/zip,*/*")
            .header("User-Agent", "Mozilla/5.0 (Android; MFCompare/1.0)")
        if (!ifNoneMatch.isNullOrBlank()) {
            reqBuilder.header("If-None-Match", ifNoneMatch)
        }
        if (!ifModifiedSince.isNullOrBlank()) {
            reqBuilder.header("If-Modified-Since", ifModifiedSince)
        }
        httpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (response.code == 304) {
                return TigzigLatestSnapshotResult(
                    allFunds = emptyList(),
                    sifFunds = emptyList(),
                    latestNavRecords = emptyList(),
                    totalRawRows = 0,
                    etag = ifNoneMatch,
                    lastModified = ifModifiedSince,
                    notModified = true
                )
            }
            if (!response.isSuccessful) {
                throw IllegalStateException("Tigzig latest HTTP ${response.code}")
            }
            val etag = response.header("ETag")
            val lastMod = response.header("Last-Modified")
            val stream = response.body?.byteStream()
                ?: throw IllegalStateException("Tigzig latest returned empty body")
            return parseTigzigLatestStream(stream, nowMillis, etag, lastMod)
        }
    }

    fun parseTigzigLatestText(
        rawText: String,
        nowMillis: Long = System.currentTimeMillis()
    ): TigzigLatestSnapshotResult {
        return parseTigzigLatestStream(
            ByteArrayInputStream(rawText.toByteArray(StandardCharsets.UTF_8)),
            nowMillis = nowMillis,
            etag = null,
            lastModified = null
        )
    }

    fun parseTigzigLatestStream(
        inputStream: InputStream,
        nowMillis: Long = System.currentTimeMillis(),
        etag: String? = null,
        lastModified: String? = null
    ): TigzigLatestSnapshotResult {
        val unwrapped = unwrapZipStreamIfNeeded(inputStream)
        val pushback = PushbackInputStream(BufferedInputStream(unwrapped, 32 * 1024), 64)
        val buffer = ByteArray(64)
        val readCount = pushback.read(buffer, 0, buffer.size)
        if (readCount <= 0) {
            return TigzigLatestSnapshotResult(emptyList(), emptyList(), emptyList(), 0, etag, lastModified)
        }
        pushback.unread(buffer, 0, readCount)

        var firstNonWs: Char? = null
        var startIdx = 0
        // Skip optional UTF-8 BOM
        if (readCount >= 3 && (buffer[0].toInt() and 0xFF) == 0xEF &&
            (buffer[1].toInt() and 0xFF) == 0xBB && (buffer[2].toInt() and 0xFF) == 0xBF
        ) {
            startIdx = 3
        }
        for (i in startIdx until readCount) {
            val ch = buffer[i].toInt().toChar()
            if (!ch.isWhitespace()) {
                firstNonWs = ch
                break
            }
        }

        return if (firstNonWs == '[' || firstNonWs == '{') {
            parseTigzigLatestJsonStream(pushback, nowMillis, etag, lastModified)
        } else {
            parseTigzigLatestTextStream(pushback, nowMillis, etag, lastModified)
        }
    }

    private fun parseTigzigLatestJsonStream(
        inputStream: InputStream,
        nowMillis: Long,
        etag: String?,
        lastModified: String?
    ): TigzigLatestSnapshotResult {
        val dedupMap = LinkedHashMap<String, Pair<FundMasterEntity, NavHistoryEntity>>(2500)
        var totalRows = 0

        JsonReader(BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8), 32 * 1024)).use { reader ->
            reader.isLenient = true
            when (reader.peek()) {
                JsonToken.BEGIN_ARRAY -> {
                    totalRows += readTigzigJsonArray(reader, dedupMap, nowMillis)
                }
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName().lowercase(Locale.US)
                        if ((key == "data" || key == "schemes" || key == "results" || key == "records" || key == "items") &&
                            reader.peek() == JsonToken.BEGIN_ARRAY
                        ) {
                            totalRows += readTigzigJsonArray(reader, dedupMap, nowMillis)
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                else -> Unit
            }
        }

        val allFunds = ArrayList<FundMasterEntity>(dedupMap.size)
        val sifFunds = ArrayList<FundMasterEntity>()
        val navRecords = ArrayList<NavHistoryEntity>(dedupMap.size)
        for ((fund, nav) in dedupMap.values) {
            allFunds.add(fund)
            if (fund.dataSource == DATA_SOURCE_SIF) sifFunds.add(fund)
            navRecords.add(nav)
        }
        return TigzigLatestSnapshotResult(
            allFunds = allFunds,
            sifFunds = sifFunds,
            latestNavRecords = navRecords,
            totalRawRows = totalRows,
            etag = etag,
            lastModified = lastModified
        )
    }

    private fun readTigzigJsonArray(
        reader: JsonReader,
        dedupMap: LinkedHashMap<String, Pair<FundMasterEntity, NavHistoryEntity>>,
        nowMillis: Long
    ): Int {
        var count = 0
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            count++
            var rawCode: String? = null
            var rawName: String? = null
            var isin: String? = null
            var navVal: Double? = null
            var dateStr: String? = null
            var amc: String? = null
            var category: String? = null
            var plan: String? = null
            var option: String? = null
            var source: String? = null

            reader.beginObject()
            while (reader.hasNext()) {
                val key = normalizeHeaderKey(reader.nextName())
                when (key) {
                    "schemecode", "amficode", "sifcode", "code" -> rawCode = nextScalarAsString(reader)
                    "schemename", "schemenavname", "name", "scheme" -> rawName = nextScalarAsString(reader)
                    "isin", "isingrowth", "isindivpayoutisingrowth", "isindivpayout" -> {
                        val candidate = nextScalarAsString(reader)
                        if (!candidate.isNullOrBlank() && candidate != "-" && !candidate.equals("null", true) && isin == null) {
                            isin = candidate
                        }
                    }
                    "nav", "netassetvalue", "navvalue", "latestnav" -> navVal = nextScalarAsDouble(reader)
                    "date", "navdate", "latestnavdate", "asofdate" -> dateStr = nextScalarAsString(reader)
                    "amc", "amcname", "fundhouse", "mutualfund" -> amc = nextScalarAsString(reader)
                    "category", "schemecategory", "subcategory", "strategy", "investmentstrategy", "sifcategory", "schemetype" -> {
                        val catVal = nextScalarAsString(reader)
                        if (!catVal.isNullOrBlank() && !catVal.equals("null", true)) {
                            category = if (category == null) catVal else "${category} - $catVal"
                        }
                    }
                    "plan", "plantype", "schemeplan" -> plan = nextScalarAsString(reader)
                    "option", "optiontype", "schemeoption" -> option = nextScalarAsString(reader)
                    "source", "fundtype", "assettype" -> source = nextScalarAsString(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            val epochDay = dateStr?.let { parseFlexibleEpochDay(it) }
            addTigzigSnapshotRow(
                rawCode = rawCode,
                rawName = rawName,
                isin = isin,
                navVal = navVal,
                epochDay = epochDay,
                rawAmc = amc,
                rawCat = category,
                plan = plan,
                option = option,
                sourceTag = source,
                nowMillis = nowMillis,
                dedupMap = dedupMap
            )
        }
        reader.endArray()
        return count
    }

    private fun parseTigzigLatestTextStream(
        inputStream: InputStream,
        nowMillis: Long,
        etag: String?,
        lastModified: String?
    ): TigzigLatestSnapshotResult {
        val dedupMap = LinkedHashMap<String, Pair<FundMasterEntity, NavHistoryEntity>>(2500)
        var totalRows = 0

        BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8), 32 * 1024).use { reader ->
            var firstLine: String? = null
            while (true) {
                val raw = reader.readLine() ?: break
                val clean = raw.removePrefix("\uFEFF").trim()
                if (clean.isNotEmpty()) {
                    firstLine = clean
                    break
                }
            }
            if (firstLine == null) {
                return TigzigLatestSnapshotResult(emptyList(), emptyList(), emptyList(), 0, etag, lastModified)
            }

            // If this is AMFI raw semicolon text (no header or "Scheme Code;ISIN..."), reuse parseAmfiStream logic
            val isRawAmfi = !firstLine.contains(",") && (
                firstLine.startsWith("Open Ended", ignoreCase = true) ||
                    firstLine.startsWith("Close Ended", ignoreCase = true) ||
                    firstLine.startsWith("Interval Fund", ignoreCase = true) ||
                    (firstLine.startsWith("Scheme Code;", ignoreCase = true) && firstLine.count { it == ';' } >= 5)
                )
            if (isRawAmfi) {
                val combinedReader = BufferedReader(StringReader(firstLine + "\n" + reader.readText()))
                val amfi = parseAmfiStream(combinedReader, nowMillis)
                val taggedFunds = amfi.eligibleFunds.map { f ->
                    val isSif = FundCategoryFilter.isSifFund(f)
                    if (isSif) {
                        val cat = FundCategoryFilter.inferSifCategoryLabel(f.schemeName, f.category)
                        f.copy(
                            category = cat,
                            searchTokens = DirectGrowthFilter.normalizeSearchTokens("${f.schemeName} ${f.amcName} $cat sif specialized investment"),
                            dataSource = DATA_SOURCE_SIF
                        )
                    } else {
                        f.copy(dataSource = DATA_SOURCE_TIGZIG_MF)
                    }
                }
                return TigzigLatestSnapshotResult(
                    allFunds = taggedFunds,
                    sifFunds = taggedFunds.filter { it.dataSource == DATA_SOURCE_SIF },
                    latestNavRecords = amfi.latestNavRecords,
                    totalRawRows = amfi.totalRawRows,
                    etag = etag,
                    lastModified = lastModified
                )
            }

            val delimiter = detectDelimiter(firstLine)
            val headerCols = splitDelimitedLine(firstLine, delimiter)
            val colMap = buildHeaderColumnMap(headerCols)

            val codeIdx = colMap.findAny("schemecode", "amficode", "sifcode", "code")
            val nameIdx = colMap.findAny("schemename", "schemenavname", "name", "scheme")
            val isinIdx = colMap.findAny("isin", "isingrowth", "isindivpayoutisingrowth", "isindivpayout")
            val navIdx = colMap.findAny("nav", "netassetvalue", "navvalue", "latestnav")
            val dateIdx = colMap.findAny("date", "navdate", "latestnavdate", "asofdate")
            val amcIdx = colMap.findAny("amc", "amcname", "fundhouse", "mutualfund")
            val catIdx = colMap.findAny("category", "schemecategory", "subcategory", "strategy", "investmentstrategy", "sifcategory", "schemetype")
            val planIdx = colMap.findAny("plan", "plantype", "schemeplan")
            val optionIdx = colMap.findAny("option", "optiontype", "schemeoption")
            val sourceIdx = colMap.findAny("source", "fundtype", "assettype")

            while (true) {
                val rawLine = reader.readLine() ?: break
                if (rawLine.isBlank()) continue
                totalRows++
                val cols = splitDelimitedLine(rawLine, delimiter)
                addTigzigSnapshotRow(
                    rawCode = cols.valueAt(codeIdx),
                    rawName = cols.valueAt(nameIdx),
                    isin = cols.valueAt(isinIdx),
                    navVal = cols.valueAt(navIdx)?.toDoubleOrNull(),
                    epochDay = cols.valueAt(dateIdx)?.let { parseFlexibleEpochDay(it) },
                    rawAmc = cols.valueAt(amcIdx),
                    rawCat = cols.valueAt(catIdx),
                    plan = cols.valueAt(planIdx),
                    option = cols.valueAt(optionIdx),
                    sourceTag = cols.valueAt(sourceIdx),
                    nowMillis = nowMillis,
                    dedupMap = dedupMap
                )
            }
        }

        val allFunds = ArrayList<FundMasterEntity>(dedupMap.size)
        val sifFunds = ArrayList<FundMasterEntity>()
        val navRecords = ArrayList<NavHistoryEntity>(dedupMap.size)
        for ((fund, nav) in dedupMap.values) {
            allFunds.add(fund)
            if (fund.dataSource == DATA_SOURCE_SIF) sifFunds.add(fund)
            navRecords.add(nav)
        }
        return TigzigLatestSnapshotResult(
            allFunds = allFunds,
            sifFunds = sifFunds,
            latestNavRecords = navRecords,
            totalRawRows = totalRows,
            etag = etag,
            lastModified = lastModified
        )
    }

    private fun addTigzigSnapshotRow(
        rawCode: String?,
        rawName: String?,
        isin: String?,
        navVal: Double?,
        epochDay: Long?,
        rawAmc: String?,
        rawCat: String?,
        plan: String?,
        option: String?,
        sourceTag: String?,
        nowMillis: Long,
        dedupMap: LinkedHashMap<String, Pair<FundMasterEntity, NavHistoryEntity>>
    ) {
        val cleanName = rawName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val effectiveName = buildEffectiveSchemeName(cleanName, plan, option)
        if (!DirectGrowthFilter.isEligibleDirectGrowthScheme(effectiveName)) return
        if (navVal == null || navVal <= 0.0 || epochDay == null) return

        val cleanIsin = isin?.trim()?.takeIf { it.isNotEmpty() && it != "-" && !it.equals("null", true) && !it.equals("na", true) }
        val cleanCode = rawCode?.trim()?.takeIf { it.isNotEmpty() && it != "-" && !it.equals("null", true) }
            ?: cleanIsin
            ?: return

        val amc = rawAmc?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) } ?: inferAmcFromName(effectiveName)
        val cleanCat = rawCat?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) }
        val isSif = sourceTag?.trim()?.equals("SIF", ignoreCase = true) == true ||
            FundCategoryFilter.isSifText(effectiveName.lowercase(), cleanCat?.lowercase().orEmpty())

        val finalCat = if (isSif) {
            FundCategoryFilter.inferSifCategoryLabel(effectiveName, cleanCat)
        } else {
            cleanCat ?: "Direct Growth Mutual Fund"
        }
        val source = if (isSif) DATA_SOURCE_SIF else DATA_SOURCE_TIGZIG_MF
        val tokens = if (isSif) {
            DirectGrowthFilter.normalizeSearchTokens("$effectiveName $amc $finalCat sif specialized investment")
        } else {
            DirectGrowthFilter.normalizeSearchTokens("$effectiveName $amc $finalCat")
        }
        val dedupKey = DirectGrowthFilter.deduplicationKey(effectiveName, cleanIsin)

        val fund = FundMasterEntity(
            schemeCode = cleanCode,
            schemeName = effectiveName,
            searchTokens = tokens,
            amcName = amc,
            category = finalCat,
            isin = cleanIsin,
            dedupKey = dedupKey,
            isIndex = false,
            isActive = true,
            latestNav = navVal,
            latestNavDateEpochDay = epochDay,
            dataSource = source,
            lastSyncedAt = nowMillis
        )
        val nav = NavHistoryEntity(
            schemeCode = cleanCode,
            dateEpochDay = epochDay,
            navValue = navVal,
            dataSource = source,
            syncedAt = nowMillis
        )

        val existing = dedupMap[dedupKey]
        if (existing == null || epochDay > (existing.first.latestNavDateEpochDay ?: Long.MIN_VALUE)) {
            dedupMap[dedupKey] = fund to nav
        }
    }

    fun tigzigNavQueryKey(fund: FundMasterEntity): String {
        val code = fund.schemeCode.trim()
        if (FundCategoryFilter.isSifFund(fund)) {
            if (code.startsWith("SIF-", ignoreCase = true)) return code.uppercase(Locale.US)
            if (code.all { it.isDigit() }) return "SIF-$code"
            val isin = fund.isin?.trim()?.takeIf { it.isNotEmpty() }
            if (isin != null) return isin
        }
        return code
    }

    /**
     * Queries Tigzig's live database endpoint `/mf/v1/nav?schemes=...&latest=true` in batches of 50
     * schemes over Cloudflare. Works worldwide (including Saudi Arabia when AMFI blocks/502s) and
     * reflects real-time daily NAVs ahead of the 3x/day `download?format=latest` R2 cache.
     */
    suspend fun fetchTigzigBatchLatestNavs(
        funds: List<FundMasterEntity>,
        @Suppress("UNUSED_PARAMETER") nowMillis: Long = System.currentTimeMillis()
    ): List<DailyLatestNavUpdate> = coroutineScope {
        val eligible = funds.filter { !it.isIndex }
        if (eligible.isEmpty()) return@coroutineScope emptyList()

        val queryToLocalCodes = LinkedHashMap<String, MutableList<String>>(eligible.size * 2)
        val uniqueQueryKeys = ArrayList<String>(eligible.size)
        val seenQueryKeys = HashSet<String>(eligible.size)

        for (fund in eligible) {
            val qKey = tigzigNavQueryKey(fund)
            queryToLocalCodes.getOrPut(qKey) { ArrayList(1) }.add(fund.schemeCode)
            queryToLocalCodes.getOrPut(fund.schemeCode) { ArrayList(1) }.add(fund.schemeCode)
            fund.isin?.trim()?.takeIf { it.isNotEmpty() }?.let { isin ->
                queryToLocalCodes.getOrPut(isin.uppercase(Locale.US)) { ArrayList(1) }.add(fund.schemeCode)
            }
            if (seenQueryKeys.add(qKey)) {
                uniqueQueryKeys.add(qKey)
            }
        }

        val sem = Semaphore(8)
        val chunks = uniqueQueryKeys.chunked(TIGZIG_NAV_BATCH_LIMIT)
        var succeededChunks = 0
        var lastException: Exception? = null

        val chunkResults = chunks.map { chunk ->
            async {
                sem.withPermit {
                    try {
                        val joined = URLEncoder.encode(chunk.joinToString(","), "UTF-8")
                        val request = Request.Builder()
                            .url("$TIGZIG_NAV_BATCH_URL?schemes=$joined&latest=true")
                            .header("Accept", "application/json")
                            .header("User-Agent", "Mozilla/5.0 (Android; MFCompare/1.0)")
                            .build()
                        bulkHistoryClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                throw IllegalStateException("Tigzig /nav HTTP ${response.code}")
                            }
                            val body = response.body?.string().orEmpty()
                            succeededChunks++
                            parseTigzigBatchNavLatestJson(body, queryToLocalCodes)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastException = e
                        emptyList()
                    }
                }
            }
        }.awaitAll()

        if (chunks.isNotEmpty() && succeededChunks == 0 && lastException != null) {
            throw lastException!!
        }

        val dedup = LinkedHashMap<String, DailyLatestNavUpdate>(eligible.size)
        for (list in chunkResults) {
            for (item in list) {
                val prev = dedup[item.schemeCode]
                if (prev == null || item.epochDay >= prev.epochDay) {
                    dedup[item.schemeCode] = item
                }
            }
        }
        dedup.values.toList()
    }

    fun parseTigzigBatchNavLatestJson(
        jsonText: String,
        queryToLocalCodes: Map<String, List<String>> = emptyMap()
    ): List<DailyLatestNavUpdate> {
        if (jsonText.isBlank()) return emptyList()
        val root = JSONObject(jsonText)
        val schemesArr = root.optJSONArray("schemes") ?: return emptyList()
        val out = ArrayList<DailyLatestNavUpdate>(schemesArr.length())

        for (i in 0 until schemesArr.length()) {
            val obj = schemesArr.optJSONObject(i) ?: continue
            val rawSchemeCode = obj.opt("scheme_code")?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            val sifCode = obj.optString("sif_code", "").trim().takeIf { it.isNotEmpty() && it != "null" }
            val source = obj.optString("source", "").trim()
            val isin = obj.optString("isin", "").trim().takeIf { it.isNotEmpty() && it != "null" }

            val dataArr = obj.optJSONArray("data") ?: continue
            var bestEpochDay: Long? = null
            var bestNav: Double? = null
            for (j in 0 until dataArr.length()) {
                val pt = dataArr.optJSONObject(j) ?: continue
                val epochDay = pt.optString("date", "").trim().let { parseFlexibleEpochDay(it) } ?: continue
                val navVal = pt.optDouble("nav", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
                    ?: pt.optString("nav", "").trim().toDoubleOrNull()?.takeIf { it > 0.0 }
                    ?: continue
                if (bestEpochDay == null || epochDay > bestEpochDay) {
                    bestEpochDay = epochDay
                    bestNav = navVal
                }
            }
            if (bestEpochDay == null || bestNav == null) continue

            val candidateKeys = buildList {
                if (sifCode != null) add(sifCode.uppercase(Locale.US))
                if (source.equals("SIF", ignoreCase = true) && rawSchemeCode != null) {
                    add("SIF-$rawSchemeCode")
                }
                if (rawSchemeCode != null) add(rawSchemeCode)
                if (isin != null) add(isin.uppercase(Locale.US))
            }

            val matchedCodes = LinkedHashSet<String>()
            if (queryToLocalCodes.isEmpty()) {
                val fallbackCode = sifCode ?: if (source.equals("SIF", true) && rawSchemeCode != null) "SIF-$rawSchemeCode" else rawSchemeCode
                if (fallbackCode != null) matchedCodes.add(fallbackCode)
            } else {
                for (key in candidateKeys) {
                    queryToLocalCodes[key]?.let { matchedCodes.addAll(it) }
                }
            }

            for (localCode in matchedCodes) {
                out.add(DailyLatestNavUpdate(localCode, bestEpochDay, bestNav))
            }
        }
        return out
    }

    private fun unwrapZipStreamIfNeeded(inputStream: InputStream): InputStream {
        val pb = PushbackInputStream(BufferedInputStream(inputStream, 16 * 1024), 4)
        val magic = ByteArray(4)
        val n = pb.read(magic, 0, 4)
        if (n <= 0) return pb
        pb.unread(magic, 0, n)
        if (n >= 2 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()) {
            val zis = ZipInputStream(pb)
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory) {
                    return zis
                }
            }
            return zis
        }
        return pb
    }

    private fun detectDelimiter(headerLine: String): Char {
        var commas = 0
        var semis = 0
        var tabs = 0
        var inQuotes = false
        for (ch in headerLine) {
            if (ch == '"') inQuotes = !inQuotes
            else if (!inQuotes) {
                when (ch) {
                    ',' -> commas++
                    ';' -> semis++
                    '\t' -> tabs++
                }
            }
        }
        return when {
            tabs >= commas && tabs >= semis && tabs > 0 -> '\t'
            semis > commas && semis > 0 -> ';'
            else -> ','
        }
    }

    private fun splitDelimitedLine(line: String, delimiter: Char): List<String> {
        val result = ArrayList<String>(10)
        val current = StringBuilder(line.length.coerceAtMost(128))
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    current.append('"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
            } else if (ch == delimiter && !inQuotes) {
                result.add(current.toString().trim())
                current.setLength(0)
            } else {
                current.append(ch)
            }
            i++
        }
        result.add(current.toString().trim())
        return result
    }

    private fun normalizeHeaderKey(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            if (ch.isLetterOrDigit()) sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }

    private fun buildHeaderColumnMap(cols: List<String>): Map<String, Int> {
        val map = LinkedHashMap<String, Int>(cols.size)
        cols.forEachIndexed { idx, col ->
            val key = normalizeHeaderKey(col)
            if (key.isNotEmpty() && !map.containsKey(key)) {
                map[key] = idx
            }
        }
        return map
    }

    private fun Map<String, Int>.findAny(vararg keys: String): Int {
        for (k in keys) {
            val idx = this[k]
            if (idx != null) return idx
        }
        return -1
    }

    private fun List<String>.valueAt(index: Int): String? {
        if (index < 0 || index >= size) return null
        return this[index].trim().takeIf { it.isNotEmpty() }
    }

    private fun nextScalarAsString(reader: JsonReader): String? {
        return when (reader.peek()) {
            JsonToken.STRING -> reader.nextString().trim().takeIf { it.isNotEmpty() && !it.equals("null", true) }
            JsonToken.NUMBER -> reader.nextString().trim()
            else -> {
                reader.skipValue()
                null
            }
        }
    }

    private fun nextScalarAsDouble(reader: JsonReader): Double? {
        return when (reader.peek()) {
            JsonToken.NUMBER -> reader.nextDouble()
            JsonToken.STRING -> reader.nextString().trim().toDoubleOrNull()
            else -> {
                reader.skipValue()
                null
            }
        }
    }

    private fun buildEffectiveSchemeName(rawName: String, plan: String?, option: String?): String {
        val cleaned = rawName.trim().replace(Regex("\\s+"), " ")
        val lower = cleaned.lowercase(Locale.US)
        val sb = StringBuilder(cleaned)
        val cleanPlan = plan?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) && !it.equals("na", true) }
        val cleanOption = option?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) && !it.equals("na", true) }
        if (cleanPlan != null && !lower.contains(cleanPlan.lowercase(Locale.US))) {
            sb.append(" - ").append(cleanPlan)
        }
        if (cleanOption != null && !lower.contains(cleanOption.lowercase(Locale.US))) {
            sb.append(" - ").append(cleanOption)
        }
        return sb.toString()
    }

    fun parseFlexibleEpochDay(dateStr: String): Long? {
        val raw = dateStr.trim()
        if (raw.isEmpty()) return null
        val s = if (raw.length > 10 && (raw[10] == 'T' || raw[10] == ' ')) {
            raw.substring(0, 10)
        } else {
            raw
        }

        // dd-MMM-yyyy (e.g. 23-Nov-2026)
        parseAmfiDateEpochDay(s)?.let { return it }
        // dd-MM-yyyy (e.g. 23-11-2026)
        parseMfApiDateEpochDay(s)?.let { return it }

        // yyyy-MM-dd or yyyy/MM/dd
        if (s.length == 10 && (s[4] == '-' || s[4] == '/') && (s[7] == '-' || s[7] == '/')) {
            val year = parseFourDigits(s, 0)
            val month = parseTwoDigits(s, 5)
            val day = parseTwoDigits(s, 8)
            if (year != null && month != null && day != null) {
                return safeEpochDay(year, month, day)
            }
        }
        // dd/MM/yyyy
        if (s.length == 10 && s[2] == '/' && s[5] == '/') {
            val day = parseTwoDigits(s, 0)
            val month = parseTwoDigits(s, 3)
            val year = parseFourDigits(s, 6)
            if (day != null && month != null && year != null) {
                return safeEpochDay(year, month, day)
            }
        }
        // d-MMM-yyyy or dd-MMM-yy
        val sep = if (s.contains('-')) '-' else if (s.contains('/')) '/' else if (s.contains(' ')) ' ' else return null
        val parts = s.split(sep)
        if (parts.size == 3) {
            val p0 = parts[0].trim()
            val p1 = parts[1].trim()
            val p2 = parts[2].trim()
            if (p0.length == 4 && p0.all { it.isDigit() }) {
                val y = p0.toIntOrNull() ?: return null
                val m = p1.toIntOrNull() ?: if (p1.length == 3) parseThreeLetterMonth(p1, 0) else null
                val d = p2.toIntOrNull()
                if (m != null && d != null) return safeEpochDay(y, m, d)
            } else {
                val d = p0.toIntOrNull()
                val m = p1.toIntOrNull() ?: if (p1.length == 3) parseThreeLetterMonth(p1, 0) else null
                val yRaw = p2.toIntOrNull()
                val y = when {
                    yRaw == null -> null
                    p2.length == 2 -> 2000 + yRaw
                    else -> yRaw
                }
                if (d != null && m != null && y != null) return safeEpochDay(y, m, d)
            }
        }
        return null
    }

    fun parseAmfiDateEpochDay(dateStr: String): Long? {
        val s = dateStr.trim()
        if (s.length == 11 && s[2] == '-' && s[6] == '-') {
            val day = parseTwoDigits(s, 0) ?: return null
            val month = parseThreeLetterMonth(s, 3) ?: return null
            val year = parseFourDigits(s, 7) ?: return null
            return safeEpochDay(year, month, day)
        }
        return null
    }

    fun parseMfApiDateEpochDay(dateStr: String): Long? {
        val s = dateStr.trim()
        if (s.length == 10 && s[2] == '-' && s[5] == '-') {
            val day = parseTwoDigits(s, 0) ?: return null
            val month = parseTwoDigits(s, 3) ?: return null
            val year = parseFourDigits(s, 6) ?: return null
            return safeEpochDay(year, month, day)
        }
        return null
    }

    private fun parseTwoDigits(s: String, idx: Int): Int? {
        val c0 = s[idx] - '0'
        val c1 = s[idx + 1] - '0'
        if (c0 !in 0..9 || c1 !in 0..9) return null
        return c0 * 10 + c1
    }

    private fun parseFourDigits(s: String, idx: Int): Int? {
        val c0 = s[idx] - '0'
        val c1 = s[idx + 1] - '0'
        val c2 = s[idx + 2] - '0'
        val c3 = s[idx + 3] - '0'
        if (c0 !in 0..9 || c1 !in 0..9 || c2 !in 0..9 || c3 !in 0..9) return null
        return c0 * 1000 + c1 * 100 + c2 * 10 + c3
    }

    private fun parseThreeLetterMonth(s: String, idx: Int): Int? {
        val c0 = s[idx].uppercaseChar()
        val c1 = s[idx + 1].lowercaseChar()
        val c2 = s[idx + 2].lowercaseChar()
        return when {
            c0 == 'J' && c1 == 'a' && c2 == 'n' -> 1
            c0 == 'F' && c1 == 'e' && c2 == 'b' -> 2
            c0 == 'M' && c1 == 'a' && c2 == 'r' -> 3
            c0 == 'A' && c1 == 'p' && c2 == 'r' -> 4
            c0 == 'M' && c1 == 'a' && c2 == 'y' -> 5
            c0 == 'J' && c1 == 'u' && c2 == 'n' -> 6
            c0 == 'J' && c1 == 'u' && c2 == 'l' -> 7
            c0 == 'A' && c1 == 'u' && c2 == 'g' -> 8
            c0 == 'S' && c1 == 'e' && c2 == 'p' -> 9
            c0 == 'O' && c1 == 'c' && c2 == 't' -> 10
            c0 == 'N' && c1 == 'o' && c2 == 'v' -> 11
            c0 == 'D' && c1 == 'e' && c2 == 'c' -> 12
            else -> null
        }
    }

    private fun safeEpochDay(year: Int, month: Int, day: Int): Long? {
        if (month !in 1..12 || day !in 1..31 || year !in 1980..2100) return null
        return try {
            LocalDate.of(year, month, day).toEpochDay()
        } catch (_: Exception) {
            null
        }
    }

    private fun inferAmcFromName(schemeName: String): String {
        val words = schemeName.split(" ").filter { it.isNotBlank() }
        return words.take(2).joinToString(" ").ifBlank { "Mutual Fund" }
    }

    private class SifSchemeAccumulator(
        var schemeCode: String,
        val schemeName: String,
        val amcName: String,
        var rawCategory: String?,
        var isin: String?,
        val dedupKey: String
    ) {
        val pointsByDay = LinkedHashMap<Long, Double>(280)
    }
}
