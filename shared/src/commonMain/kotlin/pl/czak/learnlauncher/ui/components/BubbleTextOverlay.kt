package pl.czak.learnlauncher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import pl.czak.learnlauncher.domain.model.ConversationBox
import pl.czak.learnlauncher.domain.model.RegionType

private val TextFill = Color(0xCCFFFFFF)   // semi-opaque white background behind each text block
private val TextColor = Color(0xFF111111)

private val CANDIDATE_SIZES_SP = listOf(16, 14, 12, 10, 8)

/**
 * Draws the per-bubble text (OCR'd via Claude Vision / PaddleOCR pipelines) inside each
 * bubble's bounding box. Falls back to a mock label ("Bubble N") for bubbles that don't
 * have OCR text yet, so the overlay is visible across the entire corpus immediately
 * — the same composable then surfaces real text the moment a chapter is processed.
 *
 * Skips non-BUBBLE region types (e.g. scene crops). Auto-fits font size from 16sp down
 * to 8sp; below that, falls back to 8sp with multi-line truncation.
 */
@Composable
fun BubbleTextOverlay(
    boxes: List<ConversationBox>,
    fitRect: ImageFitRect = ImageFitRect(0f, 0f, 1f, 1f),
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier) {
        val fr = if (fitRect.imageWidth <= 1f) {
            ImageFitRect(0f, 0f, size.width, size.height)
        } else fitRect

        // Per-bubble index is the index over BUBBLE-typed boxes only so mock labels
        // "Bubble 1", "Bubble 2", ... stay readable / monotonic.
        var bubbleIndex = 0
        for (box in boxes) {
            if (box.regionType != RegionType.BUBBLE) continue
            bubbleIndex++

            val topLeftX = fr.toPixelX(box.x)
            val topLeftY = fr.toPixelY(box.y)
            val boxW = fr.toPixelW(box.width)
            val boxH = fr.toPixelH(box.height)
            if (boxW <= 0f || boxH <= 0f) continue

            val display = box.text?.takeIf { it.isNotBlank() } ?: "Bubble $bubbleIndex"

            // Try each candidate size, pick the largest that fits inside the bbox.
            // softWrap=true so multi-line bubble text wraps naturally.
            var chosen: Pair<Int, androidx.compose.ui.text.TextLayoutResult>? = null
            for (sp in CANDIDATE_SIZES_SP) {
                val style = TextStyle(
                    color = TextColor,
                    fontSize = sp.sp,
                    fontWeight = FontWeight.Medium,
                )
                val layout = textMeasurer.measure(
                    text = display,
                    style = style,
                    softWrap = true,
                    maxLines = (boxH / (sp * 1.4f)).toInt().coerceAtLeast(1),
                    constraints = androidx.compose.ui.unit.Constraints(
                        maxWidth = boxW.toInt().coerceAtLeast(1),
                    ),
                )
                if (layout.size.width <= boxW && layout.size.height <= boxH) {
                    chosen = sp to layout
                    break
                }
            }
            // Even 8sp doesn't fit — render anyway at 8sp, will overflow visually but
            // user can still read fragments.
            if (chosen == null) {
                val style = TextStyle(color = TextColor, fontSize = 8.sp, fontWeight = FontWeight.Medium)
                val layout = textMeasurer.measure(
                    text = display,
                    style = style,
                    softWrap = true,
                    maxLines = (boxH / (8 * 1.4f)).toInt().coerceAtLeast(1),
                    constraints = androidx.compose.ui.unit.Constraints(
                        maxWidth = boxW.toInt().coerceAtLeast(1),
                    ),
                )
                chosen = 8 to layout
            }

            val layout = chosen.second
            val centeredX = topLeftX + (boxW - layout.size.width) / 2f
            val centeredY = topLeftY + (boxH - layout.size.height) / 2f

            // Background rect for readability (semi-opaque white).
            drawRect(
                color = TextFill,
                topLeft = Offset(centeredX - 2f, centeredY - 2f),
                size = Size(layout.size.width.toFloat() + 4f, layout.size.height.toFloat() + 4f),
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(centeredX, centeredY),
            )
        }
    }
}
