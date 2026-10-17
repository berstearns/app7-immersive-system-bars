package pl.czak.learnlauncher.domain.annotation

import pl.czak.learnlauncher.domain.model.OcrToken

data class AnnotationContext(
    val seriesPath: String,
    val chapterPath: String,
    val pageId: String,
    val boxIndex: Int,
    val userId: String?,
    val recentLabels: List<String>,
    val nowEpochMs: Long,
    /** OCR tokens of the tapped bubble (empty if none). Source for token suggestions. */
    val boxTokens: List<OcrToken> = emptyList()
)

data class AnnotationOption(
    val label: String,
    val display: String,
    val color: Long? = null
)

fun interface AnnotationDecisionFunction {
    fun decide(ctx: AnnotationContext): List<AnnotationOption>
}
