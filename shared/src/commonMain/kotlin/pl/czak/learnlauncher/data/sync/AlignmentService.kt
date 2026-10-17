package pl.czak.learnlauncher.data.sync

import pl.czak.learnlauncher.data.sync.adapter.turso.TursoDownloaderAdapter
import pl.czak.learnlauncher.data.sync.model.RemoteLearnerSnapshot

/**
 * Per-table outcome of merging a [RemoteLearnerSnapshot] into the local store.
 * `added` is the number of new rows accepted. Implementations may keep this
 * additive-only (mirroring the existing `upsertScenesFromRemote` dedup
 * pattern) or extend later to track `skippedDup` / `updated` separately.
 */
data class MergeStats(val added: Int = 0, val skippedDup: Int = 0)

data class AlignmentResult(
    val userId: String,
    val perTable: Map<String, MergeStats>,
    val totalFetched: Int,
    val message: String,
) {
    val totalAdded: Int get() = perTable.values.sumOf { it.added }
}

/**
 * Platform-specific writer that merges a pulled snapshot into the local
 * store. Desktop wires this to the in-memory repositories; Android to Room.
 */
interface SnapshotMerger {
    suspend fun merge(snapshot: RemoteLearnerSnapshot): Map<String, MergeStats>
}

/**
 * Persisted per-table pull cursors for the large append-only tables
 * (see feature spec `app7-ideas-for-later/incremental-alignment-pull.md`).
 * The cursor is the highest remote `rowid` whose row has been MERGED locally
 * — never advance it for data that wasn't written (a failed merge throws
 * before the cursor moves, so the rows are re-pulled next tick and deduped).
 */
interface AlignCursorStore {
    fun getCursor(table: String): Long
    fun setCursor(table: String, rowid: Long)

    /**
     * Small-table change watermark (F16): the last remote max-rowid (or
     * max-updated_at) this device fully pulled and merged. Defaults are no-ops
     * so an implementation that doesn't persist watermarks degrades to
     * "always pull" — it can never wrongly skip a changed table.
     */
    fun getWatermark(table: String): String? = null
    fun setWatermark(table: String, watermark: String) {}
}

/** [AlignCursorStore] backed by the platform [SettingsStore] (`align_cursor_<table>`). */
class SettingsAlignCursorStore(
    private val settingsStore: pl.czak.learnlauncher.viewmodel.SettingsStore,
) : AlignCursorStore {
    override fun getCursor(table: String): Long =
        settingsStore.getString("align_cursor_$table", null)?.toLongOrNull() ?: 0L

    override fun setCursor(table: String, rowid: Long) {
        settingsStore.putString("align_cursor_$table", rowid.toString())
    }

    override fun getWatermark(table: String): String? =
        settingsStore.getString("align_wm_$table", null)

    override fun setWatermark(table: String, watermark: String) {
        settingsStore.putString("align_wm_$table", watermark)
    }
}

/**
 * Cross-device alignment pull (see feature specs
 * `app7-ideas-for-later/cross-device-learner-records-alignment-pull.md` and
 * `app7-ideas-for-later/incremental-alignment-pull.md`).
 *
 * F16 read-cost design (see `app7-docs/F16-sync-read-optimization-SPEC.md`):
 * each tick first probes ONE cheap watermark query (~1 index-seek row per
 * small table); the six full small-table pulls run only when a watermark
 * moved. The two large append-only telemetry tables (`session_events`,
 * `page_interactions`) are NOT pulled by the tick at all — the app has no
 * on-device consumer of that history, and re-backfilling ~105k rows on every
 * fresh install was the bulk of prod's observed read volume. Their cursored
 * backfill machinery survives as [pullTelemetryBackfill] for a future
 * feature that genuinely needs the history (call it on demand, never in a
 * loop).
 */
class AlignmentService(
    private val downloader: TursoDownloaderAdapter,
    private val userId: () -> String?,
    private val merger: SnapshotMerger,
    private val cursors: AlignCursorStore,
) {
    suspend fun pullAlignment(): AlignmentResult {
        val uid = userId()
            ?: return AlignmentResult(
                userId = "",
                perTable = emptyMap(),
                totalFetched = 0,
                message = "Not logged in",
            )

        val perTable = linkedMapOf<String, MergeStats>()
        var fetched = 0

        // Watermark probe: one round trip, ~1 index-seek row per table. A null
        // probe (transport/shape failure) degrades to the unconditional pull —
        // the probe may only ever SAVE work, never skip a changed table.
        val remote = downloader.fetchSmallTableWatermarks(uid)
        val changed = remote == null ||
            TursoDownloaderAdapter.SMALL_TABLES.any { cursors.getWatermark(it) != remote[it] }

        if (changed) {
            // Small tables: full pull + dedup merge, unchanged semantics.
            val snapshot = downloader.fetchAllForUser(uid)
            fetched += snapshot.totalRows()
            merger.merge(snapshot).forEach { (table, stats) -> perTable.merge(table, stats, ::combine) }
            // Merge succeeded (it throws otherwise) — safe to remember the watermarks.
            remote?.forEach { (table, wm) -> cursors.setWatermark(table, wm) }
        }

        val added = perTable.values.sumOf { it.added }
        val note = if (changed) "" else "; watermarks unchanged — pull skipped"
        return AlignmentResult(
            userId = uid,
            perTable = perTable,
            totalFetched = fetched,
            message = "synced $added new rows across ${perTable.size} tables (fetched $fetched)$note",
        )
    }

    /**
     * On-demand cursored backfill of the two large append-only telemetry
     * tables. F16 removed this from the per-tick [pullAlignment]; call it
     * only from a feature that actually consumes the history (stats, export)
     * — never from a polling loop.
     */
    suspend fun pullTelemetryBackfill(): AlignmentResult {
        val uid = userId()
            ?: return AlignmentResult(
                userId = "",
                perTable = emptyMap(),
                totalFetched = 0,
                message = "Not logged in",
            )

        val perTable = linkedMapOf<String, MergeStats>()
        var fetched = 0

        // Big append-only tables: cursored batches, merge-then-advance.
        fetched += pullBatched(TABLE_SESSION_EVENTS, perTable) { after ->
            val batch = downloader.fetchSessionEventsAfter(uid, after)
            BatchPage(batch.rows.size, batch.maxRowid) {
                merger.merge(RemoteLearnerSnapshot(userId = uid, sessionEvents = batch.rows))
            }
        }
        fetched += pullBatched(TABLE_PAGE_INTERACTIONS, perTable) { after ->
            val batch = downloader.fetchPageInteractionsAfter(uid, after)
            BatchPage(batch.rows.size, batch.maxRowid) {
                merger.merge(RemoteLearnerSnapshot(userId = uid, pageInteractions = batch.rows))
            }
        }

        val added = perTable.values.sumOf { it.added }
        return AlignmentResult(
            userId = uid,
            perTable = perTable,
            totalFetched = fetched,
            message = "backfilled $added new telemetry rows (fetched $fetched)",
        )
    }

    private class BatchPage(
        val rowCount: Int,
        val maxRowid: Long,
        val mergeBatch: suspend () -> Map<String, MergeStats>,
    )

    private suspend fun pullBatched(
        table: String,
        perTable: MutableMap<String, MergeStats>,
        fetchPage: suspend (afterRowid: Long) -> BatchPage,
    ): Int {
        var cursor = cursors.getCursor(table)
        var pulled = 0
        while (pulled < MAX_ROWS_PER_TICK_PER_TABLE) {
            val page = fetchPage(cursor)
            if (page.rowCount == 0) break
            page.mergeBatch().forEach { (t, stats) -> perTable.merge(t, stats, ::combine) }
            // Merge succeeded (it throws otherwise) — safe to advance the cursor.
            cursor = page.maxRowid
            cursors.setCursor(table, cursor)
            pulled += page.rowCount
            if (page.rowCount < TursoDownloaderAdapter.INCR_BATCH_LIMIT) break // caught up
        }
        return pulled
    }

    private fun combine(a: MergeStats, b: MergeStats) =
        MergeStats(added = a.added + b.added, skippedDup = a.skippedDup + b.skippedDup)

    companion object {
        const val TABLE_SESSION_EVENTS = "session_events"
        const val TABLE_PAGE_INTERACTIONS = "page_interactions"

        /** Backfill budget per tick per table; 54k historical rows ≈ 6 ticks. */
        const val MAX_ROWS_PER_TICK_PER_TABLE = 10_000
    }
}
