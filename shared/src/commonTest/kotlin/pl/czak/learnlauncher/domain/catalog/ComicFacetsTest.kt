package pl.czak.learnlauncher.domain.catalog

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Table-driven round-trip asserts for the pure facetizer — the proof artifact
 * required by `catalog-filter-size-series-language.md`. Cases come from the
 * spec + the live droplet catalog, including the position-aware
 * "`hi` is SERIES, not language" edge and the embedded-`de` non-language case.
 */
class ComicFacetsTest {

    private data class Case(
        val id: String,
        val sizeMb: Double,
        val series: String,
        val language: Language,
        val bucket: SizeBucket
    )

    private val table = listOf(
        Case("evangelion-es-hq", 12.5, "evangelion", Language.ES, SizeBucket.M),
        Case("evangelion-es-lq", 8.4, "evangelion", Language.ES, SizeBucket.S),
        Case("evangelion-es-mq", 10.1, "evangelion", Language.ES, SizeBucket.M),
        // "hi" in leading position is the SERIES prefix, never a language.
        Case("hi-supremo-hq", 0.6, "hi-supremo", Language.OTHER, SizeBucket.XS),
        Case("hi-supremo-hq-rotated", 0.6, "hi-supremo", Language.OTHER, SizeBucket.XS),
        Case("chainsawman-fr-hq-tokens-translated", 18.5, "chainsawman", Language.FR, SizeBucket.M),
        // Embedded 'de' must NOT read as a language (only the head's last token counts).
        Case("histoire-de-l-art-hq", 1.7, "histoire-de-l-art", Language.OTHER, SizeBucket.S),
        Case("chainsawman-fr-hq", 176.0, "chainsawman", Language.FR, SizeBucket.XL),
        // Documented v0 limitation: trailing numeric batch tokens are stripped for
        // grouping; the embedded 'de' stays series text (language OTHER).
        Case("onepiece-de-batch-01-hq", 23.3, "onepiece-de-batch", Language.OTHER, SizeBucket.M),
        Case("batch-01-hq-tokens", 23.3, "batch", Language.OTHER, SizeBucket.M)
    )

    @Test
    fun facetsRoundTripTable() {
        table.forEach { case ->
            val actual = facetsOf(case.id, case.sizeMb)
            val expected = ComicFacets(case.series, case.language, case.bucket)
            assertEquals(expected, actual, "facetsOf(${case.id}, ${case.sizeMb})")
        }
    }

    @Test
    fun sizeBucketBoundsAreLowerInclusive() {
        assertEquals(SizeBucket.XS, sizeBucketOf(0.99))
        assertEquals(SizeBucket.S, sizeBucketOf(1.0))
        assertEquals(SizeBucket.S, sizeBucketOf(9.99))
        assertEquals(SizeBucket.M, sizeBucketOf(10.0))
        assertEquals(SizeBucket.M, sizeBucketOf(49.99))
        assertEquals(SizeBucket.L, sizeBucketOf(50.0))
        assertEquals(SizeBucket.L, sizeBucketOf(149.99))
        assertEquals(SizeBucket.XL, sizeBucketOf(150.0))
        assertEquals(SizeBucket.XL, sizeBucketOf(176.0))
    }
}
