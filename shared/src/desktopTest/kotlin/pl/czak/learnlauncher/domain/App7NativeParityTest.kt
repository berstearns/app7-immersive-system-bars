package pl.czak.learnlauncher.domain

import pl.czak.learnlauncher.domain.model.CropSelection
import pl.czak.learnlauncher.domain.model.ConversationBox
import pl.czak.learnlauncher.domain.model.FocusTransform
import pl.czak.learnlauncher.domain.model.PageBubbleOrdering
import pl.czak.learnlauncher.domain.model.RegionType
import pl.czak.learnlauncher.domain.native.App7Native
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 5-partial: parity gate between the original Kotlin domain (in
 * `commonMain/.../domain/model/<files>.kt`) and the native projection in
 * `App7Native.*`.  Lives in `desktopTest` (JVM) — Android-side parity is
 * implicitly proven by the emulator smoke test in `MainActivity` plus
 * this JVM check (same C source, same JNI shim, same Kotlin façade).
 *
 * Phase 5-extended will grow this file to cover decision functions
 * (`AnnotationDecisionFunction`, `RandomSamplingDecisionFunction`) and
 * stateful trackers (`SessionTimeTracker`, `ImageSequenceNavigator`)
 * once their Phase 1b/2b/3b ports land.
 *
 * The grid is hand-picked to hit the boundary cases the C projection's
 * branches and predicates care about — degenerate crops, out-of-range
 * normalized coords, edge containment, duplicate permutation indices,
 * etc.  Add cases here when adding new behavior in App7Domain.hs.
 */
class App7NativeParityTest {

    private val cropGrid: List<CropSelection> = listOf(
        CropSelection(0.5f, 0.2f, 0.1f, 0.4f),     // start>end on x, <on y
        CropSelection(0.2f, 0.2f, 0.6f, 0.8f),     // simple in-range
        CropSelection(0.0f, 0.0f, 0.5f, 0.5f),     // corner
        CropSelection(-0.5f, 1.5f, 1.2f, -0.2f),   // all out of [0,1]
        CropSelection(0.1f, 0.1f, 0.105f, 0.105f), // tiny degenerate
        CropSelection(0.0f, 0.0f, 0.02f, 0.02f),   // at threshold (NOT degenerate)
        CropSelection(0.0f, 0.0f, 0.019f, 0.5f),   // one-axis-under
    )

    @Test
    fun normalizedX_parity() {
        for (c in cropGrid) {
            assertEquals(
                c.normalizedX(),
                App7Native.normalizedX(c.startX, c.startY, c.endX, c.endY),
                "normalizedX mismatch for $c"
            )
        }
    }

    @Test
    fun normalizedY_parity() {
        for (c in cropGrid) {
            assertEquals(
                c.normalizedY(),
                App7Native.normalizedY(c.startX, c.startY, c.endX, c.endY),
                "normalizedY mismatch for $c"
            )
        }
    }

    @Test
    fun normalizedWidth_parity() {
        for (c in cropGrid) {
            assertEquals(
                c.normalizedWidth(),
                App7Native.normalizedWidth(c.startX, c.startY, c.endX, c.endY),
                "normalizedWidth mismatch for $c"
            )
        }
    }

    @Test
    fun normalizedHeight_parity() {
        for (c in cropGrid) {
            assertEquals(
                c.normalizedHeight(),
                App7Native.normalizedHeight(c.startX, c.startY, c.endX, c.endY),
                "normalizedHeight mismatch for $c"
            )
        }
    }

    @Test
    fun centerX_parity() {
        for (c in cropGrid) {
            assertEquals(
                c.centerX(),
                App7Native.centerX(c.startX, c.startY, c.endX, c.endY),
                "centerX mismatch for $c"
            )
        }
    }

    @Test
    fun centerY_parity() {
        for (c in cropGrid) {
            assertEquals(
                c.centerY(),
                App7Native.centerY(c.startX, c.startY, c.endX, c.endY),
                "centerY mismatch for $c"
            )
        }
    }

    @Test
    fun isDegenerate_parity() {
        val minSizes = listOf(0.01f, 0.02f, 0.05f, 0.1f)
        for (c in cropGrid) {
            for (m in minSizes) {
                assertEquals(
                    c.isDegenerate(m),
                    App7Native.isDegenerate(m, c.startX, c.startY, c.endX, c.endY),
                    "isDegenerate mismatch for min=$m, $c"
                )
            }
        }
    }

    @Test
    fun clamped_parity() {
        for (c in cropGrid) {
            val k = c.clamped()
            val n = App7Native.clamped(c.startX, c.startY, c.endX, c.endY)
            assertEquals(4, n.size, "clamped returns 4 elements")
            assertEquals(k.startX, n[0], "clamped startX mismatch for $c")
            assertEquals(k.startY, n[1], "clamped startY mismatch for $c")
            assertEquals(k.endX,   n[2], "clamped endX mismatch for $c")
            assertEquals(k.endY,   n[3], "clamped endY mismatch for $c")
        }
    }

    // rx, ry, rw, rh, pw, ph, sw, sh
    private val focusGrid: List<FloatArray> = listOf(
        floatArrayOf(0.05f, 0.10f, 0.40f, 0.70f, 1000f, 1500f, 1080f, 2160f), // tall  -> 0
        floatArrayOf(0.10f, 0.05f, 0.80f, 0.25f, 1000f, 1500f, 1080f, 2160f), // wide  -> 90
        floatArrayOf(0.00f, 0.00f, 0.50f, 0.50f, 1000f, 1000f, 1000f, 1000f), // square on square -> 0
        floatArrayOf(0.00f, 0.00f, 1.00f, 0.20f,  800f, 1200f, 1080f, 2160f), // very wide -> 90
        floatArrayOf(0.20f, 0.20f, 0.30f, 0.90f, 2000f, 3000f, 1440f, 3040f), // tall hi-res
        floatArrayOf(0.00f, 0.00f, 0.00f, 0.00f,    0f,    0f,    0f,    0f), // degenerate guard
    )

    @Test
    fun focusTransform_parity() {
        for (g in focusGrid) {
            val k = FocusTransform.compute(g[0], g[1], g[2], g[3], g[4], g[5], g[6], g[7])
            val n = App7Native.focusTransform(g[0], g[1], g[2], g[3], g[4], g[5], g[6], g[7])
            assertEquals(4, n.size, "focusTransform returns 4 elements")
            assertEquals(k.rotationDeg.toFloat(), n[0], "focus rotationDeg mismatch for ${g.toList()}")
            assertEquals(k.scale, n[1], 1e-4f, "focus scale mismatch for ${g.toList()}")
            assertEquals(k.translateX, n[2], 1e-3f, "focus translateX mismatch for ${g.toList()}")
            assertEquals(k.translateY, n[3], 1e-3f, "focus translateY mismatch for ${g.toList()}")
        }
    }

    @Test
    fun focusTransform_native_matches_hand_computed_oracle() {
        // Independent expected values (NOT from the Kotlin fallback) so this
        // proves the .so computes the fit math — not just that two code paths
        // that both call the .so agree.
        // WIDE scene on a portrait screen must auto-rotate 90; scale is the
        // contain scale after rotation = min(1080/375, 2160/800) = 2.7.
        val wide = App7Native.focusTransform(0.10f, 0.05f, 0.80f, 0.25f, 1000f, 1500f, 1080f, 2160f)
        assertEquals(90f, wide[0], "wide scene on portrait must auto-rotate 90")
        assertEquals(2.7f, wide[1], 1e-3f, "wide 90 scale = 2.7")
        // TALL scene stays 0.
        val tall = App7Native.focusTransform(0.05f, 0.10f, 0.40f, 0.70f, 1000f, 1500f, 1080f, 2160f)
        assertEquals(0f, tall[0], "tall scene keeps rotation 0")
        // Degenerate -> identity {0,1,0,0}.
        val deg = App7Native.focusTransform(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        assertEquals(0f, deg[0], "degenerate rotation 0")
        assertEquals(1f, deg[1], "degenerate scale 1")
    }

    private val boxGrid: List<ConversationBox> = listOf(
        ConversationBox(0.1f, 0.1f, 0.4f, 0.4f, RegionType.BUBBLE),
        ConversationBox(0.0f, 0.0f, 1.0f, 1.0f, RegionType.BUBBLE),  // covers all
        ConversationBox(0.5f, 0.5f, 0.01f, 0.01f, RegionType.BUBBLE), // tiny
    )

    private val tapGrid: List<Pair<Float, Float>> = listOf(
        0.3f to 0.3f,
        0.05f to 0.3f,
        0.3f to 0.6f,
        0.1f to 0.3f,    // left edge
        0.5f to 0.5f,    // corner of first box / inside second / inside third boundary
        0.51f to 0.51f,
        0.0f to 0.0f,
        1.0f to 1.0f,
    )

    @Test
    fun boxContains_parity() {
        for (box in boxGrid) {
            for ((nx, ny) in tapGrid) {
                assertEquals(
                    box.contains(nx, ny),
                    App7Native.boxContains(nx, ny, box.x, box.y, box.width, box.height),
                    "contains mismatch for tap=($nx,$ny), box=$box"
                )
            }
        }
    }

    private val permGrid: List<Pair<IntArray, Int>> = listOf(
        intArrayOf(0, 1, 2)     to 3,
        intArrayOf(2, 0, 1)     to 3,
        intArrayOf()            to 0,
        intArrayOf(0, 1)        to 3,
        intArrayOf(0, 1, 3)     to 3,
        intArrayOf(0, 0, 1)     to 3,
        intArrayOf(0, 1, 2, 3)  to 3,
        intArrayOf(3, 2, 1, 0)  to 4,
        intArrayOf(0)           to 1,
        intArrayOf(-1)          to 1,
    )

    @Test
    fun isValidPermutation_parity() {
        for ((indices, n) in permGrid) {
            val k = PageBubbleOrdering.isValidPermutation(indices.toList(), n)
            val native = App7Native.isValidPermutation(indices, n)
            assertEquals(
                k, native,
                "isValidPermutation mismatch for indices=${indices.toList()}, n=$n"
            )
        }
    }

    @Test
    fun nativeLibraryActuallyLoaded() {
        // Smoke: confirms the class init fired and `external fun` table is
        // wired.  Catches "tests pass because Kotlin domain mirrored both
        // sides of the comparison" regressions.
        assertTrue(
            App7Native.isValidPermutation(intArrayOf(0, 1, 2), 3),
            "App7Native loaded but isValidPermutation didn't return the expected true"
        )
    }

    // ── Phase 5-extended-A: annotation pool + sampler ──────────────────

    @Test
    fun annotationPool_size_matches_kotlin_default() {
        assertEquals(
            pl.czak.learnlauncher.domain.annotation.DefaultAnnotationPool.size,
            App7Native.annotationPoolSize()
        )
    }

    @Test
    fun annotationPool_labels_and_displays_match_kotlin_default() {
        val kotlinPool = pl.czak.learnlauncher.domain.annotation.DefaultAnnotationPool
        for (i in kotlinPool.indices) {
            assertEquals(
                kotlinPool[i].label,
                App7Native.annotationPoolLabel(i),
                "pool[$i].label divergence"
            )
            assertEquals(
                kotlinPool[i].display,
                App7Native.annotationPoolDisplay(i),
                "pool[$i].display divergence"
            )
        }
    }

    @Test
    fun sampleAnnotationIndices_deterministic_per_seed() {
        val a = App7Native.sampleAnnotationIndices(42L, 3)
        val b = App7Native.sampleAnnotationIndices(42L, 3)
        assertEquals(a.toList(), b.toList(), "same seed must yield same sample")
    }

    @Test
    fun sampleAnnotationIndices_returns_distinct_in_range() {
        for (seed in listOf(0L, 1L, 42L, -1L, Long.MAX_VALUE, Long.MIN_VALUE)) {
            val n = App7Native.annotationPoolSize()
            val k = 3
            val out = App7Native.sampleAnnotationIndices(seed, k)
            assertEquals(k, out.size, "sample size = $k for seed=$seed")
            assertEquals(k, out.toSet().size, "sample distinct for seed=$seed")
            for (i in out) assertTrue(i in 0 until n, "index $i in [0,$n) for seed=$seed")
        }
    }

    @Test
    fun sampleAnnotationIndices_caps_at_pool_size() {
        val n = App7Native.annotationPoolSize()
        val out = App7Native.sampleAnnotationIndices(7L, n * 2)
        assertEquals(n, out.size, "cap at pool size")
        assertEquals(n, out.toSet().size, "full permutation has no duplicates")
        for (i in out) assertTrue(i in 0 until n)
    }

    @Test
    fun sampleAnnotationIndices_handles_zero_and_negative_k() {
        assertEquals(0, App7Native.sampleAnnotationIndices(99L, 0).size)
        assertEquals(0, App7Native.sampleAnnotationIndices(99L, -3).size)
    }

    // ── Phase 5-extended-B: NativeSessionTimeTracker ──────────────────

    @Test
    fun nativeSessionTracker_startEndApp_emits_app_session() {
        val t = pl.czak.learnlauncher.domain.session.NativeSessionTimeTracker()
        try {
            t.startAppSession()
            // Tiny sleep so endedAt > startedAt.
            Thread.sleep(2)
            val results = t.endAppSession()
            assertEquals(1, results.size, "endAppSession after startAppSession emits 1")
            val s = results[0]
            assertEquals("APP", s.sessionType)
            assertTrue(s.endedAt > s.startedAt, "endedAt > startedAt")
            assertTrue(s.durationMs >= 0, "non-negative duration")
        } finally {
            t.close()
        }
    }

    @Test
    fun nativeSessionTracker_full_hierarchy_close_order() {
        // app → chapter → page; endApp closes all three in PAGE→CHAPTER→APP order.
        val t = pl.czak.learnlauncher.domain.session.NativeSessionTimeTracker()
        try {
            t.startAppSession()
            t.startChapterSession("ch1")
            t.startPageSession("p1", "Page 1")
            val closed = t.endAppSession()
            assertEquals(3, closed.size)
            assertEquals("PAGE",    closed[0].sessionType)
            assertEquals("CHAPTER", closed[1].sessionType)
            assertEquals("APP",     closed[2].sessionType)
            assertEquals("ch1",     closed[0].chapterName)
            assertEquals("p1",      closed[0].pageId)
            assertEquals("Page 1",  closed[0].pageTitle)
            assertEquals("ch1",     closed[1].chapterName)
            // APP carries no chapter/page tags
            assertEquals(null, closed[2].chapterName)
            assertEquals(null, closed[2].pageId)
        } finally {
            t.close()
        }
    }

    @Test
    fun nativeSessionTracker_startChapter_closes_prior_page_and_chapter() {
        val t = pl.czak.learnlauncher.domain.session.NativeSessionTimeTracker()
        try {
            t.startAppSession()
            t.startChapterSession("ch1")
            t.startPageSession("p1", "Page 1")
            val closed = t.startChapterSession("ch2")
            assertEquals(2, closed.size)
            assertEquals("PAGE", closed[0].sessionType)
            assertEquals("CHAPTER", closed[1].sessionType)
            assertEquals("p1", closed[0].pageId)
            assertEquals("ch1", closed[1].chapterName)
        } finally {
            t.close()
        }
    }

    @Test
    fun nativeSessionTracker_startPage_closes_prior_page_only() {
        val t = pl.czak.learnlauncher.domain.session.NativeSessionTimeTracker()
        try {
            t.startAppSession()
            t.startPageSession("p1", "Page 1")
            val closed = t.startPageSession("p2", "Page 2")
            assertTrue(closed != null, "prior page must close")
            assertEquals("PAGE", closed!!.sessionType)
            assertEquals("p1", closed.pageId)
        } finally {
            t.close()
        }
    }

    @Test
    fun nativeSessionTracker_elapsed_returns_open_or_null() {
        val t = pl.czak.learnlauncher.domain.session.NativeSessionTimeTracker()
        try {
            val initial = t.getElapsedMs()
            assertEquals(null, initial.appMs)
            t.startAppSession()
            Thread.sleep(2)
            val midway = t.getElapsedMs()
            assertTrue(midway.appMs != null && midway.appMs!! > 0L)
            assertEquals(null, midway.chapterMs)
        } finally {
            t.close()
        }
    }

    // ── Phase 5-extended-C: NativeImageSequenceNavigator ──────────────

    @Test
    fun nativeNavigator_navigates_through_images() {
        // Zero-padded filenames so lexicographic sort preserves numeric order.
        val items = (1..10).map {
            val padded = it.toString().padStart(2, '0')
            pl.czak.learnlauncher.data.model.ImageItem(
                id = "img-$it",
                filename = "$padded.jpg",
                title = "Image $it",
                chapter = if (it <= 3) "ch1" else if (it <= 6) "ch2" else "ch3",
            )
        }
        val nav = pl.czak.learnlauncher.domain.navigation.NativeImageSequenceNavigator()
        try {
            nav.initialize(items)
            assertEquals(10, nav.size)
            assertEquals(3, nav.chapterCount)
            assertEquals(-1, nav.position)

            val first = nav.next()
            assertEquals("img-1", first?.id)
            nav.next()
            val third = nav.next()
            assertEquals("img-3", third?.id)
            assertEquals("ch1", nav.currentChapterName)

            val firstOfCh2 = nav.nextChapter()
            assertEquals("img-4", firstOfCh2?.id)
            assertEquals("ch2", nav.currentChapterName)
            assertTrue(nav.canGoForward)
            assertTrue(nav.canGoBack)

            val firstOfCh3 = nav.nextChapter()
            assertEquals("img-7", firstOfCh3?.id)
            assertEquals(null, nav.nextChapter(), "no chapter beyond last")

            assertEquals("img-1", nav.goToChapter(0)?.id)
            assertEquals(null, nav.goToChapter(99), "OOB chapter returns null")

            assertEquals("img-6", nav.restorePosition(5)?.id)
            assertEquals(null, nav.restorePosition(99))

            assertEquals("img-8", nav.goToImageId("img-8")?.id)
            assertEquals(null, nav.goToImageId("missing"))
        } finally {
            nav.close()
        }
    }

    @Test
    fun nativeNavigator_edge_can_flags() {
        val items = listOf(
            pl.czak.learnlauncher.data.model.ImageItem("a", "1.jpg", "A", chapter = "ch1"),
            pl.czak.learnlauncher.data.model.ImageItem("b", "2.jpg", "B", chapter = "ch1"),
            pl.czak.learnlauncher.data.model.ImageItem("c", "1.jpg", "C", chapter = "ch2"),
        )
        val nav = pl.czak.learnlauncher.domain.navigation.NativeImageSequenceNavigator()
        try {
            nav.initialize(items)
            assertTrue(nav.canGoForward)
            assertTrue(!nav.canGoBack)
            nav.next(); nav.next(); nav.next()  // at last
            assertTrue(!nav.canGoForward)
            assertTrue(nav.canGoBack)
            assertTrue(!nav.canGoNextChapter)
            assertTrue(nav.canGoPreviousChapter)
        } finally {
            nav.close()
        }
    }

    @Test
    fun nativeAnnotationDecisionFunction_returns_correct_shape() {
        val deterministicSeed = 12345L
        val decider = pl.czak.learnlauncher.domain.annotation.NativeAnnotationDecisionFunction(
            k = 3,
            seedProvider = { deterministicSeed }
        )
        val ctx = pl.czak.learnlauncher.domain.annotation.AnnotationContext(
            seriesPath = "s", chapterPath = "c", pageId = "p", boxIndex = 0,
            userId = null, recentLabels = emptyList(), nowEpochMs = 0L
        )
        val opts1 = decider.decide(ctx)
        val opts2 = decider.decide(ctx)
        assertEquals(3, opts1.size)
        assertEquals(opts1.map { it.label }, opts2.map { it.label }, "deterministic via fixed seed")
        val poolLabels = pl.czak.learnlauncher.domain.annotation.DefaultAnnotationPool.map { it.label }.toSet()
        for (opt in opts1) {
            assertTrue(opt.label in poolLabels, "label '${opt.label}' must come from pool")
        }
    }
}
