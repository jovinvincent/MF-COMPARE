package com.example.data

/**
 * High-speed local fuzzy & partial-word search engine for Direct Growth Mutual Funds & Indices.
 * Supports:
 * - Incomplete multi-word prefixes (e.g. "qu sm ca" -> "Quant Small Cap Fund Direct Growth")
 * - Extra spaces and case-insensitivity
 * - Closest-match ranking (ordered word-prefix > unordered prefix > substring > sub-token)
 * - Market index matching (e.g. "nifty 50", "midcap 150")
 */
object FundSearchEngine {

    private val SPLIT_REGEX = Regex("\\s+")

    fun search(
        query: String,
        catalog: List<FundMasterEntity>,
        limit: Int = 60
    ): List<FundMasterEntity> {
        val cleanQuery = DirectGrowthFilter.normalizeSearchTokens(query)
        if (cleanQuery.isEmpty()) {
            // Default view when search is empty: indices first, then popular well-known schemes with latest NAV
            return catalog
                .sortedWith(
                    compareByDescending<FundMasterEntity> { it.isIndex }
                        .thenByDescending { it.latestNavDateEpochDay ?: 0L }
                        .thenBy { it.schemeName }
                )
                .take(limit)
        }

        val queryTokens = cleanQuery.split(SPLIT_REGEX).filter { it.isNotEmpty() }
        if (queryTokens.isEmpty()) return emptyList()

        val scored = ArrayList<Pair<FundMasterEntity, Int>>(64)
        for (item in catalog) {
            val score = scoreMatch(queryTokens, cleanQuery, item)
            if (score > 0) {
                scored.add(item to score)
            }
        }

        scored.sortWith(
            compareByDescending<Pair<FundMasterEntity, Int>> { it.second }
                .thenByDescending { it.first.isIndex }
                .thenBy { it.first.schemeName.length }
                .thenBy { it.first.schemeName }
        )

        return scored.take(limit).map { it.first }
    }

    internal fun scoreMatch(
        queryTokens: List<String>,
        fullNormalizedQuery: String,
        item: FundMasterEntity
    ): Int {
        val targetTokens = item.searchTokens.split(SPLIT_REGEX).filter { it.isNotEmpty() }
        if (targetTokens.isEmpty()) return 0

        var totalScore = 0

        // Bonus for exact full normalized substring match
        if (item.searchTokens.contains(fullNormalizedQuery)) {
            totalScore += 180
        }
        if (item.searchTokens.startsWith(fullNormalizedQuery)) {
            totalScore += 240
        }

        // Verify every query token matches at least one target token (prefix preferred, substring fallback)
        var lastMatchedIndex = -1
        var orderedMatches = 0
        var prefixMatches = 0

        for (qTok in queryTokens) {
            var bestTokenScore = 0
            var bestIdx = -1

            // Prefer matching at or after lastMatchedIndex to reward ordered abbreviations like "qu sm ca"
            for (idx in targetTokens.indices) {
                val tTok = targetTokens[idx]
                val tokenScore = when {
                    tTok == qTok -> 110
                    tTok.startsWith(qTok) -> 90
                    qTok.length >= 3 && tTok.contains(qTok) -> 45
                    else -> 0
                }
                if (tokenScore > 0) {
                    val orderBoost = if (idx > lastMatchedIndex) 35 else 0
                    val positionBoost = maxOf(0, 20 - idx * 2)
                    val combined = tokenScore + orderBoost + positionBoost
                    if (combined > bestTokenScore) {
                        bestTokenScore = combined
                        bestIdx = idx
                    }
                }
            }

            if (bestTokenScore == 0) {
                // Allow matching AMC name or collapsed acronym (e.g. "ppfas" for Parag Parikh if in tokens, or word initials)
                return 0
            }

            if (bestIdx > lastMatchedIndex) {
                orderedMatches++
                lastMatchedIndex = bestIdx
            }
            if (targetTokens[bestIdx].startsWith(qTok)) {
                prefixMatches++
            }
            totalScore += bestTokenScore
        }

        // Strong reward when all query tokens match in order and as prefixes (e.g., "qu sm ca", "parag par", "ni sm", "hd mi")
        if (orderedMatches == queryTokens.size) {
            totalScore += 140
        }
        if (prefixMatches == queryTokens.size) {
            totalScore += 100
        }
        // Slight boost for funds with fresh NAV data
        if (item.latestNav != null) {
            totalScore += 15
        }
        if (item.isIndex) {
            totalScore += 25
        }
        val isClosedOrFmp = item.searchTokens.contains("fmp") ||
            item.searchTokens.contains("fixed maturity") ||
            item.searchTokens.contains("interval") ||
            item.searchTokens.contains("capital protection")
        val queryWantsClosed = fullNormalizedQuery.contains("fmp") ||
            fullNormalizedQuery.contains("maturity") ||
            fullNormalizedQuery.contains("interval")
        if (isClosedOrFmp && !queryWantsClosed) {
            totalScore = (totalScore - 120).coerceAtLeast(1)
        }

        return totalScore
    }
}
