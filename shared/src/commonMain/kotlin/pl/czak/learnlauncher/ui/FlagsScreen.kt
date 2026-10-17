package pl.czak.learnlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.czak.learnlauncher.viewmodel.LauncherViewModel

@Composable
fun FlagsScreen(
    viewModel: LauncherViewModel,
    onBack: () -> Unit,
    autoSyncEnabled: Boolean = false,
    onAutoSyncToggled: ((Boolean) -> Unit)? = null,
    isDesktop: Boolean = false
) {
    val showAnnotationDots by viewModel.showAnnotationDots.collectAsState()
    val showSceneBorders by viewModel.showSceneBorders.collectAsState()
    val annotationTriggerGesture by viewModel.annotationTriggerGesture.collectAsState()
    val nextPageTriggerGesture by viewModel.nextPageTriggerGesture.collectAsState()
    val annotationBarPosition by viewModel.annotationBarPosition.collectAsState()
    val annotationBarVisibilityMode by viewModel.annotationBarVisibilityMode.collectAsState()
    val annotationOnSpace by viewModel.annotationOnSpace.collectAsState()
    val editModeEnabled by viewModel.editModeEnabled.collectAsState()
    val landscapeEnabled by viewModel.landscapeEnabled.collectAsState()
    val expandFitEnabled by viewModel.expandFitEnabled.collectAsState()
    val expandFillEnabled by viewModel.expandFillEnabled.collectAsState()
    val bubbleTextOverlayEnabled by viewModel.bubbleTextOverlayEnabled.collectAsState()
    val pageOrdering by viewModel.pageOrdering.collectAsState()
    val cloudAlignEnabled by viewModel.cloudAlignEnabled.collectAsState()
    val cloudAlignIntervalSec by viewModel.cloudAlignIntervalSec.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("< Back", color = Color(0xFF4CAF50))
            }
            Spacer(Modifier.weight(1f))
            Text("Flags", color = Color.White, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(64.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        FlagSectionHeader("Annotation")

        Text(
            "Annotation trigger gesture",
            color = Color.White,
            modifier = Modifier.padding(top = 8.dp)
        )
        val triggerOptions = listOf(
            "off" to "Off",
            "single_tap" to "Single tap",
            "double_tap" to "Double tap"
        )
        var triggerExpanded by remember { mutableStateOf(false) }
        val triggerLabel = triggerOptions.firstOrNull { it.first == annotationTriggerGesture }?.second
            ?: "Double tap"

        Box(modifier = Modifier.padding(vertical = 4.dp)) {
            OutlinedButton(
                onClick = { triggerExpanded = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text(triggerLabel, modifier = Modifier.weight(1f))
                Text(" ▼", color = Color.Gray)
            }
            DropdownMenu(
                expanded = triggerExpanded,
                onDismissRequest = { triggerExpanded = false }
            ) {
                triggerOptions.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                label,
                                color = if (value == annotationTriggerGesture) Color(0xFF4CAF50) else Color.White
                            )
                        },
                        onClick = {
                            triggerExpanded = false
                            viewModel.setAnnotationTriggerGesture(value)
                        }
                    )
                }
            }
        }

        Text(
            "Next-page trigger gesture",
            color = Color.White,
            modifier = Modifier.padding(top = 8.dp)
        )
        val nextPageOptions = listOf(
            "off" to "Off (swipe only)",
            "single_tap" to "Single tap",
            "double_tap" to "Double tap"
        )
        var nextPageExpanded by remember { mutableStateOf(false) }
        val nextPageLabel = nextPageOptions.firstOrNull { it.first == nextPageTriggerGesture }?.second
            ?: "Single tap"

        Box(modifier = Modifier.padding(vertical = 4.dp)) {
            OutlinedButton(
                onClick = { nextPageExpanded = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text(nextPageLabel, modifier = Modifier.weight(1f))
                Text(" ▼", color = Color.Gray)
            }
            DropdownMenu(
                expanded = nextPageExpanded,
                onDismissRequest = { nextPageExpanded = false }
            ) {
                nextPageOptions.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                label,
                                color = if (value == nextPageTriggerGesture) Color(0xFF4CAF50) else Color.White
                            )
                        },
                        onClick = {
                            nextPageExpanded = false
                            viewModel.setNextPageTriggerGesture(value)
                        }
                    )
                }
            }
        }

        FlagSwitchRow(
            label = "Show annotation dots",
            checked = showAnnotationDots,
            onCheckedChange = { viewModel.toggleAnnotationDots(it) }
        )

        FlagSwitchRow(
            label = "Show scene borders",
            checked = showSceneBorders,
            onCheckedChange = { viewModel.toggleSceneBorders(it) }
        )

        FlagSwitchRow(
            label = "Bubble text overlay (mock or OCR)",
            checked = bubbleTextOverlayEnabled,
            onCheckedChange = { viewModel.toggleBubbleTextOverlay(it) }
        )

        FlagSwitchRow(
            label = "Edit mode (reorder bubbles)",
            checked = editModeEnabled,
            onCheckedChange = { viewModel.toggleEditMode(it) }
        )

        FlagSwitchRow(
            label = "Landscape reader (Android)",
            checked = landscapeEnabled,
            onCheckedChange = { viewModel.toggleLandscape(it) }
        )

        FlagSwitchRow(
            label = "Expand: with distortion (Fill — covers whole screen)",
            checked = expandFillEnabled,
            onCheckedChange = { viewModel.toggleExpandFillEnabled(it) }
        )

        FlagSwitchRow(
            label = "Expand: without distortion (Fit — letterboxed)",
            checked = expandFitEnabled,
            onCheckedChange = { viewModel.toggleExpandFitEnabled(it) }
        )

        // Reset button — only meaningful when the current page actually has a
        // saved custom ordering. Hidden when the page is on its default order.
        if (pageOrdering != null) {
            TextButton(
                onClick = { viewModel.resetPageOrdering() },
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Text("Reset to default order", color = Color(0xFFF44336))
            }
        }

        // Annotation bar visibility mode
        Text(
            "Annotation bar visibility",
            color = Color.White,
            modifier = Modifier.padding(top = 8.dp)
        )
        val visibilityOptions = listOf(
            "on_double_tap" to "Show on double-tap",
            "always" to "Always show"
        )
        var visibilityExpanded by remember { mutableStateOf(false) }
        val visibilityLabel = visibilityOptions.firstOrNull { it.first == annotationBarVisibilityMode }?.second
            ?: "Show on double-tap"

        Box(modifier = Modifier.padding(vertical = 4.dp)) {
            OutlinedButton(
                onClick = { visibilityExpanded = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text(visibilityLabel, modifier = Modifier.weight(1f))
                Text(" \u25BC", color = Color.Gray)
            }
            DropdownMenu(
                expanded = visibilityExpanded,
                onDismissRequest = { visibilityExpanded = false }
            ) {
                visibilityOptions.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                label,
                                color = if (value == annotationBarVisibilityMode) Color(0xFF4CAF50) else Color.White
                            )
                        },
                        onClick = {
                            visibilityExpanded = false
                            viewModel.setAnnotationBarVisibilityMode(value)
                        }
                    )
                }
            }
        }

        // Annotation bar position
        Text(
            "Annotation bar position",
            color = Color.White,
            modifier = Modifier.padding(top = 8.dp)
        )
        val barPositionOptions = listOf(
            "bottom" to "Bottom (fixed)",
            "closest" to "Closest to bubble (top/bottom)",
            "spread" to "Spread around bubble"
        )
        var barPosExpanded by remember { mutableStateOf(false) }
        val currentPosLabel = barPositionOptions.firstOrNull { it.first == annotationBarPosition }?.second
            ?: "Bottom (fixed)"

        Box(modifier = Modifier.padding(vertical = 4.dp)) {
            OutlinedButton(
                onClick = { barPosExpanded = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text(currentPosLabel, modifier = Modifier.weight(1f))
                Text(" \u25BC", color = Color.Gray)
            }
            DropdownMenu(
                expanded = barPosExpanded,
                onDismissRequest = { barPosExpanded = false }
            ) {
                barPositionOptions.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                label,
                                color = if (value == annotationBarPosition) Color(0xFF4CAF50) else Color.White
                            )
                        },
                        onClick = {
                            barPosExpanded = false
                            viewModel.setAnnotationBarPosition(value)
                        }
                    )
                }
            }
        }

        HorizontalDivider(color = Color(0xFF333333), modifier = Modifier.padding(vertical = 8.dp))

        if (onAutoSyncToggled != null) {
            FlagSectionHeader("Sync")
            FlagSwitchRow(
                label = "Cloud sync every 10 seconds",
                checked = autoSyncEnabled,
                onCheckedChange = { onAutoSyncToggled(it) }
            )
            FlagSwitchRow(
                label = "Cross-device alignment pull",
                checked = cloudAlignEnabled,
                onCheckedChange = { viewModel.toggleCloudAlign(it) }
            )
            FlagNumberRow(
                label = "Alignment interval (seconds)",
                value = cloudAlignIntervalSec,
                onValueChange = { viewModel.setCloudAlignIntervalSec(it) },
            )
        }

        if (isDesktop) {
            HorizontalDivider(color = Color(0xFF333333), modifier = Modifier.padding(vertical = 8.dp))
            FlagSectionHeader("Desktop")
            FlagSwitchRow(
                label = "Space toggles annotation bar",
                checked = annotationOnSpace,
                onCheckedChange = { viewModel.toggleAnnotationOnSpace(it) }
            )
        }
    }
}

@Composable
private fun FlagSectionHeader(text: String) {
    Text(
        text = text,
        color = Color(0xFF4CAF50),
        fontSize = 16.sp,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun FlagSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.White)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF4CAF50),
                checkedTrackColor = Color(0xFF388E3C)
            )
        )
    }
}

@Composable
private fun FlagNumberRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color.White)
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                val digits = raw.filter { it.isDigit() }.take(5)
                text = digits
                digits.toIntOrNull()?.let(onValueChange)
            },
            modifier = Modifier.width(96.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF4CAF50),
                unfocusedBorderColor = Color(0xFF555555),
                cursorColor = Color(0xFF4CAF50),
            ),
        )
    }
}
