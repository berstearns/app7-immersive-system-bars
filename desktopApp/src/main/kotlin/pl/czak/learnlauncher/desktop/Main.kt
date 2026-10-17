package pl.czak.learnlauncher.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import pl.czak.learnlauncher.App
import pl.czak.learnlauncher.data.*
import pl.czak.learnlauncher.data.auth.AuthException
import pl.czak.learnlauncher.data.auth.DesktopAuthManager
import pl.czak.learnlauncher.data.auth.DesktopSettingsStore
import pl.czak.learnlauncher.data.auth.LocalAuthApi
import pl.czak.learnlauncher.data.download.CatalogComic
import pl.czak.learnlauncher.data.download.DesktopComicCatalogApi
import pl.czak.learnlauncher.data.download.DesktopComicDownloadManager
import pl.czak.learnlauncher.data.download.DownloadState
import pl.czak.learnlauncher.data.export.DesktopJsonExportService
import pl.czak.learnlauncher.data.source.FileImageDataSource
import pl.czak.learnlauncher.data.sync.AlignmentService
import pl.czak.learnlauncher.data.sync.BackendConfig
import pl.czak.learnlauncher.data.sync.DesktopOutbox
import pl.czak.learnlauncher.data.sync.SyncService
import pl.czak.learnlauncher.data.sync.createBackendHttpClient
import pl.czak.learnlauncher.data.sync.ports.PayloadUploader
import pl.czak.learnlauncher.data.sync.selectUploader
import pl.czak.learnlauncher.domain.navigation.ImageSequenceNavigator
import pl.czak.learnlauncher.domain.native.App7Native
import pl.czak.learnlauncher.domain.session.SessionTimeTracker
import pl.czak.learnlauncher.ui.CatalogComicUi
import pl.czak.learnlauncher.ui.DownloadStatus
import pl.czak.learnlauncher.viewmodel.ChatViewModel
import pl.czak.learnlauncher.viewmodel.LauncherViewModel
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

fun main(args: Array<String>) {
    val settingsStore = DesktopSettingsStore()
    val authManager = DesktopAuthManager(settingsStore)
    val authApi = LocalAuthApi()
    val downloadManager = DesktopComicDownloadManager(settingsStore)

    val backendUrl = resolveBackendUrl(args)
    val syncProvider = System.getenv("BACKEND_PROVIDER")?.takeIf { it.isNotBlank() } ?: "auto (turso → tcp → supabase)"
    println("[Desktop] Catalog backend: $backendUrl")
    println("[Desktop] Sync backend: $syncProvider")

    // PHASE-6 SMOKE: full-coverage native API exercise on desktop.
    runCatching {
        val perm = App7Native.isValidPermutation(intArrayOf(0, 1, 2), 3)
        val clp  = App7Native.clamped(-0.5f, 1.5f, 1.2f, -0.2f)
        val cnt  = App7Native.boxContains(0.3f, 0.3f, 0.1f, 0.1f, 0.4f, 0.4f)
        val deg  = App7Native.isDegenerate(0.02f, 0.0f, 0.0f, 0.019f, 0.5f)
        val ps   = App7Native.annotationPoolSize()
        val sIdx = App7Native.sampleAnnotationIndices(42L, 3).toList()
        val pLab = App7Native.annotationPoolLabel(sIdx.first())

        val tHandle = App7Native.sessionTrackerNew()
        App7Native.sessionTrackerStartApp(tHandle, 1000L)
        val out = LongArray(9)
        val endCount = App7Native.sessionTrackerEndApp(tHandle, 1500L, out)
        App7Native.sessionTrackerFree(tHandle)

        val nHandle = App7Native.navNew()
        App7Native.navInitialize(nHandle, 5, intArrayOf(0, 3))
        val nextIdx = App7Native.navNext(nHandle)
        val nextChapterIdx = App7Native.navNextChapter(nHandle)
        App7Native.navFree(nHandle)

        println(
            "[Desktop] PHASE-6-SMOKE perm=$perm clamped=${clp.toList()} contains=$cnt " +
            "degenerate=$deg poolSize=$ps sample(42,3)=$sIdx firstLabel=$pLab " +
            "endApp(count=$endCount,type=${out[0]}) navNext=$nextIdx navNextChapter=$nextChapterIdx"
        )
    }.onFailure {
        System.err.println("[Desktop] PHASE-6-SMOKE FAILED: ${it.message}")
        it.printStackTrace()
    }

    // Repositories (in-memory). The learner-data repo gets a `currentComicId`
    // provider so events are stamped with whichever comic is active when the
    // user logs them, matching the Android Room-backed implementation.
    val annotationRepository = InMemoryAnnotationRepository()
    val sessionRepository = InMemorySessionRepository()
    val learnerDataRepository = InMemoryLearnerDataRepository(
        currentComicId = { settingsStore.getString("selected_asset_id", null) ?: "_no_comic_" },
    )
    val tokenRegionRepository = InMemoryTokenRegionRepository()
    val translationRepository = InMemoryTranslationRepository()
    val chatRepository = InMemoryChatRepository()
    val pageBubbleOrderingRepository = InMemoryPageBubbleOrderingRepository()

    // Export service
    val exportService = DesktopJsonExportService(annotationRepository, sessionRepository, chatRepository, translationRepository)

    // Determine initial comic directory
    val comicDirOverride = parseComicDirArg(args)
    val initialComicDir = comicDirOverride ?: run {
        val savedAssetId = settingsStore.getString("selected_asset_id", null)
        if (savedAssetId != null) {
            val dir = File(downloadManager.comicsDir, savedAssetId)
            if (dir.exists() && (File(dir, "manifest.json").exists() || File(dir, "images.json").exists())) dir
            else null
        } else null
    }

    val imageDataSource = if (initialComicDir != null) FileImageDataSource(initialComicDir)
        else FileImageDataSource(downloadManager.comicsDir) // will just show empty

    // Resolve comic ID from the directory name for sync
    val initialComicId = comicDirOverride?.name
        ?: settingsStore.getString("selected_asset_id", null)
    // Persist so sync always knows the active comic
    if (initialComicId != null) {
        settingsStore.putString("selected_asset_id", initialComicId)
    }
    println("[Desktop] Active comic: ${initialComicId ?: "NONE"}")

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val viewModel = LauncherViewModel(
        imageDataSource = imageDataSource,
        imageNavigator = ImageSequenceNavigator(),
        sessionTimeTracker = SessionTimeTracker(),
        annotationRepository = annotationRepository,
        sessionRepository = sessionRepository,
        learnerDataRepository = learnerDataRepository,
        tokenRegionRepository = tokenRegionRepository,
        translationRepository = translationRepository,
        pageBubbleOrderingRepository = pageBubbleOrderingRepository,
        settingsStore = settingsStore
    )
    val chatViewModel = ChatViewModel(chatRepository)

    application {
        val windowState = rememberWindowState(width = 450.dp, height = 900.dp)

        Window(
            onCloseRequest = {
                // Phase 3b: release native handles before exit.
                runCatching { viewModel.closeNativeHandles() }
                exitApplication()
            },
            state = windowState,
            title = "Manga Reader",
            onPreviewKeyEvent = { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    handleKeyDown(keyEvent, viewModel)
                } else false
            }
        ) {

            // Auth state
            var isLoggedIn by remember { mutableStateOf(authManager.isLoggedIn()) }

            // Catalog state
            var catalogComics by remember { mutableStateOf<List<CatalogComicUi>>(emptyList()) }
            var catalogCache by remember { mutableStateOf<List<CatalogComic>>(emptyList()) }
            var downloadStatus by remember { mutableStateOf<DownloadStatus>(DownloadStatus.Idle) }
            var downloadedComicIds by remember { mutableStateOf(downloadManager.getDownloadedIds()) }
            var activeComicId by remember { mutableStateOf(initialComicId) }
            var autoSyncEnabled by remember { mutableStateOf(settingsStore.getBoolean("auto_sync_enabled", true)) }

            // Observe download state
            LaunchedEffect(Unit) {
                downloadManager.state.collectLatest { state ->
                    downloadStatus = when (state) {
                        is DownloadState.Idle -> DownloadStatus.Idle
                        is DownloadState.Downloading -> DownloadStatus.Downloading(state.comicId, state.progress)
                        is DownloadState.Extracting -> DownloadStatus.Extracting(state.comicId)
                        is DownloadState.Done -> {
                            downloadedComicIds = downloadManager.getDownloadedIds()
                            catalogComics = catalogCache.map { comic ->
                                CatalogComicUi(
                                    id = comic.id, name = comic.name,
                                    sizeMb = comic.size_mb, chapters = comic.chapters,
                                    isDownloaded = downloadManager.isDownloaded(comic.id)
                                )
                            }
                            // Auto-select if first download
                            if (activeComicId == null) {
                                selectComic(state.comicId, settingsStore, downloadManager, viewModel)
                                activeComicId = state.comicId
                            }
                            DownloadStatus.Done(state.comicId)
                        }
                        is DownloadState.Error -> DownloadStatus.Error(state.comicId, state.message)
                    }
                }
            }

            // Cross-device alignment-pull loop. Independent of the 3s push
            // loop below. Re-keyed on the toggle + interval so changes take
            // effect within one tick. See feature spec
            // `app7-ideas-for-later/cross-device-learner-records-alignment-pull.md`.
            val cloudAlignEnabled by viewModel.cloudAlignEnabled.collectAsState()
            val cloudAlignIntervalSec by viewModel.cloudAlignIntervalSec.collectAsState()
            LaunchedEffect(cloudAlignEnabled, cloudAlignIntervalSec) {
                if (!cloudAlignEnabled) return@LaunchedEffect
                val downloader = buildSceneDownloader()
                if (downloader == null) {
                    println("[Desktop] Align-pull: no Turso config (TURSO_URL/TURSO_AUTH_TOKEN missing)")
                    return@LaunchedEffect
                }
                val merger = DesktopSnapshotMerger(
                    annotationRepository = annotationRepository,
                    chatRepository = chatRepository,
                    learnerDataRepository = learnerDataRepository,
                    pageBubbleOrderingRepository = pageBubbleOrderingRepository,
                )
                val alignmentService = AlignmentService(
                    downloader = downloader,
                    userId = { authManager.getUserId() },
                    merger = merger,
                    cursors = pl.czak.learnlauncher.data.sync.SettingsAlignCursorStore(settingsStore),
                )
                val periodMs = cloudAlignIntervalSec.coerceAtLeast(10).toLong() * 1000L
                println("[Desktop] Align-pull started (every ${periodMs / 1000}s)")
                while (true) {
                    kotlinx.coroutines.delay(periodMs)
                    try {
                        val result = alignmentService.pullAlignment()
                        println("[Desktop] Align-pull tick: ${result.message}")
                        if (result.totalAdded > 0) viewModel.refreshScenesAfterPull()
                    } catch (e: Exception) {
                        println("[Desktop] Align-pull tick failed: ${e.message}")
                    }
                }
            }

            // Auto-sync loop: push every 3s, pull scenes every 30s (every 10th push tick).
            LaunchedEffect(autoSyncEnabled) {
                if (!autoSyncEnabled) return@LaunchedEffect
                println("[Desktop] Cloud sync started (every 3s, scene pull every 30s)")
                var tick = 0
                while (true) {
                    kotlinx.coroutines.delay(3_000)
                    try {
                        val syncService = buildSyncService(
                            annotationRepository = annotationRepository,
                            learnerDataRepository = learnerDataRepository,
                            chatRepository = chatRepository,
                            pageBubbleOrderingRepository = pageBubbleOrderingRepository,
                            authManager = authManager,
                            activeComicId = { activeComicId ?: "_no_comic_" },
                        )
                        if (syncService == null) {
                            println("[Desktop] Auto-sync: no backend configured")
                        } else {
                            val result = syncService.sync()
                            println("[Desktop] Auto-sync tick: ${result.message}")
                        }
                    } catch (e: Exception) {
                        println("[Desktop] Auto-sync tick failed: ${e.message}")
                    }
                    tick += 1
                    if (tick % 10 == 0) {
                        try {
                            val downloader = buildSceneDownloader()
                            if (downloader != null) {
                                val uid = authManager.getUserId() ?: "_"
                                val scenes = downloader.fetchScenes(uid)
                                val added = annotationRepository.upsertScenesFromRemote(scenes)
                                if (added > 0) {
                                    println("[Desktop] Pull-sync: fetched $added new scenes")
                                    viewModel.refreshScenesAfterPull()
                                } else {
                                    println("[Desktop] Pull-sync: 0 new scenes (have ${scenes.size} total)")
                                }
                            }
                        } catch (e: Exception) {
                            println("[Desktop] Pull-sync failed: ${e.message}")
                        }
                    }
                }
            }

            App(
                viewModel = viewModel,
                chatViewModel = chatViewModel,
                isLoggedIn = isLoggedIn,
                onLogin = { userId ->
                    try {
                        val result = authApi.login(userId)
                        authManager.saveToken(result.token, result.expiresAt, userId)
                        isLoggedIn = true
                    } catch (_: AuthException) { }
                },
                onLogout = {
                    authManager.logout()
                    isLoggedIn = false
                },
                onExportJson = {
                    appScope.launch {
                        try {
                            val file = exportService.export()
                            exportService.openContainingFolder(file)
                        } catch (_: Exception) { }
                    }
                },
                onSyncCloud = {
                    appScope.launch {
                        val syncService = buildSyncService(
                            annotationRepository = annotationRepository,
                            learnerDataRepository = learnerDataRepository,
                            chatRepository = chatRepository,
                            pageBubbleOrderingRepository = pageBubbleOrderingRepository,
                            authManager = authManager,
                            activeComicId = { activeComicId ?: "_no_comic_" },
                        )
                        if (syncService == null) {
                            println("[Desktop] Sync not configured: set TURSO_URL+TOKEN, " +
                                "QUEUE_HOST+PORT, or SUPABASE_URL+ANON_KEY")
                        } else {
                            val result = syncService.sync()
                            println("[Desktop] Sync result: ${result.message}")
                        }
                    }
                },
                autoSyncEnabled = autoSyncEnabled,
                onAutoSyncToggled = { enabled ->
                    autoSyncEnabled = enabled
                    settingsStore.putBoolean("auto_sync_enabled", enabled)
                    println("[Desktop] Auto-sync toggled: $enabled")
                },
                isDesktop = true,

                catalogComics = catalogComics,
                downloadStatus = downloadStatus,
                onRefreshCatalog = {
                    if (backendUrl.isNotEmpty()) {
                        appScope.launch {
                            downloadStatus = DownloadStatus.Idle
                            try {
                                val comics = DesktopComicCatalogApi.fetchCatalog(backendUrl)
                                catalogCache = comics
                                catalogComics = comics.map { comic ->
                                    CatalogComicUi(
                                        id = comic.id, name = comic.name,
                                        sizeMb = comic.size_mb, chapters = comic.chapters,
                                        isDownloaded = downloadManager.isDownloaded(comic.id)
                                    )
                                }
                            } catch (e: Exception) {
                                downloadStatus = DownloadStatus.Error("catalog", e.message ?: "Connection failed")
                            }
                        }
                    }
                },
                onDownloadComic = { comicId ->
                    val comic = catalogCache.find { it.id == comicId }
                    if (backendUrl.isNotEmpty() && comic != null) {
                        appScope.launch {
                            // Pair-attach download gate parity with Android: the
                            // login user id rides X-App7-User; a 401/403 triggers
                            // one attach-IP + one retry inside the manager.
                            downloadManager.download(
                                backendUrl, comic,
                                userId = authManager.getUserId(),
                            )
                        }
                    }
                },
                onRemoveComic = { comicId ->
                    appScope.launch {
                        val wasActive = comicId == activeComicId
                        val ok = downloadManager.remove(comicId)
                        if (ok) {
                            downloadedComicIds = downloadManager.getDownloadedIds()
                            catalogComics = catalogCache.map { comic ->
                                CatalogComicUi(
                                    id = comic.id, name = comic.name,
                                    sizeMb = comic.size_mb, chapters = comic.chapters,
                                    isDownloaded = downloadManager.isDownloaded(comic.id)
                                )
                            }
                            if (wasActive) {
                                val next = downloadedComicIds.firstOrNull()
                                if (next != null) {
                                    selectComic(next, settingsStore, downloadManager, viewModel)
                                    activeComicId = next
                                } else {
                                    activeComicId = null
                                    settingsStore.putString("selected_asset_id", null)
                                    viewModel.clearAsset()
                                }
                            }
                        }
                    }
                },
                downloadedComicIds = downloadedComicIds,
                activeComicId = activeComicId,
                onSelectActiveComic = { comicId ->
                    if (comicId != activeComicId) {
                        selectComic(comicId, settingsStore, downloadManager, viewModel)
                        activeComicId = comicId
                    }
                }
            )
        }
    }
}

private fun selectComic(
    comicId: String,
    settingsStore: DesktopSettingsStore,
    downloadManager: DesktopComicDownloadManager,
    viewModel: LauncherViewModel
) {
    settingsStore.putString("selected_asset_id", comicId)
    val comicDir = File(downloadManager.comicsDir, comicId)
    if (comicDir.exists()) {
        val dataSource = FileImageDataSource(comicDir)
        viewModel.switchAsset(dataSource, comicId)
    }
}

private fun parseComicDirArg(args: Array<String>): File? {
    val idx = args.indexOf("--comic-dir")
    if (idx >= 0 && idx + 1 < args.size) {
        val dir = File(args[idx + 1])
        if (dir.exists()) return dir
        System.err.println("Comic directory does not exist: ${dir.absolutePath}")
    }
    return null
}

private const val DEFAULT_BACKEND_URL = "http://129.212.166.125:8080"

private fun resolveBackendUrl(args: Array<String>): String {
    // 1. CLI override
    val idx = args.indexOf("--backend-url")
    if (idx >= 0 && idx + 1 < args.size) return args[idx + 1]

    // 2. server-config.yaml in working dir
    val configFile = File("server-config.yaml")
    if (configFile.exists()) {
        return parseYamlBackendUrl(configFile) ?: DEFAULT_BACKEND_URL
    }

    // 3. Try from classpath (bundled in jar/resources)
    val classPathStream = Thread.currentThread().contextClassLoader.getResourceAsStream("server-config.yaml")
    if (classPathStream != null) {
        val lines = BufferedReader(InputStreamReader(classPathStream)).readLines()
        classPathStream.close()
        return extractBackendUrl(lines) ?: DEFAULT_BACKEND_URL
    }

    // 4. Hardcoded fallback
    return DEFAULT_BACKEND_URL
}

private fun parseYamlBackendUrl(file: File): String? {
    return extractBackendUrl(file.readLines())
}

private fun extractBackendUrl(lines: List<String>): String? {
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("backend_url:")) {
            return trimmed.removePrefix("backend_url:").trim().removeSurrounding("\"")
        }
    }
    return null
}

private fun resolveQueueConfig(args: Array<String>): Pair<String, Int> {
    // 1. CLI override
    val hostIdx = args.indexOf("--queue-host")
    val portIdx = args.indexOf("--queue-port")
    if (hostIdx >= 0 && hostIdx + 1 < args.size && portIdx >= 0 && portIdx + 1 < args.size) {
        return args[hostIdx + 1] to (args[portIdx + 1].toIntOrNull() ?: 0)
    }

    // 2. server-config.yaml (check multiple paths since CWD varies)
    val configFiles = listOf(
        File("server-config.yaml"),
        File("desktopApp/server-config.yaml"),
        File("androidApp/server-config.yaml")
    )
    for (configFile in configFiles) {
        if (configFile.exists()) {
            val result = extractQueueConfig(configFile.readLines())
            if (result.first.isNotEmpty()) return result
        }
    }

    return "" to 0
}

private fun extractQueueConfig(lines: List<String>): Pair<String, Int> {
    var host = ""
    var port = 0
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("queue_host:")) {
            host = trimmed.removePrefix("queue_host:").trim().removeSurrounding("\"")
        }
        if (trimmed.startsWith("queue_port:")) {
            port = trimmed.removePrefix("queue_port:").trim().toIntOrNull() ?: 0
        }
    }
    return host to port
}

// ── Composition root ────────────────────────────────────────────
// Desktop reads .env via System.getenv (set with `set -a && . ./.env && set +a`
// before launching) — there is no BuildConfig generator on the JVM target.

private val desktopHttpClient by lazy { createBackendHttpClient() }

private fun envOrNull(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() }

private fun buildSceneDownloader(): pl.czak.learnlauncher.data.sync.adapter.turso.TursoDownloaderAdapter? {
    val url = envOrNull("TURSO_URL") ?: return null
    val token = envOrNull("TURSO_AUTH_TOKEN") ?: return null
    return pl.czak.learnlauncher.data.sync.adapter.turso.TursoDownloaderAdapter(
        httpClient = desktopHttpClient,
        baseUrl = url,
        authToken = token,
    )
}

private fun buildSyncService(
    annotationRepository: InMemoryAnnotationRepository,
    learnerDataRepository: InMemoryLearnerDataRepository,
    chatRepository: InMemoryChatRepository,
    pageBubbleOrderingRepository: pl.czak.learnlauncher.data.repository.PageBubbleOrderingRepository,
    authManager: DesktopAuthManager,
    activeComicId: () -> String,
): SyncService? {
    val uploader: PayloadUploader = selectUploader(
        config = BackendConfig(
            provider = envOrNull("BACKEND_PROVIDER"),
            tursoUrl = envOrNull("TURSO_URL"),
            tursoAuthToken = envOrNull("TURSO_AUTH_TOKEN"),
            supabaseUrl = envOrNull("SUPABASE_URL"),
            supabaseAnonKey = envOrNull("SUPABASE_ANON_KEY"),
            tcpAvailable = false,
        ),
        httpClient = desktopHttpClient,
        tcpFactory = null,
    ) ?: return null
    return SyncService(
        outbox = DesktopOutbox(
            annotationRepository = annotationRepository,
            learnerDataRepository = learnerDataRepository,
            chatRepository = chatRepository,
            activeComicId = activeComicId,
            pageBubbleOrderingRepository = pageBubbleOrderingRepository,
        ),
        uploader = uploader,
        deviceId = { "desktop-${System.getProperty("user.name", "unknown")}" },
        userId = { authManager.getUserId() },
        appVersion = "1.0-desktop",
    )
}

/** An action that a keyboard shortcut can trigger */
private sealed class KeyAction {
    data object PrevPage : KeyAction()
    data object NextPage : KeyAction()
    data object BubbleUp : KeyAction()
    data object BubbleDown : KeyAction()
    data object EscapeAction : KeyAction()
    data object CancelAnnotation : KeyAction()
    data object AnnotateCurrentBubble : KeyAction()
    data object OpenFreeAnnotation : KeyAction()
    data object ToggleEditMode : KeyAction()
    data class Annotate(val label: String) : KeyAction()
}

/** Default key bindings. Edit this map to remap shortcuts. */
private val keyBindings: Map<Key, KeyAction> = mapOf(
    Key.DirectionLeft to KeyAction.PrevPage,
    Key.DirectionRight to KeyAction.NextPage,
    Key.DirectionUp to KeyAction.BubbleUp,
    Key.DirectionDown to KeyAction.BubbleDown,
    Key.Escape to KeyAction.EscapeAction,
    Key.Spacebar to KeyAction.AnnotateCurrentBubble,
    Key.Zero to KeyAction.OpenFreeAnnotation,
    Key.One to KeyAction.Annotate("understood"),
    Key.Two to KeyAction.Annotate("partially"),
    Key.Three to KeyAction.Annotate("not_understood"),
    Key.Four to KeyAction.CancelAnnotation,
    Key.E to KeyAction.ToggleEditMode,
)

private fun handleKeyDown(event: KeyEvent, viewModel: LauncherViewModel): Boolean {
    // While the free-annotation dialog is open, only Escape is handled at the window level —
    // every other key (Space, letters, etc.) must reach the focused text field unmodified.
    if (viewModel.freeAnnotationDialogOpen.value) {
        return if (event.key == Key.Escape) {
            viewModel.closeFreeAnnotationDialog(); true
        } else false
    }

    // Same passthrough rule for the crop free-form text dialog.
    if (viewModel.cropFreeFormDialogOpen.value) {
        return if (event.key == Key.Escape) {
            viewModel.closeCropFreeFormDialog(); true
        } else false
    }

    // Crop annotation bar shortcuts: 1=Bubble, 2=Scene, 3=Free Form, Esc=cancel.
    // Intercepts before the global keyBindings so 1/2/3 don't fire the bubble-annotation
    // shortcuts while the crop bar is showing.
    if (viewModel.cropAnnotationBarOpen.value) {
        return when (event.key) {
            Key.One -> { viewModel.saveCropAnnotation("bubble"); true }
            Key.Two -> { viewModel.saveCropAnnotation("scene"); true }
            Key.Three -> { viewModel.openCropFreeFormDialog(); true }
            Key.Escape -> { viewModel.cancelCropSelection(); true }
            else -> false
        }
    }

    val action = keyBindings[event.key] ?: return false
    return when (action) {
        KeyAction.PrevPage -> { viewModel.previousPage(); true }
        KeyAction.NextPage -> { viewModel.nextPage(); true }
        KeyAction.BubbleUp -> { navigateBubble(viewModel, -1); true }
        KeyAction.BubbleDown -> { navigateBubble(viewModel, +1); true }
        KeyAction.EscapeAction -> when {
            viewModel.freeAnnotationDialogOpen.value -> { viewModel.closeFreeAnnotationDialog(); true }
            viewModel.annotationBarOpen.value -> {
                viewModel.closeAnnotationBar(); viewModel.clearHighlightedBubble(); true
            }
            viewModel.isZoomed.value -> { viewModel.exitZoom(); true }
            viewModel.editModeEnabled.value -> { viewModel.editorCancel(); true }
            else -> false
        }
        KeyAction.CancelAnnotation -> { viewModel.clearHighlightedBubble(); true }
        KeyAction.AnnotateCurrentBubble -> {
            if (!viewModel.annotationOnSpace.value) false
            else {
                viewModel.toggleAnnotationBarForHighlighted()
                true
            }
        }
        KeyAction.OpenFreeAnnotation -> {
            if (viewModel.annotationBarOpen.value) {
                viewModel.openFreeAnnotationDialog(); true
            } else false
        }
        KeyAction.ToggleEditMode -> {
            if (viewModel.isZoomed.value) false
            else if (viewModel.annotationBarOpen.value || viewModel.cropAnnotationBarOpen.value) false
            else {
                if (viewModel.editModeEnabled.value) viewModel.editorCancel()
                else viewModel.toggleEditMode(true)
                true
            }
        }
        is KeyAction.Annotate -> { viewModel.annotateHighlightedBubble(action.label) }
    }
}

private fun navigateBubble(viewModel: LauncherViewModel, delta: Int) {
    // Iterate in DISPLAY order so saved per-page user orderings (edit-mode reorder feature)
    // actually affect arrow-key traversal.  When no ordering is saved this is identical to
    // the original list, so behavior is unchanged for unedited pages.
    val boxes = viewModel.displayOrderedBoxes.value
    if (boxes.isEmpty()) return
    val currentDisplayIdx = viewModel.getHighlightedDisplayIndex()
    val nextDisplayIdx = if (currentDisplayIdx < 0) {
        if (delta > 0) 0 else boxes.lastIndex
    } else {
        (currentDisplayIdx + delta).coerceIn(0, boxes.lastIndex)
    }
    viewModel.selectBubbleByDisplayIndex(nextDisplayIdx, fromArrow = true)
}
