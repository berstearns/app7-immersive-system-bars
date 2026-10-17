package pl.czak.learnlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.czak.learnlauncher.data.repository.AnnotationRepository
import pl.czak.learnlauncher.domain.model.RegionAnnotation

@Composable
fun AnnotationLogScreen(
    annotationRepository: AnnotationRepository,
    onBack: () -> Unit,
    onNavigateToImage: (String) -> Unit,
    // F15: tapping a row opens the annotation-detail view (crop + label + reference),
    // instead of jumping straight to the reader.
    onOpenDetail: (RegionAnnotation) -> Unit = {},
    // Pull the logged-in user's annotations from the remote Turso DB into local Room
    // before reading. Null on platforms without Turso (desktop/iOS) — then the log is
    // local-only. See App.onRefreshAnnotationLog.
    onRefreshFromCloud: (suspend () -> Unit)? = null
) {
    var allAnnotations by remember { mutableStateOf<List<RegionAnnotation>>(emptyList()) }
    var selectedLabel by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    // On open: (1) pull the user's annotations from the remote Turso DB into local Room
    // (no-op / best-effort if unconfigured or offline), then (2) read local, ordered by
    // timestamp DESC. Without step (1) the log would only ever show annotations created
    // in THIS local session and miss everything the user recorded on other devices —
    // which reads as "I can't see annotation logs at all".
    LaunchedEffect(Unit) {
        loading = true
        runCatching { onRefreshFromCloud?.invoke() }
        allAnnotations = annotationRepository.getAll().sortedByDescending { it.timestamp }
        loading = false
    }

    val filtered = if (selectedLabel == null) allAnnotations
    else allAnnotations.filter { it.label == selectedLabel }

    val labels = allAnnotations.map { it.label }.distinct()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            // Expose Compose testTags as Android resource-ids so Maestro `id:` selectors resolve.
            .testTagsAsResourceIds()
            .testTag("annotation-log-screen")
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("annotation-log-back")) {
                Text("< Back", color = Color(0xFF4CAF50))
            }
            Spacer(Modifier.weight(1f))
            Text("Annotation Log", color = Color.White, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(64.dp))
        }

        // Most-recent marker — reflects the label of the topmost (newest-timestamp) row.
        // Lives OUTSIDE the LazyColumn so its testTag surfaces as a resource-id (tags
        // inside a LazyColumn item's subcomposition do not). Lets a UI test assert the
        // ordering (newest first) without depending on per-item ids.
        Text(
            text = filtered.firstOrNull()?.let { "Most recent: ${it.label}" }
                ?: if (loading) "" else "Most recent: —",
            color = Color(0xFF9E9E9E),
            fontSize = 12.sp,
            modifier = Modifier
                .testTag("annotation-log-newest")
                .padding(horizontal = 12.dp, vertical = 2.dp)
        )

        // Filter chips — horizontally scrollable. Without the scroll, a user with many
        // distinct labels (108 here) overflows the Row: Compose squishes the last visible
        // chip into a vertical text strip and the broken row consumes the space the list
        // needs, so NO log rows render. This was the visible half of "can't see the logs".
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterChip(
                selected = selectedLabel == null,
                onClick = { selectedLabel = null },
                label = { Text("All") }
            )
            labels.forEach { label ->
                FilterChip(
                    selected = selectedLabel == label,
                    onClick = { selectedLabel = label },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = getLabelColor(label)
                    )
                )
            }
        }

        // Body: loading spinner → empty state → the ordered list
        when {
            loading -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.testTag("annotation-log-loading"))
            }
            filtered.isEmpty() -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No annotations",
                    color = Color.Gray,
                    modifier = Modifier.testTag("annotation-log-empty")
                )
            }
            else -> LazyColumn(
                modifier = Modifier
                    // weight(1f), NOT fillMaxSize(): inside the parent Column a scrollable
                    // list needs the bounded remaining height, or it lays out with no
                    // usable height and renders zero visible rows (the second half of the
                    // "can't see annotation logs" bug — data was pulled but nothing showed).
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("annotation-log-list"),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // filtered is already sorted newest-first; index 0 is the most recent.
                itemsIndexed(filtered) { index, annotation ->
                    AnnotationLogItem(
                        annotation = annotation,
                        index = index,
                        onClick = { onOpenDetail(annotation) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AnnotationLogItem(
    annotation: RegionAnnotation,
    index: Int,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            // Re-assert testTagsAsResourceId INSIDE the LazyColumn item: the shim set on
            // the screen root does not cross into the lazy-item subcomposition, so without
            // this the item/label testTags never surface as Android resource-ids for Maestro.
            .testTagsAsResourceIds()
            .testTag("annotation-log-item-$index")
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF1E1E1E)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Label badge
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = getLabelColor(annotation.label)
            ) {
                Text(
                    text = annotation.label,
                    color = Color.White,
                    fontSize = 12.sp,
                    // index-tagged so a Maestro `id:+text:` assertion can pin the newest
                    // label to the first row (proves timestamp-DESC ordering).
                    modifier = Modifier
                        .testTag("annotation-log-label-$index")
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = annotation.imageId.substringBeforeLast(".").replace("/", " / "),
                    color = Color.White,
                    fontSize = 14.sp
                )
                Text(
                    text = "Box #${annotation.boxIndex}",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
        }
    }
}

private fun getLabelColor(label: String): Color =
    Color(pl.czak.learnlauncher.ui.components.labelColorArgb(label).toInt())
