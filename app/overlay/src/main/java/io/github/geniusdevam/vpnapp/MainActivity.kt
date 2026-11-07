package io.github.geniusdevam.vpnapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import io.github.geniusdevam.vpnapp.data.Repo
import io.github.geniusdevam.vpnapp.ui.AppRoot
import io.github.geniusdevam.vpnapp.ui.VpnTheme

@OptIn(ExperimentalComposeUiApi::class)
class MainActivity : ComponentActivity() {
    private var pendingAction: (() -> Unit)? = null

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) pendingAction?.invoke()
        pendingAction = null
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Session.init(application)
        setContent {
            VpnTheme {
                // Stable ids for UI automation (the CI emulator tests find controls by id on every screen size); no visible change.
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    AppRoot(
                        withVpnPermission = ::withVpnPermission,
                        askNotifications = ::askNotifications,
                    )
                }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getStringExtra(EXTRA_AUTO) == "quick") {
            i.removeExtra(EXTRA_AUTO)
            if (Repo.settings.value.acknowledged) withVpnPermission { Session.startQuick() }
        }
    }

    /** Android asks once for permission to create a VPN; after that, [action] runs straight away. */
    private fun withVpnPermission(action: () -> Unit) {
        val prepare = VpnService.prepare(this)
        if (prepare == null) {
            action()
        } else {
            pendingAction = action
            vpnPermission.launch(prepare)
        }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_AUTO = "auto"
    }
}
