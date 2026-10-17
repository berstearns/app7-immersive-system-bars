package pl.czak.learnlauncher.data.source

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pl.czak.learnlauncher.data.model.RegionTranslation
import pl.czak.learnlauncher.data.model.TokenTranslation

@Serializable
data class TranslatedPageFile(
    val version: Int = 1,
    @SerialName("src_lang") val srcLang: String = "fr",
    @SerialName("tgt_lang") val tgtLang: String = "en",
    val conversations: Map<String, List<TranslatedBubbleJson>> = emptyMap()
)

@Serializable
data class TranslatedBubbleJson(
    // Nullable on purpose: the upstream pipeline emits null when the source
    // bubble had no usable OCR text. We coalesce to "" downstream.
    @SerialName("source_text") val sourceText: String? = "",
    @SerialName("meaning_translation") val meaningTranslation: String? = "",
    @SerialName("literal_translation") val literalTranslation: String? = "",
    @SerialName("token_translations") val tokenTranslations: List<TokenTranslationJson> = emptyList()
)

@Serializable
data class TokenTranslationJson(
    val src: String,
    val tgt: String,
    @SerialName("src_x") val srcX: Double,
    @SerialName("src_y") val srcY: Double,
    @SerialName("src_w") val srcW: Double,
    @SerialName("src_h") val srcH: Double
)

private val translatedPagesJson = Json { ignoreUnknownKeys = true }

fun parseTranslatedPage(jsonText: String): TranslatedPageFile =
    translatedPagesJson.decodeFromString<TranslatedPageFile>(jsonText)

/**
 * Convert a parsed per-image translated-page file into a map of bubble index →
 * RegionTranslation, keyed by the *qualified* imageId
 * (`<chapterPath>/<localImageId>`). One file describes one image, but we accept
 * multiple top-level image ids defensively because the pipeline's schema
 * allows it.
 */
fun TranslatedPageFile.toRegionTranslations(
    chapterPath: String,
    src: String = this.srcLang,
    tgt: String = this.tgtLang,
): Map<String, Map<Int, RegionTranslation>> {
    val out = mutableMapOf<String, Map<Int, RegionTranslation>>()
    for ((localImgId, bubbles) in conversations) {
        val qualifiedId = if (chapterPath.isNotEmpty()) "$chapterPath/$localImgId" else localImgId
        val perBubble = mutableMapOf<Int, RegionTranslation>()
        bubbles.forEachIndexed { idx, b ->
            perBubble[idx] = RegionTranslation(
                id = "${qualifiedId}_$idx",
                imageId = qualifiedId,
                bubbleIndex = idx,
                originalText = b.sourceText.orEmpty(),
                meaningTranslation = b.meaningTranslation.orEmpty(),
                literalTranslation = b.literalTranslation.orEmpty(),
                sourceLanguage = src,
                targetLanguage = tgt,
                tokenTranslations = b.tokenTranslations.map { t ->
                    TokenTranslation(
                        src = t.src,
                        tgt = t.tgt,
                        srcX = t.srcX,
                        srcY = t.srcY,
                        srcW = t.srcW,
                        srcH = t.srcH,
                    )
                }
            )
        }
        out[qualifiedId] = perBubble
    }
    return out
}
