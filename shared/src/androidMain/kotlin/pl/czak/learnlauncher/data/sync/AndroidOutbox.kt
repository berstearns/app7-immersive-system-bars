package pl.czak.learnlauncher.data.sync

import pl.czak.learnlauncher.data.AndroidLearnerDataRepository
import pl.czak.learnlauncher.data.export.buildUnifiedPayload
import pl.czak.learnlauncher.data.model.UnifiedPayload
import pl.czak.learnlauncher.data.sync.ports.Outbox

class AndroidOutbox(
    private val repo: AndroidLearnerDataRepository,
) : Outbox {

    override suspend fun snapshotUnsynced(
        deviceId: String,
        appVersion: String,
        userId: String?,
    ): UnifiedPayload = buildUnifiedPayload(
        db = repo.db,
        deviceId = deviceId,
        appVersion = appVersion,
        userId = userId,
        unsyncedOnly = true,
    )

    override suspend fun markSynced(payload: UnifiedPayload) {
        val tables = payload.tables
        val db = repo.db
        if (tables.sessionEvents.isNotEmpty())
            db.sessionEventDao().markSynced(tables.sessionEvents.map { it.localId })
        if (tables.annotationRecords.isNotEmpty())
            db.annotationRecordDao().markSynced(tables.annotationRecords.map { it.localId })
        if (tables.chatMessages.isNotEmpty())
            db.chatMessageDao().markSynced(tables.chatMessages.map { it.localId })
        if (tables.pageInteractions.isNotEmpty())
            db.pageInteractionDao().markSynced(tables.pageInteractions.map { it.localId })
        if (tables.appLaunchRecords.isNotEmpty())
            db.appLaunchRecordDao().markSynced(tables.appLaunchRecords.map { it.localId })
        if (tables.settingsChanges.isNotEmpty())
            db.settingsChangeDao().markSynced(tables.settingsChanges.map { it.localId })
        if (tables.appSessions.isNotEmpty())
            db.appSessionDao().markSynced(tables.appSessions.map { it.localId })
        if (tables.comicSessions.isNotEmpty())
            db.comicSessionDao().markSynced(tables.comicSessions.map { it.localId })
        if (tables.chapterSessions.isNotEmpty())
            db.chapterSessionDao().markSynced(tables.chapterSessions.map { it.localId })
        if (tables.pageSessions.isNotEmpty())
            db.pageSessionDao().markSynced(tables.pageSessions.map { it.localId })
        if (tables.pageBubbleOrderings.isNotEmpty())
            db.pageBubbleOrderingDao().markSynced(
                tables.pageBubbleOrderings.map { "${it.userId}|${it.comicId}|${it.imageId}" }
            )
    }
}
