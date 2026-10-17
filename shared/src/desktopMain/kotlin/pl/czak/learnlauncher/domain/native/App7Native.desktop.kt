package pl.czak.learnlauncher.domain.native

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Desktop JVM actual for [App7Native].
 *
 * The `.so` is bundled in the jar at
 *   META-INF/native/<os>-<arch>/libapp7_domain.so
 * (built by the `buildHostNativeLib` Gradle task in
 * `desktopApp/build.gradle.kts`).  At first use we:
 *   1. Detect OS+arch from System properties.
 *   2. Extract the matching resource to
 *      ${java.io.tmpdir}/app7-domain-<sha256-of-content>/libapp7_domain.so
 *   3. Call System.load(absolute path).
 *
 * The SHA-segmented temp path is intentional: every build of the .so
 * extracts to a content-addressed directory, so an old extraction left
 * behind by a previous run can't shadow a newer binary.  Re-extraction
 * is skipped if the target file already exists with matching size.
 */
actual object App7Native {

    init {
        load()
    }

    private fun load() {
        val (os, arch) = detectPlatform()
        val resourcePath = "/META-INF/native/$os-$arch/libapp7_domain.so"
        val stream = App7Native::class.java.getResourceAsStream(resourcePath)
            ?: error("libapp7_domain.so not bundled for $os-$arch at $resourcePath — " +
                    "check desktopApp `buildHostNativeLib` task output")

        // Read once, hash, then either reuse or extract to the hashed path.
        val bytes = stream.use { it.readBytes() }
        val sha = sha256Short(bytes)
        val tmp = File(System.getProperty("java.io.tmpdir"), "app7-domain-$sha")
        val target = File(tmp, "libapp7_domain.so")

        if (!target.exists() || target.length() != bytes.size.toLong()) {
            tmp.mkdirs()
            FileOutputStream(target).use { it.write(bytes) }
        }
        System.load(target.absolutePath)
    }

    private fun detectPlatform(): Pair<String, String> {
        val osRaw = System.getProperty("os.name", "").lowercase()
        val os = when {
            osRaw.contains("linux") -> "linux"
            osRaw.contains("mac") || osRaw.contains("darwin") -> "macos"
            osRaw.contains("win") -> "windows"
            else -> error("Unsupported OS for App7Native: $osRaw")
        }
        val archRaw = System.getProperty("os.arch", "").lowercase()
        val arch = when {
            archRaw == "x86_64" || archRaw == "amd64" -> "x86_64"
            archRaw == "aarch64" || archRaw == "arm64" -> "arm64"
            else -> error("Unsupported arch for App7Native: $archRaw")
        }
        return os to arch
    }

    private fun sha256Short(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString {
            for (i in 0 until 8) {  // first 16 hex chars — collision-resistant for our use
                val b = digest[i].toInt() and 0xff
                append("0123456789abcdef"[b ushr 4])
                append("0123456789abcdef"[b and 0xf])
            }
        }
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
