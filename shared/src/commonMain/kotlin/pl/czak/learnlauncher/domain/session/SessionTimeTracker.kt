package pl.czak.learnlauncher.domain.session

import pl.czak.learnlauncher.currentTimeMillis

data class CompletedSession(
    val sessionType: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val chapterName: String? = null,
    val pageId: String? = null,
    val pageTitle: String? = null
)

data class SessionElapsed(
    val appMs: Long?,
    val chapterMs: Long?,
    val pageMs: Long?
)

/**
 * Phase 3b migration: this class is now a thin delegating wrapper over
 * [NativeSessionTimeTracker] (which routes through `App7Native` →
 * `libapp7_domain.so`).  Pure-Kotlin transition logic remains for
 * platforms that have NO native loader — concretely iOS — see the
 * `useNative` branch.  On Android + Desktop, every event flows through
 * the compiled binary, satisfying the `feedback_haskell_domain_done_gate`
 * mandate.
 *
 * **Resource management**: native paths allocate a C-side struct.  The
 * caller MUST invoke [close] when finished; missing it leaks ~24 bytes
 * per Activity lifetime (acceptable on Android since the process is
 * eventually reaped, but call it from `onDestroy` to be explicit).
 */
class SessionTimeTracker {

    // Lazy: only allocate the native handle when first event fires, so
    // unit tests that don't touch sessions don't load the .so.
    //
    // **HARD-FAIL POLICY** (feedback_haskell_domain_done_gate): on JVM
    // targets (Android, Desktop) the native path is the ONLY path —
    // a .so load failure (UnsatisfiedLinkError, SecurityException, OOM)
    // propagates out of this lazy and crashes the app at first session
    // event.  We catch ONLY NotImplementedError, which is what the iOS
    // `App7Native` stub throws — that's the deliberate fallback to
    // pure-Kotlin transition logic on the platform where the .so is
    // out of scope.  Catching `Throwable` here would let a corrupted
    // APK silently route unverified data to Turso.
    private val native: NativeSessionTimeTracker? by lazy(LazyThreadSafetyMode.NONE) {
        try { NativeSessionTimeTracker() } catch (_: NotImplementedError) { null }
    }
    // Fallback pure-Kotlin state — used only when [native] is null (iOS).
    private var appStartTime: Long? = null
    private var chapterStartTime: Long? = null
    private var chapterName: String? = null
    private var pageStartTime: Long? = null
    private var pageId: String? = null
    private var pageTitle: String? = null

    fun close() { native?.close() }

    fun startAppSession() {
        val n = native
        if (n != null) { n.startAppSession(); return }
        appStartTime = currentTimeMillis()
    }

    fun endAppSession(): List<CompletedSession> {
        val n = native
        if (n != null) return n.endAppSession()
        val now = currentTimeMillis()
        val results = mutableListOf<CompletedSession>()
        endPageInternal(now)?.let { results.add(it) }
        endChapterInternal(now)?.let { results.add(it) }
        val appStart = appStartTime
        if (appStart != null) {
            results.add(
                CompletedSession(
                    sessionType = "APP",
                    startedAt = appStart,
                    endedAt = now,
                    durationMs = now - appStart
                )
            )
        }
        appStartTime = null
        return results
    }

    fun startChapterSession(name: String): List<CompletedSession> {
        val n = native
        if (n != null) return n.startChapterSession(name)
        val now = currentTimeMillis()
        val results = mutableListOf<CompletedSession>()
        endPageInternal(now)?.let { results.add(it) }
        endChapterInternal(now)?.let { results.add(it) }
        chapterStartTime = now
        chapterName = name
        return results
    }

    fun startPageSession(id: String, title: String): CompletedSession? {
        val n = native
        if (n != null) return n.startPageSession(id, title)
        val now = currentTimeMillis()
        val ended = endPageInternal(now)
        pageStartTime = now
        pageId = id
        pageTitle = title
        return ended
    }

    private fun endPageInternal(now: Long): CompletedSession? {
        val start = pageStartTime ?: return null
        val result = CompletedSession(
            sessionType = "PAGE",
            startedAt = start,
            endedAt = now,
            durationMs = now - start,
            chapterName = chapterName,
            pageId = pageId,
            pageTitle = pageTitle
        )
        pageStartTime = null
        pageId = null
        pageTitle = null
        return result
    }

    private fun endChapterInternal(now: Long): CompletedSession? {
        val start = chapterStartTime ?: return null
        val result = CompletedSession(
            sessionType = "CHAPTER",
            startedAt = start,
            endedAt = now,
            durationMs = now - start,
            chapterName = chapterName
        )
        chapterStartTime = null
        chapterName = null
        return result
    }

    fun getElapsedMs(): SessionElapsed {
        val n = native
        if (n != null) return n.getElapsedMs()
        val now = currentTimeMillis()
        return SessionElapsed(
            appMs = appStartTime?.let { now - it },
            chapterMs = chapterStartTime?.let { now - it },
            pageMs = pageStartTime?.let { now - it }
        )
    }
}
