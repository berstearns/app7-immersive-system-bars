package pl.czak.learnlauncher.domain.annotation

/**
 * Produces the PRIMARY annotation-bar options from the tapped bubble's OCR words (use case: the user
 * taps to save a specific word). Words are chosen/ordered by [scorer] (v0 = OCR confidence), capped
 * at [k]. The label and display are the word itself. When the bubble has no tokens the caller should
 * fall back to the semantic decider (the full pool lives behind the "others" expansion).
 */
class TokenSuggestionDecisionFunction(
    private val scorer: TokenSuggestionScorer = ConfidenceTokenScorer(),
    private val k: Int = 5,
) : AnnotationDecisionFunction {
    override fun decide(ctx: AnnotationContext): List<AnnotationOption> =
        scorer.score(ctx.boxTokens, ctx)
            .take(k)
            .map { AnnotationOption(label = it.text, display = it.text) }
}
