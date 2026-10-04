package cc.cherr.shelldeck.ui

import android.os.Build
import cc.cherr.shelldeck.settings.ThemeMode
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun ShellDeckTheme(mode: ThemeMode = ThemeMode.SYSTEM, dynamic: Boolean = true, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val context = LocalContext.current
    val colors = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFFA6D5C6))
        else -> lightColorScheme(primary = Color(0xFF286B59))
    }
    MaterialTheme(colorScheme = colors, content = content)
}
