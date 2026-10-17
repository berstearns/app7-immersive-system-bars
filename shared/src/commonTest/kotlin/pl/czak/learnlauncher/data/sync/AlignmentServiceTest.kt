package pl.czak.learnlauncher.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import pl.czak.learnlauncher.data.sync.model.RemoteChatMessageRecord
import pl.czak.learnlauncher.data.sync.model.RemoteLearnerSnapshot
import pl.czak.learnlauncher.data.sync.model.RemoteSessionEventRecord

/**
 * Common-test coverage for the snapshot shape used by the cross-device
 * alignment pull (see feature spec
 * `app7-ideas-for-later/cross-device-learner-records-alignment-pull.md`).
 * The Turso transport is covered by integration tests on the desktop
 * target; here we only check the wiring shape — that [RemoteLearnerSnapshot]
 * aggregates row counts correctly and that an empty snapshot is
 * semantically distinct from a never-fetched one.
 */
class AlignmentServiceTest {

    @Test
    fun emptySnapshotHasZeroRows() {
        val s = RemoteLearnerSnapshot(userId = "u-1")
        assertEquals(0, s.totalRows())
    }

    @Test
    fun totalRowsSumsAcrossLists() {
        val s = RemoteLearnerSnapshot(
            userId = "u-1",
            sessionEvents = listOf(
                sessionEvent(localId = 1),
                sessionEvent(localId = 2),
                sessionEvent(localId = 3),
            ),
            chatMessages = listOf(chatMessage(localId = 1)),
        )
        assertEquals(4, s.totalRows())
    }

    @Test
    fun mergeStatsAddedAggregatesViaAlignmentResult() {
        val perTable = linkedMapOf(
            "session_events" to MergeStats(added = 3),
            "annotation_records" to MergeStats(added = 1),
            "chat_messages" to MergeStats(added = 0),
            "page_interactions" to MergeStats(added = 5),
        )
        val result = AlignmentResult(
            userId = "u-1",
            perTable = perTable,
            totalFetched = 9,
            message = "synced 9 new rows across 4 tables (fetched 9)",
        )
        assertEquals(9, result.totalAdded)
        assertEquals(4, result.perTable.size)
        assertEquals(3, result.perTable["session_events"]?.added)
        assertEquals(0, result.perTable["chat_messages"]?.added)
        assertTrue(result.message.contains("synced 9 new rows"))
    }

    private fun sessionEvent(localId: Long) = RemoteSessionEventRecord(
        deviceId = "test",
        localId = localId,
        eventType = "PAGE_ENTER",
        timestamp = localId * 1000,
        durationMs = null,
        comicId = "_no_comic_",
        chapterName = null,
        pageId = null,
        pageTitle = null,
    )

    private fun chatMessage(localId: Long) = RemoteChatMessageRecord(
        deviceId = "test",
        localId = localId,
        sender = "user",
        text = "hi",
        timestamp = localId * 1000,
    )

    // ── incremental-pull cursor store (incremental-alignment-pull.md) ──

    @Test
    fun cursorDefaultsToZeroWhenNeverSet() {
        val store = SettingsAlignCursorStore(FakeSettingsStore())
        assertEquals(0L, store.getCursor("session_events"))
    }

    @Test
    fun cursorRoundTripsPerTable() {
        val store = SettingsAlignCursorStore(FakeSettingsStore())
        store.setCursor("session_events", 53_887L)
        store.setCursor("page_interactions", 35_774L)
        assertEquals(53_887L, store.getCursor("session_events"))
        assertEquals(35_774L, store.getCursor("page_interactions"))
    }

    @Test
    fun corruptCursorValueFallsBackToZero() {
        val settings = FakeSettingsStore()
        settings.putString("align_cursor_session_events", "not-a-number")
        assertEquals(0L, SettingsAlignCursorStore(settings).getCursor("session_events"))
    }

    // ── F16 small-table watermarks (F16-sync-read-optimization-SPEC.md) ──

    @Test
    fun watermarkDefaultsToNullWhenNeverStored() {
        val store = SettingsAlignCursorStore(FakeSettingsStore())
        assertEquals(null, store.getWatermark("annotation_records"))
    }

    @Test
    fun watermarkRoundTripsPerTable() {
        val store = SettingsAlignCursorStore(FakeSettingsStore())
        store.setWatermark("annotation_records", "272")
        store.setWatermark("page_bubble_orderings", "1754400000000")
        assertEquals("272", store.getWatermark("annotation_records"))
        assertEquals("1754400000000", store.getWatermark("page_bubble_orderings"))
    }

    @Test
    fun watermarksAreIndependentOfCursorsForSameTable() {
        val store = SettingsAlignCursorStore(FakeSettingsStore())
        store.setCursor("session_events", 42L)
        assertEquals(null, store.getWatermark("session_events"))
        store.setWatermark("session_events", "99")
        assertEquals(42L, store.getCursor("session_events"))
    }

    @Test
    fun defaultWatermarkImplNeverSkips() {
        // An AlignCursorStore that doesn't override the watermark methods must
        // read back null even after a set — pullAlignment then always pulls.
        val bare = object : AlignCursorStore {
            override fun getCursor(table: String): Long = 0L
            override fun setCursor(table: String, rowid: Long) {}
        }
        bare.setWatermark("annotation_records", "7")
        assertEquals(null, bare.getWatermark("annotation_records"))
    }

    private class FakeSettingsStore : pl.czak.learnlauncher.viewmodel.SettingsStore {
        private val strings = mutableMapOf<String, String?>()
        private val ints = mutableMapOf<String, Int>()
        private val booleans = mutableMapOf<String, Boolean>()
        override fun getInt(key: String, default: Int): Int = ints[key] ?: default
        override fun putInt(key: String, value: Int) { ints[key] = value }
        override fun getBoolean(key: String, default: Boolean): Boolean = booleans[key] ?: default
        override fun putBoolean(key: String, value: Boolean) { booleans[key] = value }
        override fun getString(key: String, default: String?): String? = strings[key] ?: default
        override fun putString(key: String, value: String?) { strings[key] = value }
    }
}
