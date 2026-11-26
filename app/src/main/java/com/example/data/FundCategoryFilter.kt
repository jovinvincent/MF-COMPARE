package com.example.data

enum class FundCategoryGroup(val title: String) {
    EQUITY("1. Equity Schemes (13)"),
    THEMES("Sectoral / Thematic Themes"),
    DEBT("2. Debt Schemes (17)"),
    HYBRID("3. Hybrid Schemes (7)"),
    LIFE_CYCLE("4. Life Cycle Funds"),
    OTHER("5. Other Schemes"),
    SIF("6. SIF Schemes (7)")
}

data class FundCategoryOption(
    val id: String,
    val label: String,
    val group: FundCategoryGroup,
    val subtitle: String? = null
)

/**
 * Multi-selectable SEBI / AMFI Mutual Fund & Specialized Investment Fund (SIF) categorization engine.
 * Evaluates both explicit AMFI/MFAPI/Tigzig category metadata and scheme names accurately
 * so multi-tickable category filtering works cleanly across all ~5,000 Direct Growth schemes.
 */
object FundCategoryFilter {

    val ALL_CATEGORIES: List<FundCategoryOption> = listOf(
        // 1. Equity Schemes (13)
        FundCategoryOption("EQ_MULTI_CAP", "Multi Cap Fund", FundCategoryGroup.EQUITY),
        FundCategoryOption("EQ_LARGE_CAP", "Large Cap Fund", FundCategoryGroup.EQUITY),
        FundCategoryOption("EQ_LARGE_MID_CAP", "Large & Mid Cap Fund", FundCategoryGroup.EQUITY),
        FundCategoryOption("EQ_MID_CAP", "Mid Cap Fund", FundCategoryGroup.EQUITY),
        FundCategoryOption("EQ_SMALL_CAP", "Small Cap Fund", FundCategoryGroup.EQUITY),
        FundCategoryOption("EQ_FLEXI_CAP", "Flexi Cap Fund", FundCategoryGroup.EQUITY),
        FundCategoryOption("EQ_DIVIDEND_YIELD", "Dividend Yield Fund", FundCategoryGroup.EQUITY),
        FundCategoryOption("EQ_VALUE", "Value Fund", FundCategoryGroup.EQUITY),
        FundCategoryOption("EQ_CONTRA", "Contra Fund", FundCategoryGroup.EQUITY),
        FundCategoryOption("EQ_FOCUSED", "Focused Fund", FundCategoryGroup.EQUITY),
        FundCategoryOption("EQ_SECTORAL", "Sectoral Fund", FundCategoryGroup.EQUITY),
        FundCategoryOption("EQ_THEMATIC", "Thematic Fund", FundCategoryGroup.EQUITY),
        FundCategoryOption("EQ_ELSS", "ELSS (Tax Saver) Fund", FundCategoryGroup.EQUITY),

        // Sectoral / Thematic Themes (16)
        FundCategoryOption("TH_BFSI", "Banking & Financial Services", FundCategoryGroup.THEMES),
        FundCategoryOption("TH_TECH", "Technology / IT", FundCategoryGroup.THEMES),
        FundCategoryOption("TH_PHARMA", "Pharma & Healthcare", FundCategoryGroup.THEMES),
        FundCategoryOption("TH_AUTO", "Auto & Auto Ancillaries", FundCategoryGroup.THEMES),
        FundCategoryOption("TH_CONSUMPTION", "Consumption / FMCG", FundCategoryGroup.THEMES),
        FundCategoryOption("TH_ENERGY", "Energy & Power", FundCategoryGroup.THEMES),
        FundCategoryOption("TH_INFRA", "Infrastructure", FundCategoryGroup.THEMES),
        FundCategoryOption("TH_MANUFACTURING", "Manufacturing", FundCategoryGroup.THEMES),
        FundCategoryOption("TH_DEFENCE", "Defence", FundCategoryGroup.THEMES),
        FundCategoryOption("TH_PSU", "PSU", FundCategoryGroup.THEMES),
        FundCategoryOption("TH_MNC", "MNC", FundCategoryGroup.THEMES),
        FundCategoryOption("TH_ESG", "ESG", FundCategoryGroup.THEMES),
        FundCategoryOption("TH_BUSINESS_CYCLE", "Business Cycle", FundCategoryGroup.THEMES),
        FundCategoryOption("TH_INNOVATION_QUANT", "Innovation / Quant", FundCategoryGroup.THEMES),
        FundCategoryOption("TH_DIGITAL_FINTECH", "Digital / Fintech", FundCategoryGroup.THEMES),
        FundCategoryOption("TH_LOGISTICS", "Logistics", FundCategoryGroup.THEMES),

        // 2. Debt Schemes (17)
        FundCategoryOption("DT_OVERNIGHT", "Overnight Fund", FundCategoryGroup.DEBT),
        FundCategoryOption("DT_LIQUID", "Liquid Fund", FundCategoryGroup.DEBT),
        FundCategoryOption("DT_ULTRA_SHORT", "Ultra Short Term Fund", FundCategoryGroup.DEBT),
        FundCategoryOption("DT_LOW_DURATION", "Ultra Short to Short Term Fund", FundCategoryGroup.DEBT),
        FundCategoryOption("DT_MONEY_MARKET", "Money Market Fund", FundCategoryGroup.DEBT),
        FundCategoryOption("DT_SHORT_TERM", "Short Term Fund", FundCategoryGroup.DEBT),
        FundCategoryOption("DT_MEDIUM_TERM", "Medium Term Fund", FundCategoryGroup.DEBT),
        FundCategoryOption("DT_MEDIUM_LONG", "Medium to Long Term Fund", FundCategoryGroup.DEBT),
        FundCategoryOption("DT_LONG_TERM", "Long Term Fund", FundCategoryGroup.DEBT),
        FundCategoryOption("DT_DYNAMIC_BOND", "Dynamic Term Fund", FundCategoryGroup.DEBT),
        FundCategoryOption("DT_CORPORATE_BOND", "Corporate Bond Fund", FundCategoryGroup.DEBT),
        FundCategoryOption("DT_CREDIT_RISK", "Credit Risk Fund", FundCategoryGroup.DEBT),
        FundCategoryOption("DT_BANKING_PSU", "Banking and PSU Debt Fund", FundCategoryGroup.DEBT),
        FundCategoryOption("DT_GILT", "Gilt Fund", FundCategoryGroup.DEBT),
        FundCategoryOption("DT_GILT_10Y", "10-year Constant Maturity Gilt Fund", FundCategoryGroup.DEBT),
        FundCategoryOption("DT_FLOATER", "Floating Interest Rates Fund", FundCategoryGroup.DEBT),
        FundCategoryOption("DT_SECTORAL_DEBT", "Sectoral Debt Fund", FundCategoryGroup.DEBT),

        // 3. Hybrid Schemes (7)
        FundCategoryOption("HY_CONSERVATIVE", "Conservative Hybrid Fund", FundCategoryGroup.HYBRID),
        FundCategoryOption("HY_BALANCED", "Balanced Hybrid Fund", FundCategoryGroup.HYBRID),
        FundCategoryOption("HY_AGGRESSIVE", "Aggressive Hybrid Fund", FundCategoryGroup.HYBRID),
        FundCategoryOption("HY_DAA", "Dynamic Asset Allocation Fund", FundCategoryGroup.HYBRID),
        FundCategoryOption("HY_MULTI_ASSET", "Multi Asset Allocation Fund", FundCategoryGroup.HYBRID),
        FundCategoryOption("HY_ARBITRAGE", "Arbitrage Fund", FundCategoryGroup.HYBRID),
        FundCategoryOption("HY_EQUITY_SAVINGS", "Equity Savings Fund", FundCategoryGroup.HYBRID),

        // 4. Life Cycle Funds (1)
        FundCategoryOption("LC_LIFE_CYCLE", "Life Cycle Funds", FundCategoryGroup.LIFE_CYCLE),

        // 5. Other Schemes (4)
        FundCategoryOption("OT_INDEX", "Index Funds", FundCategoryGroup.OTHER),
        FundCategoryOption("OT_ETF", "ETFs", FundCategoryGroup.OTHER),
        FundCategoryOption("OT_FOF_DOMESTIC", "Fund of Funds – Domestic", FundCategoryGroup.OTHER),
        FundCategoryOption("OT_FOF_OVERSEAS", "Fund of Funds – Overseas", FundCategoryGroup.OTHER),

        // 6. SIF Schemes (7) — SEBI Specialized Investment Fund Strategies
        FundCategoryOption(
            id = "SIF_EQUITY_LONG_SHORT",
            label = "Equity Long-Short Fund",
            group = FundCategoryGroup.SIF,
            subtitle = "Equity-Oriented Investment Strategy"
        ),
        FundCategoryOption(
            id = "SIF_EQUITY_EX_TOP_100",
            label = "Equity Ex-Top 100 Long-Short Fund",
            group = FundCategoryGroup.SIF,
            subtitle = "Equity-Oriented Investment Strategy"
        ),
        FundCategoryOption(
            id = "SIF_SECTOR_ROTATION",
            label = "Sector Rotation Long-Short Fund",
            group = FundCategoryGroup.SIF,
            subtitle = "Equity-Oriented Investment Strategy"
        ),
        FundCategoryOption(
            id = "SIF_DEBT_LONG_SHORT",
            label = "Debt Long-Short Fund",
            group = FundCategoryGroup.SIF,
            subtitle = "Debt-Oriented Investment Strategy"
        ),
        FundCategoryOption(
            id = "SIF_SECTORAL_DEBT",
            label = "Sectoral Debt Long-Short Fund",
            group = FundCategoryGroup.SIF,
            subtitle = "Debt-Oriented Investment Strategy"
        ),
        FundCategoryOption(
            id = "SIF_HYBRID_LONG_SHORT",
            label = "Hybrid Long-Short Fund",
            group = FundCategoryGroup.SIF,
            subtitle = "Hybrid Investment Strategy"
        ),
        FundCategoryOption(
            id = "SIF_ACTIVE_ASSET_ALLOCATOR",
            label = "Active Asset Allocator Long-Short Fund",
            group = FundCategoryGroup.SIF,
            subtitle = "Hybrid Investment Strategy"
        )
    )

    val CATEGORIES_BY_ID: Map<String, FundCategoryOption> = ALL_CATEGORIES.associateBy { it.id }

    val GROUPED_CATEGORIES: Map<FundCategoryGroup, List<FundCategoryOption>> =
        FundCategoryGroup.entries.associateWith { grp -> ALL_CATEGORIES.filter { it.group == grp } }

    private val WORD_IT_REGEX = Regex("\\b(it|i\\.t\\.)\\b")
    private val WORD_PSU_REGEX = Regex("\\bpsu\\b")
    private val WORD_MNC_REGEX = Regex("\\bmnc\\b")
    private val WORD_ESG_REGEX = Regex("\\besg\\b")
    private val WORD_SIF_REGEX = Regex("\\b(sif|qsif)\\b")
    private val WORD_INDEX_REGEX = Regex("\\bindex\\b")
    private val WORD_ETF_REGEX = Regex("\\betfs?\\b")
    private val WORD_FOF_REGEX = Regex("\\b(fof|fofs)\\b|fund\\s+of\\s+funds?")
    private val OVERSEAS_FOF_REGEX = Regex(
        "overseas|global|world|international|\\bus\\b|u\\.s\\.|nasdaq|s&p\\s*500|europe|asia|china|japan|taiwan|brazil|hong\\s*kong|hang\\s*seng|asean|emerging\\s+market|greater\\s+china"
    )
    private val QUANT_STRATEGY_REGEX = Regex(
        "innovation|innovator|quantamental|quantitative|quant\\s+(fund|active|model|strategy|thematic|esg)|multi[-\\s]factor"
    )
    private val SECTORAL_THEME_IDS = listOf(
        "TH_BFSI", "TH_TECH", "TH_PHARMA", "TH_AUTO", "TH_CONSUMPTION", "TH_ENERGY",
        "TH_INFRA", "TH_MANUFACTURING", "TH_DEFENCE", "TH_PSU", "TH_MNC", "TH_ESG",
        "TH_BUSINESS_CYCLE", "TH_INNOVATION_QUANT", "TH_DIGITAL_FINTECH", "TH_LOGISTICS"
    )
    private val SIF_CATEGORY_OPTIONS = ALL_CATEGORIES.filter { it.group == FundCategoryGroup.SIF }

    fun matchesAnyCategory(fund: FundMasterEntity, selectedCategoryIds: Set<String>): Boolean {
        if (selectedCategoryIds.isEmpty()) return true
        if (fund.isIndex) return false
        val name = fund.schemeName.lowercase()
        val cat = fund.category.lowercase()
        val combined = "$name $cat"
        val isSif = fund.dataSource == RemoteDataSources.DATA_SOURCE_SIF || isSifText(name, cat)
        for (id in selectedCategoryIds) {
            if (matchesNormalized(name, cat, combined, isSif, id)) return true
        }
        return false
    }

    fun matchesCategory(fund: FundMasterEntity, categoryId: String): Boolean {
        if (fund.isIndex) return false
        val name = fund.schemeName.lowercase()
        val cat = fund.category.lowercase()
        val isSif = fund.dataSource == RemoteDataSources.DATA_SOURCE_SIF || isSifText(name, cat)
        return matchesNormalized(name, cat, "$name $cat", isSif, categoryId)
    }

    fun isSifFund(fund: FundMasterEntity): Boolean {
        if (fund.isIndex) return false
        if (fund.dataSource == RemoteDataSources.DATA_SOURCE_SIF) return true
        return isSifText(fund.schemeName.lowercase(), fund.category.lowercase())
    }

    fun isSifText(nameLower: String, catLower: String): Boolean {
        val combined = "$nameLower $catLower"
        return catLower.contains("sif") ||
            catLower.contains("specialized investment") ||
            combined.contains("long-short") ||
            combined.contains("long short") ||
            combined.contains("ex-top 100") ||
            combined.contains("ex top 100") ||
            combined.contains("ex-top100") ||
            combined.contains("sector rotation") ||
            combined.contains("active asset allocator") ||
            WORD_SIF_REGEX.containsMatchIn(nameLower)
    }

    fun inferSifCategoryLabel(schemeName: String, rawCategory: String?): String {
        val name = schemeName.lowercase()
        val cat = rawCategory?.lowercase().orEmpty()
        val combined = "$name $cat"
        for (opt in SIF_CATEGORY_OPTIONS) {
            // Check more specific variants first (Ex-Top 100 before Equity Long-Short, Sectoral Debt before Debt Long-Short)
            val orderedIds = listOf(
                "SIF_EQUITY_EX_TOP_100",
                "SIF_SECTOR_ROTATION",
                "SIF_EQUITY_LONG_SHORT",
                "SIF_SECTORAL_DEBT",
                "SIF_DEBT_LONG_SHORT",
                "SIF_ACTIVE_ASSET_ALLOCATOR",
                "SIF_HYBRID_LONG_SHORT"
            )
            for (id in orderedIds) {
                if (matchesNormalized(name, cat, combined, isSif = true, id = id)) {
                    val label = CATEGORIES_BY_ID[id]?.label ?: continue
                    return "SIF - $label"
                }
            }
        }
        return rawCategory?.trim()?.takeIf { it.isNotBlank() }?.let {
            if (it.contains("sif", ignoreCase = true)) it else "SIF - $it"
        } ?: "SIF - Specialized Investment Fund"
    }

    private fun isPassiveOrFof(name: String, cat: String): Boolean {
        return WORD_FOF_REGEX.containsMatchIn(name) ||
            WORD_FOF_REGEX.containsMatchIn(cat) ||
            WORD_ETF_REGEX.containsMatchIn(name) ||
            cat.contains("etf") ||
            WORD_INDEX_REGEX.containsMatchIn(name) ||
            cat.contains("index fund")
    }

    private fun isDebtCategory(cat: String): Boolean = cat.contains("debt") && !isSifText("", cat)

    private fun matchesNormalized(
        name: String,
        cat: String,
        combined: String,
        isSif: Boolean,
        id: String
    ): Boolean {
        val passiveOrFof = isPassiveOrFof(name, cat)
        val bankingAndPsu = combined.contains("banking & psu") ||
            combined.contains("banking and psu") ||
            combined.contains("banking psu")

        return when (id) {
            // 1. Equity Schemes
            "EQ_LARGE_MID_CAP" -> !passiveOrFof && !isSif && (
                combined.contains("large & mid") ||
                    combined.contains("large and mid") ||
                    combined.contains("large-mid") ||
                    combined.contains("large & midcap")
                )
            "EQ_MULTI_CAP" -> !passiveOrFof && !isSif &&
                (combined.contains("multi cap") || combined.contains("multicap")) &&
                !combined.contains("large & mid") && !combined.contains("large and mid") &&
                !combined.contains("flexi")
            "EQ_LARGE_CAP" -> !passiveOrFof && !isSif && (
                cat.contains("large cap") ||
                    ((name.contains("large cap") || name.contains("largecap") || name.contains("bluechip") || name.contains("top 100")) &&
                        !combined.contains("large & mid") && !combined.contains("large and mid") &&
                        !combined.contains("mid cap") && !combined.contains("small cap"))
                )
            "EQ_MID_CAP" -> !passiveOrFof && !isSif && (
                cat.contains("mid cap") && !cat.contains("large") ||
                    ((name.contains("mid cap") || name.contains("midcap") || name.contains("emerging equities")) &&
                        !combined.contains("large & mid") && !combined.contains("large and mid") && !combined.contains("small"))
                )
            "EQ_SMALL_CAP" -> !passiveOrFof && !isSif && (
                cat.contains("small cap") || name.contains("small cap") || name.contains("smallcap")
                )
            "EQ_FLEXI_CAP" -> !passiveOrFof && !isSif && (combined.contains("flexi cap") || combined.contains("flexicap"))
            "EQ_DIVIDEND_YIELD" -> !passiveOrFof && !isSif && combined.contains("dividend yield")
            "EQ_VALUE" -> !passiveOrFof && !isSif && (cat.contains("value") || name.contains("value fund") || name.contains("value discovery"))
            "EQ_CONTRA" -> !passiveOrFof && !isSif && combined.contains("contra")
            "EQ_FOCUSED" -> !passiveOrFof && !isSif && combined.contains("focused")
            "EQ_ELSS" -> !passiveOrFof && !isSif && (
                combined.contains("elss") || combined.contains("tax saver") ||
                    combined.contains("tax advantage") || combined.contains("tax relief") ||
                    combined.contains("tax shield") || combined.contains("long term equity")
                )
            "EQ_SECTORAL" -> !passiveOrFof && !isSif && !isDebtCategory(cat) && (
                cat.contains("sectoral") ||
                    listOf("TH_BFSI", "TH_TECH", "TH_PHARMA", "TH_AUTO", "TH_CONSUMPTION", "TH_ENERGY", "TH_INFRA", "TH_MANUFACTURING", "TH_DEFENCE", "TH_LOGISTICS")
                        .any { matchesNormalized(name, cat, combined, isSif = false, id = it) }
                )
            "EQ_THEMATIC" -> !passiveOrFof && !isSif && !isDebtCategory(cat) && (
                cat.contains("thematic") ||
                    combined.contains("thematic") ||
                    combined.contains("special opportunities") ||
                    combined.contains("india opportunities") ||
                    combined.contains("bharat") ||
                    combined.contains("housing") ||
                    combined.contains("rural") ||
                    listOf("TH_PSU", "TH_MNC", "TH_ESG", "TH_BUSINESS_CYCLE", "TH_INNOVATION_QUANT", "TH_DIGITAL_FINTECH")
                        .any { matchesNormalized(name, cat, combined, isSif = false, id = it) }
                )

            // Sectoral / Thematic Themes
            "TH_BFSI" -> !bankingAndPsu && !isDebtCategory(cat) && (
                combined.contains("banking") || combined.contains("financial service") || combined.contains("bfsi")
                )
            "TH_TECH" -> !isDebtCategory(cat) && (
                combined.contains("technology") || combined.contains("infotech") ||
                    combined.contains("tech ") || WORD_IT_REGEX.containsMatchIn(name)
                )
            "TH_PHARMA" -> !isDebtCategory(cat) && (
                combined.contains("pharma") || combined.contains("healthcare") ||
                    combined.contains("health care") || combined.contains("hospital") ||
                    combined.contains("life sciences")
                )
            "TH_AUTO" -> !isDebtCategory(cat) && (
                combined.contains("auto") || combined.contains("mobility") ||
                    combined.contains("transportation") || combined.contains("electric vehicle")
                )
            "TH_CONSUMPTION" -> !isDebtCategory(cat) && (
                combined.contains("consumption") || combined.contains("consumer") ||
                    combined.contains("fmcg") || combined.contains("non-cyclical")
                )
            "TH_ENERGY" -> !isDebtCategory(cat) && (
                combined.contains("energy") || combined.contains("power") ||
                    combined.contains("natural resources") || combined.contains("oil & gas") ||
                    combined.contains("oil and gas")
                )
            "TH_INFRA" -> !isDebtCategory(cat) && (
                combined.contains("infra") || combined.contains("build india")
                )
            "TH_MANUFACTURING" -> !isDebtCategory(cat) && (
                combined.contains("manufacturing") || combined.contains("make in india") || combined.contains("industrial")
                )
            "TH_DEFENCE" -> combined.contains("defence") || combined.contains("defense")
            "TH_PSU" -> !bankingAndPsu && !isDebtCategory(cat) && WORD_PSU_REGEX.containsMatchIn(combined)
            "TH_MNC" -> !isDebtCategory(cat) && (WORD_MNC_REGEX.containsMatchIn(combined) || combined.contains("multinational"))
            "TH_ESG" -> !isDebtCategory(cat) && (WORD_ESG_REGEX.containsMatchIn(combined) || combined.contains("sustainab"))
            "TH_BUSINESS_CYCLE" -> combined.contains("business cycle")
            "TH_INNOVATION_QUANT" -> !isDebtCategory(cat) && !isSif && QUANT_STRATEGY_REGEX.containsMatchIn(combined)
            "TH_DIGITAL_FINTECH" -> !isDebtCategory(cat) && (
                combined.contains("digital") || combined.contains("fintech") || combined.contains("internet")
                )
            "TH_LOGISTICS" -> combined.contains("logistics") || combined.contains("supply chain") || combined.contains("ports")

            // 2. Debt Schemes
            "DT_OVERNIGHT" -> !passiveOrFof && !isSif && combined.contains("overnight")
            "DT_LIQUID" -> !passiveOrFof && !isSif && combined.contains("liquid") && !combined.contains("ultra short")
            "DT_ULTRA_SHORT" -> !passiveOrFof && !isSif && (
                combined.contains("ultra short") || combined.contains("ultrashort")
                ) && !combined.contains("to short")
            "DT_LOW_DURATION" -> !passiveOrFof && !isSif && (
                combined.contains("low duration") || combined.contains("low-duration") ||
                    combined.contains("ultra short to short")
                )
            "DT_MONEY_MARKET" -> !passiveOrFof && !isSif && combined.contains("money market")
            "DT_SHORT_TERM" -> !passiveOrFof && !isSif && (
                combined.contains("short duration") || combined.contains("short term")
                ) && !combined.contains("ultra short") && !combined.contains("medium to long")
            "DT_MEDIUM_TERM" -> !passiveOrFof && !isSif && (
                combined.contains("medium duration") || combined.contains("medium term")
                ) && !combined.contains("medium to long")
            "DT_MEDIUM_LONG" -> !passiveOrFof && !isSif && (
                combined.contains("medium to long") || combined.contains("medium-to-long")
                )
            "DT_LONG_TERM" -> !passiveOrFof && !isSif && !combined.contains("long term equity") && !combined.contains("elss") && (
                combined.contains("long duration") || combined.contains("long term debt") || combined.contains("long term bond")
                )
            "DT_DYNAMIC_BOND" -> !passiveOrFof && !isSif && (
                combined.contains("dynamic bond") || combined.contains("dynamic term") || combined.contains("dynamic debt")
                )
            "DT_CORPORATE_BOND" -> !passiveOrFof && !isSif && combined.contains("corporate bond")
            "DT_CREDIT_RISK" -> !passiveOrFof && !isSif && (combined.contains("credit risk") || combined.contains("credit opportunities"))
            "DT_BANKING_PSU" -> !passiveOrFof && !isSif && bankingAndPsu
            "DT_GILT_10Y" -> !passiveOrFof && !isSif && (combined.contains("gilt") || combined.contains("g-sec")) && (
                combined.contains("10 year") || combined.contains("10-year") || combined.contains("10yr") || combined.contains("constant maturity")
                )
            "DT_GILT" -> !passiveOrFof && !isSif && (combined.contains("gilt") || combined.contains("g-sec")) &&
                !combined.contains("10 year") && !combined.contains("10-year") && !combined.contains("constant maturity")
            "DT_FLOATER" -> !passiveOrFof && !isSif && (combined.contains("floating") || combined.contains("floater"))
            "DT_SECTORAL_DEBT" -> !passiveOrFof && !isSif && (
                combined.contains("sectoral debt") || (isDebtCategory(cat) && cat.contains("sectoral"))
                )

            // 3. Hybrid Schemes
            "HY_CONSERVATIVE" -> !passiveOrFof && !isSif && (
                combined.contains("conservative hybrid") || (cat.contains("hybrid") && name.contains("conservative"))
                )
            "HY_BALANCED" -> !passiveOrFof && !isSif && (
                combined.contains("balanced hybrid") ||
                    (name.contains("balanced") && !combined.contains("balanced advantage") && !combined.contains("dynamic asset"))
                )
            "HY_AGGRESSIVE" -> !passiveOrFof && !isSif && (
                combined.contains("aggressive hybrid") || combined.contains("equity hybrid") || combined.contains("hybrid equity")
                )
            "HY_DAA" -> !passiveOrFof && !isSif && (
                combined.contains("dynamic asset allocation") || combined.contains("balanced advantage")
                )
            "HY_MULTI_ASSET" -> !passiveOrFof && !isSif && combined.contains("multi asset")
            "HY_ARBITRAGE" -> !passiveOrFof && !isSif && combined.contains("arbitrage")
            "HY_EQUITY_SAVINGS" -> !passiveOrFof && !isSif && combined.contains("equity savings")

            // 4. Life Cycle Funds
            "LC_LIFE_CYCLE" -> !isSif && (
                combined.contains("life cycle") ||
                    combined.contains("lifecycle") ||
                    combined.contains("retirement") ||
                    combined.contains("children") ||
                    combined.contains("solution oriented")
                )

            // 5. Other Schemes
            "OT_INDEX" -> !isSif && (WORD_INDEX_REGEX.containsMatchIn(name) || cat.contains("index")) &&
                !WORD_FOF_REGEX.containsMatchIn(name) && !WORD_ETF_REGEX.containsMatchIn(name)
            "OT_ETF" -> !isSif && (WORD_ETF_REGEX.containsMatchIn(name) || cat.contains("etf")) &&
                !WORD_FOF_REGEX.containsMatchIn(name)
            "OT_FOF_DOMESTIC" -> !isSif && (WORD_FOF_REGEX.containsMatchIn(name) || cat.contains("fof") || cat.contains("fund of fund")) &&
                !OVERSEAS_FOF_REGEX.containsMatchIn(combined) && !cat.contains("overseas")
            "OT_FOF_OVERSEAS" -> !isSif && (WORD_FOF_REGEX.containsMatchIn(name) || cat.contains("fof") || cat.contains("fund of fund")) &&
                (OVERSEAS_FOF_REGEX.containsMatchIn(combined) || cat.contains("overseas"))

            // 6. SIF (Specialized Investment Fund) Strategies
            "SIF_EQUITY_EX_TOP_100" -> isSif && (
                combined.contains("ex-top 100") ||
                    combined.contains("ex top 100") ||
                    combined.contains("ex-top100") ||
                    combined.contains("ex top100")
                )
            "SIF_SECTOR_ROTATION" -> isSif && combined.contains("sector rotation")
            "SIF_EQUITY_LONG_SHORT" -> isSif &&
                combined.contains("equity") &&
                (combined.contains("long-short") || combined.contains("long short")) &&
                !combined.contains("ex-top 100") &&
                !combined.contains("ex top 100") &&
                !combined.contains("ex-top100") &&
                !combined.contains("ex top100") &&
                !combined.contains("sector rotation")
            "SIF_SECTORAL_DEBT" -> isSif && combined.contains("sectoral debt")
            "SIF_DEBT_LONG_SHORT" -> isSif &&
                combined.contains("debt") &&
                (combined.contains("long-short") || combined.contains("long short")) &&
                !combined.contains("sectoral debt")
            "SIF_ACTIVE_ASSET_ALLOCATOR" -> isSif && combined.contains("active asset allocator")
            "SIF_HYBRID_LONG_SHORT" -> isSif &&
                combined.contains("hybrid") &&
                (combined.contains("long-short") || combined.contains("long short")) &&
                !combined.contains("active asset allocator")

            else -> false
        }
    }

    /**
     * Expose theme ID list check for sectoral equity grouping.
     */
    fun isSectoralOrThematicCategory(id: String): Boolean = id in SECTORAL_THEME_IDS
}
