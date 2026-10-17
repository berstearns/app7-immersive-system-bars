package pl.czak.learnlauncher.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.czak.learnlauncher.currentTimeMillis
import pl.czak.learnlauncher.data.model.ImageItem
import pl.czak.learnlauncher.data.model.RegionTranslation
import pl.czak.learnlauncher.data.model.TokenTranslation
import pl.czak.learnlauncher.data.repository.AnnotationRepository
import pl.czak.learnlauncher.data.repository.LearnerDataRepository
import pl.czak.learnlauncher.data.repository.PageBubbleOrderingRepository
import pl.czak.learnlauncher.data.repository.SessionRepository
import pl.czak.learnlauncher.data.repository.TokenRegionRepository
import pl.czak.learnlauncher.data.repository.TranslationRepository
import pl.czak.learnlauncher.data.source.ImageDataSource
import pl.czak.learnlauncher.domain.annotation.AnnotationContext
import pl.czak.learnlauncher.domain.annotation.AnnotationDecisionFunction
import pl.czak.learnlauncher.domain.annotation.AnnotationOption
import pl.czak.learnlauncher.domain.annotation.NativeAnnotationDecisionFunction
import pl.czak.learnlauncher.domain.annotation.RandomSamplingDecisionFunction
import pl.czak.learnlauncher.domain.annotation.TokenSuggestionScorer
import pl.czak.learnlauncher.domain.annotation.ConfidenceTokenScorer
import pl.czak.learnlauncher.domain.model.ConversationBox
import pl.czak.learnlauncher.domain.model.CropSelection
import pl.czak.learnlauncher.domain.model.DoubleTapAction
import pl.czak.learnlauncher.domain.model.PageBubbleOrdering
import pl.czak.learnlauncher.domain.model.RegionAnnotation
import pl.czak.learnlauncher.domain.model.RegionType
import pl.czak.learnlauncher.domain.navigation.ImageSequenceNavigator
import pl.czak.learnlauncher.domain.session.CompletedSession
import pl.czak.learnlauncher.domain.session.SessionElapsed
import pl.czak.learnlauncher.domain.session.SessionTimeTracker
import pl.czak.learnlauncher.ui.AppInfo

sealed class ZoomModeState {
    data object Viewing : ZoomModeState()
    data class AnnotatingToken(val token: ConversationBox) : ZoomModeState()
    data class ShowingTranslation(val translation: RegionTranslation?) : ZoomModeState()
}

sealed class Screen {
    data object Login : Screen()
    data object Main : Screen()
    data object Settings : Screen()
    data object Flags : Screen()
    data object Chat : Screen()
    data object AnnotationLog : Screen()
    // F15: the annotation-detail view (bubble crop + label + reference-to-source button).
    data class AnnotationDetail(val annotation: RegionAnnotation) : Screen()
}

enum class ExpandMode { Off, Fit, Fill }

class LauncherViewModel(
    private var imageDataSource: ImageDataSource,
    private var imageNavigator: ImageSequenceNavigator,
    private val sessionTimeTracker: SessionTimeTracker,
    val annotationRepository: AnnotationRepository,
    val sessionRepository: SessionRepository,
    private val learnerDataRepository: LearnerDataRepository,
    private val tokenRegionRepository: TokenRegionRepository,
    private val translationRepository: TranslationRepository,
    private val settingsStore: SettingsStore,
    private val pageBubbleOrderingRepository: PageBubbleOrderingRepository,
    // Phase 3b migration: native first; falls back to Kotlin RNG if the .so
    // isn't loadable (e.g. iOS).  feedback_haskell_domain_done_gate.
    private val annotationDecider: AnnotationDecisionFunction = pickAnnotationDecider()
) : ViewModel() {

    private var lastTrackedChapter: String? = null
    private var currentComicId: String? = null

    /**
     * Releases native-domain handles owned by the trackers.  Call from
     * `MainActivity.onDestroy` / desktop app exit.  Harmless to call
     * twice — each tracker's `close()` is idempotent.
     */
    fun closeNativeHandles() {
        runCatching { sessionTimeTracker.close() }
        runCatching { imageNavigator.close() }
    }

    private fun positionKey(): String =
        currentComicId?.let { "image_navigator_position__$it" } ?: "image_navigator_position"

    // Navigation
    val currentScreen = MutableStateFlow<Screen>(Screen.Main)

    // Image state
    private val _currentImage = MutableStateFlow<ImageItem?>(null)
    val currentImage: StateFlow<ImageItem?> = _currentImage

    private val _currentImageBytes = MutableStateFlow<ByteArray?>(null)
    val currentImageBytes: StateFlow<ByteArray?> = _currentImageBytes

    // Navigation state
    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack

    private val _canGoForward = MutableStateFlow(false)
    val canGoForward: StateFlow<Boolean> = _canGoForward

    private val _canGoNextChapter = MutableStateFlow(false)
    val canGoNextChapter: StateFlow<Boolean> = _canGoNextChapter

    private val _canGoPreviousChapter = MutableStateFlow(false)
    val canGoPreviousChapter: StateFlow<Boolean> = _canGoPreviousChapter

    private val _currentChapterName = MutableStateFlow("")
    val currentChapterName: StateFlow<String> = _currentChapterName

    private val _chapterNames = MutableStateFlow<List<String>>(emptyList())
    val chapterNames: StateFlow<List<String>> = _chapterNames

    // Annotation state — "off" | "single_tap" | "double_tap"
    private val _annotationTriggerGesture = MutableStateFlow("single_tap")
    val annotationTriggerGesture: StateFlow<String> = _annotationTriggerGesture

    // Page-nav state — "off" | "single_tap" | "double_tap". Fires on a page tap that
    // misses every bubble. Decoupled from annotation so a user can mix gestures
    // (e.g. double-tap to annotate, single-tap to advance).
    private val _nextPageTriggerGesture = MutableStateFlow("single_tap")
    val nextPageTriggerGesture: StateFlow<String> = _nextPageTriggerGesture

    private val _showAnnotationDots = MutableStateFlow(true)
    val showAnnotationDots: StateFlow<Boolean> = _showAnnotationDots

    private val _showSceneBorders = MutableStateFlow(true)
    val showSceneBorders: StateFlow<Boolean> = _showSceneBorders

    private val _annotationBarPosition = MutableStateFlow("bottom")
    val annotationBarPosition: StateFlow<String> = _annotationBarPosition

    // Catalog filter facets (Settings → Comic Downloads). Sets of canonical
    // values: series slugs / Language enum names / SizeBucket enum names.
    // Persisted comma-joined under the catalog_filter_* keys via the same
    // SettingsStore mechanism as the other toggles; empty set = "all" in that
    // dimension (AND across dimensions, OR within one).
    private val _catalogFilterSeries = MutableStateFlow<Set<String>>(emptySet())
    val catalogFilterSeries: StateFlow<Set<String>> = _catalogFilterSeries

    private val _catalogFilterLanguage = MutableStateFlow<Set<String>>(emptySet())
    val catalogFilterLanguage: StateFlow<Set<String>> = _catalogFilterLanguage

    private val _catalogFilterSize = MutableStateFlow<Set<String>>(emptySet())
    val catalogFilterSize: StateFlow<Set<String>> = _catalogFilterSize

    // "always" | "on_double_tap" — controls when the annotation bar is visible.
    private val _annotationBarVisibilityMode = MutableStateFlow("on_double_tap")
    val annotationBarVisibilityMode: StateFlow<String> = _annotationBarVisibilityMode

    // Bumped to a fresh timestamp when arrow navigation should hide the bar (on_double_tap mode).
    private val _hideAnnotationBarTrigger = MutableStateFlow(0L)
    val hideAnnotationBarTrigger: StateFlow<Long> = _hideAnnotationBarTrigger

    // Desktop-only: Spacebar opens the annotation bar for the currently highlighted bubble.
    private val _annotationOnSpace = MutableStateFlow(false)
    val annotationOnSpace: StateFlow<Boolean> = _annotationOnSpace

    private val _doubleTapAction = MutableStateFlow<DoubleTapAction?>(null)
    val doubleTapAction: StateFlow<DoubleTapAction?> = _doubleTapAction

    // Authoritative annotation bar state. Promoted from MainScreen so any entry point
    // (UI, keyboard) can observe and toggle it.
    private val _annotationBarOpen = MutableStateFlow(false)
    val annotationBarOpen: StateFlow<Boolean> = _annotationBarOpen

    private val _pendingBubbleAction = MutableStateFlow<DoubleTapAction.AnnotateBubble?>(null)
    val pendingBubbleAction: StateFlow<DoubleTapAction.AnnotateBubble?> = _pendingBubbleAction

    private val _pendingTokenAction = MutableStateFlow<DoubleTapAction.AnnotateToken?>(null)
    val pendingTokenAction: StateFlow<DoubleTapAction.AnnotateToken?> = _pendingTokenAction

    private val _currentAnnotationOptions = MutableStateFlow<List<AnnotationOption>>(emptyList())
    val currentAnnotationOptions: StateFlow<List<AnnotationOption>> = _currentAnnotationOptions

    // Primary bar = the tapped bubble's top OCR words (use case: tap to save a specific word),
    // chosen by the scorer seam (v0 = confidence). Falls back to the semantic decider when the
    // bubble has no tokens.
    private val tokenScorer: TokenSuggestionScorer = ConfidenceTokenScorer()
    private val tokenSuggestionK = 5

    // "others" expansion: ALL of the bubble's tokens, then the semantic pool after.
    private val _expandedTokenOptions = MutableStateFlow<List<AnnotationOption>>(emptyList())
    val expandedTokenOptions: StateFlow<List<AnnotationOption>> = _expandedTokenOptions

    private val _annotationExpanded = MutableStateFlow(false)
    val annotationExpanded: StateFlow<Boolean> = _annotationExpanded
    fun toggleAnnotationExpanded() { _annotationExpanded.value = !_annotationExpanded.value }

    private val _freeAnnotationDialogOpen = MutableStateFlow(false)
    val freeAnnotationDialogOpen: StateFlow<Boolean> = _freeAnnotationDialogOpen

    private val _currentImageAnnotations = MutableStateFlow<List<Pair<ConversationBox, String>>>(emptyList())
    val currentImageAnnotations: StateFlow<List<Pair<ConversationBox, String>>> = _currentImageAnnotations

    private val _scenesForCurrentImage = MutableStateFlow<List<RegionAnnotation>>(emptyList())
    val scenesForCurrentImage: StateFlow<List<RegionAnnotation>> = _scenesForCurrentImage

    // Conversation boxes for current image (all regions)
    private val _currentConversationBoxes = MutableStateFlow<List<ConversationBox>>(emptyList())
    val currentConversationBoxes: StateFlow<List<ConversationBox>> = _currentConversationBoxes

    // Per-user, per-page bubble reordering ("edit mode")
    private val _editModeEnabled = MutableStateFlow(false)
    val editModeEnabled: StateFlow<Boolean> = _editModeEnabled

    // Landscape rotation toggle — persisted setting (Android honors via requestedOrientation).
    private val _landscapeEnabled = MutableStateFlow(false)
    val landscapeEnabled: StateFlow<Boolean> = _landscapeEnabled

    // Expand-to-max-reading-area — hides header row + session timers + bottom controls.
    // Session-only (not persisted): defaults to Off on every app launch.
    //   Off  — normal reader (chrome visible)
    //   Fit  — fullscreen, aspect preserved (letterboxed)
    //   Fill — fullscreen, stretched to cover (distortion accepted — useful in
    //          landscape on tall comic pages where Fit would leave huge bars)
    private val _expandMode = MutableStateFlow(ExpandMode.Off)
    val expandMode: StateFlow<ExpandMode> = _expandMode

    // Which expand entry-buttons are exposed in the reader overlay.
    // Both flags persisted (Settings). Defaults: Fill on (user calls it the
    // useful default on tall comic pages), Fit also on for discoverability.
    private val _expandFitEnabled = MutableStateFlow(true)
    val expandFitEnabled: StateFlow<Boolean> = _expandFitEnabled

    private val _expandFillEnabled = MutableStateFlow(true)
    val expandFillEnabled: StateFlow<Boolean> = _expandFillEnabled

    // ── Scene-focus reading mode (read-only) ──────────────────────────
    // Crops the page to one scene (CROP annotation, label='scene'),
    // auto-rotates to best-fit (domain FocusTransform), and hides the reader
    // chrome so a single scene fills the screen. Chrome hiding is gated purely
    // by isFocusMode (NOT by expandMode) so it can never leak into normal
    // navigation. Session-only.
    private val _isFocusMode = MutableStateFlow(false)
    val isFocusMode: StateFlow<Boolean> = _isFocusMode

    // Index into scenesForCurrentImage (creation order) of the focused scene.
    private val _focusSceneIndex = MutableStateFlow(0)
    val focusSceneIndex: StateFlow<Int> = _focusSceneIndex

    // Manual rotation override, added ON TOP of the domain auto-rotation, in
    // {0,90,180,270}. The rotate button cycles +90; reset on scene change.
    private val _focusManualRotation = MutableStateFlow(0)
    val focusManualRotation: StateFlow<Int> = _focusManualRotation

    // Bubble-text overlay (Compose Text drawn inside each bubble's bbox).
    // Falls back to "Bubble N" mock for un-OCR'd chapters; uses real OCR text
    // (ConversationBox.text) when present. Persisted, default ON.
    // Cross-device alignment pull (see feature spec
    // `app7-ideas-for-later/cross-device-learner-records-alignment-pull.md`).
    // Two independent settings: on/off + numeric interval (seconds, clamped
    // to [10, 3600] inside the setter). Defaults to enabled, 60 s.
    private val _cloudAlignEnabled = MutableStateFlow(true)
    val cloudAlignEnabled: StateFlow<Boolean> = _cloudAlignEnabled

    private val _cloudAlignIntervalSec = MutableStateFlow(60)
    val cloudAlignIntervalSec: StateFlow<Int> = _cloudAlignIntervalSec

    private val _bubbleTextOverlayEnabled = MutableStateFlow(true)
    val bubbleTextOverlayEnabled: StateFlow<Boolean> = _bubbleTextOverlayEnabled

    // Crop-region annotation (edit-mode drag-to-select)
    private val _cropSelectionInProgress = MutableStateFlow<CropSelection?>(null)
    val cropSelectionInProgress: StateFlow<CropSelection?> = _cropSelectionInProgress

    private val _cropSelectionFinalized = MutableStateFlow<CropSelection?>(null)
    val cropSelectionFinalized: StateFlow<CropSelection?> = _cropSelectionFinalized

    private val _cropAnnotationBarOpen = MutableStateFlow(false)
    val cropAnnotationBarOpen: StateFlow<Boolean> = _cropAnnotationBarOpen

    private val _cropFreeFormDialogOpen = MutableStateFlow(false)
    val cropFreeFormDialogOpen: StateFlow<Boolean> = _cropFreeFormDialogOpen

    // Original indices, in selection order
    private val _editorSelection = MutableStateFlow<List<Int>>(emptyList())
    val editorSelection: StateFlow<List<Int>> = _editorSelection

    private val _pageOrdering = MutableStateFlow<PageBubbleOrdering?>(null)
    val pageOrdering: StateFlow<PageBubbleOrdering?> = _pageOrdering

    // Boxes in display order: applies _pageOrdering permutation if present, else original order.
    val displayOrderedBoxes: StateFlow<List<ConversationBox>> =
        combine(_currentConversationBoxes, _pageOrdering) { boxes, ordering ->
            applyPermutation(boxes, ordering)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Currently highlighted bubble (yellow highlight on double-tap)
    private val _highlightedBubble = MutableStateFlow<ConversationBox?>(null)
    val highlightedBubble: StateFlow<ConversationBox?> = _highlightedBubble

    // Zoom state
    private val _isZoomed = MutableStateFlow(false)
    val isZoomed: StateFlow<Boolean> = _isZoomed

    private val _currentTokenRegions = MutableStateFlow<List<ConversationBox>>(emptyList())
    val currentTokenRegions: StateFlow<List<ConversationBox>> = _currentTokenRegions

    private val _currentTokenAnnotations = MutableStateFlow<List<Pair<ConversationBox, String>>>(emptyList())
    val currentTokenAnnotations: StateFlow<List<Pair<ConversationBox, String>>> = _currentTokenAnnotations

    // Per-token translations for the zoomed bubble (parallel-indexed to _currentTokenRegions).
    // Populated from the active RegionTranslation.tokenTranslations when zoom is entered.
    private val _currentTokenTranslations = MutableStateFlow<List<TokenTranslation>>(emptyList())
    val currentTokenTranslations: StateFlow<List<TokenTranslation>> = _currentTokenTranslations

    private var zoomedBubbleIndex: Int? = null
    private var zoomedBubbleBox: ConversationBox? = null

    // Selected token in zoom mode (highlighted on tap)
    private val _selectedTokenIndex = MutableStateFlow<Int?>(null)
    val selectedTokenIndex: StateFlow<Int?> = _selectedTokenIndex

    private val _zoomModeState = MutableStateFlow<ZoomModeState>(ZoomModeState.Viewing)
    val zoomModeState: StateFlow<ZoomModeState> = _zoomModeState

    // Translation
    private val _showTranslationOverlay = MutableStateFlow(false)
    val showTranslationOverlay: StateFlow<Boolean> = _showTranslationOverlay

    private val _currentTranslation = MutableStateFlow<RegionTranslation?>(null)
    val currentTranslation: StateFlow<RegionTranslation?> = _currentTranslation

    // Session timer
    private val _sessionElapsed = MutableStateFlow(SessionElapsed(null, null, null))
    val sessionElapsed: StateFlow<SessionElapsed> = _sessionElapsed

    // App list
    private var _allApps: List<AppInfo> = emptyList()
    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps
    private val _hiddenApps: MutableSet<String> = mutableSetOf()
    private val _pinnedApps: MutableSet<String> = mutableSetOf()
    private var _currentQuery: String = ""

    // Show apps overlay
    private val _showAppsOverlay = MutableStateFlow(false)
    val showAppsOverlay: StateFlow<Boolean> = _showAppsOverlay

    init {
        loadSettings()
        loadHiddenApps()
        loadPinnedApps()
        startSessionTimer()

        // Seed translations from the constructor-passed data source so
        // desktop / initial-load paths that don't go through switchAsset
        // still get per-page translations available on first lookup.
        translationRepository.seedFromComic(imageDataSource.loadTranslatedPagesIfAny())

        imageNavigator.initialize(imageDataSource.getImageItems())
        _chapterNames.value = imageNavigator.getChapterNames()

        restoreSavedPositionOrAdvance()
    }

    private fun restoreSavedPositionOrAdvance() {
        val savedIndex = settingsStore.getInt(positionKey(), -1)
        if (savedIndex >= 0) {
            val image = imageNavigator.restorePosition(savedIndex)
            if (image != null) {
                _currentImage.value = image
                _currentImageBytes.value = imageDataSource.loadImageBytes(image.filename, image.chapter)
                updateNavigationState()
                viewModelScope.launch { updateAnnotationsForCurrentImage() }
                return
            }
        }
        nextPage()
    }

    private fun startSessionTimer() {
        viewModelScope.launch {
            while (true) {
                delay(1000)
                _sessionElapsed.value = sessionTimeTracker.getElapsedMs()
            }
        }
    }

    private fun loadSettings() {
        val storedGesture = settingsStore.getString("annotation_trigger_gesture", null)
        _annotationTriggerGesture.value = storedGesture ?: run {
            // Honor only the explicit "off" choice from the legacy boolean. Both
            // "absent" and "true" fall through to the new single_tap default.
            if (!settingsStore.getBoolean("double_tap_annotation", true)) "off" else "single_tap"
        }
        _nextPageTriggerGesture.value =
            settingsStore.getString("next_page_trigger_gesture", null) ?: "single_tap"
        _showAnnotationDots.value = settingsStore.getBoolean("show_annotation_dots", true)
        _showSceneBorders.value = settingsStore.getBoolean("show_scene_borders", true)
        _annotationBarPosition.value = settingsStore.getString("annotation_bar_position", "bottom") ?: "bottom"
        _annotationBarVisibilityMode.value =
            settingsStore.getString("annotation_bar_visibility_mode", "on_double_tap") ?: "on_double_tap"
        _annotationOnSpace.value = settingsStore.getBoolean("annotation_on_space", false)
        _editModeEnabled.value = settingsStore.getBoolean("edit_mode_enabled", false)
        _landscapeEnabled.value = settingsStore.getBoolean("landscape_enabled", false)
        _expandFitEnabled.value = settingsStore.getBoolean("expand_fit_enabled", true)
        _expandFillEnabled.value = settingsStore.getBoolean("expand_fill_enabled", true)
        _bubbleTextOverlayEnabled.value = settingsStore.getBoolean("bubble_text_overlay_enabled", true)
        _cloudAlignEnabled.value = settingsStore.getBoolean("cloud_align_enabled", true)
        _cloudAlignIntervalSec.value =
            settingsStore.getString("cloud_align_interval_seconds", null)
                ?.toIntOrNull()
                ?.coerceIn(MIN_ALIGN_INTERVAL_SEC, MAX_ALIGN_INTERVAL_SEC)
                ?: DEFAULT_ALIGN_INTERVAL_SEC
        _catalogFilterSeries.value = parseFilterSet(settingsStore.getString("catalog_filter_series", null))
        _catalogFilterLanguage.value = parseFilterSet(settingsStore.getString("catalog_filter_language", null))
        _catalogFilterSize.value = parseFilterSet(settingsStore.getString("catalog_filter_size", null))
    }

    private fun parseFilterSet(raw: String?): Set<String> =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    private fun toggleCatalogFilter(
        flow: MutableStateFlow<Set<String>>,
        key: String,
        value: String
    ) {
        val oldValue = flow.value.sorted().joinToString(",")
        val updated = if (value in flow.value) flow.value - value else flow.value + value
        flow.value = updated
        val serialized = updated.sorted().joinToString(",")
        settingsStore.putString(key, serialized.ifEmpty { null })
        viewModelScope.launch {
            learnerDataRepository.logSettingsChange(key, oldValue, serialized)
        }
    }

    /** OR-toggle a series slug in the catalog filter (persisted). */
    fun toggleCatalogFilterSeries(series: String) =
        toggleCatalogFilter(_catalogFilterSeries, "catalog_filter_series", series)

    /** OR-toggle a [pl.czak.learnlauncher.domain.catalog.Language] name (persisted). */
    fun toggleCatalogFilterLanguage(language: String) =
        toggleCatalogFilter(_catalogFilterLanguage, "catalog_filter_language", language)

    /** OR-toggle a [pl.czak.learnlauncher.domain.catalog.SizeBucket] name (persisted). */
    fun toggleCatalogFilterSize(bucket: String) =
        toggleCatalogFilter(_catalogFilterSize, "catalog_filter_size", bucket)

    /** Clear all three catalog filter dimensions (persisted). */
    fun clearCatalogFilters() {
        _catalogFilterSeries.value = emptySet()
        _catalogFilterLanguage.value = emptySet()
        _catalogFilterSize.value = emptySet()
        settingsStore.putString("catalog_filter_series", null)
        settingsStore.putString("catalog_filter_language", null)
        settingsStore.putString("catalog_filter_size", null)
    }

    private fun loadHiddenApps() {
        val hidden = settingsStore.getString("hidden_apps", null)
        if (hidden != null && hidden.isNotEmpty()) {
            _hiddenApps.addAll(hidden.split(","))
        }
    }

    private fun loadPinnedApps() {
        val pinned = settingsStore.getString("pinned_apps", null)
        if (pinned != null && pinned.isNotEmpty()) {
            _pinnedApps.addAll(pinned.split(","))
        }
    }

    fun reloadSettingsIfChanged() {
        loadSettings()
    }

    fun toggleCloudAlign(enabled: Boolean) {
        val oldValue = _cloudAlignEnabled.value.toString()
        _cloudAlignEnabled.value = enabled
        settingsStore.putBoolean("cloud_align_enabled", enabled)
        viewModelScope.launch {
            learnerDataRepository.logSettingsChange("cloud_align_enabled", oldValue, enabled.toString())
        }
    }

    fun setCloudAlignIntervalSec(seconds: Int) {
        val clamped = seconds.coerceIn(MIN_ALIGN_INTERVAL_SEC, MAX_ALIGN_INTERVAL_SEC)
        val oldValue = _cloudAlignIntervalSec.value.toString()
        _cloudAlignIntervalSec.value = clamped
        settingsStore.putString("cloud_align_interval_seconds", clamped.toString())
        viewModelScope.launch {
            learnerDataRepository.logSettingsChange(
                "cloud_align_interval_seconds",
                oldValue,
                clamped.toString(),
            )
        }
    }

    fun toggleAnnotationDots(enabled: Boolean) {
        val oldValue = _showAnnotationDots.value.toString()
        _showAnnotationDots.value = enabled
        settingsStore.putBoolean("show_annotation_dots", enabled)
        viewModelScope.launch {
            learnerDataRepository.logSettingsChange("show_annotation_dots", oldValue, enabled.toString())
        }
    }

    fun toggleSceneBorders(enabled: Boolean) {
        val oldValue = _showSceneBorders.value.toString()
        _showSceneBorders.value = enabled
        settingsStore.putBoolean("show_scene_borders", enabled)
        viewModelScope.launch {
            learnerDataRepository.logSettingsChange("show_scene_borders", oldValue, enabled.toString())
        }
    }

    fun toggleLandscape(enabled: Boolean) {
        val oldValue = _landscapeEnabled.value.toString()
        _landscapeEnabled.value = enabled
        settingsStore.putBoolean("landscape_enabled", enabled)
        viewModelScope.launch {
            learnerDataRepository.logSettingsChange("landscape_enabled", oldValue, enabled.toString())
        }
    }

    fun toggleExpandFitEnabled(enabled: Boolean) {
        val oldValue = _expandFitEnabled.value.toString()
        _expandFitEnabled.value = enabled
        settingsStore.putBoolean("expand_fit_enabled", enabled)
        viewModelScope.launch {
            learnerDataRepository.logSettingsChange("expand_fit_enabled", oldValue, enabled.toString())
        }
    }

    fun toggleExpandFillEnabled(enabled: Boolean) {
        val oldValue = _expandFillEnabled.value.toString()
        _expandFillEnabled.value = enabled
        settingsStore.putBoolean("expand_fill_enabled", enabled)
        viewModelScope.launch {
            learnerDataRepository.logSettingsChange("expand_fill_enabled", oldValue, enabled.toString())
        }
    }

    fun toggleBubbleTextOverlay(enabled: Boolean) {
        val oldValue = _bubbleTextOverlayEnabled.value.toString()
        _bubbleTextOverlayEnabled.value = enabled
        settingsStore.putBoolean("bubble_text_overlay_enabled", enabled)
        viewModelScope.launch {
            learnerDataRepository.logSettingsChange("bubble_text_overlay_enabled", oldValue, enabled.toString())
        }
    }

    fun setExpandMode(mode: ExpandMode) {
        // Expand mode is incompatible with edit mode (header row hosts the
        // Exit Edit Mode button). Auto-exit edit mode on entering any expanded view.
        if (mode != ExpandMode.Off && _editModeEnabled.value) editorCancel()
        _expandMode.value = mode
    }

    // ── Scene-focus controls (read-only mode; never edits a scene) ────────
    // Chrome hiding is driven by isFocusMode alone; expandMode is left
    // untouched so exiting Focus (or a stray back press) always restores the
    // normal reader with the "Apps" launcher chrome.

    /** Enter focus mode on scene [index]. No-op if the page has no scenes. */
    fun enterFocusScene(index: Int) {
        val scenes = _scenesForCurrentImage.value
        if (scenes.isEmpty()) return
        _focusSceneIndex.value = index.coerceIn(0, scenes.size - 1)
        _focusManualRotation.value = 0
        _isFocusMode.value = true
    }

    /** Exit focus mode, restoring the full-page reader chrome. */
    fun exitFocusScene() {
        if (!_isFocusMode.value) return
        _isFocusMode.value = false
        _focusManualRotation.value = 0
    }

    /** Advance to the next scene on the page (clamped; resets manual rotation). */
    fun focusNextScene() {
        val scenes = _scenesForCurrentImage.value
        if (scenes.isEmpty()) return
        val next = _focusSceneIndex.value + 1
        if (next < scenes.size) {
            _focusSceneIndex.value = next
            _focusManualRotation.value = 0
        }
    }

    /** Go to the previous scene on the page (clamped; resets manual rotation). */
    fun focusPreviousScene() {
        val scenes = _scenesForCurrentImage.value
        if (scenes.isEmpty()) return
        val prev = _focusSceneIndex.value - 1
        if (prev >= 0) {
            _focusSceneIndex.value = prev
            _focusManualRotation.value = 0
        }
    }

    /** Manual rotate override: cycle the on-screen rotation +90°. */
    fun focusRotate() {
        _focusManualRotation.value = (_focusManualRotation.value + 90) % 360
    }

    /** Scene hit-test for tap-to-focus: index of the first scene whose rect
     *  contains (nx, ny), or -1. Containment routes through the compiled
     *  domain via [ConversationBox.contains]. */
    fun sceneHitTest(nx: Float, ny: Float): Int {
        val scenes = _scenesForCurrentImage.value
        for (i in scenes.indices) {
            val s = scenes[i]
            val box = ConversationBox(s.boxX, s.boxY, s.boxWidth, s.boxHeight, RegionType.CROP)
            if (box.contains(nx, ny)) return i
        }
        return -1
    }

    fun setAnnotationTriggerGesture(mode: String) {
        val normalized = when (mode) {
            "off", "single_tap", "double_tap" -> mode
            else -> "single_tap"
        }
        val oldValue = _annotationTriggerGesture.value
        _annotationTriggerGesture.value = normalized
        settingsStore.putString("annotation_trigger_gesture", normalized)
        viewModelScope.launch {
            learnerDataRepository.logSettingsChange("annotation_trigger_gesture", oldValue, normalized)
        }
    }

    fun setNextPageTriggerGesture(mode: String) {
        val normalized = when (mode) {
            "off", "single_tap", "double_tap" -> mode
            else -> "single_tap"
        }
        val oldValue = _nextPageTriggerGesture.value
        _nextPageTriggerGesture.value = normalized
        settingsStore.putString("next_page_trigger_gesture", normalized)
        viewModelScope.launch {
            learnerDataRepository.logSettingsChange("next_page_trigger_gesture", oldValue, normalized)
        }
    }

    fun setAnnotationBarPosition(position: String) {
        val oldValue = _annotationBarPosition.value
        _annotationBarPosition.value = position
        settingsStore.putString("annotation_bar_position", position)
        viewModelScope.launch {
            learnerDataRepository.logSettingsChange("annotation_bar_position", oldValue, position)
        }
    }

    fun setAnnotationBarVisibilityMode(mode: String) {
        val oldValue = _annotationBarVisibilityMode.value
        _annotationBarVisibilityMode.value = mode
        settingsStore.putString("annotation_bar_visibility_mode", mode)
        viewModelScope.launch {
            learnerDataRepository.logSettingsChange("annotation_bar_visibility_mode", oldValue, mode)
        }
    }

    fun toggleAnnotationOnSpace(enabled: Boolean) {
        val oldValue = _annotationOnSpace.value.toString()
        _annotationOnSpace.value = enabled
        settingsStore.putBoolean("annotation_on_space", enabled)
        viewModelScope.launch {
            learnerDataRepository.logSettingsChange("annotation_on_space", oldValue, enabled.toString())
        }
    }

    // App list management
    fun setApps(apps: List<AppInfo>) {
        _allApps = apps
        refilter()
    }

    fun filterApps(query: String) {
        _currentQuery = query
        refilter()
    }

    fun hideApp(packageName: String) {
        _hiddenApps.add(packageName)
        settingsStore.putString("hidden_apps", _hiddenApps.joinToString(","))
        if (_pinnedApps.remove(packageName)) {
            settingsStore.putString("pinned_apps", _pinnedApps.joinToString(","))
        }
        refilter()
    }

    fun resetHiddenApps() {
        _hiddenApps.clear()
        settingsStore.putString("hidden_apps", "")
        refilter()
    }

    fun getHiddenAppsCount(): Int = _hiddenApps.size

    fun togglePinned(packageName: String) {
        if (!_pinnedApps.add(packageName)) {
            _pinnedApps.remove(packageName)
        }
        settingsStore.putString("pinned_apps", _pinnedApps.joinToString(","))
        refilter()
    }

    private fun refilter() {
        val q = _currentQuery.lowercase()
        val visible = _allApps.filter { it.packageName !in _hiddenApps }
        val filtered = if (q.isEmpty()) visible
            else visible.filter { it.label.lowercase().contains(q) }
        val tagged = filtered.map { it.copy(isPinned = it.packageName in _pinnedApps) }
        _apps.value = tagged.filter { it.isPinned } + tagged.filterNot { it.isPinned }
    }

    fun showAppsOverlay() {
        _currentQuery = ""
        refilter()
        _showAppsOverlay.value = true
    }

    fun hideAppsOverlay() {
        _currentQuery = ""
        _showAppsOverlay.value = false
    }

    // Asset switching (for downloaded comics)
    fun switchAsset(newImageDataSource: ImageDataSource, comicId: String? = null) {
        imageDataSource = newImageDataSource
        // Pull any per-page translation JSONs the new comic carries into the
        // translation repo so getTranslation() returns them ahead of DB lookups.
        translationRepository.seedFromComic(newImageDataSource.loadTranslatedPagesIfAny())
        imageNavigator = ImageSequenceNavigator()
        imageNavigator.initialize(imageDataSource.getImageItems())
        _chapterNames.value = imageNavigator.getChapterNames()

        currentComicId = comicId
        lastTrackedChapter = null

        _isZoomed.value = false
        _zoomModeState.value = ZoomModeState.Viewing
        _currentImageAnnotations.value = emptyList()
        _scenesForCurrentImage.value = emptyList()
        _currentTokenRegions.value = emptyList()
        _currentTokenAnnotations.value = emptyList()
        _currentTokenTranslations.value = emptyList()

        restoreSavedPositionOrAdvance()
    }

    fun clearAsset() {
        imageDataSource = object : ImageDataSource {
            override fun getImageItems() = emptyList<pl.czak.learnlauncher.data.model.ImageItem>()
            override fun getConversationBoxes(imageId: String) = emptyList<ConversationBox>()
            override fun loadImageBytes(filename: String, chapter: String) = null
        }
        imageNavigator = ImageSequenceNavigator()
        imageNavigator.initialize(emptyList())
        _chapterNames.value = emptyList()
        _currentChapterName.value = ""
        _currentImage.value = null
        _currentImageBytes.value = null
        _currentConversationBoxes.value = emptyList()
        _highlightedBubble.value = null
        _currentImageAnnotations.value = emptyList()
        _scenesForCurrentImage.value = emptyList()
        _currentTokenRegions.value = emptyList()
        _currentTokenAnnotations.value = emptyList()
        _currentTokenTranslations.value = emptyList()
        _pageOrdering.value = null
        _editorSelection.value = emptyList()
        _isZoomed.value = false
        _zoomModeState.value = ZoomModeState.Viewing
        currentComicId = null
        settingsStore.putInt("image_navigator_position", -1)
        lastTrackedChapter = null
        _canGoBack.value = false
        _canGoForward.value = false
        _canGoNextChapter.value = false
        _canGoPreviousChapter.value = false
    }

    private fun onPageLanded(pageId: String, pageTitle: String, chapterName: String) {
        val completedSessions = mutableListOf<CompletedSession>()
        if (chapterName != lastTrackedChapter) {
            completedSessions.addAll(sessionTimeTracker.startChapterSession(chapterName))
            lastTrackedChapter = chapterName
        }
        sessionTimeTracker.startPageSession(pageId, pageTitle)?.let { completedSessions.add(it) }
        if (completedSessions.isNotEmpty()) {
            viewModelScope.launch { sessionRepository.addSessions(completedSessions) }
        }
        viewModelScope.launch { updateAnnotationsForCurrentImage() }
        viewModelScope.launch {
            learnerDataRepository.logSessionEvent("PAGE_ENTER", chapterName, pageId, pageTitle)
        }
    }

    fun onAppResumed() {
        sessionTimeTracker.startAppSession()
        _currentImage.value?.let { image ->
            lastTrackedChapter = image.chapter
            sessionTimeTracker.startChapterSession(image.chapter)
            sessionTimeTracker.startPageSession(image.id, image.title)
        }
        viewModelScope.launch { learnerDataRepository.logSessionEvent("APP_START") }
    }

    fun onAppPaused() {
        val completedSessions = sessionTimeTracker.endAppSession()
        if (completedSessions.isNotEmpty()) {
            viewModelScope.launch { sessionRepository.addSessions(completedSessions) }
        }
        lastTrackedChapter = null
        _sessionElapsed.value = SessionElapsed(null, null, null)
        viewModelScope.launch { learnerDataRepository.logSessionEvent("APP_STOP") }
    }

    fun refreshScenesAfterPull() {
        viewModelScope.launch { updateAnnotationsForCurrentImage() }
    }

    private suspend fun updateAnnotationsForCurrentImage() {
        val imageId = _currentImage.value?.id ?: return
        val boxes = imageDataSource.getConversationBoxes(imageId)
        _currentConversationBoxes.value = boxes
        _highlightedBubble.value = null
        val annotations = annotationRepository.getForImage(imageId)
        _currentImageAnnotations.value = annotations
            .filter { it.regionType == RegionType.BUBBLE }
            .sortedByDescending { it.timestamp }
            .distinctBy { it.boxIndex }
            .mapNotNull { ann ->
                boxes.getOrNull(ann.boxIndex)?.let { box -> Pair(box, ann.label) }
            }
        _scenesForCurrentImage.value = annotations
            .filter { it.regionType == RegionType.CROP && it.label == "scene" }
        _pageOrdering.value = pageBubbleOrderingRepository.getForPage(
            currentUserId(), orderingComicKey(), imageId
        )
    }

    // TODO(userId): no AuthManager exists yet; SyncService takes a `userId: () -> String?` lambda.
    // For v1 we compose the row-key with empty string. Composition-root agent (F) may inject a
    // userIdProvider lambda later.
    private fun currentUserId(): String = ""

    private fun orderingComicKey(): String = currentComicId?.takeIf { it.isNotEmpty() } ?: NO_COMIC_KEY

    private fun applyPermutation(boxes: List<ConversationBox>, ordering: PageBubbleOrdering?): List<ConversationBox> {
        if (ordering == null) return boxes
        val indices = ordering.originalIndices
        if (!PageBubbleOrdering.isValidPermutation(indices, boxes.size)) return boxes
        return indices.map { boxes[it] }
    }

    fun toggleEditMode(enabled: Boolean) {
        _editModeEnabled.value = enabled
        settingsStore.putBoolean("edit_mode_enabled", enabled)
        if (!enabled) {
            _editorSelection.value = emptyList()
        }
    }

    fun editorAddBubble(originalIndex: Int) {
        val current = _editorSelection.value
        // Tap-toggle on the most-recently-added bubble: clicking it again pops
        // it (acts as Undo for that one bubble). Tapping a non-latest already-
        // selected bubble is ignored — popping it would leave a gap that the
        // user didn't intend.
        if (current.isNotEmpty() && current.last() == originalIndex) {
            _editorSelection.value = current.dropLast(1)
            return
        }
        if (originalIndex in current) return // already selected (not latest); ignore
        _editorSelection.value = current + originalIndex
    }

    fun editorUndo() {
        val current = _editorSelection.value
        if (current.isNotEmpty()) _editorSelection.value = current.dropLast(1)
    }

    fun editorClear() {
        _editorSelection.value = emptyList()
    }

    fun editorCancel() {
        _editorSelection.value = emptyList()
        _editModeEnabled.value = false
        settingsStore.putBoolean("edit_mode_enabled", false)
    }

    fun editorSave() {
        val n = _currentConversationBoxes.value.size
        val sel = _editorSelection.value
        if (!PageBubbleOrdering.isValidPermutation(sel, n)) return // invariant
        val image = _currentImage.value ?: return
        val ordering = PageBubbleOrdering(
            userId = currentUserId(),
            comicId = orderingComicKey(),
            imageId = image.id,
            originalIndices = sel,
            updatedAt = currentTimeMillis(),
        )
        viewModelScope.launch {
            pageBubbleOrderingRepository.save(ordering)
            _pageOrdering.value = ordering
            _editorSelection.value = emptyList()
            _editModeEnabled.value = false
            settingsStore.putBoolean("edit_mode_enabled", false)
        }
    }

    fun resetPageOrdering() {
        val image = _currentImage.value ?: return
        viewModelScope.launch {
            pageBubbleOrderingRepository.deleteForPage(currentUserId(), orderingComicKey(), image.id)
            _pageOrdering.value = null
        }
    }

    private fun updateNavigationState() {
        _canGoBack.value = imageNavigator.canGoBack
        _canGoForward.value = imageNavigator.canGoForward
        _canGoNextChapter.value = imageNavigator.canGoNextChapter
        _canGoPreviousChapter.value = imageNavigator.canGoPreviousChapter
        _currentChapterName.value = imageNavigator.currentChapterName
        settingsStore.putInt(positionKey(), imageNavigator.position)
    }

    fun nextPage() {
        val image = imageNavigator.next()
        if (image != null) {
            _currentImage.value = image
            _currentImageBytes.value = imageDataSource.loadImageBytes(image.filename, image.chapter)
            onPageChanged()
            onPageLanded(image.id, image.title, image.chapter)
        }
        updateNavigationState()
        viewModelScope.launch {
            learnerDataRepository.logPageInteraction(
                "SWIPE_NEXT", _currentImage.value?.chapter, _currentImage.value?.id
            )
        }
    }

    fun previousPage() {
        val image = imageNavigator.previous()
        if (image != null) {
            _currentImage.value = image
            _currentImageBytes.value = imageDataSource.loadImageBytes(image.filename, image.chapter)
            onPageChanged()
            onPageLanded(image.id, image.title, image.chapter)
        }
        updateNavigationState()
        viewModelScope.launch {
            learnerDataRepository.logPageInteraction(
                "SWIPE_PREV", _currentImage.value?.chapter, _currentImage.value?.id
            )
        }
    }

    // Drop any stale bubble selection and signal the annotation bar to close.
    // The MainScreen LaunchedEffect on hideAnnotationBarTrigger honors the
    // "always" visibility mode, so that setting still pins the bar.
    private fun onPageChanged() {
        _highlightedBubble.value = null
        _hideAnnotationBarTrigger.value = currentTimeMillis()
    }

    // Hit-test the current image's bubbles. Returns the index of the first bubble
    // containing the normalized point, or -1 if the tap missed every bubble (or
    // there is no current image / no bubbles).
    fun bubbleHitTest(normalizedX: Float, normalizedY: Float): Int {
        val image = _currentImage.value ?: return -1
        val boxes = imageDataSource.getConversationBoxes(image.id)
        if (boxes.isEmpty()) return -1
        return boxes.indexOfFirst { it.contains(normalizedX, normalizedY) }
    }

    // Open the annotation bar (or add to the editor selection) for a confirmed
    // bubble hit. `gestureName` is the firing gesture for telemetry ("SINGLE_TAP"
    // or "DOUBLE_TAP"). Caller is responsible for the hit-test; this no-ops if
    // the annotation gesture is set to "off".
    fun onBubbleAnnotateTapped(
        normalizedX: Float,
        normalizedY: Float,
        hitIndex: Int,
        gestureName: String
    ) {
        if (_annotationTriggerGesture.value == "off") return
        val image = _currentImage.value ?: return
        val boxes = imageDataSource.getConversationBoxes(image.id)
        if (hitIndex < 0 || hitIndex >= boxes.size) return
        if (_editModeEnabled.value) {
            editorAddBubble(hitIndex)
            viewModelScope.launch {
                learnerDataRepository.logPageInteraction(
                    gestureName, image.chapter, image.id,
                    normalizedX, normalizedY, "edit_select_$hitIndex"
                )
            }
            return
        }
        _highlightedBubble.value = boxes[hitIndex]
        _doubleTapAction.value = DoubleTapAction.AnnotateBubble(
            nx = normalizedX, ny = normalizedY,
            box = boxes[hitIndex], boxIndex = hitIndex
        )
        viewModelScope.launch {
            learnerDataRepository.logPageInteraction(
                gestureName, image.chapter, image.id,
                normalizedX, normalizedY, "bubble_hit"
            )
        }
    }

    // Advance / go-back on a confirmed bubble miss. Right half → next page,
    // left half → previous page. No-ops if next-page gesture is "off".
    fun onPageNavTapped(normalizedX: Float, normalizedY: Float, gestureName: String) {
        if (_nextPageTriggerGesture.value == "off") return
        val image = _currentImage.value ?: return
        viewModelScope.launch {
            learnerDataRepository.logPageInteraction(
                gestureName, image.chapter, image.id,
                normalizedX, normalizedY, "bubble_miss"
            )
        }
        if (normalizedX >= 0.5f) {
            nextPage()
            _doubleTapAction.value = DoubleTapAction.MarkUnderstoodAndAdvance
        } else {
            previousPage()
            _doubleTapAction.value = DoubleTapAction.GoToPrevious
        }
    }

    fun onZoomDoubleTapped(normalizedX: Float, normalizedY: Float): Boolean {
        val imageId = _currentImage.value?.id ?: return false
        val bubbleIndex = zoomedBubbleIndex ?: return false
        val tokens = _currentTokenRegions.value
        val hitTokenIndex = tokens.indexOfFirst { token ->
            normalizedX >= token.x && normalizedX <= token.x + token.width &&
            normalizedY >= token.y && normalizedY <= token.y + token.height
        }
        if (hitTokenIndex >= 0) {
            val token = tokens[hitTokenIndex]
            _doubleTapAction.value = DoubleTapAction.AnnotateToken(
                nx = normalizedX, ny = normalizedY,
                token = token, parentBubbleIndex = bubbleIndex, tokenIndex = hitTokenIndex
            )
            _zoomModeState.value = ZoomModeState.AnnotatingToken(token)
            viewModelScope.launch {
                learnerDataRepository.logPageInteraction(
                    "ZOOM_DOUBLE_TAP", null, imageId,
                    normalizedX, normalizedY, "token_hit_$hitTokenIndex"
                )
            }
            return true
        } else {
            showTranslationOverlay()
            return true
        }
    }

    private fun showTranslationOverlay() {
        val imageId = _currentImage.value?.id ?: return
        val bubbleIndex = zoomedBubbleIndex ?: return
        val bubble = zoomedBubbleBox
        viewModelScope.launch {
            var translation = translationRepository.getTranslation(imageId, bubbleIndex)
            // Pre-fill from OCR text if no saved translation exists
            if (translation == null && bubble?.text != null) {
                translation = RegionTranslation(
                    id = "",
                    imageId = imageId,
                    bubbleIndex = bubbleIndex,
                    originalText = bubble.text ?: "",
                    meaningTranslation = "",
                    literalTranslation = "",
                )
            }
            _currentTranslation.value = translation
            _showTranslationOverlay.value = true
            _zoomModeState.value = ZoomModeState.ShowingTranslation(translation)
            learnerDataRepository.logPageInteraction(
                "ZOOM_DOUBLE_TAP", null, imageId, hitResult = "translation_show"
            )
        }
    }

    fun hideTranslationOverlay() {
        _showTranslationOverlay.value = false
        _currentTranslation.value = null
        _zoomModeState.value = ZoomModeState.Viewing
    }

    fun saveTokenAnnotation(
        token: ConversationBox,
        parentBubbleIndex: Int,
        tokenIndex: Int,
        label: String,
        tapX: Float,
        tapY: Float
    ) {
        val imageId = _currentImage.value?.id ?: return
        val annotation = RegionAnnotation(
            imageId = imageId, boxIndex = tokenIndex,
            boxX = token.x, boxY = token.y, boxWidth = token.width, boxHeight = token.height,
            label = label, timestamp = currentTimeMillis(),
            tapX = tapX, tapY = tapY,
            regionType = RegionType.TOKEN,
            parentBubbleIndex = parentBubbleIndex, tokenIndex = tokenIndex
        )
        viewModelScope.launch {
            annotationRepository.addAnnotation(annotation)
            updateTokenAnnotationsForCurrentBubble()
        }
        _zoomModeState.value = ZoomModeState.Viewing
    }

    fun saveAnnotation(box: ConversationBox, boxIndex: Int, label: String, tapX: Float, tapY: Float) {
        val imageId = _currentImage.value?.id ?: return
        val annotation = RegionAnnotation(
            imageId = imageId, boxIndex = boxIndex,
            boxX = box.x, boxY = box.y, boxWidth = box.width, boxHeight = box.height,
            label = label, timestamp = currentTimeMillis(),
            tapX = tapX, tapY = tapY
        )
        viewModelScope.launch {
            annotationRepository.addAnnotation(annotation)
            updateAnnotationsForCurrentImage()
        }
    }

    fun consumeDoubleTapAction() {
        _doubleTapAction.value = null
    }

    fun clearHighlightedBubble() {
        _highlightedBubble.value = null
    }

    fun openAnnotationBarForBubble(action: DoubleTapAction.AnnotateBubble) {
        _pendingBubbleAction.value = action
        _pendingTokenAction.value = null
        _annotationExpanded.value = false
        _currentAnnotationOptions.value = decideOptions(action.boxIndex)
        _expandedTokenOptions.value = allTokenOptions(action.boxIndex)
        _annotationBarOpen.value = true
    }

    fun openAnnotationBarForToken(action: DoubleTapAction.AnnotateToken) {
        _pendingTokenAction.value = action
        _pendingBubbleAction.value = null
        _annotationExpanded.value = false
        _currentAnnotationOptions.value = decideOptions(action.parentBubbleIndex)
        _expandedTokenOptions.value = allTokenOptions(action.parentBubbleIndex)
        _annotationBarOpen.value = true
    }

    fun closeAnnotationBar() {
        _annotationBarOpen.value = false
        _pendingBubbleAction.value = null
        _pendingTokenAction.value = null
        _currentAnnotationOptions.value = emptyList()
        _expandedTokenOptions.value = emptyList()
        _freeAnnotationDialogOpen.value = false
        _annotationExpanded.value = false
    }

    fun openFreeAnnotationDialog() {
        if (_annotationBarOpen.value) _freeAnnotationDialogOpen.value = true
    }

    fun closeFreeAnnotationDialog() {
        _freeAnnotationDialogOpen.value = false
    }

    // ----- Crop-region annotation -----

    fun onCropDragStart(nx: Float, ny: Float) {
        if (!_editModeEnabled.value) return
        if (_cropAnnotationBarOpen.value || _cropFreeFormDialogOpen.value) return
        val x = nx.coerceIn(0f, 1f)
        val y = ny.coerceIn(0f, 1f)
        _cropSelectionInProgress.value = CropSelection(x, y, x, y)
    }

    fun onCropDragUpdate(nx: Float, ny: Float) {
        val cur = _cropSelectionInProgress.value ?: return
        _cropSelectionInProgress.value = cur.copy(
            endX = nx.coerceIn(0f, 1f),
            endY = ny.coerceIn(0f, 1f),
        )
    }

    fun onCropDragEnd() {
        val sel = _cropSelectionInProgress.value ?: return
        _cropSelectionInProgress.value = null
        if (sel.isDegenerate(CropSelection.MIN_NORMALIZED_SIZE)) return
        _cropSelectionFinalized.value = sel.clamped()
        _cropAnnotationBarOpen.value = true
    }

    fun saveCropAnnotation(label: String) {
        val sel = _cropSelectionFinalized.value ?: return
        val imageId = _currentImage.value?.id ?: run {
            cancelCropSelection()
            return
        }
        val annotation = RegionAnnotation(
            imageId = imageId,
            boxIndex = -1,
            boxX = sel.normalizedX(),
            boxY = sel.normalizedY(),
            boxWidth = sel.normalizedWidth(),
            boxHeight = sel.normalizedHeight(),
            label = label,
            timestamp = currentTimeMillis(),
            tapX = sel.centerX(),
            tapY = sel.centerY(),
            regionType = RegionType.CROP,
            parentBubbleIndex = null,
            tokenIndex = null,
        )
        viewModelScope.launch {
            annotationRepository.addAnnotation(annotation)
            updateAnnotationsForCurrentImage()
        }
        clearCropState()
    }

    fun openCropFreeFormDialog() {
        if (_cropAnnotationBarOpen.value) _cropFreeFormDialogOpen.value = true
    }

    fun closeCropFreeFormDialog() {
        _cropFreeFormDialogOpen.value = false
    }

    fun submitCropFreeForm(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        saveCropAnnotation(trimmed)
    }

    fun cancelCropSelection() {
        clearCropState()
    }

    private fun clearCropState() {
        _cropSelectionInProgress.value = null
        _cropSelectionFinalized.value = null
        _cropAnnotationBarOpen.value = false
        _cropFreeFormDialogOpen.value = false
    }

    private fun annotationCtx(boxIndex: Int): AnnotationContext {
        val image = _currentImage.value
        val tokens = _currentConversationBoxes.value.getOrNull(boxIndex)?.tokens ?: emptyList()
        return AnnotationContext(
            seriesPath = currentComicId.orEmpty(),
            chapterPath = image?.chapter.orEmpty(),
            pageId = image?.id.orEmpty(),
            boxIndex = boxIndex,
            userId = null,
            recentLabels = emptyList(),
            nowEpochMs = currentTimeMillis(),
            boxTokens = tokens
        )
    }

    /** Primary bar: top-K OCR words (use case: tap to save a word); semantic fallback if no tokens. */
    private fun decideOptions(boxIndex: Int): List<AnnotationOption> {
        val ctx = annotationCtx(boxIndex)
        return if (ctx.boxTokens.isNotEmpty())
            tokenScorer.score(ctx.boxTokens, ctx).take(tokenSuggestionK)
                .map { AnnotationOption(label = it.text, display = it.text) }
        else annotationDecider.decide(ctx)
    }

    /** Expansion: ALL of the bubble's tokens as options (uncapped), or empty if it has none. */
    private fun allTokenOptions(boxIndex: Int): List<AnnotationOption> {
        val ctx = annotationCtx(boxIndex)
        return tokenScorer.score(ctx.boxTokens, ctx)
            .map { AnnotationOption(label = it.text, display = it.text) }
    }

    /** Toggles the annotation bar for the currently highlighted bubble.
     *  Returns true if a toggle happened (bar was shown or hidden). */
    fun toggleAnnotationBarForHighlighted(): Boolean {
        if (_annotationBarOpen.value) {
            closeAnnotationBar()
            return true
        }
        val hb = _highlightedBubble.value ?: return false
        val idx = _currentConversationBoxes.value.indexOf(hb)
        if (idx < 0) return false
        openAnnotationBarForBubble(
            DoubleTapAction.AnnotateBubble(
                nx = hb.x + hb.width / 2f,
                ny = hb.y + hb.height / 2f,
                box = hb,
                boxIndex = idx
            )
        )
        return true
    }

    fun onImageLongPressed(nx: Float, ny: Float): Boolean {
        if (_isZoomed.value) return false
        val image = _currentImage.value ?: return false
        val boxes = imageDataSource.getConversationBoxes(image.id)
        val hitIndex = boxes.indexOfFirst { it.contains(nx, ny) }
        if (hitIndex < 0) return false

        val hit = boxes[hitIndex]
        _isZoomed.value = true
        zoomedBubbleIndex = hitIndex
        zoomedBubbleBox = hit
        loadTokenRegionsForBubble(image.id, hitIndex)
        return true
    }

    private fun loadTokenRegionsForBubble(imageId: String, bubbleIndex: Int) {
        // 1. Prefer OCR tokens embedded in the ConversationBox itself
        val bubble = zoomedBubbleBox
        val ocrTokens = bubble?.ocrTokenRegions(bubbleIndex)
        println("[LauncherVM] loadTokenRegions: bubble=$bubbleIndex text=${bubble?.text} tokens=${bubble?.tokens?.size ?: 0} ocrRegions=${ocrTokens?.size ?: 0}")
        if (!ocrTokens.isNullOrEmpty()) {
            _currentTokenRegions.value = ocrTokens
        } else {
            // 2. Try token regions from the active image data source (comic-level token_regions.json)
            val dsTokens = imageDataSource.getTokensForBubble(imageId, bubbleIndex)
            if (dsTokens.isNotEmpty()) {
                _currentTokenRegions.value = dsTokens
            } else {
                // 3. Fallback to global token region repository (bundled assets)
                _currentTokenRegions.value = tokenRegionRepository.getTokensForBubble(imageId, bubbleIndex)
            }
        }
        println("[LauncherVM] loadTokenRegions: result=${_currentTokenRegions.value.size} regions")
        _selectedTokenIndex.value = null
        // Pull per-token translations from the active RegionTranslation if any.
        viewModelScope.launch {
            val rt = translationRepository.getTranslation(imageId, bubbleIndex)
            _currentTokenTranslations.value = rt?.tokenTranslations.orEmpty()
            updateTokenAnnotationsForCurrentBubble()
        }
    }

    private suspend fun updateTokenAnnotationsForCurrentBubble() {
        val imageId = _currentImage.value?.id ?: return
        val bubbleIndex = zoomedBubbleIndex ?: return
        val tokens = _currentTokenRegions.value
        val annotations = annotationRepository.getTokenAnnotationsForBubble(imageId, bubbleIndex)
        _currentTokenAnnotations.value = annotations
            .sortedByDescending { it.timestamp }
            .distinctBy { it.tokenIndex }
            .mapNotNull { ann ->
                tokens.getOrNull(ann.tokenIndex ?: -1)?.let { token -> Pair(token, ann.label) }
            }
    }

    private fun clearZoomState() {
        _currentTokenRegions.value = emptyList()
        _currentTokenAnnotations.value = emptyList()
        _currentTokenTranslations.value = emptyList()
        _selectedTokenIndex.value = null
        zoomedBubbleIndex = null
        zoomedBubbleBox = null
        _zoomModeState.value = ZoomModeState.Viewing
        _showTranslationOverlay.value = false
        _currentTranslation.value = null
    }

    /** Tap in zoom mode: select token if hit, deselect if already selected, exit zoom if no token hit. */
    fun onZoomTapped(nx: Float, ny: Float): Boolean {
        val tokens = _currentTokenRegions.value
        val hitIndex = tokens.indexOfFirst { it.contains(nx, ny) }
        if (hitIndex >= 0) {
            // Toggle selection
            _selectedTokenIndex.value = if (_selectedTokenIndex.value == hitIndex) null else hitIndex
            return true
        }
        // Tap outside tokens — exit zoom
        exitZoom()
        return false
    }

    /**
     * Long-press inside the zoom view: show the bubble's full TranslationOverlay
     * (meaning + literal). Independent of (and additive to) the legacy
     * double-tap-miss trigger so users get the overlay via a discoverable hold.
     */
    fun onZoomLongPressed(@Suppress("UNUSED_PARAMETER") nx: Float, @Suppress("UNUSED_PARAMETER") ny: Float): Boolean {
        if (!_isZoomed.value) return false
        if (zoomedBubbleIndex == null) return false
        showTranslationOverlay()
        return true
    }

    fun exitZoom() {
        if (!_isZoomed.value) return
        _isZoomed.value = false
        clearZoomState()
    }

    fun nextChapter() {
        val image = imageNavigator.nextChapter()
        if (image != null) {
            _currentImage.value = image
            _currentImageBytes.value = imageDataSource.loadImageBytes(image.filename, image.chapter)
            onPageLanded(image.id, image.title, image.chapter)
        }
        updateNavigationState()
        viewModelScope.launch {
            learnerDataRepository.logPageInteraction(
                "CHAPTER_NEXT", _currentImage.value?.chapter, _currentImage.value?.id
            )
        }
    }

    fun previousChapter() {
        val image = imageNavigator.previousChapter()
        if (image != null) {
            _currentImage.value = image
            _currentImageBytes.value = imageDataSource.loadImageBytes(image.filename, image.chapter)
            onPageLanded(image.id, image.title, image.chapter)
        }
        updateNavigationState()
        viewModelScope.launch {
            learnerDataRepository.logPageInteraction(
                "CHAPTER_PREV", _currentImage.value?.chapter, _currentImage.value?.id
            )
        }
    }

    fun goToChapter(chapterIndex: Int) {
        val image = imageNavigator.goToChapter(chapterIndex)
        if (image != null) {
            _currentImage.value = image
            _currentImageBytes.value = imageDataSource.loadImageBytes(image.filename, image.chapter)
            onPageChanged()
            onPageLanded(image.id, image.title, image.chapter)
        }
        updateNavigationState()
        viewModelScope.launch {
            learnerDataRepository.logPageInteraction(
                "CHAPTER_SELECT", _currentImage.value?.chapter, _currentImage.value?.id
            )
        }
    }

    // F15: load the raw bytes of an arbitrary annotation's page image (imageId =
    // "<chapter>/<file>.<ext>"), so the annotation-detail view can crop the bubble.
    fun loadImageBytesForImageId(imageId: String): ByteArray? {
        val filename = imageId.substringAfterLast('/')
        val chapter = imageId.substringBeforeLast('/', "")
        return imageDataSource.loadImageBytes(filename, chapter)
    }

    fun goToImageId(imageId: String): Boolean {
        val image = imageNavigator.goToImageId(imageId) ?: return false
        _currentImage.value = image
        _currentImageBytes.value = imageDataSource.loadImageBytes(image.filename, image.chapter)
        onPageChanged()
        onPageLanded(image.id, image.title, image.chapter)
        updateNavigationState()
        if (_isZoomed.value) exitZoom()
        return true
    }

    val zoomedBubble: ConversationBox?
        get() = zoomedBubbleBox

    /**
     * Select a bubble by index. Returns true if a bubble was hit.
     *
     * When [fromArrow] is true (keyboard arrow navigation), only the highlighted bubble is
     * updated and a hide-bar signal is emitted. When false (e.g. simulated double-tap), a full
     * AnnotateBubble action is emitted so the annotation bar appears.
     */
    fun selectBubbleByIndex(index: Int, fromArrow: Boolean = false): Boolean {
        val image = _currentImage.value ?: return false
        val boxes = _currentConversationBoxes.value
        if (index < 0 || index >= boxes.size) return false
        val box = boxes[index]
        val cx = box.x + box.width / 2
        val cy = box.y + box.height / 2
        _highlightedBubble.value = box
        if (fromArrow) {
            _hideAnnotationBarTrigger.value = currentTimeMillis()
        } else {
            _doubleTapAction.value = DoubleTapAction.AnnotateBubble(
                nx = cx, ny = cy, box = box, boxIndex = index
            )
        }
        viewModelScope.launch {
            learnerDataRepository.logPageInteraction(
                "KEY_SELECT", image.chapter, image.id, cx, cy, "bubble_hit"
            )
        }
        return true
    }

    /** Get the index of the currently highlighted bubble in the ORIGINAL JSON-array order, or -1.
     *  This is the canonical bubble identifier used by annotations / sync — do not change it to
     *  return display-order indices.  For arrow-key navigation use [getHighlightedDisplayIndex]. */
    fun getHighlightedBubbleIndex(): Int {
        val highlighted = _highlightedBubble.value ?: return -1
        return _currentConversationBoxes.value.indexOf(highlighted)
    }

    /** Get the index of the currently highlighted bubble in [displayOrderedBoxes], or -1.
     *  When no per-page user ordering is saved, this matches the original index. */
    fun getHighlightedDisplayIndex(): Int {
        val highlighted = _highlightedBubble.value ?: return -1
        return displayOrderedBoxes.value.indexOf(highlighted)
    }

    /** Select a bubble by its position in [displayOrderedBoxes].  Resolves to the original-list
     *  index internally so annotations stay anchored to physical bubbles regardless of display
     *  order.  Used by arrow-key nav so traversal honors the saved user ordering. */
    fun selectBubbleByDisplayIndex(displayIndex: Int, fromArrow: Boolean = false): Boolean {
        val display = displayOrderedBoxes.value
        if (displayIndex < 0 || displayIndex >= display.size) return false
        val box = display[displayIndex]
        val originalIndex = _currentConversationBoxes.value.indexOf(box)
        if (originalIndex < 0) return false
        return selectBubbleByIndex(originalIndex, fromArrow)
    }

    /** Annotate the currently highlighted bubble with the given label. Returns true if annotated. */
    fun annotateHighlightedBubble(label: String): Boolean {
        val highlighted = _highlightedBubble.value ?: return false
        val idx = _currentConversationBoxes.value.indexOf(highlighted)
        if (idx < 0) return false
        val cx = highlighted.x + highlighted.width / 2
        val cy = highlighted.y + highlighted.height / 2
        saveAnnotation(highlighted, idx, label, cx, cy)
        _doubleTapAction.value = null
        closeAnnotationBar()
        clearHighlightedBubble()
        return true
    }

    private companion object {
        const val NO_COMIC_KEY = "_no_comic_"
        const val MIN_ALIGN_INTERVAL_SEC = 10
        const val MAX_ALIGN_INTERVAL_SEC = 3600
        const val DEFAULT_ALIGN_INTERVAL_SEC = 60
    }
}

interface SettingsStore {
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getString(key: String, default: String?): String?
    fun putString(key: String, value: String?)
}

/**
 * Phase 3b migration helper: choose the native sampler.  **Only catches
 * `NotImplementedError`** (the iOS App7Native stub signal) — any other
 * failure (UnsatisfiedLinkError, SecurityException, OOM) propagates so
 * the app fails fast instead of silently routing annotation samples
 * through pure-Kotlin code, which would violate the
 * `feedback_haskell_domain_done_gate` mandate ("every remote-bound
 * write must have had a binary domain check").
 */
private fun pickAnnotationDecider(): AnnotationDecisionFunction =
    try {
        NativeAnnotationDecisionFunction().also {
            it.decide(AnnotationContext(
                seriesPath = "", chapterPath = "", pageId = "", boxIndex = 0,
                userId = null, recentLabels = emptyList(), nowEpochMs = 0L,
            ))
        }
    } catch (_: NotImplementedError) {
        RandomSamplingDecisionFunction()
    }
