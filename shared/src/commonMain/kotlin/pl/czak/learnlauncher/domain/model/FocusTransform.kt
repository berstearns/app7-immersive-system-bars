package pl.czak.learnlauncher.domain.model

import kotlin.math.min
import pl.czak.learnlauncher.domain.native.App7Native

/**
 * Scene-focus fit transform — how to crop one scene rectangle out of a
 * page and rotate + scale it so it fills the screen, maximized and
 * letterboxed.
 *
 * **The fit math routes through `App7Native.focusTransform` on JVM**
 * (Android + Desktop), so the scene-focus auto-rotation/scale decision is
 * computed by the compiled `libapp7_domain.so`, not re-implemented in
 * Kotlin (rule: no pure-domain logic left in Kotlin — the pure-Kotlin
 * formula below survives only as the iOS fallback, caught locally exactly
 * like [CropSelection.clamped]).  Any throwable other than
 * `NotImplementedError` (a broken `.so`) propagates so the call site
 * crashes instead of silently using an unverified transform.
 *
 * @property rotationDeg auto-rotation in {0, 90}
 * @property scale       contain scale after rotation (capped at [MAX_FOCUS_SCALE])
 * @property translateX  centering translate on X, in screen pixels
 * @property translateY  centering translate on Y, in screen pixels
 */
data class FocusTransform(
    val rotationDeg: Int,
    val scale: Float,
    val translateX: Float,
    val translateY: Float,
) {
    companion object {
        /** Kept in lock-step with `domain-core/src/crop.c` (APP7_MAX_FOCUS_SCALE)
         *  and `App7Domain.hs` (maxFocusScale). */
        const val MAX_FOCUS_SCALE: Float = 8.0f

        /**
         * @param sceneX,sceneY,sceneWidth,sceneHeight scene rect as page-normalized fractions (0..1)
         * @param pageWidth,pageHeight page size in pixels
         * @param screenWidth,screenHeight screen/viewport size in pixels
         */
        fun compute(
            sceneX: Float, sceneY: Float, sceneWidth: Float, sceneHeight: Float,
            pageWidth: Float, pageHeight: Float, screenWidth: Float, screenHeight: Float,
        ): FocusTransform =
            try {
                val o = App7Native.focusTransform(
                    sceneX, sceneY, sceneWidth, sceneHeight,
                    pageWidth, pageHeight, screenWidth, screenHeight,
                )
                FocusTransform(o[0].toInt(), o[1], o[2], o[3])
            } catch (_: NotImplementedError) {
                pureFallback(
                    sceneWidth, sceneHeight,
                    pageWidth, pageHeight, screenWidth, screenHeight,
                )
            }

        // iOS-only. Byte-for-byte with domain-core/src/crop.c app7_focus_transform.
        private fun pureFallback(
            rw: Float, rh: Float, pw: Float, ph: Float, sw: Float, sh: Float,
        ): FocusTransform {
            val sceneW = rw * pw
            val sceneH = rh * ph
            if (sceneW <= 0f || sceneH <= 0f || sw <= 0f || sh <= 0f) {
                return FocusTransform(0, 1f, 0f, 0f)
            }
            val scale0 = min(sw / sceneW, sh / sceneH)
            val scale90 = min(sw / sceneH, sh / sceneW)
            val rot: Int
            val s0: Float
            if (scale90 > scale0) { rot = 90; s0 = scale90 } else { rot = 0; s0 = scale0 }
            val s = if (s0 > MAX_FOCUS_SCALE) MAX_FOCUS_SCALE else s0
            val dispW = (if (rot == 0) sceneW else sceneH) * s
            val dispH = (if (rot == 0) sceneH else sceneW) * s
            return FocusTransform(rot, s, (sw - dispW) / 2f, (sh - dispH) / 2f)
        }
    }
}
