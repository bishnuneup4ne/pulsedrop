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
import com.pulsedrop.app.ui.MainScreen
import com.pulsedrop.app.ui.MainViewModel
import com.pulsedrop.app.ui.theme.PulseDropTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark icons on the light bar. The window background comes from the theme the manifest names,
        // so there is no second copy of that decision to keep in step with the palette.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )

        handleIncomingShareIntent(intent)
        handleCallActionIntent(intent)
        handleUploadActionIntent(intent)

        setContent {
            PulseDropTheme {
                MainScreen(viewModel = viewModel)
            }
        }
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
