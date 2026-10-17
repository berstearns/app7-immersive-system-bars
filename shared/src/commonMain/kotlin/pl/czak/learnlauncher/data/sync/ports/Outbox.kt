package pl.czak.learnlauncher.data.sync.ports

import pl.czak.learnlauncher.data.model.UnifiedPayload

interface Outbox {
    suspend fun snapshotUnsynced(deviceId: String, appVersion: String, userId: String?): UnifiedPayload
    suspend fun markSynced(payload: UnifiedPayload)
}
