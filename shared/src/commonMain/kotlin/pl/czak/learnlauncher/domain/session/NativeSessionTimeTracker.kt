package pl.czak.learnlauncher.domain.session

import pl.czak.learnlauncher.currentTimeMillis
import pl.czak.learnlauncher.domain.native.App7Native

/**
 * [SessionTimeTracker]-API-compatible implementation backed by the
 * compiled domain binary (`libapp7_domain.so` via [App7Native]).
 *
 * The C side stores the three timestamps (app / chapter / page starts)
 * inside an opaque struct that this object owns via [handle].  Strings
 * (chapter name, page id/title) stay in Kotlin — they're carried over
 * to the [CompletedSession] objects this method assembles from the
 * (type, started_at, ended_at) triplets the C side emits.
 *
 * Resource management: the caller MUST invoke [close] when done (or
 * accept the leak — the OS reclaims the malloc on process exit).
 * Android's `MainActivity.onDestroy` is the natural close point; desktop
 * relies on `application { exitApplication() }`.
 */
class NativeSessionTimeTracker {

    private val handle: Long = App7Native.sessionTrackerNew()
    private var chapterName: String? = null
    private var pageId: String? = null
    private var pageTitle: String? = null
    private var closed = false

    fun startAppSession() {
        App7Native.sessionTrackerStartApp(handle, currentTimeMillis())
    }

    fun endAppSession(): List<CompletedSession> {
        val now = currentTimeMillis()
        val out = LongArray(9)
        val count = App7Native.sessionTrackerEndApp(handle, now, out)
        val results = ArrayList<CompletedSession>(count)
        for (i in 0 until count) {
            results.add(buildSession(out, i * 3))
        }
        chapterName = null; pageId = null; pageTitle = null
        return results
    }

    fun startChapterSession(name: String): List<CompletedSession> {
        val now = currentTimeMillis()
        val out = LongArray(6)
        val count = App7Native.sessionTrackerStartChapter(handle, now, out)
        val results = ArrayList<CompletedSession>(count)
        for (i in 0 until count) {
            results.add(buildSession(out, i * 3))
        }
        chapterName = name; pageId = null; pageTitle = null
        return results
    }

    fun startPageSession(id: String, title: String): CompletedSession? {
        val now = currentTimeMillis()
        val out = LongArray(3)
        val count = App7Native.sessionTrackerStartPage(handle, now, out)
        val result = if (count == 1) buildSession(out, 0) else null
        pageId = id; pageTitle = title
        return result
    }

    fun getElapsedMs(): SessionElapsed {
        val now = currentTimeMillis()
        val out = LongArray(3)
        App7Native.sessionTrackerElapsed(handle, now, out)
        return SessionElapsed(
            appMs     = if (out[0] >= 0) out[0] else null,
            chapterMs = if (out[1] >= 0) out[1] else null,
            pageMs    = if (out[2] >= 0) out[2] else null,
        )
    }

    fun close() {
        if (!closed) {
            App7Native.sessionTrackerFree(handle)
            closed = true
        }
    }

    /** Pull a (type, started_at, ended_at) triplet from `out[offset .. +2]`
     *  and pair it with the strings stored on this side. */
    private fun buildSession(out: LongArray, offset: Int): CompletedSession {
        val typeCode = out[offset].toInt()
        val startedAt = out[offset + 1]
        val endedAt   = out[offset + 2]
        // Type codes: 0=PAGE, 1=CHAPTER, 2=APP — matches C session_tracker.c.
        val (typeName, chapterStr, pageIdStr, pageTitleStr) = when (typeCode) {
            0    -> Quad("PAGE",    chapterName, pageId, pageTitle)
            1    -> Quad("CHAPTER", chapterName, null,    null)
            2    -> Quad("APP",     null,        null,    null)
            else -> Quad("UNKNOWN", null,        null,    null)
        }
        return CompletedSession(
            sessionType = typeName,
            startedAt = startedAt,
            endedAt = endedAt,
            durationMs = endedAt - startedAt,
            chapterName = chapterStr,
            pageId = pageIdStr,
            pageTitle = pageTitleStr,
        )
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
