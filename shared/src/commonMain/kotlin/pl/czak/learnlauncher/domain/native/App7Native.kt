package pl.czak.learnlauncher.domain.native

/**
 * Native domain API — the Kotlin façade over the C projection in
 * `domain-core/`.  Each function delegates to the compiled
 * `libapp7_domain.so` (loaded once at the platform actual's init).
 *
 * **Source of truth:** `domain-haskell/App7Domain.hs`.  Adding a function
 * here = adding it to App7Domain.hs (and Test.hs), `domain-core/`, the
 * JNI shim, and ParityTest.  See dev-folder EXECUTION.md "Cascade
 * discipline" — single-layer changes are a smell.
 *
 * Phase 3a scope: CropSelection predicates + ConversationBox.contains +
 * isValidPermutation.  Decision functions and stateful trackers
 * (SessionTimeTracker, ImageSequenceNavigator) land in Phase 3b.
 */
expect object App7Native {
    fun normalizedX     (sx: Float, sy: Float, ex: Float, ey: Float): Float
    fun normalizedY     (sx: Float, sy: Float, ex: Float, ey: Float): Float
    fun normalizedWidth (sx: Float, sy: Float, ex: Float, ey: Float): Float
    fun normalizedHeight(sx: Float, sy: Float, ex: Float, ey: Float): Float
    fun centerX         (sx: Float, sy: Float, ex: Float, ey: Float): Float
    fun centerY         (sx: Float, sy: Float, ex: Float, ey: Float): Float

    fun isDegenerate    (minSize: Float, sx: Float, sy: Float, ex: Float, ey: Float): Boolean

    /** Returns a 4-element FloatArray: [clampedSx, clampedSy, clampedEx, clampedEy]. */
    fun clamped         (sx: Float, sy: Float, ex: Float, ey: Float): FloatArray

    /**
     * Scene-focus fit transform.  Args: scene rect page-fractions
     * (rx,ry,rw,rh), page px (pw,ph), screen px (sw,sh).  Returns a
     * 4-element FloatArray: [rotationDeg(0|90), scale, translateX, translateY].
     */
    fun focusTransform  (rx: Float, ry: Float, rw: Float, rh: Float,
                         pw: Float, ph: Float, sw: Float, sh: Float): FloatArray

    fun boxContains     (nx: Float, ny: Float, bx: Float, by: Float, bw: Float, bh: Float): Boolean

    fun isValidPermutation(indices: IntArray, domainN: Int): Boolean

    // ── Annotation pool + sampler (Phase 3b-A) ────────────────────────
    fun annotationPoolSize(): Int
    fun annotationPoolLabel(index: Int): String
    fun annotationPoolDisplay(index: Int): String
    /** Up to k distinct pool indices; deterministic per seed. */
    fun sampleAnnotationIndices(seed: Long, k: Int): IntArray

    // ── SessionTimeTracker (Phase 3b-B) — opaque handle ────────────────
    /** Returns native handle (pointer-as-Long).  Caller must free via [sessionTrackerFree]. */
    fun sessionTrackerNew(): Long
    fun sessionTrackerFree(handle: Long)
    fun sessionTrackerStartApp(handle: Long, nowMs: Long)
    /** Out array must be LongArray(9).  Returns count of closed sessions (0..3). */
    fun sessionTrackerEndApp(handle: Long, nowMs: Long, out: LongArray): Int
    /** Out array must be LongArray(6).  Returns count (0..2). */
    fun sessionTrackerStartChapter(handle: Long, nowMs: Long, out: LongArray): Int
    /** Out array must be LongArray(3).  Returns count (0..1). */
    fun sessionTrackerStartPage(handle: Long, nowMs: Long, out: LongArray): Int
    /** Out array must be LongArray(3) for (app_ms, chapter_ms, page_ms); -1 = not open. */
    fun sessionTrackerElapsed(handle: Long, nowMs: Long, out: LongArray)

    // ── ImageSequenceNavigator (Phase 3b-C) — opaque handle ────────────
    fun navNew(): Long
    fun navFree(handle: Long)
    fun navInitialize(handle: Long, totalSize: Int, chapterStarts: IntArray)
    fun navSize(handle: Long): Int
    fun navPosition(handle: Long): Int
    fun navChapterCount(handle: Long): Int
    fun navCurrentChapterIndex(handle: Long): Int
    /** Returns new index, or -1 if move rejected. */
    fun navNext(handle: Long): Int
    fun navPrevious(handle: Long): Int
    fun navNextChapter(handle: Long): Int
    fun navPreviousChapter(handle: Long): Int
    fun navGoToChapter(handle: Long, chapterIdx: Int): Int
    fun navRestorePosition(handle: Long, savedIdx: Int): Int
    fun navCanGoForward(handle: Long): Boolean
    fun navCanGoBack(handle: Long): Boolean
    fun navCanGoNextChapter(handle: Long): Boolean
    fun navCanGoPreviousChapter(handle: Long): Boolean
}
