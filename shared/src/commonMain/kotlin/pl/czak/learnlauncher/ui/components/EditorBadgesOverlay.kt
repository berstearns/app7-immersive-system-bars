package pl.czak.learnlauncher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.czak.learnlauncher.domain.model.ConversationBox

private val BadgeFill = Color(0xFF2196F3)
private val BadgeStroke = Color.White
private val BadgeTextColor = Color.White

/**
 * Draws numbered circle badges on bubbles in their selection order.
 *
 * `selection` is a list of original-list indices in the order the user tapped
 * them. For each entry we draw a blue filled circle at the bubble's center
 * with the 1-based selection rank in white inside the circle.
 *
 * Mirrors the Canvas + ImageFitRect rendering style of [AnnotationOverlay].
 */
@Composable
fun EditorBadgesOverlay(
    boxes: List<ConversationBox>,
    selection: List<Int>,
    fitRect: ImageFitRect = ImageFitRect(0f, 0f, 1f, 1f),
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val radiusPx = with(density) { 18.dp.toPx() }
    val strokePx = with(density) { 2.dp.toPx() }
    val textStyle = TextStyle(
        color = BadgeTextColor,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
    )

    Canvas(modifier = modifier) {
        // Default fitRect (imageWidth <= 1f) means caller didn't supply real
        // dimensions yet; fall back to full canvas size.
        val fr = if (fitRect.imageWidth <= 1f) {
            ImageFitRect(0f, 0f, size.width, size.height)
        } else fitRect

        for ((selectionIndex, originalIndex) in selection.withIndex()) {
            val box = boxes.getOrNull(originalIndex) ?: continue
            val cx = fr.toPixelX(box.x + box.width / 2f)
            val cy = fr.toPixelY(box.y + box.height / 2f)
            val center = Offset(cx, cy)

            drawCircle(color = BadgeFill, radius = radiusPx, center = center)
            drawCircle(
                color = BadgeStroke,
                radius = radiusPx,
                center = center,
                style = Stroke(width = strokePx),
            )

            val layout = textMeasurer.measure(
                text = (selectionIndex + 1).toString(),
                style = textStyle,
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    x = cx - layout.size.width / 2f,
                    y = cy - layout.size.height / 2f,
                ),
            )
        }
    }
}
