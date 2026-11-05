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
import io.github.geniusdevam.vpnapp.engine.CipherPolicy
import io.github.geniusdevam.vpnapp.engine.ConfigSanitizer
import io.github.geniusdevam.vpnapp.engine.ConnectResult
import io.github.geniusdevam.vpnapp.engine.FailureKind
import io.github.geniusdevam.vpnapp.engine.SanitizeResult
import io.github.geniusdevam.vpnapp.engine.Server
import io.github.geniusdevam.vpnapp.engine.ServerAuthPolicy
import io.github.geniusdevam.vpnapp.engine.TunnelController
import io.github.geniusdevam.vpnapp.net.AndroidRoots
import java.io.StringReader
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    /** The data-channel cipher the engine reported. Null = not observed; it is never filled in from what the relay's profile claims. */
    val cipher: String? = null,
    val cipherVerified: Boolean = false,
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
    private val authPolicy: () -> ServerAuthPolicy = { AndroidRoots.policy() },
) : TunnelController, VpnStatus.StateListener, VpnStatus.LogListener, VpnStatus.ByteCountListener {

    private class Pending(val uuid: String, val deferred: CompletableDeferred<ConnectResult>) {
        @Volatile var cipher: String? = null
    }

    private val _state = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = _state

    @Volatile private var pending: Pending? = null
    @Volatile override var currentServerId: String? = null
        private set

    /** Called once per connected session when it ends. `unexpected` is false when the app or the user ended it. */
    @Volatile var onSessionEnded: ((server: Server, durationMs: Long, unexpected: Boolean) -> Unit)? = null
    @Volatile private var sessionServer: Server? = null
    @Volatile private var sessionStartedAt = 0L

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
                    p.deferred.complete(ConnectResult.connected(p.cipher))
                level == ConnectionStatus.LEVEL_AUTH_FAILED ->
                    p.deferred.complete(ConnectResult.failed(FailureKind.AUTH, "Authentication failed"))
            }
        } else if (level == ConnectionStatus.LEVEL_NOTCONNECTED || level == ConnectionStatus.LEVEL_AUTH_FAILED) {
            // The engine dropped a tunnel we did not ask to stop (network loss, relay went away).
            endSession(unexpected = true)
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
        // Only a short allowlist of engine verdicts is ever logged, only when the debug tag is switched on (never in normal use),
        // and with addresses masked. No configs, no credentials, no traffic details.
        if (Log.isLoggable(TAG, Log.DEBUG) && ENGINE_LOG_ALLOW.containsMatchIn(msg)) Log.d(TAG, "engine: " + IPV4.replace(msg, "$1.$2.x.x"))
        if (p.cipher == null) {
            (DATA_CHANNEL_RE.find(msg) ?: OUTGOING_RE.find(msg))?.let { p.cipher = it.groupValues[1].uppercase() }
        }
        when {
            msg.contains(NO_VERIFY_WARNING) -> {
                // Regression guard: identity checks must always be on. Refuse rather than connect unauthenticated.
                p.deferred.complete(ConnectResult.failed(FailureKind.CERTIFICATE, "Server identity checks were not enabled"))
            }
            VERIFY_FAIL_RE.containsMatchIn(msg) -> {
                dbg("certificate verification failed")
                p.deferred.complete(ConnectResult.failed(FailureKind.CERTIFICATE, "Server certificate rejected"))
            }
        }
    }

    override fun updateByteCount(inBytes: Long, outBytes: Long, diffIn: Long, diffOut: Long) {
        _state.update { it.copy(rxRate = diffIn / BYTECOUNT_INTERVAL_S, txRate = diffOut / BYTECOUNT_INTERVAL_S) }
    }

    private fun clearConnected() {
        currentServerId = null
        _state.update { it.copy(server = null, cipher = null, cipherVerified = false, connectedAtMs = null, rxRate = 0, txRate = 0) }
    }

    @Synchronized
    private fun endSession(unexpected: Boolean) {
        val s = sessionServer ?: return
        sessionServer = null
        val dur = System.currentTimeMillis() - sessionStartedAt
        onSessionEnded?.invoke(s, dur, unexpected)
    }

    // ---- TunnelController ----

    override suspend fun connect(server: Server, timeoutMs: Long): ConnectResult {
        val s = settings()
        val sanitized = ConfigSanitizer.sanitize(server.config, s.encryption, server.ip, authPolicy())
        if (sanitized is SanitizeResult.Rejected) return ConnectResult.failed(FailureKind.CONFIG_REJECTED, sanitized.reason)
        val vp = try {
            buildProfile((sanitized as SanitizeResult.Ok).config, s, server)
        } catch (e: Exception) {
            return ConnectResult.failed(FailureKind.CONFIG_REJECTED, "Config error: ${e.message}")
        }
        dbg("connect ${server.id} ${server.transport}")
        val check = vp.checkProfile(app)
        if (check != de.blinkt.openvpn.R.string.no_error_found) {
            return ConnectResult.failed(FailureKind.CONFIG_REJECTED, "Profile rejected: ${app.getString(check)}")
        }

        val p = Pending(vp.getUUIDString(), CompletableDeferred())
        pending?.deferred?.cancel()
        pending = p
        endSession(unexpected = false) // replacing a live tunnel is our own decision
        clearConnected()
        try {
            ProfileManager.setTemporaryProfile(app, vp)
            VPNLaunchHelper.startOpenVpn(vp, app, "VPN App", true)
            val r = try {
                withTimeoutOrNull(timeoutMs) { p.deferred.await() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Either we were cancelled (propagate) or a newer connect() cancelled this attempt's result.
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) { stopEngine() }
                return ConnectResult.failed(FailureKind.CANCELLED, "Superseded by a newer connection attempt")
            }
            dbg("connect ${server.id} -> ${if (r == null) "timeout after ${timeoutMs}ms" else if (r.success) "up" else r.kind}")
            if (r == null) {
                stopEngine()
                return ConnectResult.failed(FailureKind.TIMEOUT, "Timed out")
            }
            if (!r.success) {
                stopEngine()
                return r
            }
            // Only what the engine reported counts as the negotiated cipher. If it reported nothing, the connection is
            // marked unverified; the relay's own claim is never substituted.
            val cipher = r.cipher
            currentServerId = server.id
            sessionServer = server
            sessionStartedAt = System.currentTimeMillis()
            _state.update { it.copy(server = server, cipher = cipher, cipherVerified = cipher != null, connectedAtMs = sessionStartedAt) }
            return ConnectResult.connected(cipher)
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
        endSession(unexpected = false)
        stopEngine()
        clearConnected()
    }

    // ---- helpers ----

    private fun buildProfile(config: String, s: Settings, server: Server): VpnProfile {
        val policy = authPolicy()
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
        // Server identity, enforced here as well as in the sanitized text. ConfigParser.convertProfile() clears these
        // flags, which is why earlier builds logged "No server certificate verification method has been enabled".
        vp.mExpectTLSCert = true // remote-cert-tls server: the peer must present a server certificate
        vp.mCheckRemoteCN = true
        vp.mRemoteCN = policy.expectedName
        vp.mX509AuthType = VpnProfile.X509_VERIFY_TLSREMOTE_RDN // verify-x509-name <name> name
        vp.mUseLzo = false // no compression, ever
        vp.mPersistTun = true // keep the interface up while reconnecting, so nothing leaks (this is NOT a kill switch, see docs/SECURITY.md)
        vp.mBlockUnusedAddressFamilies = true // e.g. IPv6 is blocked if the server has none
        vp.mUseDefaultRoute = true
        vp.mUseDefaultRoutev6 = true
        vp.mOverrideDNS = true // ignore DNS pushed by an unknown volunteer
        vp.mDNS1 = s.dns.primary
        vp.mDNS2 = s.dns.secondary
        vp.mUseCustomConfig = true
        // VPN Gate's servers (SoftEther) cannot negotiate ciphers, so the client must be told the one cipher to use.
        // The engine then refuses anything outside --data-ciphers, which is built from the encryption policy and never
        // contains a weak cipher; the observed cipher is additionally checked after connecting (ConnectionGate).
        val cipher = vp.mCipher?.takeIf { it.isNotBlank() && !CipherPolicy.isWeak(it) }
        vp.mDataCiphers = CipherPolicy.dataCiphers(s.encryption, cipher)
        vp.mCustomConfigOptions = buildString {
            append("tls-version-min 1.2\n")
            append("allow-compression no\n")
            if (cipher != null) append("cipher ").append(cipher).append("\n").append("data-ciphers-fallback ").append(cipher).append("\n")
        }
        if (s.excludedApps.isNotEmpty()) {
            vp.mAllowedAppsVpn = HashSet(s.excludedApps)
            vp.mAllowedAppsVpnAreDisallowed = true
        }
        // Final check on the text the engine will actually run: identity checks present, compression absent.
        val text = vp.getConfigFile(app, false)
        val lines = text.lineSequence().map { it.trim() }.toSet()
        check("remote-cert-tls server" in lines) { "profile lacks remote-cert-tls server" }
        check("verify-x509-name ${policy.expectedName} name" in lines) { "profile lacks verify-x509-name" }
        check(lines.none { it.startsWith("comp-lzo") || it.startsWith("compress") }) { "profile enables compression" }
        check(lines.contains("remote ${server.ip} ${server.port}") || lines.any { it.startsWith("remote ${server.ip} ") }) { "profile does not dial the vetted address" }
        return vp
    }

    /** Asks the engine service to stop. Never waits longer than [SERVICE_BIND_TIMEOUT_MS], so cleanup cannot stall a time-boxed run. */
    private suspend fun stopEngine() {
        if (!VpnStatus.isVPNActive()) return
        withService { it.stopVPN(false) }
    }

    private suspend fun withService(block: (IOpenVPNServiceInternal) -> Unit) {
        withTimeoutOrNull(SERVICE_BIND_TIMEOUT_MS) {
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
                cont.invokeOnCancellation { try { app.unbindService(conn) } catch (_: Exception) {} }
            }
        }
    }

    companion object {
        private const val TAG = "VPNAPP"
        /** "Data Channel: cipher 'AES-128-CBC', auth 'SHA1'" is printed once the data channel is up (seen in OpenVPN 2.7-icsopenvpn). */
        private val DATA_CHANNEL_RE = Regex("""Data Channel: cipher '([A-Za-z0-9._-]+)'""")
        private val OUTGOING_RE = Regex("""Outgoing Data Channel: Cipher '([A-Za-z0-9._-]+)' initialized""")
        private val VERIFY_FAIL_RE = Regex("""VERIFY (X509NAME )?ERROR|certificate verify failed|VERIFY KU ERROR|VERIFY EKU ERROR""")
        private val ENGINE_LOG_ALLOW = Regex("""^(VERIFY |Data Channel: cipher|Outgoing Data Channel: Cipher|Control Channel: TLS|Initialization Sequence Completed|AUTH_FAILED|WARNING: No server certificate|TLS Error|OpenVPN 2|library versions)""")
        private val IPV4 = Regex("""\b(\d{1,3})\.(\d{1,3})\.\d{1,3}\.\d{1,3}\b""")
        private const val NO_VERIFY_WARNING = "No server certificate verification method has been enabled"
        private const val BYTECOUNT_INTERVAL_S = 2L
        private const val SERVICE_BIND_TIMEOUT_MS = 3_000L
    }
}
