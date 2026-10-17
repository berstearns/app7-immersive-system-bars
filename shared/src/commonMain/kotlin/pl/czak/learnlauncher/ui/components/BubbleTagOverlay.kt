package pl.czak.learnlauncher.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import pl.czak.learnlauncher.ui.testTagsAsResourceIds
import pl.czak.learnlauncher.domain.model.ConversationBox
import kotlin.math.roundToInt

/**
 * Invisible, NON-interactive markers — one per conversation box, positioned over the bubble's
 * bbox — each carrying `testTag("bubble-<idx>")` where idx is the bubble's index in the page's
 * ordered conversation boxes (the same index `bubbleHitTest` returns).
 *
 * Purpose: let UI automation (Maestro) tap a SPECIFIC bubble by id (`id: "bubble-2"`). The markers
 * have no pointerInput, so they do not consume touches — a tap on a marker falls through to the
 * image's gesture detector, which hit-tests to that same bubble. Behavior is therefore identical
 * for real users (the markers are inert); only the automation surface is added.
 *
 * testTagsAsResourceId is enabled on this overlay's root so the child testTags surface as
 * resource-ids that Maestro's `id:` matcher can see.
 */
@Composable
fun BubbleTagOverlay(
    boxes: List<ConversationBox>,
    fitRect: ImageFitRect,
    modifier: Modifier = Modifier
) {
    if (boxes.isEmpty()) return
    // Skip until the image fit rect is known, else markers would be mis-positioned.
    if (fitRect.imageWidth <= 1f) return
    val density = LocalDensity.current
    Box(modifier.testTagsAsResourceIds()) {
        boxes.forEachIndexed { idx, b ->
            val xPx = fitRect.toPixelX(b.x)
            val yPx = fitRect.toPixelY(b.y)
            val wPx = fitRect.toPixelW(b.width).coerceAtLeast(1f)
            val hPx = fitRect.toPixelH(b.height).coerceAtLeast(1f)
            Box(
                Modifier
                    .offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }
                    .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
                    .testTag("bubble-$idx")
            )
        }
    }
}
