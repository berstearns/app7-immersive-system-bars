package pl.czak.learnlauncher.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

actual fun Modifier.testTagsAsResourceIds(): Modifier =
    semantics { testTagsAsResourceId = true }
