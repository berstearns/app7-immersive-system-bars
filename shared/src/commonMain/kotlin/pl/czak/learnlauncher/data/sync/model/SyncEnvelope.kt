package pl.czak.learnlauncher.data.sync.model

import pl.czak.learnlauncher.data.model.UnifiedPayload

data class SyncEnvelope(
    val payload: UnifiedPayload,
    val deviceId: String,
    val userId: String?,
    val schemaVersion: Int = 5,
    val correlationId: String,
)
