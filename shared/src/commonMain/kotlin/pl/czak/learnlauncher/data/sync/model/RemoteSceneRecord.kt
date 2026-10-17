package pl.czak.learnlauncher.data.sync.model

data class RemoteSceneRecord(
    val deviceId: String,
    val localId: Long,
    val imageId: String,
    val boxIndex: Int,
    val boxX: Float,
    val boxY: Float,
    val boxWidth: Float,
    val boxHeight: Float,
    val label: String,
    val timestamp: Long,
    val tapX: Float,
    val tapY: Float,
    val regionType: String,
    val parentBubbleIndex: Int?,
    val tokenIndex: Int?,
    val comicId: String?,
    val userId: String?,
)
