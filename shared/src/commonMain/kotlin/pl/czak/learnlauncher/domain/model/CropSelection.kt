package pl.czak.learnlauncher.domain.model

import kotlin.math.abs
import kotlin.math.min
import pl.czak.learnlauncher.domain.native.App7Native

/**
 * **All instance methods route through `App7Native` on JVM targets**
 * (Android + Desktop), so every coordinate that downstream code might
 * persist to Room and sync to Turso has passed the compiled binary's
 * check (rule: *never store long-term anything unverified*).
 *
 * On iOS the `App7Native` stub throws `NotImplementedError`, caught
 * locally so iOS keeps working via the pure-Kotlin formula — iOS is
 * out of scope for the port (Swift FFI bridge later).  Any OTHER
 * throwable (UnsatisfiedLinkError, SecurityException, …) propagates
 * uncaught so a broken `.so` on JVM crashes the call site instead of
 * silently substituting an unverified value.
 */
data class CropSelection(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
) {
    fun normalizedX(): Float =
        try { App7Native.normalizedX(startX, startY, endX, endY) }
        catch (_: NotImplementedError) { min(startX, endX) }

    fun normalizedY(): Float =
        try { App7Native.normalizedY(startX, startY, endX, endY) }
        catch (_: NotImplementedError) { min(startY, endY) }

    fun normalizedWidth(): Float =
        try { App7Native.normalizedWidth(startX, startY, endX, endY) }
        catch (_: NotImplementedError) { abs(endX - startX) }

    fun normalizedHeight(): Float =
        try { App7Native.normalizedHeight(startX, startY, endX, endY) }
        catch (_: NotImplementedError) { abs(endY - startY) }

    fun centerX(): Float =
        try { App7Native.centerX(startX, startY, endX, endY) }
        catch (_: NotImplementedError) { normalizedX() + normalizedWidth() / 2f }

    fun centerY(): Float =
        try { App7Native.centerY(startX, startY, endX, endY) }
        catch (_: NotImplementedError) { normalizedY() + normalizedHeight() / 2f }

    fun isDegenerate(minSize: Float): Boolean =
        try { App7Native.isDegenerate(minSize, startX, startY, endX, endY) }
        catch (_: NotImplementedError) {
            normalizedWidth() < minSize || normalizedHeight() < minSize
        }

    fun clamped(): CropSelection =
        try {
            val out = App7Native.clamped(startX, startY, endX, endY)
            CropSelection(out[0], out[1], out[2], out[3])
        } catch (_: NotImplementedError) {
            copy(
                startX = startX.coerceIn(0f, 1f),
                startY = startY.coerceIn(0f, 1f),
                endX = endX.coerceIn(0f, 1f),
                endY = endY.coerceIn(0f, 1f),
            )
        }

    companion object {
        const val MIN_NORMALIZED_SIZE: Float = 0.02f
    }
}
