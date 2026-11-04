package io.github.geniusdevam.vpnapp

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.geniusdevam.vpnapp.engine.Countries
import io.github.geniusdevam.vpnapp.engine.Scope
import io.github.geniusdevam.vpnapp.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile: tap to Quick Connect (opens the app for the VPN permission), tap again to disconnect. */
class QuickTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        Session.init(application)
        val sc = CoroutineScope(Dispatchers.Main)
        scope = sc
        sc.launch { Session.ui.collect { render(it) } }
    }

    override fun onStopListening() {
        scope?.cancel()
    }

    private fun render(s: SessionUi) {
        val tile = qsTile ?: return
        val scopeSel = Repo.settings.value.quickScope
        val where = if (scopeSel is Scope.Country) Countries.name(scopeSel.iso) else "Any country"
        when {
            s.run != null -> { tile.state = Tile.STATE_ACTIVE; tile.label = "Connecting…" }
            s.vpn.status == ConnState.CONNECTED -> { tile.state = Tile.STATE_ACTIVE; tile.label = "VPN on" }
            else -> { tile.state = Tile.STATE_INACTIVE; tile.label = "Quick Connect" }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (s.vpn.status == ConnState.CONNECTED) s.vpn.server?.id ?: "Connected" else where
        }
        tile.updateTile()
    }

    override fun onClick() {
        Session.init(application)
        val s = Session.ui.value
        if (s.vpn.status == ConnState.CONNECTED || s.run != null) {
            Session.disconnect()
            return
        }
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_AUTO, "quick")
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
