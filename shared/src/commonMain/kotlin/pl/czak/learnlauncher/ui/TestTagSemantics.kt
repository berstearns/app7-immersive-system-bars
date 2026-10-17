package pl.czak.learnlauncher.ui

import androidx.compose.ui.Modifier

/**
 * Expose descendant `testTag`s as Android resource-ids so UI automation
 * (Maestro/UiAutomator `id:` matchers) can target them.
 *
 * `androidx.compose.ui.semantics.testTagsAsResourceId` exists only in the
 * Android Compose UI artifact — the desktop/iOS CMP artifacts do not ship it,
 * so referencing it from commonMain breaks `:shared:compileKotlinDesktop`.
 * This expect/actual applies it on Android and is a no-op elsewhere
 * (automation resource-ids are an Android-only concept anyway).
 */
expect fun Modifier.testTagsAsResourceIds(): Modifier
