package pl.czak.learnlauncher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import pl.czak.learnlauncher.domain.model.RegionAnnotation

private val SceneStroke = Color(0xFFEF5350)

@Composable
fun SceneBordersOverlay(
    scenes: List<RegionAnnotation>,
    fitRect: ImageFitRect = ImageFitRect(0f, 0f, 1f, 1f),
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val fr = if (fitRect.imageWidth <= 1f) {
            ImageFitRect(0f, 0f, size.width, size.height)
        } else fitRect

        scenes.forEach { s ->
            val topLeft = Offset(fr.toPixelX(s.boxX), fr.toPixelY(s.boxY))
            val boxSize = Size(fr.toPixelW(s.boxWidth), fr.toPixelH(s.boxHeight))
            drawRect(
                color = SceneStroke,
                topLeft = topLeft,
                size = boxSize,
                style = Stroke(width = 2f),
            )
        }
    }
}
