package pl.czak.learnlauncher.domain.native

/**
 * iOS actual stub for [App7Native].  **Out of scope for the current
 * Haskell-domain port feature folder** — iOS would need a Swift FFI
 * bridge rather than JNI, which is a separate effort.
 *
 * iOS call sites continue to execute the original pure-Kotlin domain
 * implementations in `commonMain` / `iosMain`.  Calling any of these
 * stubs throws [NotImplementedError].
 */
actual object App7Native {
    private fun nyi(): Nothing =
        throw NotImplementedError("App7Native iOS bridge is out of scope for the current Haskell-domain port")

    actual fun normalizedX     (sx: Float, sy: Float, ex: Float, ey: Float): Float = nyi()
    actual fun normalizedY     (sx: Float, sy: Float, ex: Float, ey: Float): Float = nyi()
    actual fun normalizedWidth (sx: Float, sy: Float, ex: Float, ey: Float): Float = nyi()
    actual fun normalizedHeight(sx: Float, sy: Float, ex: Float, ey: Float): Float = nyi()
    actual fun centerX         (sx: Float, sy: Float, ex: Float, ey: Float): Float = nyi()
    actual fun centerY         (sx: Float, sy: Float, ex: Float, ey: Float): Float = nyi()
    actual fun isDegenerate    (minSize: Float, sx: Float, sy: Float, ex: Float, ey: Float): Boolean = nyi()
    actual fun clamped         (sx: Float, sy: Float, ex: Float, ey: Float): FloatArray = nyi()
    actual fun focusTransform  (rx: Float, ry: Float, rw: Float, rh: Float, pw: Float, ph: Float, sw: Float, sh: Float): FloatArray = nyi()
    actual fun boxContains     (nx: Float, ny: Float, bx: Float, by: Float, bw: Float, bh: Float): Boolean = nyi()
    actual fun isValidPermutation(indices: IntArray, domainN: Int): Boolean = nyi()

    actual fun annotationPoolSize(): Int = nyi()
    actual fun annotationPoolLabel(index: Int): String = nyi()
    actual fun annotationPoolDisplay(index: Int): String = nyi()
    actual fun sampleAnnotationIndices(seed: Long, k: Int): IntArray = nyi()

    actual fun sessionTrackerNew(): Long = nyi()
    actual fun sessionTrackerFree(handle: Long): Unit = nyi()
    actual fun sessionTrackerStartApp(handle: Long, nowMs: Long): Unit = nyi()
    actual fun sessionTrackerEndApp(handle: Long, nowMs: Long, out: LongArray): Int = nyi()
    actual fun sessionTrackerStartChapter(handle: Long, nowMs: Long, out: LongArray): Int = nyi()
    actual fun sessionTrackerStartPage(handle: Long, nowMs: Long, out: LongArray): Int = nyi()
    actual fun sessionTrackerElapsed(handle: Long, nowMs: Long, out: LongArray): Unit = nyi()

    actual fun navNew(): Long = nyi()
    actual fun navFree(handle: Long): Unit = nyi()
    actual fun navInitialize(handle: Long, totalSize: Int, chapterStarts: IntArray): Unit = nyi()
    actual fun navSize(handle: Long): Int = nyi()
    actual fun navPosition(handle: Long): Int = nyi()
    actual fun navChapterCount(handle: Long): Int = nyi()
    actual fun navCurrentChapterIndex(handle: Long): Int = nyi()
    actual fun navNext(handle: Long): Int = nyi()
    actual fun navPrevious(handle: Long): Int = nyi()
    actual fun navNextChapter(handle: Long): Int = nyi()
    actual fun navPreviousChapter(handle: Long): Int = nyi()
    actual fun navGoToChapter(handle: Long, chapterIdx: Int): Int = nyi()
    actual fun navRestorePosition(handle: Long, savedIdx: Int): Int = nyi()
    actual fun navCanGoForward(handle: Long): Boolean = nyi()
    actual fun navCanGoBack(handle: Long): Boolean = nyi()
    actual fun navCanGoNextChapter(handle: Long): Boolean = nyi()
    actual fun navCanGoPreviousChapter(handle: Long): Boolean = nyi()
}
