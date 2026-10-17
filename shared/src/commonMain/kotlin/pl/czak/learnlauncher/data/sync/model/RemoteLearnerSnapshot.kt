package pl.czak.learnlauncher.data.sync.model

data class RemoteSessionEventRecord(
    val deviceId: String,
    val localId: Long,
    val eventType: String,
    val timestamp: Long,
    val durationMs: Long?,
    val comicId: String,
    val chapterName: String?,
    val pageId: String?,
    val pageTitle: String?,
)

data class RemoteAnnotationRecord(
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
)

data class RemoteChatMessageRecord(
    val deviceId: String,
    val localId: Long,
    val sender: String,
    val text: String,
    val timestamp: Long,
)

data class RemotePageInteractionRecord(
    val deviceId: String,
    val localId: Long,
    val interactionType: String,
    val timestamp: Long,
    val comicId: String,
    val chapterName: String?,
    val pageId: String?,
    val normalizedX: Float?,
    val normalizedY: Float?,
    val hitResult: String?,
)

data class RemoteAppLaunchRecord(
    val deviceId: String,
    val localId: Long,
    val packageName: String,
    val timestamp: Long,
    val comicId: String,
    val currentChapter: String?,
    val currentPageId: String?,
)

data class RemoteSettingsChangeRecord(
    val deviceId: String,
    val localId: Long,
    val settingKey: String,
    val oldValue: String,
    val newValue: String,
    val timestamp: Long,
)

data class RemoteRegionTranslationRecord(
    val id: String,
    val imageId: String,
    val bubbleIndex: Int,
    val originalText: String,
    val meaningTranslation: String,
    val literalTranslation: String,
    val sourceLanguage: String,
    val targetLanguage: String,
)

data class RemotePageBubbleOrderingRecord(
    val userId: String,
    val comicId: String,
    val imageId: String,
    val originalIndicesCsv: String,
    val updatedAt: Long,
)

/**
 * Aggregated pull payload for the "Cross-device alignment" feature.
 * Mirrors the eight tables in scope (see spec
 * `app7-ideas-for-later/cross-device-learner-records-alignment-pull.md`).
 */
data class RemoteLearnerSnapshot(
    val userId: String,
    val sessionEvents: List<RemoteSessionEventRecord> = emptyList(),
    val annotations: List<RemoteAnnotationRecord> = emptyList(),
    val chatMessages: List<RemoteChatMessageRecord> = emptyList(),
    val pageInteractions: List<RemotePageInteractionRecord> = emptyList(),
    val appLaunches: List<RemoteAppLaunchRecord> = emptyList(),
    val settingsChanges: List<RemoteSettingsChangeRecord> = emptyList(),
    val regionTranslations: List<RemoteRegionTranslationRecord> = emptyList(),
    val pageBubbleOrderings: List<RemotePageBubbleOrderingRecord> = emptyList(),
) {
    fun totalRows(): Int =
        sessionEvents.size + annotations.size + chatMessages.size +
            pageInteractions.size + appLaunches.size + settingsChanges.size +
            regionTranslations.size + pageBubbleOrderings.size
}
