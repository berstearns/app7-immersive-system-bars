package pl.czak.learnlauncher.data.sync

import pl.czak.learnlauncher.currentTimeMillis
import pl.czak.learnlauncher.data.InMemoryAnnotationRepository
import pl.czak.learnlauncher.data.InMemoryChatRepository
import pl.czak.learnlauncher.data.InMemoryLearnerDataRepository
import pl.czak.learnlauncher.data.model.AnnotationRecord
import pl.czak.learnlauncher.data.model.ChatMessageRecord
import pl.czak.learnlauncher.data.model.PageBubbleOrderingRecord
import pl.czak.learnlauncher.data.model.UnifiedPayload
import pl.czak.learnlauncher.data.model.UnifiedTables
import pl.czak.learnlauncher.data.sync.ports.Outbox
import pl.czak.learnlauncher.data.repository.PageBubbleOrderingRepository
import pl.czak.learnlauncher.domain.model.PageBubbleOrdering

/**
 * Desktop [Outbox] aggregating every in-memory store that participates in
 * sync: annotations, chat messages, and the four event types behind
 * [InMemoryLearnerDataRepository] (session events, page interactions,
 * app launches, settings changes).
 *
 * Session-hierarchy aggregates (app/comic/chapter/page sessions) are NOT
 * captured on desktop in this iteration — they require a dedicated session
 * tracker output sink that does not exist on the JVM target yet. The Turso
 * schema accepts empty lists for those, so the omission is forward-compatible.
 */
class DesktopOutbox(
    private val annotationRepository: InMemoryAnnotationRepository,
    private val learnerDataRepository: InMemoryLearnerDataRepository,
    private val chatRepository: InMemoryChatRepository,
    private val activeComicId: () -> String,
    private val pageBubbleOrderingRepository: PageBubbleOrderingRepository? = null,
) : Outbox {

    override suspend fun snapshotUnsynced(
        deviceId: String,
        appVersion: String,
        userId: String?,
    ): UnifiedPayload {
        val annotationRecords = annotationRepository.getUnsynced().map { ann ->
            AnnotationRecord(
                localId = ann.timestamp,
                imageId = ann.imageId,
                boxIndex = ann.boxIndex,
                boxX = ann.boxX,
                boxY = ann.boxY,
                boxWidth = ann.boxWidth,
                boxHeight = ann.boxHeight,
                label = ann.label,
                timestamp = ann.timestamp,
                tapX = ann.tapX,
                tapY = ann.tapY,
                regionType = ann.regionType.name,
                parentBubbleIndex = ann.parentBubbleIndex,
                tokenIndex = ann.tokenIndex,
                comicId = activeComicId(),
            )
        }
        val chatMessages = chatRepository.getUnsynced().map { msg ->
            ChatMessageRecord(
                localId = msg.timestamp,
                sender = msg.sender,
                text = msg.text,
                timestamp = msg.timestamp,
            )
        }
        val pageBubbleOrderings = pageBubbleOrderingRepository?.getUnsynced().orEmpty().map { o ->
            PageBubbleOrderingRecord(
                userId = o.userId,
                comicId = o.comicId,
                imageId = o.imageId,
                originalIndices = o.originalIndices,
                updatedAt = o.updatedAt,
            )
        }
        return UnifiedPayload(
            schemaVersion = 5,
            exportTimestamp = currentTimeMillis(),
            appVersion = appVersion,
            deviceId = deviceId,
            userId = userId,
            mode = "sync",
            tables = UnifiedTables(
                sessionEvents = learnerDataRepository.getUnsyncedSessionEvents(),
                annotationRecords = annotationRecords,
                chatMessages = chatMessages,
                pageInteractions = learnerDataRepository.getUnsyncedPageInteractions(),
                appLaunchRecords = learnerDataRepository.getUnsyncedAppLaunches(),
                settingsChanges = learnerDataRepository.getUnsyncedSettingsChanges(),
                pageBubbleOrderings = pageBubbleOrderings,
            ),
        )
    }

    override suspend fun markSynced(payload: UnifiedPayload) {
        val tables = payload.tables
        if (tables.annotationRecords.isNotEmpty()) {
            annotationRepository.markSynced(tables.annotationRecords.map { it.timestamp })
        }
        if (tables.chatMessages.isNotEmpty()) {
            chatRepository.markSynced(tables.chatMessages.map { it.timestamp })
        }
        if (tables.sessionEvents.isNotEmpty()) {
            learnerDataRepository.markSessionEventsSynced(tables.sessionEvents.map { it.localId })
        }
        if (tables.pageInteractions.isNotEmpty()) {
            learnerDataRepository.markPageInteractionsSynced(tables.pageInteractions.map { it.localId })
        }
        if (tables.appLaunchRecords.isNotEmpty()) {
            learnerDataRepository.markAppLaunchesSynced(tables.appLaunchRecords.map { it.localId })
        }
        if (tables.settingsChanges.isNotEmpty()) {
            learnerDataRepository.markSettingsChangesSynced(tables.settingsChanges.map { it.localId })
        }
        if (tables.pageBubbleOrderings.isNotEmpty() && pageBubbleOrderingRepository != null) {
            pageBubbleOrderingRepository.markSynced(
                tables.pageBubbleOrderings.map {
                    PageBubbleOrdering(
                        userId = it.userId,
                        comicId = it.comicId,
                        imageId = it.imageId,
                        originalIndices = it.originalIndices,
                        updatedAt = it.updatedAt,
                    )
                }
            )
        }
    }
}
