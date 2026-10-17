package pl.czak.learnlauncher.domain.native

/**
 * Android actual for [App7Native].  All implementations delegate via JNI
 * to `libapp7_domain.so` (built from `androidApp/src/main/cpp/` and
 * `domain-core/` — see the AGP `externalNativeBuild` block in
 * `androidApp/build.gradle.kts`).
 *
 * JNI symbol prefix: `Java_pl_czak_learnlauncher_domain_native_App7Native_<methodName>`.
 * Matching shims live in `androidApp/src/main/cpp/app7_domain_jni.c`.
 */
actual object App7Native {
    init {
        // Loaded once per class-loader.  Android's namespacing puts the
        // .so at /data/app/.../lib/<abi>/libapp7_domain.so; loadLibrary
        // resolves it via the package's library search path.
        System.loadLibrary("app7_domain")
    }

    actual external fun normalizedX     (sx: Float, sy: Float, ex: Float, ey: Float): Float
    actual external fun normalizedY     (sx: Float, sy: Float, ex: Float, ey: Float): Float
    actual external fun normalizedWidth (sx: Float, sy: Float, ex: Float, ey: Float): Float
    actual external fun normalizedHeight(sx: Float, sy: Float, ex: Float, ey: Float): Float
    actual external fun centerX         (sx: Float, sy: Float, ex: Float, ey: Float): Float
    actual external fun centerY         (sx: Float, sy: Float, ex: Float, ey: Float): Float
    actual external fun isDegenerate    (minSize: Float, sx: Float, sy: Float, ex: Float, ey: Float): Boolean
    actual external fun clamped         (sx: Float, sy: Float, ex: Float, ey: Float): FloatArray
    actual external fun focusTransform  (rx: Float, ry: Float, rw: Float, rh: Float, pw: Float, ph: Float, sw: Float, sh: Float): FloatArray
    actual external fun boxContains     (nx: Float, ny: Float, bx: Float, by: Float, bw: Float, bh: Float): Boolean
    actual external fun isValidPermutation(indices: IntArray, domainN: Int): Boolean

    actual external fun annotationPoolSize(): Int
    actual external fun annotationPoolLabel(index: Int): String
    actual external fun annotationPoolDisplay(index: Int): String
    actual external fun sampleAnnotationIndices(seed: Long, k: Int): IntArray

    actual external fun sessionTrackerNew(): Long
    actual external fun sessionTrackerFree(handle: Long)
    actual external fun sessionTrackerStartApp(handle: Long, nowMs: Long)
    actual external fun sessionTrackerEndApp(handle: Long, nowMs: Long, out: LongArray): Int
    actual external fun sessionTrackerStartChapter(handle: Long, nowMs: Long, out: LongArray): Int
    actual external fun sessionTrackerStartPage(handle: Long, nowMs: Long, out: LongArray): Int
    actual external fun sessionTrackerElapsed(handle: Long, nowMs: Long, out: LongArray)

    actual external fun navNew(): Long
    actual external fun navFree(handle: Long)
    actual external fun navInitialize(handle: Long, totalSize: Int, chapterStarts: IntArray)
    actual external fun navSize(handle: Long): Int
    actual external fun navPosition(handle: Long): Int
    actual external fun navChapterCount(handle: Long): Int
    actual external fun navCurrentChapterIndex(handle: Long): Int
    actual external fun navNext(handle: Long): Int
    actual external fun navPrevious(handle: Long): Int
    actual external fun navNextChapter(handle: Long): Int
    actual external fun navPreviousChapter(handle: Long): Int
    actual external fun navGoToChapter(handle: Long, chapterIdx: Int): Int
    actual external fun navRestorePosition(handle: Long, savedIdx: Int): Int
    actual external fun navCanGoForward(handle: Long): Boolean
    actual external fun navCanGoBack(handle: Long): Boolean
    actual external fun navCanGoNextChapter(handle: Long): Boolean
    actual external fun navCanGoPreviousChapter(handle: Long): Boolean
}
