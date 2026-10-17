package pl.czak.learnlauncher.domain.annotation

import pl.czak.learnlauncher.domain.native.App7Native

/**
 * [AnnotationDecisionFunction] backed by the compiled domain binary
 * (`libapp7_domain.so` via [App7Native]).
 *
 * Phase 3b-A: drop-in replacement for [RandomSamplingDecisionFunction]
 * on Android + Desktop.  Per the source-of-truth mandate (memory:
 * `feedback_haskell_domain_done_gate`), JVM-platform call sites should
 * use this implementation; the original [RandomSamplingDecisionFunction]
 * stays in the codebase because (a) iOS is out of scope for the port and
 * still needs a pure-Kotlin path, and (b) it's a useful comparator in
 * tests.
 *
 * Determinism contract: same `seed` always yields the same indices.
 * The seed defaults to system time, mirroring `Random.Default` behaviour
 * — callers that need reproducible output pass an explicit `seed`.
 */
class NativeAnnotationDecisionFunction(
    private val k: Int = 3,
    private val seedProvider: () -> Long = { kotlin.random.Random.Default.nextLong() },
) : AnnotationDecisionFunction {

    override fun decide(ctx: AnnotationContext): List<AnnotationOption> {
        val indices = App7Native.sampleAnnotationIndices(seedProvider(), k)
        return indices.map { i ->
            AnnotationOption(
                label   = App7Native.annotationPoolLabel(i),
                display = App7Native.annotationPoolDisplay(i),
            )
        }
    }
}
