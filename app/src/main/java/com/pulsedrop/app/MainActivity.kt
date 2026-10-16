package com.pulsedrop.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import com.pulsedrop.app.ui.MainScreen
import com.pulsedrop.app.ui.MainViewModel
import com.pulsedrop.app.ui.theme.PulseDropTheme
import com.pulsedrop.app.ui.theme.ThemePreference
import com.pulsedrop.app.ui.theme.isPulseDark
import com.pulsedrop.app.ui.theme.resolvePulsePalette
import com.pulsedrop.app.ui.theme.systemUiIsDark

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before the first frame: the window background behind Compose has to already be the
        // colour the saved preference resolves to, or opening the app shows a flash of the other.
        val dark = wantsDarkSurface()
        applyWindowTheme(dark)
        enableEdgeToEdge(statusBarStyle = barStyle(dark), navigationBarStyle = barStyle(dark))

        handleIncomingShareIntent(intent)
        handleCallActionIntent(intent)
        handleUploadActionIntent(intent)

        setContent {
            PulseDropTheme {
                // The bars follow the palette rather than the system, so changing appearance in
                // Settings moves the icons while the app is open.
                val dark = isPulseDark()
                LaunchedEffect(dark) {
                    enableEdgeToEdge(statusBarStyle = barStyle(dark), navigationBarStyle = barStyle(dark))
                }
                MainScreen(viewModel = viewModel)
            }
        }
    }

    /** Dark icons on a light surface, light icons on a dark one. */
    private fun barStyle(dark: Boolean): SystemBarStyle =
        if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)

    /** The same resolution the Compose palette uses, so the window and the app never disagree. */
    private fun wantsDarkSurface(): Boolean =
        resolvePulsePalette(ThemePreference.mode.value, systemUiIsDark(this)).isDark

    private fun applyWindowTheme(dark: Boolean) {
        setTheme(if (dark) R.style.Theme_PulseDrop_Dark else R.style.Theme_PulseDrop_Light)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
        handleCallActionIntent(intent)
        handleUploadActionIntent(intent)
    }

    /** Answer / decline / hang up pressed on the call notification itself. */
    private fun handleCallActionIntent(intent: Intent?) {
        val action = intent?.getStringExtra(MainViewModel.EXTRA_CALL_ACTION) ?: return
        intent.removeExtra(MainViewModel.EXTRA_CALL_ACTION)
        viewModel.runCallNotificationAction(action)
    }

    /** Accept / decline pressed on the "a file is waiting" notification. */
    private fun handleUploadActionIntent(intent: Intent?) {
        val action = intent?.getStringExtra(MainViewModel.EXTRA_UPLOAD_ACTION) ?: return
        intent.removeExtra(MainViewModel.EXTRA_UPLOAD_ACTION)
        viewModel.runUploadNotificationAction(action)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        if (action == Intent.ACTION_SEND && type != null) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (uri != null) {
                viewModel.addFiles(listOf(uri))
            } else {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    viewModel.sendClipMessage(text)
                }
            }
        } else if (action == Intent.ACTION_SEND_MULTIPLE && type != null) {
            val uris: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            if (!uris.isNullOrEmpty()) {
                viewModel.addFiles(uris)
            }
        }
    }
}
