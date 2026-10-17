package pl.czak.learnlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.czak.learnlauncher.domain.model.ConversationBox
import pl.czak.learnlauncher.domain.model.RegionAnnotation

/**
 * F15 — annotation-detail view. Opened by tapping a row in the Annotation Log. Shows the
 * cropped bubble the annotation was made on, the label, and a tiny reference button
 * (episode · page) that navigates to the source page in the reader.
 */
@Composable
fun AnnotationDetailScreen(
    annotation: RegionAnnotation,
    loadImageBytes: (String) -> ByteArray?,
    onBack: () -> Unit,
    onOpenSource: (String) -> Unit
) {
    val bytes = remember(annotation.imageId) { loadImageBytes(annotation.imageId) }
    // Reuse the reader's ImageDisplay crop: a non-null zoomedBubble crops the page to that
    // normalized box, which is exactly the annotation's region.
    val cropBox = remember(annotation) {
        ConversationBox(
            x = annotation.boxX, y = annotation.boxY,
            width = annotation.boxWidth, height = annotation.boxHeight,
            regionType = annotation.regionType
        )
    }
    val episode = annotation.imageId.substringBeforeLast('/', "")
    val page = annotation.imageId.substringAfterLast('/').substringBeforeLast('.')
    val reference = listOf(episode, page).filter { it.isNotEmpty() }.joinToString(" · ")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .testTagsAsResourceIds()
            .testTag("annotation-detail-screen")
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("annotation-detail-back")) {
                Text("< Back", color = Color(0xFF4CAF50))
            }
            Spacer(Modifier.weight(1f))
            Text("Annotation", color = Color.White, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(64.dp))
        }

        // Bubble crop
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(12.dp)
                .testTag("annotation-detail-crop"),
            contentAlignment = Alignment.Center
        ) {
            if (bytes != null) {
                ImageDisplay(
                    imageBytes = bytes,
                    zoomedBubble = cropBox,
                    modifier = Modifier.fillMaxWidth(),
                    onImageSizeKnown = null,
                    contentScale = ContentScale.Fit
                )
            } else {
                Text("(image unavailable)", color = Color.Gray)
            }
        }

        // Label badge
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color(pl.czak.learnlauncher.ui.components.labelColorArgb(annotation.label).toInt())
            ) {
                Text(
                    text = annotation.label,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .testTag("annotation-detail-label")
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        // Tiny reference button → navigate to the source page
        TextButton(
            onClick = { onOpenSource(annotation.imageId) },
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .testTag("annotation-detail-ref")
        ) {
            Text("↪ $reference", color = Color(0xFF4CAF50), fontSize = 12.sp)
        }
    }
}
