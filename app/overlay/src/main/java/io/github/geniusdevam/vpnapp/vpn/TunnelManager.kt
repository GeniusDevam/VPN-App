package io.github.geniusdevam.vpnapp.vpn

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.LogItem
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import io.github.geniusdevam.vpnapp.data.Settings
import io.github.geniusdevam.vpnapp.engine.ConfigSanitizer
import io.github.geniusdevam.vpnapp.engine.ConnectResult
import io.github.geniusdevam.vpnapp.engine.SanitizeResult
import io.github.geniusdevam.vpnapp.engine.Server
import io.github.geniusdevam.vpnapp.engine.TunnelController
import java.io.StringReader
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class TunnelState(
    val level: ConnectionStatus = ConnectionStatus.LEVEL_NOTCONNECTED,
    val stateName: String = "",
    val message: String? = null,
    val server: Server? = null,
    val cipher: String? = null,
    val connectedAtMs: Long? = null,
    val rxRate: Long = 0,
    val txRate: Long = 0,
)

/**
 * Drives the embedded OpenVPN engine (ics-openvpn). The engine itself runs in its own ":openvpn" process;
 * this class builds a profile from a sanitized config, starts it, and turns the engine's status callbacks
 * into suspend-friendly results.
 */
class TunnelManager(
    private val app: Application,
    private val settings: () -> Settings,
) : TunnelController, VpnStatus.StateListener, VpnStatus.LogListener, VpnStatus.ByteCountListener {

    private class Pending(val uuid: String, val deferred: CompletableDeferred<ConnectResult>) {
        @Volatile var cipher: String? = null
    }

    private val _state = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = _state

    @Volatile private var pending: Pending? = null
    @Volatile override var currentServerId: String? = null
        private set

    init {
        VpnStatus.addStateListener(this)
        VpnStatus.addLogListener(this)
        VpnStatus.addByteCountListener(this)
    }

    // ---- engine callbacks (arbitrary threads) ----

    override fun updateState(state: String, logmessage: String?, resid: Int, level: ConnectionStatus, intent: Intent?) {
        val p = pending
        _state.update { it.copy(level = level, stateName = state, message = logmessage) }
        if (p != null) {
            when {
                level == ConnectionStatus.LEVEL_CONNECTED && VpnStatus.getLastConnectedVPNProfile() == p.uuid ->
                    p.deferred.complete(ConnectResult(true, cipher = p.cipher))
                level == ConnectionStatus.LEVEL_AUTH_FAILED ->
                    p.deferred.complete(ConnectResult(false, "Authentication failed", hard = true))
            }
        } else if (level == ConnectionStatus.LEVEL_NOTCONNECTED || level == ConnectionStatus.LEVEL_AUTH_FAILED) {
            clearConnected()
        }
    }

    override fun setConnectedVPN(uuid: String?) {}

    private fun dbg(msg: String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, msg)
    }

    override fun newLog(logItem: LogItem) {
        val p = pending ?: return
        val msg = try { logItem.getString(app) } catch (_: Exception) { return } ?: return
        dbg("engine: $msg")
        if (p.cipher == null && (msg.contains("Data Channel") || msg.contains("data channel"))) {
            CIPHER_RE.find(msg)?.let { p.cipher = it.groupValues[1] }
        }
        if (msg.contains("VERIFY ERROR") || msg.contains("certificate verify failed")) {
            p.deferred.complete(ConnectResult(false, "Server certificate rejected", hard = true))
        }
    }

    override fun updateByteCount(inBytes: Long, outBytes: Long, diffIn: Long, diffOut: Long) {
        _state.update { it.copy(rxRate = diffIn / BYTECOUNT_INTERVAL_S, txRate = diffOut / BYTECOUNT_INTERVAL_S) }
    }

    private fun clearConnected() {
        currentServerId = null
        _state.update { it.copy(server = null, cipher = null, connectedAtMs = null, rxRate = 0, txRate = 0) }
    }

    // ---- TunnelController ----

    override suspend fun connect(server: Server, timeoutMs: Long): ConnectResult {
        val s = settings()
        val sanitized = ConfigSanitizer.sanitize(server.config, s.encryption)
        if (sanitized is SanitizeResult.Rejected) return ConnectResult(false, sanitized.reason, hard = true)
        val vp = try {
            buildProfile((sanitized as SanitizeResult.Ok).config, s)
        } catch (e: Exception) {
            return ConnectResult(false, "Config error: ${e.message}")
        }
        dbg("connect ${server.id} ${server.ip}:${server.port} ${server.transport} declaredCipher=${server.declaredCipher}")
        dbg("sanitized config:\n" + sanitized.config.lines().filterNot { it.matches(Regex("^[A-Za-z0-9+/=]{20,}$")) }.joinToString("\n"))
        val check = vp.checkProfile(app)
        if (check != de.blinkt.openvpn.R.string.no_error_found) {
            return ConnectResult(false, "Profile rejected: ${app.getString(check)}")
        }

        val p = Pending(vp.getUUIDString(), CompletableDeferred())
        pending?.deferred?.cancel()
        pending = p
        clearConnected()
        try {
            ProfileManager.setTemporaryProfile(app, vp)
            VPNLaunchHelper.startOpenVpn(vp, app, "VPN App", true)
            val r = withTimeoutOrNull(timeoutMs) { p.deferred.await() }
            dbg("connect ${server.id} -> ${r ?: "timeout after ${timeoutMs}ms"}")
            if (r == null) {
                stopEngine()
                return ConnectResult(false, "Timed out")
            }
            if (!r.success) {
                stopEngine()
                return r
            }
            val cipher = r.cipher ?: server.declaredCipher
            currentServerId = server.id
            _state.update { it.copy(server = server, cipher = cipher, connectedAtMs = System.currentTimeMillis()) }
            return ConnectResult(true, cipher = cipher)
        } catch (e: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) { stopEngine() }
            throw e
        } finally {
            if (pending === p) pending = null
        }
    }

    override suspend fun abort() {
        if (pending != null) {
            pending?.deferred?.cancel()
            pending = null
            stopEngine()
        }
    }

    override suspend fun disconnect() {
        pending?.deferred?.cancel()
        pending = null
        stopEngine()
        clearConnected()
    }

    // ---- helpers ----

    private fun buildProfile(config: String, s: Settings): VpnProfile {
        val cp = ConfigParser()
        cp.parseConfig(StringReader(config))
        val vp = cp.convertProfile()
        vp.mName = "VPN App"
        vp.mProfileCreator = app.packageName
        // VPN Gate's public relays use the shared credentials vpn / vpn.
        vp.mUsername = "vpn"
        vp.mPassword = "vpn"
        // A config with neither a client certificate nor auth-user-pass would default to a (non-existent)
        // Android keystore certificate and be rejected. VPN Gate relays accept the shared vpn/vpn login.
        if (vp.mAuthenticationType == VpnProfile.TYPE_KEYSTORE || vp.mAuthenticationType == VpnProfile.TYPE_USERPASS_KEYSTORE) {
            vp.mAuthenticationType = VpnProfile.TYPE_USERPASS
        }
        vp.mPersistTun = true // keep the interface up while reconnecting, so nothing leaks
        vp.mBlockUnusedAddressFamilies = true // e.g. IPv6 is blocked if the server has none
        vp.mUseDefaultRoute = true
        vp.mUseDefaultRoutev6 = true
        vp.mOverrideDNS = true // ignore DNS pushed by an unknown volunteer
        vp.mDNS1 = s.dns.primary
        vp.mDNS2 = s.dns.secondary
        vp.mUseCustomConfig = true
        vp.mCustomConfigOptions = "tls-version-min 1.2\n"
        if (s.excludedApps.isNotEmpty()) {
            vp.mAllowedAppsVpn = HashSet(s.excludedApps)
            vp.mAllowedAppsVpnAreDisallowed = true
        }
        return vp
    }

    private suspend fun stopEngine() {
        if (!VpnStatus.isVPNActive()) return
        withService { it.stopVPN(false) }
    }

    private suspend fun withService(block: (IOpenVPNServiceInternal) -> Unit) =
        suspendCancellableCoroutine<Unit> { cont ->
            val intent = Intent(app, OpenVPNService::class.java).setAction(OpenVPNService.START_SERVICE)
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    try { block(IOpenVPNServiceInternal.Stub.asInterface(binder)) } catch (_: Exception) {}
                    try { app.unbindService(this) } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onServiceDisconnected(name: ComponentName?) {}
            }
            if (!app.bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
                if (cont.isActive) cont.resume(Unit)
            }
        }

    companion object {
        private const val TAG = "VPNAPP"
        private val CIPHER_RE = Regex("[Cc]ipher '([A-Za-z0-9._-]+)'")
        private const val BYTECOUNT_INTERVAL_S = 2L
    }
}
