package pl.czak.learnlauncher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import pl.czak.learnlauncher.domain.model.CropSelection

private val CropFill = Color(0x332196F3)
private val CropStroke = Color(0xFF2196F3.toInt())

@Composable
fun CropSelectionOverlay(
    inProgress: CropSelection?,
    finalized: CropSelection?,
    fitRect: ImageFitRect = ImageFitRect(0f, 0f, 1f, 1f),
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val fr = if (fitRect.imageWidth <= 1f) {
            ImageFitRect(0f, 0f, size.width, size.height)
        } else fitRect

        inProgress?.let {
            val topLeft = Offset(fr.toPixelX(it.normalizedX()), fr.toPixelY(it.normalizedY()))
            val boxSize = Size(fr.toPixelW(it.normalizedWidth()), fr.toPixelH(it.normalizedHeight()))
            drawRect(color = CropFill, topLeft = topLeft, size = boxSize)
            drawRect(
                color = CropStroke,
                topLeft = topLeft,
                size = boxSize,
                style = Stroke(
                    width = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                ),
            )
        }

        finalized?.let {
            val topLeft = Offset(fr.toPixelX(it.normalizedX()), fr.toPixelY(it.normalizedY()))
            val boxSize = Size(fr.toPixelW(it.normalizedWidth()), fr.toPixelH(it.normalizedHeight()))
            drawRect(color = CropFill, topLeft = topLeft, size = boxSize)
            drawRect(
                color = CropStroke,
                topLeft = topLeft,
                size = boxSize,
                style = Stroke(width = 3f),
            )
        }
    }
}
