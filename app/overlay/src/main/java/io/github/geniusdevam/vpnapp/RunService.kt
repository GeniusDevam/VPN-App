package io.github.geniusdevam.vpnapp

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.blinkt.openvpn.R
import io.github.geniusdevam.vpnapp.engine.PhaseId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps the app alive (and shows progress) while Quick/Best Connect is running, even with the screen off. */
class RunService : Service() {
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var lastText = ""
    private var lastAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Session.init(application)
        if (intent?.action == ACTION_CANCEL) {
            Session.cancel()
            return START_NOT_STICKY
        }
        ensureChannel()
        // The "special use" foreground-service type exists from Android 14; older versions take the plain call.
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, build("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, build("Starting…"))
        }
        scope?.cancel()
        val sc = CoroutineScope(Dispatchers.Main)
        scope = sc
        job = sc.launch {
            Session.ui.collect { s ->
                val r = s.run
                if (r == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    sc.cancel()
                } else {
                    val t = text(r)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (t != lastText && now - lastAt >= 1_000) {
                        lastText = t
                        lastAt = now
                        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(t))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        super.onDestroy()
    }

    private fun text(r: RunUi): String = when (r.mode) {
        RunMode.QUICK -> when (r.phase) {
            PhaseId.SCANNING -> "Finding the fastest server… ${r.done}/${r.total}"
            PhaseId.CONNECTING -> "Securing connection to ${r.connectingTo?.id ?: "server"}…"
            else -> "Quick Connect…"
        }
        RunMode.BEST -> when (r.phase) {
            PhaseId.SCANNING -> "Scanning servers… ${r.done}/${r.total}"
            PhaseId.SPEED_TESTING -> "Speed-testing servers… ${r.tested}/${r.planned}"
            PhaseId.CONFIRMING -> "Confirming the best server…"
            PhaseId.CONNECTING -> "Connecting to ${r.connectingTo?.id ?: "the best server"}…"
            else -> "Best Connect…"
        }
        RunMode.WORLDWIDE -> r.search?.let { p ->
            (if (p.paused) "Paused. " else "") + when (p.phase) {
                io.github.geniusdevam.vpnapp.engine.SearchPhase.SPEED_TESTING, io.github.geniusdevam.vpnapp.engine.SearchPhase.CONFIRMING ->
                    "Worldwide search: speed-tested ${p.speedTested}, ${p.bytesUsed shr 20} MB used"
                io.github.geniusdevam.vpnapp.engine.SearchPhase.CONNECTING -> "Connecting to the best server found…"
                else -> "Worldwide search: probed ${p.probed} of ${p.poolSize} servers"
            }
        } ?: "Worldwide search…"
        RunMode.MANUAL -> "Connecting to ${r.connectingTo?.id ?: "server"}…"
    }

    private fun ensureChannel() {
        // Channels exist from Android 8.0; the compat call does nothing on older versions.
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW).setName("Connection progress").build(),
        )
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, RunService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.vpnapp_ic_tile)
            .setContentTitle("VPN App")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(0, 0, true)
            .setContentIntent(open)
            .addAction(R.drawable.vpnapp_ic_tile, "Cancel", cancel)
            .build()
    }

    companion object {
        private const val CHANNEL = "vpnapp_run"
        private const val NOTIF_ID = 4242
        private const val ACTION_CANCEL = "io.github.geniusdevam.vpnapp.CANCEL"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RunService::class.java))
        }
    }
}
