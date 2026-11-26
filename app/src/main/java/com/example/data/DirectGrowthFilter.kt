package com.example.data

/**
 * Enforces strict Mutual Fund eligibility:
 * - Includes ONLY Indian Direct Plan Growth schemes (and explicitly tracked market indices).
 * - Strictly excludes Regular plans, IDCW plans, Dividend payout/reinvestment, Bonus, Sweep,
 *   Institutional, Retail, and other non-growth variants.
 * - Normalizes base scheme names for deduplication across duplicate variants.
 */
object DirectGrowthFilter {

    private val MULTI_SPACE_REGEX = Regex("\\s+")
    private val EXCLUSION_REGEX = Regex(
        "\\b(regular|idcw|dividend|payout|reinvest|reinvestment|bonus|sweep|segregated|institutional|retail|unclaimed|trigger|capital\\s*appreciation)\\b",
        RegexOption.IGNORE_CASE
    )
    private val DIV_SHORT_REGEX = Regex(
        "(?:\\(\\s*d\\s*\\)|\\bdiv\\b|-\\s*d\\b)",
        RegexOption.IGNORE_CASE
    )
    private val DIRECT_REGEX = Regex(
        "(?:\\bdirect\\b|\\bdir\\s*-?\\s*plan\\b|\\bdirect\\s*-?\\s*plan\\b|\\(d\\)\\s*growth)",
        RegexOption.IGNORE_CASE
    )
    private val GROWTH_REGEX = Regex(
        "(?:\\bgrowth\\b|\\(\\s*g\\s*\\)|-\\s*g\\b|-\\s*gr\\b|\\bgrowth\\s*option\\b)",
        RegexOption.IGNORE_CASE
    )

    fun isEligibleDirectGrowthScheme(
        schemeName: String,
        schemeType: String? = null,
        schemeCategory: String? = null
    ): Boolean {
        val cleaned = schemeName.trim().replace(MULTI_SPACE_REGEX, " ")
        if (cleaned.isEmpty()) return false

        val combined = buildString {
            append(cleaned)
            if (!schemeType.isNullOrBlank()) {
                append(' ')
                append(schemeType)
            }
            if (!schemeCategory.isNullOrBlank()) {
                append(' ')
                append(schemeCategory)
            }
        }

        if (EXCLUSION_REGEX.containsMatchIn(combined)) return false
        if (DIV_SHORT_REGEX.containsMatchIn(cleaned)) return false

        val hasDirect = DIRECT_REGEX.containsMatchIn(cleaned)
        val hasGrowth = GROWTH_REGEX.containsMatchIn(cleaned)
        return hasDirect && hasGrowth
    }

    /**
     * Produces a canonical deduplication key for a scheme so duplicate entries for the same
     * underlying fund (e.g., slight punctuation variations in "Direct Plan - Growth Option")
     * collapse cleanly while preserving genuinely distinct schemes.
     */
    fun deduplicationKey(schemeName: String, isin: String? = null): String {
        val cleanIsin = isin?.trim()?.uppercase()?.takeIf { it.length >= 10 && it.startsWith("INF") }
        if (cleanIsin != null) return "ISIN:$cleanIsin"

        val normalized = schemeName.lowercase()
            .replace(Regex("\\b(direct|plan|growth|option|fund)\\b"), " ")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
        return "NAME:$normalized"
    }

    fun normalizeSearchTokens(text: String): String {
        return text.lowercase()
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }
}
