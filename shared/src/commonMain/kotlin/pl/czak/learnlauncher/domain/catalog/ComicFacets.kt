package pl.czak.learnlauncher.domain.catalog

/**
 * Pure facet derivation for catalog comics (v0: everything derived from the
 * comic id + sizeMb on the client — no server change). Shared by the Android
 * and Desktop filter bars; candidate for the Haskell domain later (see
 * `haskell-domain-source-of-truth.md`).
 *
 * Id grammar (observed live catalog): `<series…>[-<lang>]-<quality>[-<variant…>]`
 * e.g. `chainsawman-fr-hq-tokens`, `evangelion-es-lq`, `hi-supremo-hq`
 * (there `hi` is the SERIES prefix, not a language — derivation is
 * position-aware, never "contains hi").
 */

/** Recognized language codes; OTHER = no/unknown language token. */
enum class Language {
    FR, ES, HI, DE, OTHER
}

/** Display-friendly size ranges; lower bound inclusive. Keep stable so a persisted filter keeps meaning the same thing. */
enum class SizeBucket(val label: String) {
    XS("XS (<1 MB)"),
    S("S (1-10 MB)"),
    M("M (10-50 MB)"),
    L("L (50-150 MB)"),
    XL("XL (>150 MB)")
}

data class ComicFacets(
    val series: String,
    val language: Language,
    val sizeBucket: SizeBucket
)

private val QUALITY_TOKENS = setOf("hq", "mq", "lq")

private val LANGUAGE_CODES = mapOf(
    "fr" to Language.FR,
    "es" to Language.ES,
    "hi" to Language.HI,
    "de" to Language.DE
)

/** [SizeBucket] for a raw MB size — lower bound inclusive. */
fun sizeBucketOf(sizeMb: Double): SizeBucket = when {
    sizeMb < 1.0 -> SizeBucket.XS
    sizeMb < 10.0 -> SizeBucket.S
    sizeMb < 50.0 -> SizeBucket.M
    sizeMb < 150.0 -> SizeBucket.L
    else -> SizeBucket.XL
}

/**
 * Derive facets from a comic id + size.
 *
 * - Split the id on `-`; the *head* is every token before the FIRST quality
 *   token (`hq`/`mq`/`lq`); if there is no quality token, the head is all tokens.
 * - POSITION-AWARE language: the language is the head's LAST token IFF it is a
 *   recognized code ({fr,es,hi,de}) AND the head has more than one token — a
 *   leading code is series, never language (`hi-supremo-hq` → series
 *   `hi-supremo`, language OTHER). Otherwise language = OTHER and the whole
 *   head is the series.
 * - For series GROUPING, trailing pure-numeric tokens are stripped, so
 *   `onepiece-de-batch-01` and `onepiece-de-batch-02` group as series
 *   `onepiece-de-batch`. (Documented v0 limitation: an embedded `de` in such
 *   ids is series text, not a language.)
 */
fun facetsOf(id: String, sizeMb: Double): ComicFacets {
    val tokens = id.lowercase().split('-').filter { it.isNotBlank() }
    val head = tokens.takeWhile { it !in QUALITY_TOKENS }.ifEmpty { tokens }

    val language: Language =
        if (head.size > 1) LANGUAGE_CODES[head.last()] ?: Language.OTHER
        else Language.OTHER

    val seriesTokens = if (language != Language.OTHER) head.dropLast(1) else head

    // Series grouping: strip trailing pure-numeric batch counters (…-batch-01).
    val groupedTokens = seriesTokens
        .dropLastWhile { token -> token.isNotEmpty() && token.all(Char::isDigit) }
        .ifEmpty { seriesTokens }

    val series = groupedTokens.joinToString("-").ifEmpty { id.lowercase() }

    return ComicFacets(series = series, language = language, sizeBucket = sizeBucketOf(sizeMb))
}
