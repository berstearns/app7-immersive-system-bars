package pl.czak.learnlauncher.data.sync.model

import kotlin.time.Duration

sealed interface SubmitResult {
    data class Ok(
        val acceptedRows: Int,
        val perTable: Map<String, Int>,
        val backendId: String? = null,
    ) : SubmitResult

    data class Retry(
        val reason: String,
        val backoffHint: Duration? = null,
    ) : SubmitResult

    data class Fail(
        val reason: String,
        val permanent: Boolean,
    ) : SubmitResult
}
