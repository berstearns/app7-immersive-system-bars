package pl.czak.learnlauncher.data.sync

import kotlin.random.Random
import pl.czak.learnlauncher.data.model.UnifiedPayload
import pl.czak.learnlauncher.data.sync.model.SubmitResult
import pl.czak.learnlauncher.data.sync.model.SyncEnvelope
import pl.czak.learnlauncher.data.sync.ports.Outbox
import pl.czak.learnlauncher.data.sync.ports.PayloadUploader

class SyncService(
    private val outbox: Outbox,
    private val uploader: PayloadUploader,
    private val deviceId: () -> String,
    private val userId: () -> String?,
    private val appVersion: String,
    private val correlationIdGen: () -> String = { defaultCorrelationId() },
) {
    suspend fun sync(): SyncResult {
        val uid = userId()
            ?: return SyncResult(success = false, message = "Not logged in")

        val payload: UnifiedPayload = outbox.snapshotUnsynced(
            deviceId = deviceId(),
            appVersion = appVersion,
            userId = uid,
        )

        val total = countSyncableRows(payload)
        if (total == 0) {
            return SyncResult(success = true, totalSynced = 0, message = "Already up to date")
        }

        val envelope = SyncEnvelope(
            payload = payload,
            deviceId = deviceId(),
            userId = uid,
            schemaVersion = payload.schemaVersion,
            correlationId = correlationIdGen(),
        )

        return when (val r = uploader.submit(envelope)) {
            is SubmitResult.Ok -> {
                outbox.markSynced(payload)
                SyncResult(success = true, totalSynced = total, message = "Synced $total records")
            }
            is SubmitResult.Retry -> SyncResult(success = false, message = r.reason)
            is SubmitResult.Fail -> SyncResult(success = false, message = r.reason)
        }
    }

    private fun countSyncableRows(payload: UnifiedPayload): Int = with(payload.tables) {
        sessionEvents.size + annotationRecords.size + chatMessages.size +
            pageInteractions.size + appLaunchRecords.size + settingsChanges.size +
            appSessions.size + comicSessions.size + chapterSessions.size + pageSessions.size
    }
}

private fun defaultCorrelationId(): String =
    Random.nextLong().toULong().toString(16).padStart(16, '0')
