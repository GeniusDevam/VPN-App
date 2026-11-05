package io.github.geniusdevam.vpnapp

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import de.blinkt.openvpn.core.ConnectionStatus
import io.github.geniusdevam.vpnapp.data.Repo
import io.github.geniusdevam.vpnapp.data.SavedEntry
import io.github.geniusdevam.vpnapp.data.SavedSearch
import io.github.geniusdevam.vpnapp.data.VpnGateCatalog
import io.github.geniusdevam.vpnapp.engine.BestClaim
import io.github.geniusdevam.vpnapp.engine.BestConfig
import io.github.geniusdevam.vpnapp.engine.BestConnectEngine
import io.github.geniusdevam.vpnapp.engine.BestControl
import io.github.geniusdevam.vpnapp.engine.BestEntry
import io.github.geniusdevam.vpnapp.engine.BestOutcome
import io.github.geniusdevam.vpnapp.engine.BestReport
import io.github.geniusdevam.vpnapp.engine.Clock
import io.github.geniusdevam.vpnapp.engine.ConnectionGate
import io.github.geniusdevam.vpnapp.engine.Countries
import io.github.geniusdevam.vpnapp.engine.EngineEvent
import io.github.geniusdevam.vpnapp.engine.FailReason
import io.github.geniusdevam.vpnapp.engine.GateResult
import io.github.geniusdevam.vpnapp.engine.GlobalSearchConfig
import io.github.geniusdevam.vpnapp.engine.GlobalSearchEngine
import io.github.geniusdevam.vpnapp.engine.NetworkProber
import io.github.geniusdevam.vpnapp.engine.PhaseId
import io.github.geniusdevam.vpnapp.engine.QuickConfig
import io.github.geniusdevam.vpnapp.engine.QuickConnectEngine
import io.github.geniusdevam.vpnapp.engine.QuickOutcome
import io.github.geniusdevam.vpnapp.engine.Ranked
import io.github.geniusdevam.vpnapp.engine.RunContext
import io.github.geniusdevam.vpnapp.engine.RunCoordinator
import io.github.geniusdevam.vpnapp.engine.Scope
import io.github.geniusdevam.vpnapp.engine.SearchControl
import io.github.geniusdevam.vpnapp.engine.SearchOutcome
import io.github.geniusdevam.vpnapp.engine.SearchProgress
import io.github.geniusdevam.vpnapp.engine.Server
import io.github.geniusdevam.vpnapp.engine.StopReason
import io.github.geniusdevam.vpnapp.net.Networks
import io.github.geniusdevam.vpnapp.vpn.HttpThroughputTester
import io.github.geniusdevam.vpnapp.vpn.TunnelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RunMode { QUICK, BEST, WORLDWIDE, MANUAL }
enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED }

data class RunUi(
    val mode: RunMode,
    val startedAtMs: Long,
    val phase: PhaseId? = null,
    val detail: String? = null,
    val done: Int = 0,
    val total: Int = 0,
    val top: List<Ranked> = emptyList(),
    val leaderboard: List<BestEntry> = emptyList(),
    val tested: Int = 0,
    val planned: Int = 0,
    val bytesUsed: Long = 0,
    val connectingTo: Server? = null,
    /** Worldwide search progress (null for the other modes). */
    val search: SearchProgress? = null,
    /** Why a worldwide search is paused when the app paused it itself (battery, no network, metered). */
    val pauseReason: String? = null,
)

data class VpnUi(
    val status: ConnState = ConnState.DISCONNECTED,
    val server: Server? = null,
    val cipher: String? = null,
    val cipherVerified: Boolean = false,
    val connectedAtMs: Long? = null,
    val rxRate: Long = 0,
    val txRate: Long = 0,
    val stateName: String = "",
)

data class Banner(val text: String, val isError: Boolean)

data class SessionUi(
    val vpn: VpnUi = VpnUi(),
    val run: RunUi? = null,
    val banner: Banner? = null,
    val lastReport: BestReport? = null,
)

/**
 * Orchestrates connection runs. One run at a time; the UI, notification and tile all observe [ui].
 * Commands (start / cancel / disconnect) go through a [RunCoordinator], so an explicit disconnect can never be undone
 * by the cleanup of an older, cancelled run.
 */
object Session {
    val ui = MutableStateFlow(SessionUi())

    private lateinit var app: Application
    lateinit var tunnel: TunnelManager
        private set
    private val catalog = VpnGateCatalog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var coordinator: RunCoordinator
    private var bestControl: BestControl? = null
    private var searchControl: SearchControl? = null
    @Volatile private var userPausedSearch = false
    private var inited = false

    /** Elapsed-realtime clock: unlike the coroutine clock it keeps counting while the CPU sleeps, so time budgets are wall-clock budgets. */
    private val wallClock = Clock { SystemClock.elapsedRealtime() }

    @Synchronized
    fun init(application: Application) {
        if (inited) return
        inited = true
        app = application
        Repo.init(app)
        tunnel = TunnelManager(app) { Repo.settings.value }
        tunnel.onSessionEnded = { server, durationMs, unexpected ->
            Repo.ledger.sessionEnded(server.id, Networks.fingerprint(app), durationMs, unexpected)
            Repo.saveLearning()
        }
        coordinator = RunCoordinator(scope, tunnel)
        // Warm the server list so Quick Connect rarely has to wait for the download.
        scope.launch { runCatching { catalog.servers(30L * 60_000, 20_000) } }
        scope.launch {
            tunnel.state.collect { t ->
                ui.update { s ->
                    val status = when (t.level) {
                        ConnectionStatus.LEVEL_CONNECTED -> ConnState.CONNECTED
                        ConnectionStatus.LEVEL_NOTCONNECTED, ConnectionStatus.LEVEL_AUTH_FAILED, ConnectionStatus.UNKNOWN_LEVEL -> ConnState.DISCONNECTED
                        else -> ConnState.CONNECTING
                    }
                    s.copy(vpn = VpnUi(status, t.server, t.cipher, t.cipherVerified, t.connectedAtMs, t.rxRate, t.txRate, t.stateName))
                }
            }
        }
    }

    val isRunning: Boolean get() = coordinator.isRunning

    fun dismissBanner() = ui.update { it.copy(banner = null) }

    /** User-requested refresh. Still subject to the minimum interval and back-off, so it cannot hammer VPN Gate. */
    fun refreshDirectory() {
        scope.launch { runCatching { catalog.servers(0, 20_000) } }
    }

    fun startQuick() = start(RunMode.QUICK)
    fun startBest() = start(RunMode.BEST)
    fun connectTo(server: Server) = start(RunMode.MANUAL, server)

    /** Caller must have shown the consent dialog (time, data, battery, no continuous protection while testing). */
    fun startWorldwide(resume: Boolean = false) = start(RunMode.WORLDWIDE, resumeSearch = resume)

    /** Cancel the current attempt. The previous connection (if any) is restored. */
    fun cancel() = coordinator.cancel()

    fun finishBestNow() {
        bestControl?.finishNow()
        searchControl?.finishNow()
    }

    fun pauseWorldwide() { userPausedSearch = true; searchControl?.pause() }
    fun resumeWorldwide() { userPausedSearch = false; searchControl?.resume(); ui.update { u -> u.copy(run = u.run?.copy(pauseReason = null)) } }

    /** Explicit disconnect. Whatever is running is stopped, and nothing it was doing can reconnect afterwards. */
    fun disconnect() {
        coordinator.disconnect { ui.update { it.copy(run = null, banner = Banner("Disconnected", false)) } }
    }

    private fun start(mode: RunMode, manual: Server? = null, resumeSearch: Boolean = false) {
        if (coordinator.isRunning) return
        ui.update { it.copy(run = RunUi(mode, System.currentTimeMillis()), banner = null) }
        RunService.start(app)
        val started = coordinator.start { h ->
            val previous = tunnel.state.value.server // read after any pending disconnect has completed
            try {
                directIpNoVpn = if (previous == null && (mode == RunMode.BEST || mode == RunMode.WORLDWIDE)) {
                    withContext(Dispatchers.IO) { Networks.directEgressIp(app) }
                } else null
                when (mode) {
                    RunMode.QUICK -> runQuick(previous)
                    RunMode.BEST -> runBest(previous)
                    RunMode.WORLDWIDE -> runWorldwide(previous, resumeSearch)
                    RunMode.MANUAL -> runManual(manual!!, previous)
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    // A plain cancel brings the previous connection back; an explicit disconnect (or a newer run) forbids it.
                    if (h.mayRestore()) {
                        restore(previous)
                        ui.update { it.copy(banner = Banner("Cancelled", false)) }
                    }
                }
                throw e
            } catch (e: Exception) {
                restore(previous)
                ui.update { it.copy(banner = Banner("Something went wrong: ${e.javaClass.simpleName}", true)) }
            } finally {
                bestControl = null
                searchControl = null
                userPausedSearch = false
                ui.update { it.copy(run = null) }
                Repo.saveLearning()
            }
        }
        if (!started) ui.update { it.copy(run = null) }
    }

    private fun runContext(previous: Server?): RunContext = RunContext(
        userCountry = Networks.userCountry(app),
        fingerprint = Networks.fingerprint(app),
        previous = previous,
        favorites = Repo.settings.value.favorites,
    )

    /**
     * Probe sockets are bound to the physical network, so they bypass an active tunnel. Consequences, stated plainly:
     * every probed relay sees the phone's real IP address (as it would on connecting, minus any payload), and under
     * Android's "Block connections without VPN" such sockets may be blocked, in which case probes simply fail.
     */
    private fun prober(): NetworkProber {
        fun bind(bindFn: (android.net.Network) -> Unit) {
            try { Networks.physicalNetwork(app)?.let(bindFn) } catch (_: Exception) {}
        }
        return NetworkProber(protectUdp = { s -> bind { it.bindSocket(s) } }, protectTcp = { s -> bind { it.bindSocket(s) } })
    }

    /**
     * The phone's own public IP, captured only while NO tunnel was up (so it cannot be a VPN address). Used to confirm
     * speed-test traffic really leaves through the tunnel. Unknown (null) means the check is skipped, never failed.
     */
    @Volatile private var directIpNoVpn: String? = null

    private fun tester() = HttpThroughputTester(
        directIp = { directIpNoVpn },
        networkToken = { Networks.networkToken(app) },
    )

    private fun gate(fp: String) = ConnectionGate(tunnel, Repo.settings.value.encryption, Repo.ledger, Repo.ledger, fp, wallClock)

    private suspend fun runQuick(previous: Server?) {
        val st = Repo.settings.value
        val ctx = runContext(previous)
        val engine = QuickConnectEngine(
            catalog, prober(), tunnel, Repo.ledger, Repo.ledger,
            clock = wallClock, cfg = QuickConfig(mode = st.encryption), recorder = Repo.ledger,
        )
        val t0 = SystemClock.elapsedRealtime()
        var timedOut = false
        val outcome = try {
            coroutineScope {
                // The engine's own timeout counts coroutine time, which pauses while the CPU sleeps. This watchdog counts
                // real elapsed time, so "35 seconds" means 35 seconds on the clock even if the screen is off.
                val job = currentCoroutineContext()[Job]!!
                val dog = launch {
                    while (isActive) {
                        delay(1_000)
                        if (SystemClock.elapsedRealtime() - t0 > QuickConfig().budgetMs + WATCHDOG_GRACE_MS) { timedOut = true; job.cancel(); break }
                    }
                }
                try { engine.run(st.quickScope, ctx, ::onEvent) } finally { dog.cancel() }
            }
        } catch (e: CancellationException) {
            if (!timedOut) throw e
            withContext(NonCancellable) {
                tunnel.abort()
                ui.update { it.copy(banner = Banner(failText(FailReason.TIMEOUT, st.quickScope), true)) }
                restore(previous)
            }
            return
        }
        when (outcome) {
            is QuickOutcome.Connected -> {
                val sel = outcome.selection
                val how = "Picked the lowest-latency of ${sel.responded} servers that answered (${sel.probed} checked, ${sel.poolSize} available). " +
                    "This is a quick estimate, not a speed test." + if (sel.lowConfidence) " Some results only show the server is online, not that its VPN service is." else ""
                ui.update {
                    it.copy(banner = Banner("Connected to ${Countries.flag(outcome.server.countryCode)} ${outcome.server.id} in ${outcome.elapsedMs / 1000} s. $how${cipherNote(outcome.cipher, outcome.cipherVerified)}", false))
                }
            }
            is QuickOutcome.Failed -> {
                ui.update { it.copy(banner = Banner(failText(outcome.reason, st.quickScope), true)) }
                restore(outcome.restore)
            }
        }
    }

    private suspend fun runBest(previous: Server?) {
        val st = Repo.settings.value
        val metered = Networks.isMetered(app)
        val control = BestControl().also { bestControl = it }
        val cfg = BestConfig(
            optimize = st.optimize, maxCandidates = st.maxCandidates, mode = st.encryption,
            perCandidateBytes = if (metered) 8L shl 20 else 30L shl 20,
            runCapBytes = if (metered) 60L shl 20 else 300L shl 20,
        )
        val engine = BestConnectEngine(catalog, prober(), tunnel, tester(), Repo.ledger, Repo.ledger, clock = wallClock, cfg = cfg, recorder = Repo.ledger)
        when (val out = engine.run(st.bestScope, runContext(previous), control, ::onEvent)) {
            is BestOutcome.Connected -> ui.update {
                it.copy(
                    lastReport = out.report,
                    banner = Banner("Best server: ${Countries.flag(out.report.winner.countryCode)} ${out.report.winner.id}. ${out.report.reason}${cipherNote(out.cipher, out.cipherVerified)}", false),
                )
            }
            is BestOutcome.Failed -> {
                ui.update { it.copy(banner = Banner(failText(out.reason, st.bestScope), true)) }
                restore(out.restore)
            }
        }
    }

    private suspend fun runWorldwide(previous: Server?, resume: Boolean) {
        val st = Repo.settings.value
        val ctx = runContext(previous)
        val metered = Networks.isMetered(app)
        if (metered && !st.worldwideAllowMetered) {
            ui.update { it.copy(banner = Banner("Worldwide search is off on mobile data and metered Wi-Fi. Turn it on in Settings, or connect to unmetered Wi-Fi.", true)) }
            return
        }
        val battery = batteryState()
        if (battery.percent in 0..19 && !battery.charging) {
            ui.update { it.copy(banner = Banner("Battery is below 20%. Plug in, then start the worldwide search.", true)) }
            return
        }
        val cap = st.worldwideDataMb.toLong() shl 20
        val control = SearchControl().also { searchControl = it; userPausedSearch = false }
        val cfg = GlobalSearchConfig(
            mode = st.encryption, optimize = st.optimize,
            runCapBytes = cap, perCandidateBytes = minOf(30L shl 20, cap / 4), connectAtEnd = true,
        )
        val engine = GlobalSearchEngine(catalog, prober(), tunnel, tester(), Repo.ledger, Repo.ledger, Repo.ledger, wallClock, cfg)
        val seeds = if (resume) Repo.searchSeeds() else emptyMap()
        var lastSaved = 0L
        fun save(p: SearchProgress, finished: Boolean, stop: String?) {
            Repo.saveSearch(
                SavedSearch(
                    System.currentTimeMillis(), finished, p.poolSize, p.probed, p.speedTested, p.bytesUsed, stop,
                    p.leaderboard.take(10).mapNotNull { e ->
                        e.throughput?.let { t -> SavedEntry(e.server.id, e.server.countryCode, t.downMbps, t.upMbps, t.idleRttMs, t.loadedRttMs, t.bytesUsed, e.score) }
                    },
                ),
            )
        }
        var latest: SearchProgress? = null
        val outcome = coroutineScope {
            // Pause automatically when conditions are bad (no network, low battery), resume when they clear, unless the user paused.
            val monitor = launch {
                var autoPaused = false
                while (isActive) {
                    delay(5_000)
                    val bad = when {
                        Networks.physicalNetwork(app) == null -> "No network connection."
                        batteryState().let { it.percent in 0..14 && !it.charging } -> "Battery is low."
                        else -> null
                    }
                    if (bad != null && !autoPaused && !userPausedSearch) {
                        autoPaused = true; control.pause(); ui.update { it.copy(run = it.run?.copy(pauseReason = bad)) }
                    } else if (bad == null && autoPaused) {
                        autoPaused = false
                        if (!userPausedSearch) control.resume()
                        ui.update { it.copy(run = it.run?.copy(pauseReason = null)) }
                    }
                }
            }
            try {
                engine.run(ctx, control, seeds) { ev ->
                    onEvent(ev)
                    if (ev is EngineEvent.Search) {
                        latest = ev.progress
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastSaved > 10_000) { lastSaved = now; save(ev.progress, false, null) }
                    }
                }
            } finally {
                monitor.cancel()
            }
        }
        val out = outcome as SearchOutcome.Finished
        latest?.let { save(it, true, out.stop.name) }
        val r = out.report
        when {
            r == null -> {
                ui.update { it.copy(banner = Banner(if (out.stop == StopReason.NO_SERVERS) "No usable servers were available for a worldwide search." else "The worldwide search found nothing.", true)) }
                restore(out.restore)
            }
            out.connected == null -> {
                ui.update { it.copy(lastReport = r, banner = Banner("Search finished, but no server accepted the connection. ${r.reason}", true)) }
                restore(out.restore)
            }
            else -> ui.update {
                it.copy(
                    lastReport = r,
                    banner = Banner("Best found: ${Countries.flag(r.winner.countryCode)} ${r.winner.id}. ${r.reason}${cipherNote(out.connected!!.cipher, out.connected!!.cipherVerified)}", false),
                )
            }
        }
    }

    private suspend fun runManual(server: Server, previous: Server?) {
        ui.update { it.copy(run = it.run?.copy(connectingTo = server, phase = PhaseId.CONNECTING)) }
        when (val g = gate(Networks.fingerprint(app)).connect(server, 30_000)) {
            is GateResult.Open -> ui.update {
                it.copy(banner = Banner("Connected to ${Countries.flag(server.countryCode)} ${server.id}.${cipherNote(g.admitted.cipher, g.admitted.cipherVerified)}", false))
            }
            is GateResult.Refused -> {
                ui.update { it.copy(banner = Banner("Could not connect to ${server.id}: ${g.result.error ?: "failed"}", true)) }
                restore(previous)
            }
        }
    }

    /** Brings back [previous] through the same encryption gate as any other connection; never reconnects something the policy now forbids. */
    private suspend fun restore(previous: Server?) {
        if (previous == null) { tunnel.disconnect(); return }
        if (tunnel.currentServerId == previous.id) return
        ui.update { it.copy(banner = Banner("Reconnecting to ${previous.id}…", false)) }
        when (val g = gate(Networks.fingerprint(app)).connect(previous, 20_000)) {
            is GateResult.Open -> Unit
            is GateResult.Refused -> {
                tunnel.disconnect()
                ui.update { it.copy(banner = Banner("Could not restore your previous connection: ${g.result.error ?: "failed"}.", true)) }
            }
        }
    }

    private fun cipherNote(cipher: String?, verified: Boolean): String =
        if (verified && cipher != null) " Encryption: $cipher (verified)." else " Encryption could not be verified."

    private fun failText(reason: FailReason, scope: Scope): String {
        val where = if (scope is Scope.Country) Countries.name(scope.iso) else "any country"
        return when (reason) {
            FailReason.NO_SERVERS_IN_SCOPE ->
                "VPN Gate has no usable servers in $where right now. Try again later or choose Any country."
            FailReason.COULD_NOT_CONNECT ->
                "No server in $where accepted the connection. Try again, pick another country, or run Best Connect."
            FailReason.TIMEOUT -> "Could not connect within 35 seconds. Try again or pick another country."
        }
    }

    private data class Battery(val percent: Int, val charging: Boolean)

    private fun batteryState(): Battery {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return Battery(pct, status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
    }

    private fun onEvent(ev: EngineEvent) {
        if (Log.isLoggable("VPNAPP", Log.DEBUG)) {
            when (ev) {
                is EngineEvent.Phase -> Log.d("VPNAPP", "phase ${ev.phase} ${ev.detail ?: ""}")
                is EngineEvent.ProbeProgress -> Log.d("VPNAPP", "probed ${ev.done}/${ev.total}")
                is EngineEvent.Connecting -> Log.d("VPNAPP", "connecting #${ev.attempt}")
                is EngineEvent.Leaderboard -> Log.d("VPNAPP", "leaderboard tested=${ev.tested}/${ev.planned}")
                is EngineEvent.Search -> Log.d("VPNAPP", "search ${ev.progress.phase} probed=${ev.progress.probed}/${ev.progress.poolSize} tested=${ev.progress.speedTested}")
            }
        }
        ui.update { s ->
            val r = s.run ?: return@update s
            s.copy(
                run = when (ev) {
                    is EngineEvent.Phase -> r.copy(phase = ev.phase, detail = ev.detail)
                    is EngineEvent.ProbeProgress -> r.copy(done = ev.done, total = ev.total, top = ev.top)
                    is EngineEvent.Connecting -> r.copy(connectingTo = ev.server, phase = PhaseId.CONNECTING)
                    is EngineEvent.Leaderboard ->
                        r.copy(leaderboard = ev.entries, tested = ev.tested, planned = ev.planned, bytesUsed = ev.bytesUsed)
                    is EngineEvent.Search -> r.copy(search = ev.progress, leaderboard = ev.progress.leaderboard, bytesUsed = ev.progress.bytesUsed)
                },
            )
        }
    }

    private const val WATCHDOG_GRACE_MS = 3_000L
}
