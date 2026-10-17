package pl.czak.learnlauncher.data.sync.ports

import pl.czak.learnlauncher.data.sync.model.SubmitResult
import pl.czak.learnlauncher.data.sync.model.SyncEnvelope

interface PayloadUploader {
    suspend fun submit(envelope: SyncEnvelope): SubmitResult
}
