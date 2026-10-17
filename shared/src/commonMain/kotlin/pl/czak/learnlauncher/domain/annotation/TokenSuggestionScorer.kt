package pl.czak.learnlauncher.domain.annotation

import pl.czak.learnlauncher.domain.model.OcrToken

/** A token-as-suggestion candidate with a relevance score (higher = surface sooner). */
data class ScoredToken(val text: String, val score: Float)

/**
 * Scores a bubble's OCR tokens for suggestion in the annotation bar.
 *
 * This is the SEAM for a future ML model: today [ConfidenceTokenScorer] just passes OCR confidence
 * through, but the interface takes the full [AnnotationContext] so a later model can use page/user
 * signal. Future direction (deferred): emit composite labels — `understood "X"`, `didn't get "X Y"`
 * (bi/tri-grams) — by returning richer text; [AnnotationOption] already separates label from display.
 */
fun interface TokenSuggestionScorer {
    fun score(tokens: List<OcrToken>, ctx: AnnotationContext): List<ScoredToken>
}

/** v0 scorer: rank by OCR confidence, de-duped, blank-filtered, highest first. */
class ConfidenceTokenScorer : TokenSuggestionScorer {
    override fun score(tokens: List<OcrToken>, ctx: AnnotationContext): List<ScoredToken> =
        tokens.asSequence()
            .map { ScoredToken(it.text.trim(), it.confidence) }
            .filter { it.text.isNotBlank() }
            .distinctBy { it.text.lowercase() }
            .sortedByDescending { it.score }
            .toList()
}
