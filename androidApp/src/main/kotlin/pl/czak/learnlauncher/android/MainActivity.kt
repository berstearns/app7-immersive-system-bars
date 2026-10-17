package pl.czak.learnlauncher.android

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.czak.learnlauncher.App
import pl.czak.learnlauncher.data.*
import pl.czak.learnlauncher.data.AndroidPageBubbleOrderingRepository
import pl.czak.learnlauncher.data.auth.AuthManager
import pl.czak.learnlauncher.data.auth.LocalAuthApi
import pl.czak.learnlauncher.data.db.AppDatabase
import pl.czak.learnlauncher.android.askip.AskIpBootstrap
import pl.czak.learnlauncher.data.download.AdmissionClient
import pl.czak.learnlauncher.data.download.CatalogComic
import pl.czak.learnlauncher.data.download.ComicCatalogApi
import pl.czak.learnlauncher.data.download.ComicDownloadManager
import pl.czak.learnlauncher.data.download.DownloadState
import pl.czak.learnlauncher.data.export.JsonExportService
import pl.czak.learnlauncher.data.source.AssetsImageDataSource
import pl.czak.learnlauncher.data.source.FileImageDataSource
import pl.czak.learnlauncher.data.sync.AndroidOutbox
import pl.czak.learnlauncher.data.sync.AuthManagerAuthProvider
import pl.czak.learnlauncher.data.sync.BackendConfig
import pl.czak.learnlauncher.data.sync.RemoteSyncApi
import pl.czak.learnlauncher.data.sync.SyncResult
import pl.czak.learnlauncher.data.sync.SyncService
import pl.czak.learnlauncher.data.sync.createBackendHttpClient
import pl.czak.learnlauncher.data.sync.ports.PayloadUploader
import pl.czak.learnlauncher.data.sync.selectUploader
import pl.czak.learnlauncher.data.sync.tcp.TcpQueueSyncApi
import pl.czak.learnlauncher.domain.navigation.ImageSequenceNavigator
import pl.czak.learnlauncher.domain.native.App7Native
import pl.czak.learnlauncher.domain.session.SessionTimeTracker
import pl.czak.learnlauncher.ui.AppInfo
import pl.czak.learnlauncher.ui.CatalogComicUi
import pl.czak.learnlauncher.ui.DownloadStatus
import pl.czak.learnlauncher.viewmodel.ChatViewModel
import pl.czak.learnlauncher.viewmodel.LauncherViewModel
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var authManager: AuthManager
    private lateinit var viewModel: LauncherViewModel
    private lateinit var chatViewModel: ChatViewModel
    private lateinit var learnerDataRepository: AndroidLearnerDataRepository
    private lateinit var settingsStore: AndroidSettingsStore
    private var lastKnownAssetId: String? = null

    // Comic download state
    private lateinit var downloadManager: ComicDownloadManager
    private val _catalogComics = mutableStateListOf<CatalogComicUi>()
    private val _downloadStatus = mutableStateOf<DownloadStatus>(DownloadStatus.Idle)
    private val _downloadedComicIds = mutableStateListOf<String>()
    private val _activeComicId = mutableStateOf<String?>(null)
    private var catalogCache: List<CatalogComic> = emptyList()

    // Google Sign-In
    private lateinit var googleSignInClient: GoogleSignInClient
    private val _googleEmail = mutableStateOf<String?>(null)

    // Auto-sync
    private var autoSyncJob: Job? = null
    private val autoSyncEnabled = mutableStateOf(false)

    // Cross-device alignment pull (see feature spec
    // `app7-ideas-for-later/cross-device-learner-records-alignment-pull.md`).
    // Single job restarted on toggle/interval change via observeCloudAlignSettings().
    private var cloudAlignJob: Job? = null

    // Session hierarchy tracker (Part 2)
    private lateinit var sessionHierarchy: pl.czak.learnlauncher.data.session.SessionHierarchyTracker

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            _googleEmail.value = account.email
            Toast.makeText(this, "Signed in as ${account.email}", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                learnerDataRepository.logSettingsChange("google_account", "signed_out", "signed_in:${account.email}")
            }
        } catch (e: ApiException) {
            Log.w("MainActivity", "Google sign-in failed: code=${e.statusCode}", e)
            Toast.makeText(this, "Sign-in failed (code ${e.statusCode})", Toast.LENGTH_LONG).show()
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        Log.i(
            "FeatureFolder",
            "BUILD_QUEUE_HOST=${BuildConfig.QUEUE_HOST} variant=app7-explicit-db-hierarchy_20260409_154552"
        )

        // PHASE-6 SMOKE: full-coverage check that every native API surface
        // dispatches correctly through JNI.  Logged once on each Activity
        // creation; if any line fails the .so was either not loaded or
        // a symbol drifted from the Kotlin expect.
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
            val endType = out[0]   // 2 = APP
            App7Native.sessionTrackerFree(tHandle)

            val nHandle = App7Native.navNew()
            App7Native.navInitialize(nHandle, 5, intArrayOf(0, 3))
            val nextIdx = App7Native.navNext(nHandle)
            val nextChapterIdx = App7Native.navNextChapter(nHandle)
            App7Native.navFree(nHandle)

            Log.i(
                "App7Native",
                "PHASE-6-SMOKE perm=$perm clamped=${clp.toList()} contains=$cnt " +
                "degenerate=$deg poolSize=$ps sample(42,3)=$sIdx firstLabel=$pLab " +
                "endApp(count=$endCount,type=$endType) navNext=$nextIdx navNextChapter=$nextChapterIdx"
            )
        }.onFailure {
            Log.e("App7Native", "PHASE-6-SMOKE FAILED: ${it.message}", it)
        }

        setupLockScreenDisplay()

        authManager = AuthManager(this)
        // ask-ip: kick off runtime endpoint resolution. Non-blocking — starts on
        // baked BuildConfig endpoints and upgrades to cache/resolver when ready.
        pl.czak.learnlauncher.android.askip.AskIpBootstrap.start(this, lifecycleScope)
        val db = AppDatabase.getInstance(this)
        settingsStore = AndroidSettingsStore(this)
        autoSyncEnabled.value = settingsStore.getBoolean("auto_sync_enabled", false)
        sessionHierarchy = pl.czak.learnlauncher.data.session.SessionHierarchyTracker(db)
        lifecycleScope.launch {
            sessionHierarchy.onAppStart()
            // If a comic is already selected at launch, open its comic_session
            val initialComicId = settingsStore.getString("selected_asset_id", null)
            if (initialComicId != null) {
                sessionHierarchy.onComicSelected(initialComicId)
            }
        }

        // Handle intent extras (lets tests toggle auto_sync without UI)
        if (intent?.getBooleanExtra("auto_sync", false) == true) {
            autoSyncEnabled.value = true
            settingsStore.putBoolean("auto_sync_enabled", true)
        }
        val imageDataSource = AssetsImageDataSource(this)
        val annotationRepository = AndroidAnnotationRepository(db, settingsStore)
        val sessionRepository = AndroidSessionRepository(db, settingsStore)
        learnerDataRepository = AndroidLearnerDataRepository(db, settingsStore)
        val tokenRegionRepository = AndroidTokenRegionRepository(this)
        val translationRepository = AndroidTranslationRepository(this, db)
        val chatRepository = AndroidChatRepository(db)
        val pageBubbleOrderingRepository = AndroidPageBubbleOrderingRepository(db)
        // E2E_EXTRA_USERS is empty in prod builds; e2e builds inject a synthetic
        // login id so tests never authenticate as a real user (daily rule 11).
        val authApi = LocalAuthApi(
            extraValidUsers = BuildConfig.E2E_EXTRA_USERS
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        )

        viewModel = LauncherViewModel(
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

        chatViewModel = ChatViewModel(chatRepository)

        // Cross-device alignment pull observer (see feature spec
        // `app7-ideas-for-later/cross-device-learner-records-alignment-pull.md`).
        // Wired here so the loop starts on activity create and restarts
        // automatically when the user toggles cloud_align_enabled or
        // adjusts the interval in FlagsScreen.
        observeCloudAlignSettings()

        // Honor the landscape-reader setting: when off, hard-lock to portrait
        // (preserves pre-feature behavior); when on, unlock to sensor-driven.
        // Manifest no longer hard-locks, so this flag is the sole gate.
        requestedOrientation = if (settingsStore.getBoolean("landscape_enabled", false)) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        lifecycleScope.launch {
            viewModel.landscapeEnabled.collectLatest { enabled ->
                requestedOrientation = if (enabled) {
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }
        }

        lastKnownAssetId = settingsStore.getString("selected_asset_id", null)

        // Google Sign-In setup
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
        _googleEmail.value = GoogleSignIn.getLastSignedInAccount(this)?.email

        // Initialize download manager
        downloadManager = ComicDownloadManager(this)
        refreshDownloadedList()
        _activeComicId.value = settingsStore.getString("selected_asset_id", null)

        // Observe download state changes
        lifecycleScope.launch {
            downloadManager.state.collectLatest { state ->
                _downloadStatus.value = when (state) {
                    is DownloadState.Idle -> DownloadStatus.Idle
                    is DownloadState.Downloading -> DownloadStatus.Downloading(state.comicId, state.progress)
                    is DownloadState.Extracting -> DownloadStatus.Extracting(state.comicId)
                    is DownloadState.Done -> {
                        refreshDownloadedList()
                        refreshCatalogUi()
                        // Auto-select if first download
                        if (_activeComicId.value == null) {
                            selectActiveComic(state.comicId)
                        }
                        DownloadStatus.Done(state.comicId)
                    }
                    is DownloadState.Error -> DownloadStatus.Error(state.comicId, state.message)
                }
            }
        }

        // If user already has a selected comic, switch to it
        val savedAssetId = settingsStore.getString("selected_asset_id", null)
        if (savedAssetId != null) {
            val comicDir = File(getExternalFilesDir("comics"), savedAssetId)
            if (comicDir.exists() && (File(comicDir, "manifest.json").exists() || File(comicDir, "images.json").exists())) {
                val dataSource = FileImageDataSource(comicDir)
                viewModel.switchAsset(dataSource, savedAssetId)
            }
        }

        setContent {
            var isLoggedIn by remember { mutableStateOf(authManager.isLoggedIn()) }
            val catalogComics = _catalogComics.toList()
            val downloadStatus by _downloadStatus
            val downloadedComicIds = _downloadedComicIds.toList()
            val activeComicId by _activeComicId
            val googleEmail by _googleEmail
            val currentScreen by viewModel.currentScreen.collectAsState()
            val showAppsOverlay by viewModel.showAppsOverlay.collectAsState()
            val isFocusMode by viewModel.isFocusMode.collectAsState()

            // Back must NOT fall through to the system launcher (app7 is a HOME
            // launcher — a raw back would surface the underlying launcher).
            // Consume it to close the current in-app surface, in priority order:
            //   Focus mode   -> exit Focus (restores full-page reader chrome)
            //   Apps overlay -> close it (back to reading, "Apps" chrome visible)
            //   non-Main screen -> back to reading
            BackHandler(
                enabled = isFocusMode ||
                    showAppsOverlay ||
                    currentScreen != pl.czak.learnlauncher.viewmodel.Screen.Main
            ) {
                when {
                    isFocusMode -> viewModel.exitFocusScene()
                    showAppsOverlay -> viewModel.hideAppsOverlay()
                    else -> viewModel.currentScreen.value = pl.czak.learnlauncher.viewmodel.Screen.Main
                }
            }

            Box(
                modifier = Modifier
                    .background(Color(0xFF1E1E1E))
                    .windowInsetsPadding(WindowInsets.displayCutout)
            ) {
                App(
                    viewModel = viewModel,
                    chatViewModel = chatViewModel,
                    isLoggedIn = isLoggedIn,
                    onLogin = { userId ->
                        lifecycleScope.launch {
                            try {
                                val result = authApi.login(userId)
                                authManager.saveToken(result.token, result.expiresAt, userId)
                                isLoggedIn = true
                            } catch (_: Exception) { }
                        }
                    },
                    onLogout = {
                        authManager.logout()
                        isLoggedIn = false
                    },
                    onLaunchApp = { packageName ->
                        val pm = packageManager
                        val intent = pm.getLaunchIntentForPackage(packageName)
                        intent?.let {
                            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(it)
                        }
                        lifecycleScope.launch {
                            learnerDataRepository.logAppLaunch(
                                packageName = packageName,
                                currentChapter = viewModel.currentImage.value?.chapter,
                                currentPageId = viewModel.currentImage.value?.id
                            )
                        }
                    },
                    onSyncCloud = {
                        val syncService = buildSyncService(authManager, learnerDataRepository)
                        if (syncService != null) {
                            lifecycleScope.launch {
                                val result = syncService.sync()
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "No backend configured (set TURSO_URL+TOKEN, QUEUE_HOST+PORT, or SUPABASE_*)",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                    // The Annotation Log invokes this on open: pull the logged-in user's
                    // annotations from Turso into local Room. Targeted (small tables only) so
                    // it returns quickly — see runAnnotationPull.
                    onRefreshAnnotationLog = { runAnnotationPull() },
                    onExportJson = {
                        lifecycleScope.launch {
                            val exportService = JsonExportService(learnerDataRepository)
                            try {
                                val json = exportService.exportToJsonString()
                                val file = File(cacheDir, "mangareader-data-${System.currentTimeMillis()}.json")
                                file.writeText(json)
                                val uri = FileProvider.getUriForFile(
                                    this@MainActivity,
                                    "${packageName}.fileprovider",
                                    file
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_SUBJECT, "Manga Reader Data Export")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                startActivity(Intent.createChooser(shareIntent, "Send export via..."))
                            } catch (_: Exception) { }
                        }
                    },
                    onLoadApps = {
                        loadInstalledApps()
                    },
                    onOpenHomeSettings = {
                        openHomeSettings()
                    },
                    catalogComics = catalogComics,
                    downloadStatus = downloadStatus,
                    onRefreshCatalog = {
                        val backendUrl = AskIpBootstrap.backendUrl()
                        if (backendUrl.isNotEmpty()) {
                            lifecycleScope.launch {
                                _downloadStatus.value = DownloadStatus.Idle
                                try {
                                    val comics = ComicCatalogApi.fetchCatalog(backendUrl)
                                    catalogCache = comics
                                    refreshCatalogUi()
                                } catch (e: Exception) {
                                    _downloadStatus.value = DownloadStatus.Error("catalog", e.message ?: "Connection failed")
                                }
                            }
                        }
                    },
                    onDownloadComic = { comicId ->
                        val backendUrl = AskIpBootstrap.backendUrl()
                        val comic = catalogCache.find { it.id == comicId }
                        if (backendUrl.isNotEmpty() && comic != null) {
                            lifecycleScope.launch {
                                downloadManager.download(
                                    backendUrl, comic, authManager.getUserId(), AskIpBootstrap.admitUrl(),
                                )
                            }
                        }
                    },
                    onRemoveComic = { comicId ->
                        lifecycleScope.launch {
                            val wasActive = comicId == _activeComicId.value
                            val ok = downloadManager.remove(comicId)
                            if (ok) {
                                refreshDownloadedList()
                                refreshCatalogUi()
                                learnerDataRepository.logSettingsChange("removed_comic", "", comicId)
                                if (wasActive) {
                                    val next = _downloadedComicIds.firstOrNull()
                                    if (next != null) {
                                        selectActiveComic(next)
                                    } else {
                                        _activeComicId.value = null
                                        settingsStore.putString("selected_asset_id", null)
                                        viewModel.clearAsset()
                                    }
                                }
                                Toast.makeText(this@MainActivity, "Removed ${comicId.replace("-", " ")}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    downloadedComicIds = downloadedComicIds,
                    activeComicId = activeComicId,
                    onSelectActiveComic = { comicId ->
                        selectActiveComic(comicId)
                    },
                    googleAccountEmail = googleEmail,
                    onGoogleSignIn = {
                        signInLauncher.launch(googleSignInClient.signInIntent)
                    },
                    onAttachIp = {
                        // Enrollment keys on the app's OWN logged-in user id
                        // (auth.xml user_id) — Google email is metadata only.
                        val userId = authManager.getUserId()
                        val backendUrl = AskIpBootstrap.backendUrl()
                        if (userId.isNullOrEmpty() || backendUrl.isEmpty()) {
                            Toast.makeText(this@MainActivity, "Log in first to attach this device", Toast.LENGTH_SHORT).show()
                        } else {
                            lifecycleScope.launch {
                                val res = AdmissionClient.attachIp(backendUrl, userId, AskIpBootstrap.admitUrl())
                                Toast.makeText(
                                    this@MainActivity,
                                    if (res.ok) "IP added: ${res.detail}" else "Attach failed: ${res.detail}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    onGoogleSignOut = {
                        googleSignInClient.signOut().addOnCompleteListener {
                            _googleEmail.value = null
                            Toast.makeText(this@MainActivity, "Signed out", Toast.LENGTH_SHORT).show()
                            lifecycleScope.launch {
                                learnerDataRepository.logSettingsChange("google_account", "signed_in", "signed_out")
                            }
                        }
                    },
                    autoSyncEnabled = autoSyncEnabled.value,
                    onAutoSyncToggled = { enabled ->
                        settingsStore.putBoolean("auto_sync_enabled", enabled)
                        autoSyncEnabled.value = enabled
                        if (enabled) startAutoSyncLoop() else stopAutoSyncLoop()
                    }
                )
            }
        }
    }

    private fun startAutoSyncLoop() {
        autoSyncJob?.cancel()
        autoSyncJob = lifecycleScope.launch {
            while (isActive) {
                delay(10_000)
                runCatching {
                    Log.i("AutoSync", "triggering SyncService.sync()")
                    val syncService = buildSyncService(authManager, learnerDataRepository) ?: run {
                        Log.i("AutoSync", "no backend configured; skipping")
                        return@runCatching
                    }
                    val res: SyncResult = syncService.sync()
                    Log.i("AutoSync", "result: ${res.message} total=${res.totalSynced}")
                }.onFailure { Log.w("AutoSync", "tick failed: ${it.message}") }
            }
        }
    }

    private val httpClient by lazy { createBackendHttpClient() }

    /**
     * Single composition-root call. Reads BuildConfig (which is generated from
     * .env at build time), assembles the right uploader per the priority
     * order Turso → TCP → Supabase, and wires it into a SyncService.
     */
    private fun buildSyncService(
        authManager: pl.czak.learnlauncher.data.auth.AuthManager,
        learnerDataRepository: pl.czak.learnlauncher.data.AndroidLearnerDataRepository,
    ): SyncService? {
        val authProvider = AuthManagerAuthProvider(authManager)
        // ask-ip: prefer resolver-supplied endpoints; fall back to baked.
        val tcpHost = AskIpBootstrap.queueHost()
        val tcpPort = AskIpBootstrap.queuePort()
        val backendUrl = AskIpBootstrap.backendUrl()
        val tcpFactory: (() -> PayloadUploader)? = when {
            tcpHost.isNotEmpty() && tcpPort > 0 -> {
                { TcpQueueSyncApi(tcpHost, tcpPort, authProvider) }
            }
            backendUrl.isNotEmpty() -> {
                { RemoteSyncApi(backendUrl, authProvider) }
            }
            else -> null
        }
        val uploader = selectUploader(
            config = BackendConfig(
                provider = BuildConfig.BACKEND_PROVIDER.takeIf { it.isNotEmpty() },
                tursoUrl = BuildConfig.TURSO_URL.takeIf { it.isNotEmpty() },
                tursoAuthToken = BuildConfig.TURSO_AUTH_TOKEN.takeIf { it.isNotEmpty() },
                supabaseUrl = BuildConfig.SUPABASE_URL.takeIf { it.isNotEmpty() },
                supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY.takeIf { it.isNotEmpty() },
                tcpAvailable = tcpFactory != null,
            ),
            httpClient = httpClient,
            tcpFactory = tcpFactory,
        ) ?: return null
        return SyncService(
            outbox = AndroidOutbox(learnerDataRepository),
            uploader = uploader,
            deviceId = { android.os.Build.MODEL },
            userId = { authManager.getUserId() },
            appVersion = "1.0-kmp",
            correlationIdGen = { java.util.UUID.randomUUID().toString() },
        )
    }

    private fun stopAutoSyncLoop() {
        autoSyncJob?.cancel()
        autoSyncJob = null
    }

    /**
     * Cross-device alignment pull (Android side).  Observes the
     * `cloud_align_enabled` + `cloud_align_interval_seconds` settings via
     * the LauncherViewModel's StateFlows and restarts the inner loop on
     * any change.  Each tick fetches all rows for the current user_id from
     * Turso and merges into Room via [AndroidSnapshotMerger].
     */
    private fun observeCloudAlignSettings() {
        lifecycleScope.launch {
            viewModel.cloudAlignEnabled
                .combine(viewModel.cloudAlignIntervalSec) { e, i -> e to i }
                .collectLatest { (enabled, intervalSec) ->
                    cloudAlignJob?.cancel()
                    if (!enabled) {
                        Log.i("AlignPull", "disabled — no loop")
                        return@collectLatest
                    }
                    cloudAlignJob = lifecycleScope.launch {
                        val periodMs = intervalSec.coerceAtLeast(10).toLong() * 1000L
                        Log.i("AlignPull", "started (every ${periodMs / 1000}s)")
                        // Fire one tick immediately so freshly-installed
                        // devices populate before waiting a full interval.
                        runAlignmentTick()
                        while (currentCoroutineContext().isActive) {
                            delay(periodMs)
                            runAlignmentTick()
                        }
                    }
                }
        }
    }

    private suspend fun runAlignmentTick() {
        runCatching {
            val service = buildAlignmentService() ?: run {
                Log.i("AlignPull", "no Turso config — skipping tick")
                return@runCatching
            }
            val result = service.pullAlignment()
            Log.i("AlignPull", "tick: ${result.message}")
        }.onFailure { Log.w("AlignPull", "tick failed: ${it.message}", it) }
    }

    private fun buildAlignmentService(): pl.czak.learnlauncher.data.sync.AlignmentService? {
        val tursoUrl = BuildConfig.TURSO_URL.takeIf { it.isNotEmpty() } ?: return null
        val tursoToken = BuildConfig.TURSO_AUTH_TOKEN.takeIf { it.isNotEmpty() } ?: return null
        val downloader = pl.czak.learnlauncher.data.sync.adapter.turso.TursoDownloaderAdapter(
            httpClient = httpClient,
            baseUrl = tursoUrl,
            authToken = tursoToken,
        )
        val db = pl.czak.learnlauncher.data.db.AppDatabase.getInstance(this)
        val merger = pl.czak.learnlauncher.data.AndroidSnapshotMerger(db)
        return pl.czak.learnlauncher.data.sync.AlignmentService(
            downloader = downloader,
            userId = { authManager.getUserId() },
            merger = merger,
            cursors = pl.czak.learnlauncher.data.sync.SettingsAlignCursorStore(
                pl.czak.learnlauncher.data.AndroidSettingsStore(this)
            ),
        )
    }

    /**
     * Targeted pull for the Annotation Log: fetch just the SMALL per-user tables from
     * Turso (which include annotation_records) and merge them into local Room.
     * Deliberately NOT the full pullAlignment() — that also streams the big append-only
     * telemetry tables (session_events, page_interactions) in cursored batches, which
     * can take far longer than the log's on-open spinner should wait, and are irrelevant
     * to the annotation log.
     */
    private suspend fun runAnnotationPull() {
        runCatching {
            val tursoUrl = BuildConfig.TURSO_URL.takeIf { it.isNotEmpty() } ?: return@runCatching
            val tursoToken = BuildConfig.TURSO_AUTH_TOKEN.takeIf { it.isNotEmpty() } ?: return@runCatching
            val uid = authManager.getUserId()?.takeIf { it.isNotEmpty() } ?: run {
                Log.i("AnnPull", "not logged in — skipping annotation pull"); return@runCatching
            }
            val downloader = pl.czak.learnlauncher.data.sync.adapter.turso.TursoDownloaderAdapter(
                httpClient = httpClient,
                baseUrl = tursoUrl,
                authToken = tursoToken,
            )
            // ONLY annotations — one SELECT. Merging a snapshot that carries just the
            // annotations list touches only annotation_records (the other mergeX steps
            // no-op on empty lists).
            val annotations = downloader.fetchAnnotationsForUser(uid)
            val snapshot = pl.czak.learnlauncher.data.sync.model.RemoteLearnerSnapshot(
                userId = uid, annotations = annotations
            )
            val db = pl.czak.learnlauncher.data.db.AppDatabase.getInstance(this)
            val stats = pl.czak.learnlauncher.data.AndroidSnapshotMerger(db).merge(snapshot)
            Log.i("AnnPull", "pulled ${annotations.size} annotations for $uid; merged=${stats["annotation_records"]?.added ?: 0}")
        }.onFailure { Log.w("AnnPull", "annotation pull failed: ${it.message}", it) }
    }

    override fun onStart() {
        super.onStart()
        if (autoSyncEnabled.value) startAutoSyncLoop()
    }

    override fun onStop() {
        stopAutoSyncLoop()
        super.onStop()
    }

    override fun onDestroy() {
        // Close the session hierarchy chain (app_session → comic → chapter → page).
        // Uses lifecycleScope.launchWhenCreated so the close-out SQL lands
        // before the process is reaped. Best-effort; worker-side close-out
        // policy (objective 24) is the final safety net.
        lifecycleScope.launch {
            runCatching { sessionHierarchy.onAppStop() }
                .onFailure { Log.w("SessionHierarchy", "onAppStop failed: ${it.message}") }
        }

        // Phase 3b: release native-domain handles before the Activity dies.
        // Without these, libapp7_domain.so leaks ~24 + ~32 bytes per Activity
        // lifetime — harmless until you spawn many but cheap to do right.
        runCatching { viewModel.closeNativeHandles() }

        super.onDestroy()
    }

    private fun selectActiveComic(comicId: String) {
        val oldId = _activeComicId.value
        if (comicId != oldId) {
            _activeComicId.value = comicId
            settingsStore.putString("selected_asset_id", comicId)
            lastKnownAssetId = comicId
            lifecycleScope.launch {
                runCatching { sessionHierarchy.onComicSelected(comicId) }
                    .onFailure { Log.w("SessionHierarchy", "onComicSelected failed: ${it.message}") }
            }

            val comicDir = File(getExternalFilesDir("comics"), comicId)
            if (comicDir.exists()) {
                val dataSource = FileImageDataSource(comicDir)
                viewModel.switchAsset(dataSource, comicId)
            }

            lifecycleScope.launch {
                learnerDataRepository.logSettingsChange("selected_asset_id", oldId ?: "", comicId)
            }

            Toast.makeText(this, "Active comic: ${comicId.replace("-", " ")}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshDownloadedList() {
        val downloadPrefs = getSharedPreferences("downloaded_comics", MODE_PRIVATE)
        val downloaded = downloadPrefs.all.filter { it.value == true }.keys.sorted()
        _downloadedComicIds.clear()
        _downloadedComicIds.addAll(downloaded)
    }

    private fun refreshCatalogUi() {
        _catalogComics.clear()
        _catalogComics.addAll(catalogCache.map { comic ->
            CatalogComicUi(
                id = comic.id,
                name = comic.name,
                sizeMb = comic.sizeMb,
                chapters = comic.chapters,
                isDownloaded = downloadManager.isDownloaded(comic.id)
            )
        })
    }

    private fun setupLockScreenDisplay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val keyguardManager = getSystemService(android.app.KeyguardManager::class.java)
            keyguardManager?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }
    }

    private fun loadInstalledApps(): List<AppInfo> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)
        return resolveInfos
            .filter { it.activityInfo.packageName != packageName }
            .map { resolveInfo ->
                AppInfo(
                    packageName = resolveInfo.activityInfo.packageName,
                    label = resolveInfo.loadLabel(pm).toString()
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun openHomeSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_HOME_SETTINGS)
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::viewModel.isInitialized) {
            // Check if comic selection changed
            val currentAssetId = settingsStore.getString("selected_asset_id", null)
            if (currentAssetId != null && currentAssetId != lastKnownAssetId) {
                lastKnownAssetId = currentAssetId
                val comicDir = File(getExternalFilesDir("comics"), currentAssetId)
                if (comicDir.exists() && (File(comicDir, "manifest.json").exists() || File(comicDir, "images.json").exists())) {
                    val dataSource = FileImageDataSource(comicDir)
                    viewModel.switchAsset(dataSource, currentAssetId)
                    return
                }
            }
            viewModel.reloadSettingsIfChanged()
            viewModel.onAppResumed()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::viewModel.isInitialized) {
            viewModel.onAppPaused()
        }
    }
}
