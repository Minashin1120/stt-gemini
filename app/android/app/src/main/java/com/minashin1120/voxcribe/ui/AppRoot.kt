package com.minashin1120.voxcribe.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import com.minashin1120.voxcribe.VoxcribeApp
import com.minashin1120.voxcribe.ui.common.ConfirmDialog
import com.minashin1120.voxcribe.ui.common.ToastHost
import com.minashin1120.voxcribe.ui.settings.SettingsScreen
import com.minashin1120.voxcribe.ui.theme.LocalAppTheme
import com.minashin1120.voxcribe.ui.theme.Themes
import com.minashin1120.voxcribe.ui.update.UpdateDialog
import com.minashin1120.voxcribe.ui.welcome.WelcomeScreen
import com.minashin1120.voxcribe.ui.workspace.WorkspaceScreen
import java.io.File

/** 現在のテーマキー（設定画面で即時プレビューできるよう状態として保持） */
object ThemeState {
    var key by mutableStateOf(VoxcribeApp.instance.prefs.theme)
}

@Composable
fun AppRoot(onRequestPermissions: () -> Unit, onInstallUpdate: (File) -> Unit) {
    val app = VoxcribeApp.instance
    val theme = Themes.of(ThemeState.key)
    var screen by rememberSaveable { mutableStateOf(if (app.prefs.welcomeSeen) "workspace" else "welcome") }

    LaunchedEffect(Unit) { onRequestPermissions() }
    LaunchedEffect(Unit) { app.updates.checkOnStart() }

    val scheme = if (theme.isDark) darkColorScheme(primary = theme.primary, background = theme.bg, surface = theme.cardBg)
    else lightColorScheme(primary = theme.primary, background = theme.bg, surface = if (theme.cardBg.alpha < 1f) androidx.compose.ui.graphics.Color.White else theme.cardBg)

    CompositionLocalProvider(LocalAppTheme provides theme) {
        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography.let { ty ->
            ty.copy(
                bodyLarge = ty.bodyLarge.merge(TextStyle(fontFamily = theme.font)),
                bodyMedium = ty.bodyMedium.merge(TextStyle(fontFamily = theme.font)),
                bodySmall = ty.bodySmall.merge(TextStyle(fontFamily = theme.font)),
                labelLarge = ty.labelLarge.merge(TextStyle(fontFamily = theme.font)),
                titleMedium = ty.titleMedium.merge(TextStyle(fontFamily = theme.font)),
            )
        }) {
            androidx.compose.material3.ProvideTextStyle(TextStyle(fontFamily = theme.font, color = theme.text)) {
                Box(Modifier.fillMaxSize()) {
                    BackHandler(enabled = screen == "settings") { screen = "workspace" }
                    AnimatedContent(screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { s ->
                        when (s) {
                            "welcome" -> WelcomeScreen(onStart = {
                                app.prefs.welcomeSeen = true
                                screen = "workspace"
                            })
                            "settings" -> SettingsScreen(onNavigate = { screen = it })
                            else -> WorkspaceScreen(onNavigate = { screen = it })
                        }
                    }
                    app.workspace.confirm?.let { req -> ConfirmDialog(req) { app.workspace.confirm = null } }
                    UpdateDialog(onInstall = onInstallUpdate)
                    ToastHost(app.toaster)
                }
            }
        }
    }
}
