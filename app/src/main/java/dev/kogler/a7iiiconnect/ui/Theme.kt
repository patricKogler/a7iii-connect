package dev.kogler.a7iiiconnect.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Material You colours from the phone's wallpaper (Android 12+; our minimum is 13). Always
 * dark, so the Camera and Remote screens match Photos and the editor.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = dynamicDarkColorScheme(LocalContext.current), content = content)
}
