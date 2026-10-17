package pl.czak.learnlauncher.data.sync

data class SyncResult(
    val success: Boolean,
    val totalSynced: Int = 0,
    val message: String,
)
