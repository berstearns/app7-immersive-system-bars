package pl.czak.learnlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.czak.learnlauncher.domain.catalog.Language
import pl.czak.learnlauncher.domain.catalog.SizeBucket
import pl.czak.learnlauncher.domain.catalog.facetsOf
import pl.czak.learnlauncher.viewmodel.LauncherViewModel

data class CatalogComicUi(
    val id: String,
    val name: String,
    val sizeMb: Double,
    val chapters: Int,
    val isDownloaded: Boolean
)

sealed class DownloadStatus {
    data object Idle : DownloadStatus()
    data class Downloading(val comicId: String, val progress: Int) : DownloadStatus()
    data class Extracting(val comicId: String) : DownloadStatus()
    data class Done(val comicId: String) : DownloadStatus()
    data class Error(val comicId: String, val message: String) : DownloadStatus()
}

@Composable
fun SettingsScreen(
    viewModel: LauncherViewModel,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenAnnotationLog: () -> Unit,
    onOpenFlags: () -> Unit,
    onSyncCloud: (() -> Unit)?,
    onExportJson: (() -> Unit)?,
    onOpenHomeSettings: (() -> Unit)? = null,
    catalogComics: List<CatalogComicUi> = emptyList(),
    downloadStatus: DownloadStatus = DownloadStatus.Idle,
    onRefreshCatalog: (() -> Unit)? = null,
    onDownloadComic: ((String) -> Unit)? = null,
    onRemoveComic: ((String) -> Unit)? = null,
    downloadedComicIds: List<String> = emptyList(),
    activeComicId: String? = null,
    onSelectActiveComic: ((String) -> Unit)? = null,
    googleAccountEmail: String? = null,
    onGoogleSignIn: (() -> Unit)? = null,
    onGoogleSignOut: (() -> Unit)? = null,
    onAttachIp: (() -> Unit)? = null
) {
    val scrollState = rememberScrollState()
    var downloadsExpanded by remember { mutableStateOf(false) }
    var removeConfirmFor by remember { mutableStateOf<CatalogComicUi?>(null) }

    // Fetch catalog on first composition
    LaunchedEffect(onRefreshCatalog) {
        onRefreshCatalog?.invoke()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Expose Compose testTag as resource-id so UI automation (Maestro) can target
            // per-comic Download buttons deterministically. Inert in production.
            .testTagsAsResourceIds()
            .background(Color(0xFF121212))
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("< Back", color = Color(0xFF4CAF50))
            }
            Spacer(Modifier.weight(1f))
            Text("Settings", color = Color.White, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(64.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Active Comic Selector ──
        if (downloadedComicIds.isNotEmpty() && onSelectActiveComic != null) {
            SectionHeader("Active Comic")

            var expanded by remember { mutableStateOf(false) }
            val activeName = activeComicId
                ?.replace("-", " ")
                ?.replaceFirstChar { it.uppercase() }
                ?: "None selected"

            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text(activeName, modifier = Modifier.weight(1f))
                    Text(" \u25BC", color = Color.Gray)
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    downloadedComicIds.forEach { comicId ->
                        val displayName = comicId
                            .replace("-", " ")
                            .replaceFirstChar { it.uppercase() }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    displayName,
                                    color = if (comicId == activeComicId) Color(0xFF4CAF50) else Color.White
                                )
                            },
                            onClick = {
                                expanded = false
                                onSelectActiveComic(comicId)
                            }
                        )
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF333333), modifier = Modifier.padding(vertical = 8.dp))
        }

        // ── Comic Downloads ──
        if (onRefreshCatalog != null) {
            // Clickable header that collapses/expands the catalog list
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Comic Downloads",
                    color = Color(0xFF4CAF50),
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { downloadsExpanded = !downloadsExpanded }) {
                    Text(
                        if (downloadsExpanded) "Hide \u25B2" else "Show \u25BC",
                        color = Color(0xFF4CAF50)
                    )
                }
            }

            // Status text
            val statusText = when (downloadStatus) {
                is DownloadStatus.Idle -> if (catalogComics.isEmpty()) "Tap Refresh to load catalog" else "${catalogComics.size} comics available"
                is DownloadStatus.Downloading -> "Downloading ${downloadStatus.comicId}... ${downloadStatus.progress}%"
                is DownloadStatus.Extracting -> "Extracting ${downloadStatus.comicId}..."
                is DownloadStatus.Done -> "Downloaded ${downloadStatus.comicId}"
                is DownloadStatus.Error -> "Error: ${downloadStatus.message}"
            }
            val statusColor = when (downloadStatus) {
                is DownloadStatus.Done -> Color(0xFF4CAF50)
                is DownloadStatus.Error -> Color(0xFFFF5252)
                else -> Color(0xFF888888)
            }
            Text(text = statusText, color = statusColor, fontSize = 14.sp)

            // Progress bar
            if (downloadStatus is DownloadStatus.Downloading) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { downloadStatus.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF4CAF50),
                    trackColor = Color(0xFF333333)
                )
            }
            if (downloadStatus is DownloadStatus.Extracting) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF4CAF50),
                    trackColor = Color(0xFF333333)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (downloadsExpanded) {
                // Refresh button
                OutlinedButton(
                    onClick = { onRefreshCatalog() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF4CAF50))
                ) {
                    Text("Refresh Catalog")
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ── Filter bar (series / language / size; AND across dimensions,
                // OR within one; selections persist via LauncherViewModel →
                // SettingsStore catalog_filter_* keys) ──
                val selectedSeries by viewModel.catalogFilterSeries.collectAsState()
                val selectedLanguages by viewModel.catalogFilterLanguage.collectAsState()
                val selectedSizes by viewModel.catalogFilterSize.collectAsState()

                // Not remember()ed: catalogComics is a SnapshotStateList mutated
                // in place on refresh — recomposition recomputes this cheaply.
                val seriesOptions = catalogComics
                    .map { facetsOf(it.id, it.sizeMb).series }
                    .distinct()
                    .sorted()

                CatalogFilterBar(
                    seriesOptions = seriesOptions,
                    selectedSeries = selectedSeries,
                    selectedLanguages = selectedLanguages,
                    selectedSizes = selectedSizes,
                    onToggleSeries = { viewModel.toggleCatalogFilterSeries(it) },
                    onToggleLanguage = { viewModel.toggleCatalogFilterLanguage(it) },
                    onToggleSize = { viewModel.toggleCatalogFilterSize(it) },
                    onClear = { viewModel.clearCatalogFilters() }
                )

                val filteredComics = catalogComics.filter { comic ->
                    val facets = facetsOf(comic.id, comic.sizeMb)
                    (selectedSeries.isEmpty() || facets.series in selectedSeries) &&
                        (selectedLanguages.isEmpty() || facets.language.name in selectedLanguages) &&
                        (selectedSizes.isEmpty() || facets.sizeBucket.name in selectedSizes)
                }

                // Live result count
                Text(
                    "${filteredComics.size} of ${catalogComics.size}",
                    color = Color(0xFF888888),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                // Empty state — exact text is the Maestro contract ("No comics match")
                if (filteredComics.isEmpty() && catalogComics.isNotEmpty()) {
                    Text(
                        "No comics match",
                        color = Color(0xFF888888),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    TextButton(onClick = { viewModel.clearCatalogFilters() }) {
                        Text("Clear filters", color = Color(0xFF4CAF50))
                    }
                }

                // Comic list (filtered)
                filteredComics.forEach { comic ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(comic.name, color = Color.White, fontSize = 15.sp)
                            Text(
                                "${comic.sizeMb} MB \u2022 ${comic.chapters} chapters",
                                color = Color(0xFF888888),
                                fontSize = 12.sp
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        if (comic.isDownloaded) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val isActive = comic.id == activeComicId
                                Text(
                                    if (isActive) "Active" else "Downloaded",
                                    color = if (isActive) Color(0xFF4CAF50) else Color(0xFFAAAAAA),
                                    fontSize = 13.sp
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = { removeConfirmFor = comic },
                                    enabled = onRemoveComic != null,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFF44336),
                                        disabledContainerColor = Color(0xFF555555),
                                        disabledContentColor = Color(0xFFAAAAAA)
                                    )
                                ) {
                                    Text("Remove")
                                }
                            }
                        } else {
                            val isCurrentlyDownloading = downloadStatus is DownloadStatus.Downloading &&
                                    downloadStatus.comicId == comic.id ||
                                    downloadStatus is DownloadStatus.Extracting &&
                                    downloadStatus.comicId == comic.id
                            Button(
                                onClick = { onDownloadComic?.invoke(comic.id) },
                                enabled = !isCurrentlyDownloading && downloadStatus !is DownloadStatus.Downloading,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF4CAF50)
                                ),
                                modifier = Modifier.testTag("download-${comic.id}")
                            ) {
                                Text(if (isCurrentlyDownloading) "..." else "Download")
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF333333), modifier = Modifier.padding(vertical = 8.dp))
        }

        // Launcher Section
        if (onOpenHomeSettings != null) {
            SectionHeader("Launcher")

            SettingsButton("Set as Default Launcher") { onOpenHomeSettings() }
            SettingsButton("Change Default Launcher") { onOpenHomeSettings() }

            HorizontalDivider(color = Color(0xFF333333))
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Actions
        SectionHeader("Actions")

        SettingsButton("Flags", onClick = onOpenFlags)
        SettingsButton("Open Chatbot", onClick = onOpenChat)
        SettingsButton("View Annotation Log", onClick = onOpenAnnotationLog)
        onExportJson?.let {
            SettingsButton("Export / Email JSON", onClick = it)
        }
        onSyncCloud?.let {
            SettingsButton("Sync to Cloud", onClick = it)
        }

        HorizontalDivider(color = Color(0xFF333333))

        // Hidden Apps
        val hiddenCount = viewModel.getHiddenAppsCount()
        if (hiddenCount > 0) {
            SectionHeader("Hidden Apps")
            Text(
                text = "$hiddenCount apps hidden",
                color = Color(0xFF888888),
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            SettingsButton("Reset Hidden Apps") { viewModel.resetHiddenApps() }
            HorizontalDivider(color = Color(0xFF333333))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Account
        SectionHeader("Account")
        Spacer(modifier = Modifier.height(8.dp))

        // Enrollment is keyed on the app's own logged-in user id; Google
        // account state gates nothing here (email is optional metadata).
        if (onAttachIp != null) {
            OutlinedButton(
                onClick = onAttachIp,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text("Attach IP to my user account")
            }
        }

        if (googleAccountEmail != null) {
            Text(
                text = "Google: $googleAccountEmail",
                color = Color(0xFF4CAF50),
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            if (onGoogleSignOut != null) {
                OutlinedButton(
                    onClick = onGoogleSignOut,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("Sign Out of Google")
                }
            }
        } else if (onGoogleSignIn != null) {
            OutlinedButton(
                onClick = onGoogleSignIn,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text("Sign in with Google")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onLogout,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Logout")
        }
    }

    removeConfirmFor?.let { target ->
        val targetIsActive = target.id == activeComicId
        val otherDownloaded = downloadedComicIds.firstOrNull { it != target.id }
        AlertDialog(
            onDismissRequest = { removeConfirmFor = null },
            title = { Text("Remove \"${target.name}\"?") },
            text = {
                val baseMsg = "This deletes the extracted ${target.sizeMb} MB of content from this device. You can re-download later from the catalog."
                val activeMsg = when {
                    !targetIsActive -> ""
                    otherDownloaded != null -> "\n\nThis is the active comic — the reader will switch to \"${otherDownloaded.replace("-", " ")}\"."
                    else -> "\n\nThis is the only downloaded comic — after removing, no comic will be active."
                }
                Text(baseMsg + activeMsg)
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveComic?.invoke(target.id)
                    removeConfirmFor = null
                }) {
                    Text("Remove", color = Color(0xFFF44336))
                }
            },
            dismissButton = {
                TextButton(onClick = { removeConfirmFor = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Filter bar for the catalog list: three multi-select dropdowns (Series /
 * Language / Size) + Clear. Selections OR-combine within a dropdown and
 * AND-combine across them. testTags (surfaced as resource-ids via the
 * screen-root / popup-root [testTagsAsResourceIds], same shim the per-comic
 * "download-<id>" button uses): filter-series, filter-language, filter-size,
 * filter-clear; options facet-series-<series>, facet-lang-<CODE>,
 * facet-size-<BUCKET>.
 */
@Composable
private fun CatalogFilterBar(
    seriesOptions: List<String>,
    selectedSeries: Set<String>,
    selectedLanguages: Set<String>,
    selectedSizes: Set<String>,
    onToggleSeries: (String) -> Unit,
    onToggleLanguage: (String) -> Unit,
    onToggleSize: (String) -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterDropdown(
            label = "Series",
            tag = "filter-series",
            options = seriesOptions,
            optionLabel = { it },
            optionTag = { "facet-series-$it" },
            selected = selectedSeries,
            onToggle = onToggleSeries,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(4.dp))
        FilterDropdown(
            label = "Lang",
            tag = "filter-language",
            options = Language.entries.map { it.name },
            optionLabel = { it.lowercase() },
            optionTag = { "facet-lang-$it" },
            selected = selectedLanguages,
            onToggle = onToggleLanguage,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(4.dp))
        FilterDropdown(
            label = "Size",
            tag = "filter-size",
            options = SizeBucket.entries.map { it.name },
            optionLabel = { name -> SizeBucket.valueOf(name).label },
            optionTag = { "facet-size-$it" },
            selected = selectedSizes,
            onToggle = onToggleSize,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(4.dp))
        TextButton(
            onClick = onClear,
            modifier = Modifier.testTag("filter-clear")
        ) {
            Text("Clear", color = Color(0xFF4CAF50), fontSize = 12.sp)
        }
    }
}

@Composable
private fun FilterDropdown(
    label: String,
    tag: String,
    options: List<String>,
    optionLabel: (String) -> String,
    optionTag: (String) -> String,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(tag),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (selected.isEmpty()) Color.White else Color(0xFF4CAF50)
            )
        ) {
            Text(
                if (selected.isEmpty()) label else "$label (${selected.size})",
                fontSize = 12.sp,
                maxLines = 1
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            // Popup hosts a separate compose hierarchy — re-apply the
            // resource-id shim so the facet-* testTags work with `id:`
            // selectors (mirrors the annotation-bar pattern in MainScreen).
            modifier = Modifier.testTagsAsResourceIds()
        ) {
            options.forEach { value ->
                val isSelected = value in selected
                DropdownMenuItem(
                    modifier = Modifier.testTag(optionTag(value)),
                    text = {
                        Text(
                            (if (isSelected) "✓ " else "") + optionLabel(value),
                            color = if (isSelected) Color(0xFF4CAF50) else Color.White
                        )
                    },
                    onClick = {
                        expanded = false
                        onToggle(value)
                    }
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = Color(0xFF4CAF50),
        fontSize = 16.sp,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun SettingsButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
