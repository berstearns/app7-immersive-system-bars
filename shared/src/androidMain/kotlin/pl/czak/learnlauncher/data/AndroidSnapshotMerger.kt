package pl.czak.learnlauncher.data

import pl.czak.learnlauncher.data.db.AppDatabase
import pl.czak.learnlauncher.data.db.entity.AnnotationRecordEntity
import pl.czak.learnlauncher.data.db.entity.AppLaunchRecordEntity
import pl.czak.learnlauncher.data.db.entity.ChatMessageEntity
import pl.czak.learnlauncher.data.db.entity.NO_COMIC_SENTINEL
import pl.czak.learnlauncher.data.db.entity.PageInteractionEntity
import pl.czak.learnlauncher.data.db.entity.SessionEventEntity
import pl.czak.learnlauncher.data.db.entity.SettingsChangeEntity
import pl.czak.learnlauncher.data.sync.MergeStats
import pl.czak.learnlauncher.data.sync.SnapshotMerger
import pl.czak.learnlauncher.data.sync.model.RemoteLearnerSnapshot

/**
 * Cross-device alignment pull merger (Android / Room).
 *
 * Each per-table call is additive with content-based dedup, mirroring the
 * desktop in-memory pattern. Inserted rows are flagged `synced = true` so
 * the existing push pipeline does not echo them back up to Turso and
 * create a self-amplifying loop.
 *
 * The Room schema does not carry `device_id` / source `local_id`, so the
 * dedup keys are content-shaped (timestamp + identifying columns) rather
 * than `(device_id, local_id)`. This is good enough for v1: timestamps
 * are millisecond-precision producer-side, and the geometry epsilon match
 * already in use on desktop catches the rare same-millisecond collision.
 */
class AndroidSnapshotMerger(
    private val db: AppDatabase,
) : SnapshotMerger {

    override suspend fun merge(snapshot: RemoteLearnerSnapshot): Map<String, MergeStats> {
        val annotationsAdded = mergeAnnotations(snapshot)
        val sessionEventsAdded = mergeSessionEvents(snapshot)
        val chatAdded = mergeChatMessages(snapshot)
        val interactionsAdded = mergePageInteractions(snapshot)
        val launchesAdded = mergeAppLaunches(snapshot)
        val settingsAdded = mergeSettingsChanges(snapshot)
        return linkedMapOf(
            "annotation_records" to MergeStats(added = annotationsAdded),
            "session_events" to MergeStats(added = sessionEventsAdded),
            "chat_messages" to MergeStats(added = chatAdded),
            "page_interactions" to MergeStats(added = interactionsAdded),
            "app_launch_records" to MergeStats(added = launchesAdded),
            "settings_changes" to MergeStats(added = settingsAdded),
        )
    }

    private suspend fun mergeAnnotations(snapshot: RemoteLearnerSnapshot): Int {
        if (snapshot.annotations.isEmpty()) return 0
        val dao = db.annotationRecordDao()
        val existing = dao.getAll()
        val seen = existing.map { (it.imageId to it.timestamp) }.toHashSet()
        var added = 0
        for (r in snapshot.annotations) {
            val key = r.imageId to r.timestamp
            if (key in seen) continue
            // Belt-and-suspenders: also check geometry epsilon match so a
            // remote row whose timestamp happens to collide with a local
            // one is not silently duplicated.
            val geomDup = existing.any { e ->
                e.imageId == r.imageId &&
                    e.label == r.label &&
                    e.regionType == r.regionType &&
                    kotlin.math.abs(e.boxX - r.boxX) < 0.0005f &&
                    kotlin.math.abs(e.boxY - r.boxY) < 0.0005f &&
                    kotlin.math.abs(e.boxWidth - r.boxWidth) < 0.0005f &&
                    kotlin.math.abs(e.boxHeight - r.boxHeight) < 0.0005f
            }
            if (geomDup) continue
            dao.insert(
                AnnotationRecordEntity(
                    imageId = r.imageId,
                    boxIndex = r.boxIndex,
                    boxX = r.boxX,
                    boxY = r.boxY,
                    boxWidth = r.boxWidth,
                    boxHeight = r.boxHeight,
                    label = r.label,
                    timestamp = r.timestamp,
                    tapX = r.tapX,
                    tapY = r.tapY,
                    regionType = r.regionType,
                    parentBubbleIndex = r.parentBubbleIndex,
                    tokenIndex = r.tokenIndex,
                    comicId = r.comicId ?: NO_COMIC_SENTINEL,
                    synced = true,
                ),
            )
            seen += key
            added += 1
        }
        return added
    }

    private suspend fun mergeSessionEvents(snapshot: RemoteLearnerSnapshot): Int {
        if (snapshot.sessionEvents.isEmpty()) return 0
        val dao = db.sessionEventDao()
        val seen = dao.getAll().map { Triple(it.eventType, it.timestamp, it.pageId) }.toHashSet()
        val toInsert = mutableListOf<SessionEventEntity>()
        for (r in snapshot.sessionEvents) {
            val key = Triple(r.eventType, r.timestamp, r.pageId)
            if (key in seen) continue
            toInsert += SessionEventEntity(
                eventType = r.eventType,
                timestamp = r.timestamp,
                durationMs = r.durationMs,
                comicId = r.comicId,
                chapterName = r.chapterName,
                pageId = r.pageId,
                pageTitle = r.pageTitle,
                synced = true,
            )
            seen += key
        }
        if (toInsert.isNotEmpty()) dao.insertAll(toInsert)
        return toInsert.size
    }

    private suspend fun mergeChatMessages(snapshot: RemoteLearnerSnapshot): Int {
        if (snapshot.chatMessages.isEmpty()) return 0
        val dao = db.chatMessageDao()
        val seen = dao.getAll().map { Triple(it.sender, it.text, it.timestamp) }.toHashSet()
        var added = 0
        for (r in snapshot.chatMessages) {
            val key = Triple(r.sender, r.text, r.timestamp)
            if (key in seen) continue
            dao.insert(
                ChatMessageEntity(
                    sender = r.sender,
                    text = r.text,
                    timestamp = r.timestamp,
                    synced = true,
                ),
            )
            seen += key
            added += 1
        }
        return added
    }

    private suspend fun mergePageInteractions(snapshot: RemoteLearnerSnapshot): Int {
        if (snapshot.pageInteractions.isEmpty()) return 0
        val dao = db.pageInteractionDao()
        val seen = dao.getAll().map { Triple(it.interactionType, it.timestamp, it.pageId) }.toHashSet()
        var added = 0
        for (r in snapshot.pageInteractions) {
            val key = Triple(r.interactionType, r.timestamp, r.pageId)
            if (key in seen) continue
            dao.insert(
                PageInteractionEntity(
                    interactionType = r.interactionType,
                    timestamp = r.timestamp,
                    comicId = r.comicId,
                    chapterName = r.chapterName,
                    pageId = r.pageId,
                    normalizedX = r.normalizedX,
                    normalizedY = r.normalizedY,
                    hitResult = r.hitResult,
                    synced = true,
                ),
            )
            seen += key
            added += 1
        }
        return added
    }

    private suspend fun mergeAppLaunches(snapshot: RemoteLearnerSnapshot): Int {
        if (snapshot.appLaunches.isEmpty()) return 0
        val dao = db.appLaunchRecordDao()
        val seen = dao.getAll().map { Triple(it.packageName, it.timestamp, it.currentPageId) }.toHashSet()
        var added = 0
        for (r in snapshot.appLaunches) {
            val key = Triple(r.packageName, r.timestamp, r.currentPageId)
            if (key in seen) continue
            dao.insert(
                AppLaunchRecordEntity(
                    packageName = r.packageName,
                    timestamp = r.timestamp,
                    comicId = r.comicId,
                    currentChapter = r.currentChapter,
                    currentPageId = r.currentPageId,
                    synced = true,
                ),
            )
            seen += key
            added += 1
        }
        return added
    }

    private suspend fun mergeSettingsChanges(snapshot: RemoteLearnerSnapshot): Int {
        if (snapshot.settingsChanges.isEmpty()) return 0
        val dao = db.settingsChangeDao()
        val seen = dao.getAll().map { Triple(it.setting, it.timestamp, it.newValue) }.toHashSet()
        var added = 0
        for (r in snapshot.settingsChanges) {
            val key = Triple(r.settingKey, r.timestamp, r.newValue)
            if (key in seen) continue
            dao.insert(
                SettingsChangeEntity(
                    setting = r.settingKey,
                    oldValue = r.oldValue,
                    newValue = r.newValue,
                    timestamp = r.timestamp,
                    synced = true,
                ),
            )
            seen += key
            added += 1
        }
        return added
    }
}
