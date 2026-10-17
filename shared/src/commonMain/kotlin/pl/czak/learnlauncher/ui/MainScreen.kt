@file:OptIn(ExperimentalFoundationApi::class)

package pl.czak.learnlauncher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp
import pl.czak.learnlauncher.currentTimeMillis
import pl.czak.learnlauncher.domain.annotation.AnnotationOption
import pl.czak.learnlauncher.domain.annotation.DefaultAnnotationPool
import pl.czak.learnlauncher.ui.components.ImageFitRect
import pl.czak.learnlauncher.ui.components.labelColorArgb
import pl.czak.learnlauncher.ui.components.textColorOn
import pl.czak.learnlauncher.domain.model.DoubleTapAction
import pl.czak.learnlauncher.ui.components.AnnotationOverlay
import pl.czak.learnlauncher.ui.components.BubbleTagOverlay
import pl.czak.learnlauncher.ui.components.BubbleTextOverlay
import pl.czak.learnlauncher.ui.components.SceneBordersOverlay
import pl.czak.learnlauncher.ui.components.ChapterDropdown
import pl.czak.learnlauncher.ui.components.CropSelectionOverlay
import pl.czak.learnlauncher.ui.components.EditorBadgesOverlay
import pl.czak.learnlauncher.ui.components.SessionTimers
import pl.czak.learnlauncher.ui.components.TranslationOverlay
import pl.czak.learnlauncher.viewmodel.LauncherViewModel
import pl.czak.learnlauncher.viewmodel.Screen
import kotlin.math.abs

@Composable
fun MainScreen(
    viewModel: LauncherViewModel,
    onLaunchApp: ((String) -> Unit)? = null,
    onLoadApps: (() -> List<AppInfo>)? = null,
    onOpenHomeSettings: (() -> Unit)? = null
) {
    val currentImage by viewModel.currentImage.collectAsState()
    val imageBytes by viewModel.currentImageBytes.collectAsState()
    val canGoBack by viewModel.canGoBack.collectAsState()
    val canGoForward by viewModel.canGoForward.collectAsState()
    val canGoNextChapter by viewModel.canGoNextChapter.collectAsState()
    val canGoPreviousChapter by viewModel.canGoPreviousChapter.collectAsState()
    val currentChapterName by viewModel.currentChapterName.collectAsState()
    val chapterNames by viewModel.chapterNames.collectAsState()
    val doubleTapAction by viewModel.doubleTapAction.collectAsState()
    val isZoomed by viewModel.isZoomed.collectAsState()
    val showAnnotationDots by viewModel.showAnnotationDots.collectAsState()
    val annotations by viewModel.currentImageAnnotations.collectAsState()
    val scenes by viewModel.scenesForCurrentImage.collectAsState()
    val showSceneBorders by viewModel.showSceneBorders.collectAsState()
    val tokenRegions by viewModel.currentTokenRegions.collectAsState()
    val tokenAnnotations by viewModel.currentTokenAnnotations.collectAsState()
    val tokenTranslations by viewModel.currentTokenTranslations.collectAsState()
    val selectedTokenIndex by viewModel.selectedTokenIndex.collectAsState()
    val showTranslationOverlay by viewModel.showTranslationOverlay.collectAsState()
    val currentTranslation by viewModel.currentTranslation.collectAsState()
    val sessionElapsed by viewModel.sessionElapsed.collectAsState()
    val showAppsOverlay by viewModel.showAppsOverlay.collectAsState()
    val filteredApps by viewModel.apps.collectAsState()
    val conversationBoxes by viewModel.currentConversationBoxes.collectAsState()
    val highlightedBubble by viewModel.highlightedBubble.collectAsState()
    val annotationBarPosition by viewModel.annotationBarPosition.collectAsState()
    val annotationBarVisibilityMode by viewModel.annotationBarVisibilityMode.collectAsState()
    val hideAnnotationBarTrigger by viewModel.hideAnnotationBarTrigger.collectAsState()
    val showAnnotationBar by viewModel.annotationBarOpen.collectAsState()
    val annotationTriggerGesture by viewModel.annotationTriggerGesture.collectAsState()
    val nextPageTriggerGesture by viewModel.nextPageTriggerGesture.collectAsState()
    val pendingBubbleAction by viewModel.pendingBubbleAction.collectAsState()
    val pendingTokenAction by viewModel.pendingTokenAction.collectAsState()
    val annotationOptions by viewModel.currentAnnotationOptions.collectAsState()
    val annotationExpanded by viewModel.annotationExpanded.collectAsState()
    val expandedTokenOptions by viewModel.expandedTokenOptions.collectAsState()
    val freeAnnotationDialogOpen by viewModel.freeAnnotationDialogOpen.collectAsState()
    val editModeEnabled by viewModel.editModeEnabled.collectAsState()
    val editorSelection by viewModel.editorSelection.collectAsState()
    val expandMode by viewModel.expandMode.collectAsState()
    val expandFitEnabled by viewModel.expandFitEnabled.collectAsState()
    val expandFillEnabled by viewModel.expandFillEnabled.collectAsState()
    val isFocusMode by viewModel.isFocusMode.collectAsState()
    val focusSceneIndex by viewModel.focusSceneIndex.collectAsState()
    val focusManualRotation by viewModel.focusManualRotation.collectAsState()
    val bubbleTextOverlayEnabled by viewModel.bubbleTextOverlayEnabled.collectAsState()
    val isExpanded = expandMode != pl.czak.learnlauncher.viewmodel.ExpandMode.Off
    val cropSelectionInProgress by viewModel.cropSelectionInProgress.collectAsState()
    val cropSelectionFinalized by viewModel.cropSelectionFinalized.collectAsState()
    val cropAnnotationBarOpen by viewModel.cropAnnotationBarOpen.collectAsState()
    val cropFreeFormDialogOpen by viewModel.cropFreeFormDialogOpen.collectAsState()
    // Subscribe so visual order changes recompose this screen even when no
    // direct reader exists in MainScreen.
    @Suppress("UNUSED_VARIABLE")
    val displayOrderedBoxes by viewModel.displayOrderedBoxes.collectAsState()

    var imageViewSize by remember { mutableStateOf(IntSize.Zero) }
    // Raw inputs for imageFitRect — kept separate so the rect recomputes when
    // expandMode flips (Fill ⇄ Fit) without waiting for a layout-bounds change.
    var lastImgW by remember { mutableStateOf(0) }
    var lastImgH by remember { mutableStateOf(0) }
    var lastContainerW by remember { mutableStateOf(0f) }
    var lastContainerH by remember { mutableStateOf(0f) }
    val imageFitRect = remember(lastImgW, lastImgH, lastContainerW, lastContainerH, expandMode) {
        if (lastImgW <= 0 || lastImgH <= 0 || lastContainerW <= 0f || lastContainerH <= 0f) {
            ImageFitRect(0f, 0f, 1f, 1f)
        } else if (expandMode == pl.czak.learnlauncher.viewmodel.ExpandMode.Fill) {
            // FillBounds stretches the bitmap to fill the entire container — so
            // bubble overlays must map across the full container, no letterbox.
            ImageFitRect(0f, 0f, lastContainerW, lastContainerH)
        } else {
            ImageFitRect.compute(lastImgW, lastImgH, lastContainerW, lastContainerH)
        }
    }
    var appFilterQuery by remember { mutableStateOf("") }

    // Triple-tap detection for emergency exit
    var emergencyTapCount by remember { mutableIntStateOf(0) }
    var lastEmergencyTapTime by remember { mutableLongStateOf(0L) }

    // Load apps on first composition if callback available
    LaunchedEffect(onLoadApps) {
        onLoadApps?.let { loader ->
            viewModel.setApps(loader())
        }
    }

    // Observe double tap actions
    LaunchedEffect(doubleTapAction) {
        doubleTapAction?.let { action ->
            when (action) {
                is DoubleTapAction.AnnotateBubble -> viewModel.openAnnotationBarForBubble(action)
                is DoubleTapAction.AnnotateToken -> viewModel.openAnnotationBarForToken(action)
                else -> {}
            }
            viewModel.consumeDoubleTapAction()
        }
    }

    // Arrow-nav: in on_double_tap mode, hide the bar. In always mode the bar stays and
    // the effect below re-syncs the pending action to the new highlighted bubble.
    LaunchedEffect(hideAnnotationBarTrigger) {
        if (hideAnnotationBarTrigger > 0L && annotationBarVisibilityMode != "always") {
            viewModel.closeAnnotationBar()
        }
    }

    // Always-show mode: keep the bar visible for the currently highlighted bubble.
    LaunchedEffect(annotationBarVisibilityMode, highlightedBubble) {
        if (annotationBarVisibilityMode == "always") {
            val hb = highlightedBubble
            if (hb != null) {
                val idx = viewModel.getHighlightedBubbleIndex()
                if (idx >= 0) {
                    viewModel.openAnnotationBarForBubble(
                        DoubleTapAction.AnnotateBubble(
                            nx = hb.x + hb.width / 2f,
                            ny = hb.y + hb.height / 2f,
                            box = hb,
                            boxIndex = idx
                        )
                    )
                }
            } else {
                viewModel.closeAnnotationBar()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!isExpanded && !isFocusMode) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Page title
                Text(
                    text = currentImage?.title?.substringAfter(" - ", currentImage?.title ?: "") ?: "",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )

                // Explicit Exit Edit Mode button — visible only when in edit
                // mode. Clears editor selection and turns edit mode off (page-
                // nav gestures are disabled in edit mode, so this is the only
                // way to leave from the page screen).
                if (editModeEnabled) {
                    TextButton(
                        onClick = { viewModel.editorCancel() },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF7043))
                    ) { Text("Exit Edit Mode", fontSize = 12.sp) }
                }

                // Chapter dropdown
                if (chapterNames.isNotEmpty()) {
                    ChapterDropdown(
                        currentChapter = currentChapterName,
                        chapters = chapterNames,
                        onChapterSelected = { viewModel.goToChapter(it) }
                    )
                }
            }

            // Session timers
            SessionTimers(elapsed = sessionElapsed)
            } // end if (!isExpanded) — header + timers hidden in any expand mode

            // Image viewer with swipe detection
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { imageViewSize = it }
                    .pointerInput(isZoomed, imageFitRect, annotationTriggerGesture, nextPageTriggerGesture, editModeEnabled) {
                        detectTapGestures(
                            onDoubleTap = { offset ->
                                val nx = imageFitRect.toNormalizedX(offset.x, size.width.toFloat())
                                val ny = imageFitRect.toNormalizedY(offset.y, size.height.toFloat())
                                if (isZoomed) {
                                    viewModel.onZoomDoubleTapped(nx, ny)
                                } else {
                                    val hitIndex = viewModel.bubbleHitTest(nx, ny)
                                    if (hitIndex >= 0) {
                                        if (annotationTriggerGesture == "double_tap") {
                                            viewModel.onBubbleAnnotateTapped(nx, ny, hitIndex, "DOUBLE_TAP")
                                        }
                                    } else {
                                        if (nextPageTriggerGesture == "double_tap" && !editModeEnabled) {
                                            viewModel.onPageNavTapped(nx, ny, "DOUBLE_TAP")
                                        }
                                    }
                                }
                            },
                            onLongPress = { offset ->
                                val nx = imageFitRect.toNormalizedX(offset.x, size.width.toFloat())
                                val ny = imageFitRect.toNormalizedY(offset.y, size.height.toFloat())
                                if (isZoomed) {
                                    // In zoom view: long-press shows the bubble's full
                                    // TranslationOverlay (meaning + literal).
                                    viewModel.onZoomLongPressed(nx, ny)
                                } else if (!viewModel.onImageLongPressed(nx, ny)) {
                                    viewModel.currentScreen.value = Screen.Settings
                                }
                            },
                            onTap = { offset ->
                                val nx = imageFitRect.toNormalizedX(offset.x, size.width.toFloat())
                                val ny = imageFitRect.toNormalizedY(offset.y, size.height.toFloat())
                                if (isZoomed) {
                                    viewModel.onZoomTapped(nx, ny)
                                } else {
                                    val hitIndex = viewModel.bubbleHitTest(nx, ny)
                                    if (hitIndex >= 0) {
                                        if (annotationTriggerGesture == "single_tap") {
                                            viewModel.onBubbleAnnotateTapped(nx, ny, hitIndex, "SINGLE_TAP")
                                        }
                                    } else {
                                        if (nextPageTriggerGesture == "single_tap" && !editModeEnabled) {
                                            viewModel.onPageNavTapped(nx, ny, "SINGLE_TAP")
                                        }
                                    }
                                }
                            }
                        )
                    }
                    .pointerInput(isZoomed, showAnnotationBar, showAppsOverlay, editModeEnabled, cropAnnotationBarOpen, cropFreeFormDialogOpen, imageFitRect) {
                        if (!editModeEnabled) return@pointerInput
                        if (isZoomed || showAnnotationBar || showAppsOverlay) return@pointerInput
                        if (cropAnnotationBarOpen || cropFreeFormDialogOpen) return@pointerInput
                        detectDragGestures(
                            onDragStart = { offset ->
                                val nx = imageFitRect.toNormalizedX(offset.x, size.width.toFloat())
                                val ny = imageFitRect.toNormalizedY(offset.y, size.height.toFloat())
                                viewModel.onCropDragStart(nx, ny)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val nx = imageFitRect.toNormalizedX(change.position.x, size.width.toFloat())
                                val ny = imageFitRect.toNormalizedY(change.position.y, size.height.toFloat())
                                viewModel.onCropDragUpdate(nx, ny)
                            },
                            onDragEnd = { viewModel.onCropDragEnd() },
                            onDragCancel = { viewModel.cancelCropSelection() }
                        )
                    }
            ) {
                // Image display - platform-specific rendering via expect/actual.
                // Fill expand mode uses ContentScale.FillBounds so a tall comic page
                // covers a landscape screen end-to-end (with distortion). Default Fit
                // preserves aspect ratio (letterboxed).
                ImageDisplay(
                    imageBytes = imageBytes,
                    zoomedBubble = if (isZoomed) viewModel.zoomedBubble else null,
                    modifier = Modifier.fillMaxSize(),
                    onImageSizeKnown = { imgW, imgH, containerW, containerH ->
                        lastImgW = imgW; lastImgH = imgH
                        lastContainerW = containerW; lastContainerH = containerH
                    },
                    contentScale = if (expandMode == pl.czak.learnlauncher.viewmodel.ExpandMode.Fill)
                        androidx.compose.ui.layout.ContentScale.FillBounds
                    else
                        androidx.compose.ui.layout.ContentScale.Fit
                )

                // Annotation overlay (always show conversation box regions)
                if (showAnnotationDots || conversationBoxes.isNotEmpty() || highlightedBubble != null) {
                    AnnotationOverlay(
                        annotations = if (showAnnotationDots) annotations else emptyList(),
                        conversationBoxes = if (!isZoomed) conversationBoxes else emptyList(),
                        highlightedBubble = if (!isZoomed) highlightedBubble else null,
                        tokenAnnotations = if (isZoomed) tokenAnnotations else emptyList(),
                        tokenRegions = if (isZoomed) tokenRegions else emptyList(),
                        selectedTokenIndex = if (isZoomed) selectedTokenIndex else null,
                        isZoomMode = isZoomed,
                        fitRect = imageFitRect,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Automation markers: one tagged, inert overlay per bubble (testTag "bubble-<idx>")
                // so Maestro can tap a specific bubble by id. Falls through to the gesture handler.
                if (!isZoomed && conversationBoxes.isNotEmpty()) {
                    BubbleTagOverlay(
                        boxes = conversationBoxes,
                        fitRect = imageFitRect,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (showSceneBorders && !isZoomed && scenes.isNotEmpty()) {
                    SceneBordersOverlay(
                        scenes = scenes,
                        fitRect = imageFitRect,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Bubble-text overlay — Compose Text inside each bubble's bbox.
                // Uses ConversationBox.text when present (OCR'd chapters) or a
                // "Bubble N" mock fallback. Hidden in edit + zoom modes so we
                // don't fight the editor badges or the zoom-mode token overlay.
                if (bubbleTextOverlayEnabled && !isZoomed && !editModeEnabled && conversationBoxes.isNotEmpty()) {
                    BubbleTextOverlay(
                        boxes = conversationBoxes,
                        fitRect = imageFitRect,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Editor badges overlay — numbered circles for the in-progress
                // bubble reordering selection. Rendered alongside (not in place
                // of) the annotation overlay; gated behind edit mode so it
                // disappears entirely when the flag is off.
                if (editModeEnabled && !isZoomed) {
                    EditorBadgesOverlay(
                        boxes = conversationBoxes,
                        selection = editorSelection,
                        fitRect = imageFitRect,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Crop selection overlay — dashed rect during drag, solid once
                // finalized. Only visible in edit mode.
                if (editModeEnabled && !isZoomed && (cropSelectionInProgress != null || cropSelectionFinalized != null)) {
                    CropSelectionOverlay(
                        inProgress = cropSelectionInProgress,
                        finalized = cropSelectionFinalized,
                        fitRect = imageFitRect,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Translation overlay
                if (showTranslationOverlay) {
                    TranslationOverlay(
                        translation = currentTranslation,
                        onDismiss = { viewModel.hideTranslationOverlay() },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Zoom mode UI
                if (isZoomed) {
                    val bubbleText = viewModel.zoomedBubble?.text

                    // Clean replacement text panel at bottom
                    if (bubbleText != null) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                        ) {
                            // High-quality rendered text (replacement bubble)
                            Surface(
                                color = Color.White,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 4.dp)
                            ) {
                                Text(
                                    text = bubbleText,
                                    color = Color.Black,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                            // Selected token text + its translation (if any)
                            val selIdx = selectedTokenIndex
                            val selToken = if (selIdx != null) tokenRegions.getOrNull(selIdx) else null
                            val selTokenTgt = if (selIdx != null) tokenTranslations.getOrNull(selIdx)?.tgt else null
                            if (selToken?.text != null) {
                                Surface(
                                    color = Color(0xFF00BCD4),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                        .padding(bottom = 4.dp)
                                ) {
                                    Text(
                                        text = selToken.text ?: "",
                                        color = Color.White,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                                if (!selTokenTgt.isNullOrBlank()) {
                                    Surface(
                                        color = Color(0xFF004D5A),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp)
                                            .padding(bottom = 8.dp)
                                    ) {
                                        Text(
                                            text = selTokenTgt,
                                            color = Color(0xFFB2EBF2),
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Exit zoom button
                    TextButton(
                        onClick = { viewModel.exitZoom() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        Text("Exit Zoom", color = Color.White)
                    }
                }

                // Swipe overlay for vertical drag navigation. Disabled in edit mode
                // so the crop drag has the gesture surface.
                if (!isZoomed && !showAnnotationBar && !showAppsOverlay && !editModeEnabled) {
                    SwipeDetector(
                        onSwipeUp = { viewModel.nextPage() },
                        onSwipeDown = { viewModel.previousPage() },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Expand-to-max-reading-area controls (top-right overlay).
                // - Off       : two small entry buttons — ⛶ (Fit, no distortion)
                //               and ⤢ (Fill, distortion accepted — best in landscape
                //               on tall comic pages)
                // - Fit/Fill  : single ✕ close button (the only chrome visible)
                if (isExpanded && !isFocusMode) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .background(Color(0x99000000), shape = androidx.compose.foundation.shape.CircleShape)
                            .clickable {
                                viewModel.setExpandMode(pl.czak.learnlauncher.viewmodel.ExpandMode.Off)
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .zIndex(10f)
                    ) {
                        Text(text = "✕", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                } else if (expandFitEnabled || expandFillEnabled) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .zIndex(10f),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Fill (with distortion) entry — listed first since the
                        // user calls this the more useful default in landscape.
                        if (expandFillEnabled) {
                            Box(
                                modifier = Modifier
                                    .background(Color(0x99000000), shape = androidx.compose.foundation.shape.CircleShape)
                                    .clickable {
                                        viewModel.setExpandMode(pl.czak.learnlauncher.viewmodel.ExpandMode.Fill)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(text = "⤢", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        // Fit (no distortion) entry
                        if (expandFitEnabled) {
                            Box(
                                modifier = Modifier
                                    .background(Color(0x99000000), shape = androidx.compose.foundation.shape.CircleShape)
                                    .clickable {
                                        viewModel.setExpandMode(pl.czak.learnlauncher.viewmodel.ExpandMode.Fit)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(text = "⛶", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Bottom controls — hidden in expand mode + focus mode so the image is literal fullscreen
            if (!isExpanded && !isFocusMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (canGoPreviousChapter) {
                    TextButton(onClick = { viewModel.previousChapter() }) {
                        Text("< Ch", color = Color.White, fontSize = 12.sp)
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }

                // Emergency triple-tap zone
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            val now = currentTimeMillis()
                            if (now - lastEmergencyTapTime < 500L) {
                                emergencyTapCount++
                            } else {
                                emergencyTapCount = 1
                            }
                            lastEmergencyTapTime = now
                            if (emergencyTapCount >= 3) {
                                emergencyTapCount = 0
                                onOpenHomeSettings?.invoke()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when {
                            canGoBack && canGoForward -> "\u25BC swipe \u25B2"
                            canGoBack -> "\u25BC swipe"
                            canGoForward -> "swipe \u25B2"
                            else -> ""
                        },
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }

                // Focus-scenes entry — visible only when the current page has
                // scenes. Enters the read-only crop→auto-rotate→expand mode.
                if (scenes.isNotEmpty()) {
                    TextButton(
                        onClick = { viewModel.enterFocusScene(0) },
                        modifier = Modifier.testTagsAsResourceIds().testTag("focus-scenes-enter")
                    ) {
                        Text("Focus", color = Color(0xFF29B6F6), fontSize = 12.sp)
                    }
                }

                // See Apps button
                if (onLaunchApp != null) {
                    TextButton(onClick = { viewModel.showAppsOverlay() }) {
                        Text("Apps", color = Color(0xFF4CAF50), fontSize = 12.sp)
                    }
                }

                if (canGoNextChapter) {
                    TextButton(onClick = { viewModel.nextChapter() }) {
                        Text("Ch >", color = Color.White, fontSize = 12.sp)
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }
            }
            } // end if (!isExpanded) — bottom controls hidden in expand mode
        }

        // Annotation bar
        if (showAnnotationBar) {
            val onAnnotate = { label: String ->
                submitAnnotation(viewModel, label, pendingBubbleAction, pendingTokenAction)
                viewModel.closeAnnotationBar()
                viewModel.clearHighlightedBubble()
            }
            val onCancel = {
                viewModel.closeAnnotationBar()
                viewModel.clearHighlightedBubble()
            }

            val bubbleBox = pendingBubbleAction?.box ?: pendingTokenAction?.token

            when (annotationBarPosition) {
                "closest" -> {
                    // Place bar at top or bottom, whichever is closer to the bubble
                    val bubbleCenterY = bubbleBox?.let { it.y + it.height / 2f } ?: 1f
                    val alignment = if (bubbleCenterY < 0.5f) Alignment.TopCenter else Alignment.BottomCenter
                    AnnotationBarRow(
                        modifier = Modifier.align(alignment).fillMaxWidth(),
                        options = annotationOptions,
                        expanded = annotationExpanded,
                        expandedOptions = expandedTokenOptions + DefaultAnnotationPool,
                        onAnnotate = onAnnotate,
                        onWriteFree = { viewModel.openFreeAnnotationDialog() },
                        onToggleExpanded = { viewModel.toggleAnnotationExpanded() }
                    )
                }
                "spread" -> {
                    // Individual buttons spread around the bubble, clamped to bounds
                    val box = bubbleBox
                    if (box != null && imageViewSize.width > 0 && imageViewSize.height > 0) {
                        val containerW = imageViewSize.width.toFloat()
                        val containerH = imageViewSize.height.toFloat()

                        SpreadAnnotationButtons(
                            bubbleLeftPx = imageFitRect.toPixelX(box.x),
                            bubbleTopPx = imageFitRect.toPixelY(box.y),
                            bubbleRightPx = imageFitRect.toPixelX(box.x + box.width),
                            bubbleBottomPx = imageFitRect.toPixelY(box.y + box.height),
                            containerWidthPx = containerW,
                            containerHeightPx = containerH,
                            options = annotationOptions,
                            onAnnotate = onAnnotate,
                            onWriteFree = { viewModel.openFreeAnnotationDialog() },
                            onCancel = onCancel
                        )
                    } else {
                        AnnotationBarRow(
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                            options = annotationOptions,
                            expanded = annotationExpanded,
                            expandedOptions = expandedTokenOptions + DefaultAnnotationPool,
                            onAnnotate = onAnnotate,
                            onWriteFree = { viewModel.openFreeAnnotationDialog() },
                            onToggleExpanded = { viewModel.toggleAnnotationExpanded() }
                        )
                    }
                }
                else -> {
                    // "bottom" — default fixed at bottom
                    AnnotationBarRow(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                        options = annotationOptions,
                        expanded = annotationExpanded,
                        expandedOptions = expandedTokenOptions + DefaultAnnotationPool,
                        onAnnotate = onAnnotate,
                        onWriteFree = { viewModel.openFreeAnnotationDialog() },
                        onToggleExpanded = { viewModel.toggleAnnotationExpanded() }
                    )
                }
            }

            if (freeAnnotationDialogOpen) {
                FreeAnnotationDialog(
                    onSubmit = { text -> onAnnotate(text) },
                    onDismiss = { viewModel.closeFreeAnnotationDialog() }
                )
            }
        }

        // Crop annotation bar — strict 3-button (Bubble / Scene / Free Form)
        // shown after a crop drag finalizes. Mutex with bubble annotation bar
        // and editor bar.
        if (cropAnnotationBarOpen && !showAnnotationBar) {
            CropAnnotationBarRow(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                onPick = { label -> viewModel.saveCropAnnotation(label) },
                onWriteFree = { viewModel.openCropFreeFormDialog() },
                onCancel = { viewModel.cancelCropSelection() }
            )
            if (cropFreeFormDialogOpen) {
                CropFreeFormDialog(
                    onSubmit = { text -> viewModel.submitCropFreeForm(text) },
                    onDismiss = { viewModel.closeCropFreeFormDialog() }
                )
            }
        }

        // Editor bar — mutex with annotation bar and crop bar. Save is enabled
        // only when the selection is a complete permutation of the original
        // bubble list: every original index appears exactly once.
        if (editModeEnabled && !showAnnotationBar && !cropAnnotationBarOpen) {
            val total = conversationBoxes.size
            // Permutation: size matches, every value is in range, no duplicates.
            // Single set allocation (vs. comparing two sets).
            val saveEnabled = total > 0 &&
                editorSelection.size == total &&
                editorSelection.all { it in 0 until total } &&
                editorSelection.toHashSet().size == total
            EditorBarRow(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                selectedCount = editorSelection.size,
                totalCount = total,
                onUndo = { viewModel.editorUndo() },
                onClear = { viewModel.editorClear() },
                onCancel = { viewModel.editorCancel() },
                onSave = { viewModel.editorSave() },
                saveEnabled = saveEnabled,
            )
        }

        // Apps overlay
        if (showAppsOverlay && onLaunchApp != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xE6000000))
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Installed Apps", color = Color.White, fontSize = 18.sp)
                        TextButton(onClick = { viewModel.hideAppsOverlay() }) {
                            Text("Continue Reading", color = Color(0xFF4CAF50))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Filter input
                    OutlinedTextField(
                        value = appFilterQuery,
                        onValueChange = {
                            appFilterQuery = it
                            viewModel.filterApps(it)
                        },
                        placeholder = { Text("Filter apps...", color = Color.Gray) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF4CAF50),
                            unfocusedBorderColor = Color(0xFF555555)
                        ),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(filteredApps) { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            onLaunchApp(app.packageName)
                                            viewModel.hideAppsOverlay()
                                        },
                                        onLongClick = {
                                            viewModel.togglePinned(app.packageName)
                                        }
                                    )
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (app.isPinned) {
                                    Text(
                                        text = "📌",
                                        fontSize = 14.sp,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                }
                                Text(
                                    text = app.label,
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "open",
                                    color = Color(0xFF4CAF50),
                                    fontSize = 12.sp
                                )
                            }
                            HorizontalDivider(color = Color(0xFF333333))
                        }
                    }
                }
            }
        }

        // ── Scene-focus overlay (read-only) ───────────────────────────────
        // Full-screen: crops the page to the focused scene, auto-rotates to
        // best-fit (domain FocusTransform via libapp7_domain.so), fills the
        // screen. Drawn on top of the reader (covers chrome). Horizontal swipe
        // moves scene→scene; the rotate control applies a manual +90 override.
        if (isFocusMode) {
            val scene = scenes.getOrNull(focusSceneIndex)
            if (scene == null) {
                // Scene list changed out from under us — leave focus cleanly.
                LaunchedEffect(scenes.size) { viewModel.exitFocusScene() }
            } else {
                var focusBoxW by remember { mutableStateOf(0f) }
                var focusBoxH by remember { mutableStateOf(0f) }
                val density = LocalDensity.current
                val pageW = if (lastImgW > 0) lastImgW.toFloat() else focusBoxW
                val pageH = if (lastImgH > 0) lastImgH.toFloat() else focusBoxH
                val ft = remember(scene, pageW, pageH, focusBoxW, focusBoxH) {
                    pl.czak.learnlauncher.domain.model.FocusTransform.compute(
                        scene.boxX, scene.boxY, scene.boxWidth, scene.boxHeight,
                        pageW, pageH,
                        if (focusBoxW > 0f) focusBoxW else 1f,
                        if (focusBoxH > 0f) focusBoxH else 1f,
                    )
                }
                val displayedRotation = (((ft.rotationDeg + focusManualRotation) % 360) + 360) % 360

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .onSizeChanged {
                            focusBoxW = it.width.toFloat(); focusBoxH = it.height.toFloat()
                        }
                        .pointerInput(focusSceneIndex, scenes.size) {
                            var dx = 0f
                            var handled = false
                            detectDragGestures(
                                onDragStart = { dx = 0f; handled = false },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dx += amount.x
                                    if (!handled && abs(dx) > 80f) {
                                        handled = true
                                        if (dx < 0) viewModel.focusNextScene()
                                        else viewModel.focusPreviousScene()
                                    }
                                },
                                onDragEnd = {},
                                onDragCancel = {},
                            )
                        }
                        .testTagsAsResourceIds()
                        .testTag("scene-focus-root")
                ) {
                    val scenePxW = scene.boxWidth * pageW
                    val scenePxH = scene.boxHeight * pageH
                    if (scenePxW > 0f && scenePxH > 0f) {
                        // Size the cropped scene to (scene px * fit scale) and
                        // rotate it; the size preserves the scene aspect so
                        // FillBounds == Fit (no distortion) within that box.
                        val wDp = with(density) { (scenePxW * ft.scale).toDp() }
                        val hDp = with(density) { (scenePxH * ft.scale).toDp() }
                        ImageDisplay(
                            imageBytes = imageBytes,
                            zoomedBubble = pl.czak.learnlauncher.domain.model.ConversationBox(
                                scene.boxX, scene.boxY, scene.boxWidth, scene.boxHeight,
                                pl.czak.learnlauncher.domain.model.RegionType.CROP
                            ),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(wDp, hDp)
                                .graphicsLayer { rotationZ = displayedRotation.toFloat() },
                            onImageSizeKnown = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
                        )
                    }

                    // Top control bar: indicator, rotation label, rotate, exit.
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .background(Color(0x99000000))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${focusSceneIndex + 1}/${scenes.size}",
                            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("scene-focus-indicator"),
                        )
                        Text(
                            text = "${displayedRotation}°",
                            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("scene-focus-rotation"),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Text(
                                text = "⟳",
                                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .testTag("scene-focus-rotate")
                                    .clickable { viewModel.focusRotate() },
                            )
                            Text(
                                text = "✕",
                                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .testTag("scene-focus-exit")
                                    .clickable { viewModel.exitFocusScene() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeDetector(
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
    modifier: Modifier = Modifier
) {
    var totalDragY by remember { mutableFloatStateOf(0f) }
    var dragHandled by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        totalDragY = 0f
                        dragHandled = false
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        totalDragY += dragAmount.y
                        if (!dragHandled && abs(totalDragY) > 80f) {
                            dragHandled = true
                            if (totalDragY < 0) {
                                onSwipeUp()
                            } else {
                                onSwipeDown()
                            }
                        }
                    },
                    onDragEnd = {},
                    onDragCancel = {}
                )
            }
    )
}

/**
 * Annotation bar.
 *  - Collapsed: the tapped bubble's OCR-word suggestions ([options], scored) + two tiny trailing
 *    icons — ✎ free (custom note) and ⋯ others (toggle the extensive list).
 *  - Expanded: the full semantic pool ([expandedOptions]) under the primary row.
 * Selecting any option (word or semantic) closes the bar via [onAnnotate]. testTags are exposed as
 * resource-ids for automation. The standalone Cancel is dropped (only the two icons remain).
 */
@Composable
private fun AnnotationBarRow(
    modifier: Modifier = Modifier,
    options: List<AnnotationOption>,
    expanded: Boolean,
    expandedOptions: List<AnnotationOption>,
    onAnnotate: (String) -> Unit,
    onWriteFree: () -> Unit,
    onToggleExpanded: () -> Unit
) {
    Column(
        modifier = modifier
            .testTagsAsResourceIds()
            .testTag("annotation-bar")
            .background(Color(0xFF333333))
            .padding(8.dp)
    ) {
        // Primary row: OCR-word suggestions scroll horizontally; the two tiny icons stay PINNED
        // to the right so they're always reachable (long OCR tokens like "TATSUKIFUJIMOTO" used to
        // push ✎/⋯ off-screen entirely — unreachable for the user, not just automation).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                options.forEachIndexed { idx, opt ->
                    val bg = Color((opt.color ?: labelColorArgb(opt.label)).toInt())
                    Button(
                        onClick = { onAnnotate(opt.label) },
                        colors = ButtonDefaults.buttonColors(containerColor = bg),
                        modifier = Modifier.testTag("annotation-suggestion-$idx")
                    ) { Text(opt.display, color = textColorOn(bg)) }
                }
            }
            // ✎ free — write a custom annotation. Pinned (outside the scroll).
            Button(
                onClick = onWriteFree,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.testTag("annotation-free")
            ) { Text("✎", color = Color.White) }
            // ⋯ others — toggle the extensive list. Pinned (outside the scroll).
            Button(
                onClick = onToggleExpanded,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF555555)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.testTag("annotation-others")
            ) { Text("⋯", color = Color.White) }
        }

        // Expanded: the full semantic annotation pool.
        if (expanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState())
                    .testTag("annotation-bar-expanded"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                expandedOptions.forEach { opt ->
                    val bg = Color((opt.color ?: labelColorArgb(opt.label)).toInt())
                    Button(
                        onClick = { onAnnotate(opt.label) },
                        colors = ButtonDefaults.buttonColors(containerColor = bg),
                        modifier = Modifier.testTag("annotation-option-${opt.label}")
                    ) { Text(opt.display, color = textColorOn(bg)) }
                }
            }
        }
    }
}

/**
 * Bottom bar shown while edit mode is active and the annotation bar is not.
 * Mirrors [AnnotationBarRow]'s layout and styling so the two bars feel like
 * peers. Save is only clickable when the caller passes [saveEnabled]=true,
 * i.e. when the current selection is a complete permutation.
 */
@Composable
private fun EditorBarRow(
    modifier: Modifier = Modifier,
    selectedCount: Int,
    totalCount: Int,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean,
) {
    Row(
        modifier = modifier
            .background(Color(0xFF333333))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Selected: $selectedCount/$totalCount",
            color = Color.White,
            fontSize = 14.sp
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onUndo) {
                Text("Undo", color = Color.White)
            }
            TextButton(onClick = onClear) {
                Text("Clear", color = Color.White)
            }
            TextButton(onClick = onCancel) {
                Text("Cancel", color = Color.Gray)
            }
            Button(
                onClick = onSave,
                enabled = saveEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50),
                    disabledContainerColor = Color(0xFF555555),
                    disabledContentColor = Color(0xFF999999),
                )
            ) {
                Text("Save", color = if (saveEnabled) Color.White else Color(0xFF999999))
            }
        }
    }
}

/**
 * Places 4 annotation buttons spread around the bubble.
 *
 * Each button has a preferred side (above, below, left, right).
 * Placement rules:
 *  1. Position on preferred side, just outside the bubble edge + margin.
 *  2. Clamp to container bounds so nothing goes off-screen.
 *  3. If clamping pushes a button to overlap the bubble, relocate it to the
 *     side with the most available space instead.
 *  4. When multiple buttons end up on the same side, stack them so they
 *     don't overlap each other.
 */
@Composable
private fun SpreadAnnotationButtons(
    bubbleLeftPx: Float,
    bubbleTopPx: Float,
    bubbleRightPx: Float,
    bubbleBottomPx: Float,
    containerWidthPx: Float,
    containerHeightPx: Float,
    options: List<AnnotationOption>,
    onAnnotate: (String) -> Unit,
    onWriteFree: () -> Unit,
    onCancel: () -> Unit
) {
    if (options.isEmpty()) return

    val density = androidx.compose.ui.platform.LocalDensity.current
    val gap = with(density) { 4.dp.toPx() }
    val btnH = with(density) { 36.dp.toPx() }
    val btnW = with(density) { 90.dp.toPx() }
    val smallBtnW = with(density) { 40.dp.toPx() }

    val midX = (bubbleLeftPx + bubbleRightPx) / 2f
    val midY = (bubbleTopPx + bubbleBottomPx) / 2f

    // Available space on each side
    val spaceAbove = bubbleTopPx
    val spaceBelow = containerHeightPx - bubbleBottomPx
    val spaceLeft = bubbleLeftPx
    val spaceRight = containerWidthPx - bubbleRightPx

    // Each button: (label, action, preferred side, estimated width)
    // Preferred: above, below, left, right
    data class BtnSpec(val id: String, val preferredSide: Int, val w: Float)
    val above = 0; val below = 1; val left = 2; val right = 3

    // Cancel is pinned separately to the top-right of the container (see below).
    // The N primary buttons fight for the four sides around the bubble; preferred
    // sides cycle above/below/left/right so options spread out instead of stacking.
    val sideCycle = intArrayOf(above, below, left, right)
    val specs = options.mapIndexed { i, _ ->
        val side = sideCycle[i % sideCycle.size]
        val w = if (side == above || side == below) btnW else smallBtnW
        BtnSpec(options[i].label, side, w)
    }

    // Minimum space needed for a button on each side
    val minVertical = btnH + gap
    val minHorizontal = smallBtnW + gap

    // Assign each button to a side. If preferred side has no room, pick best alternative.
    val sides = arrayOf(
        spaceAbove to minVertical,
        spaceBelow to minVertical,
        spaceLeft to minHorizontal,
        spaceRight to minHorizontal
    )

    // Track how many buttons assigned to each side for stacking
    val sideSlots = IntArray(4) // count per side
    val assignments = IntArray(specs.size) // which side each button got

    for (i in specs.indices) {
        val pref = specs[i].preferredSide
        val (space, needed) = sides[pref]
        if (space >= needed) {
            assignments[i] = pref
        } else {
            // Pick side with most space (prefer vertical for wider buttons)
            val ranked = listOf(above, below, left, right)
                .sortedByDescending { sides[it].first - sides[it].second }
            assignments[i] = ranked.first { sides[it].first >= sides[it].second || it == ranked.last() }
        }
        sideSlots[assignments[i]]++
    }

    // Now compute positions. For each side, stack buttons with gap between them.
    val sideCounters = IntArray(4) // current slot index per side
    val positions = Array(specs.size) { floatArrayOf(0f, 0f) } // x, y per button

    for (i in specs.indices) {
        val side = assignments[i]
        val slot = sideCounters[side]
        val total = sideSlots[side]
        val w = specs[i].w

        when (side) {
            above -> {
                // Stack upward from bubble top edge
                val y = (bubbleTopPx - gap - btnH * (slot + 1) - gap * slot)
                    .coerceIn(0f, containerHeightPx - btnH)
                val x = (midX - w / 2f).coerceIn(0f, containerWidthPx - w)
                positions[i] = floatArrayOf(x, y)
            }
            below -> {
                // Stack downward from bubble bottom edge
                val y = (bubbleBottomPx + gap + (btnH + gap) * slot)
                    .coerceIn(0f, containerHeightPx - btnH)
                val x = (midX - w / 2f).coerceIn(0f, containerWidthPx - w)
                positions[i] = floatArrayOf(x, y)
            }
            left -> {
                // Stack leftward from bubble left edge
                val x = (bubbleLeftPx - gap - w * (slot + 1) - gap * slot)
                    .coerceIn(0f, containerWidthPx - w)
                // Vertically distribute if multiple on this side
                val yOffset = if (total > 1) (slot - (total - 1) / 2f) * (btnH + gap) else 0f
                val y = (midY - btnH / 2f + yOffset).coerceIn(0f, containerHeightPx - btnH)
                positions[i] = floatArrayOf(x, y)
            }
            right -> {
                // Stack rightward from bubble right edge
                val x = (bubbleRightPx + gap + (w + gap) * slot)
                    .coerceIn(0f, containerWidthPx - w)
                val yOffset = if (total > 1) (slot - (total - 1) / 2f) * (btnH + gap) else 0f
                val y = (midY - btnH / 2f + yOffset).coerceIn(0f, containerHeightPx - btnH)
                positions[i] = floatArrayOf(x, y)
            }
        }
        sideCounters[side]++
    }

    // Render N primary buttons
    for (i in specs.indices) {
        val xDp = with(density) { positions[i][0].toDp() }
        val yDp = with(density) { positions[i][1].toDp() }
        val opt = options[i]
        val color = Color((opt.color ?: labelColorArgb(opt.label)).toInt())
        Button(
            onClick = { onAnnotate(opt.label) },
            colors = ButtonDefaults.buttonColors(containerColor = color),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.offset(x = xDp, y = yDp)
        ) { Text(opt.display, color = textColorOn(color), fontSize = 12.sp) }
    }

    // Cancel pinned to the top-right of the image container so it is always
    // reachable regardless of bubble placement. zIndex guarantees it draws
    // above any primary button that happens to be clamped to the same spot.
    val cancelPad = with(density) { 4.dp.toPx() }
    val cancelXDp = with(density) { (containerWidthPx - smallBtnW - cancelPad).toDp() }
    val cancelYDp = with(density) { cancelPad.toDp() }
    Button(
        onClick = onCancel,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC333333)),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier
            .offset(x = cancelXDp, y = cancelYDp)
            .zIndex(1f)
    ) { Text("✕", color = Color.White, fontSize = 14.sp) }

    // Free-annotation pencil pinned to top-left, mirror of Cancel.
    val freeXDp = with(density) { cancelPad.toDp() }
    val freeYDp = with(density) { cancelPad.toDp() }
    Button(
        onClick = onWriteFree,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier
            .offset(x = freeXDp, y = freeYDp)
            .zIndex(1f)
    ) { Text("✎", color = Color.White, fontSize = 14.sp) }
}

@Composable
private fun FreeAnnotationDialog(
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val canSave = text.isNotBlank()

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Write annotation", color = Color.White) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(500) },
                placeholder = { Text("Write your note...", color = Color.Gray) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (canSave) onSubmit(text.trim()) }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF2196F3),
                    unfocusedBorderColor = Color(0xFF555555)
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (canSave) onSubmit(text.trim()) },
                enabled = canSave
            ) { Text("Save", color = if (canSave) Color(0xFF2196F3) else Color.Gray) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) }
        },
        containerColor = Color(0xFF222222)
    )
}

private fun submitAnnotation(
    viewModel: LauncherViewModel,
    label: String,
    bubbleAction: DoubleTapAction.AnnotateBubble?,
    tokenAction: DoubleTapAction.AnnotateToken?
) {
    tokenAction?.let {
        viewModel.saveTokenAnnotation(it.token, it.parentBubbleIndex, it.tokenIndex, label, it.nx, it.ny)
        return
    }
    bubbleAction?.let {
        viewModel.saveAnnotation(it.box, it.boxIndex, label, it.nx, it.ny)
    }
}

@Composable
private fun CropAnnotationBarRow(
    modifier: Modifier = Modifier,
    onPick: (label: String) -> Unit,
    onWriteFree: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = modifier
            .background(Color(0xFF333333))
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Button(
            onClick = { onPick("bubble") },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFEB3B))
        ) { Text("Bubble", color = Color.Black) }

        Button(
            onClick = { onPick("scene") },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0))
        ) { Text("Scene", color = Color.White) }

        Button(
            onClick = onWriteFree,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
        ) { Text("Free Form", color = Color.White) }

        TextButton(onClick = onCancel) { Text("Cancel", color = Color.Gray) }
    }
}

@Composable
private fun CropFreeFormDialog(
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val canSave = text.isNotBlank()

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crop annotation", color = Color.White) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(500) },
                placeholder = { Text("Label this crop...", color = Color.Gray) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF2196F3),
                    unfocusedBorderColor = Color(0xFF555555)
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (canSave) onSubmit(text.trim()) },
                enabled = canSave
            ) { Text("Save", color = if (canSave) Color(0xFF2196F3) else Color.Gray) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) }
        },
        containerColor = Color(0xFF222222)
    )
}

@Composable
expect fun ImageDisplay(
    imageBytes: ByteArray?,
    zoomedBubble: pl.czak.learnlauncher.domain.model.ConversationBox?,
    modifier: Modifier,
    onImageSizeKnown: ((imgWidth: Int, imgHeight: Int, containerWidth: Float, containerHeight: Float) -> Unit)?,
    contentScale: androidx.compose.ui.layout.ContentScale = androidx.compose.ui.layout.ContentScale.Fit
)
