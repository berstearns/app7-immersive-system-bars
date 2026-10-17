package pl.czak.learnlauncher.domain.annotation

import kotlin.random.Random

val DefaultAnnotationPool: List<AnnotationOption> = listOf(
    AnnotationOption("understood", "Got it"),
    AnnotationOption("partially", "Partial"),
    AnnotationOption("not_understood", "Didn't get it"),
    AnnotationOption("need_re_read", "Re-read"),
    AnnotationOption("key_idiom", "Idiom"),
    AnnotationOption("vocabulary", "Vocab"),
    AnnotationOption("grammar", "Grammar"),
    AnnotationOption("surprising", "Surprising"),
    AnnotationOption("funny", "Funny"),
    AnnotationOption("sad", "Sad"),
    AnnotationOption("agree", "Agree"),
    AnnotationOption("disagree", "Disagree"),
    AnnotationOption("confused", "Confused"),
    AnnotationOption("follow_up_question", "Question"),
    AnnotationOption("character_intro", "Character"),
    AnnotationOption("plot_beat", "Plot beat"),
    AnnotationOption("callback", "Callback")
)

class RandomSamplingDecisionFunction(
    private val pool: List<AnnotationOption> = DefaultAnnotationPool,
    private val k: Int = 3,
    private val rng: Random = Random.Default
) : AnnotationDecisionFunction {
    override fun decide(ctx: AnnotationContext): List<AnnotationOption> =
        pool.shuffled(rng).take(k.coerceAtMost(pool.size))
}

class ConstantDecisionFunction(
    private val options: List<AnnotationOption>
) : AnnotationDecisionFunction {
    override fun decide(ctx: AnnotationContext): List<AnnotationOption> = options
}
