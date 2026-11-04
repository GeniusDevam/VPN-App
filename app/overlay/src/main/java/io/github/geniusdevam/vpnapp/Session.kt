package io.github.geniusdevam.vpnapp

import android.app.Application
import de.blinkt.openvpn.core.ConnectionStatus
import io.github.geniusdevam.vpnapp.data.Repo
import io.github.geniusdevam.vpnapp.data.VpnGateCatalog
import io.github.geniusdevam.vpnapp.engine.BestConfig
import io.github.geniusdevam.vpnapp.engine.BestConnectEngine
import io.github.geniusdevam.vpnapp.engine.BestControl
import io.github.geniusdevam.vpnapp.engine.BestEntry
import io.github.geniusdevam.vpnapp.engine.BestOutcome
import io.github.geniusdevam.vpnapp.engine.BestReport
import io.github.geniusdevam.vpnapp.engine.Countries
import io.github.geniusdevam.vpnapp.engine.EngineEvent
import io.github.geniusdevam.vpnapp.engine.FailReason
import io.github.geniusdevam.vpnapp.engine.NetworkProber
import io.github.geniusdevam.vpnapp.engine.PhaseId
import io.github.geniusdevam.vpnapp.engine.QuickConfig
import io.github.geniusdevam.vpnapp.engine.QuickConnectEngine
import io.github.geniusdevam.vpnapp.engine.QuickOutcome
import io.github.geniusdevam.vpnapp.engine.Ranked
import io.github.geniusdevam.vpnapp.engine.RunContext
import io.github.geniusdevam.vpnapp.engine.Scope
import io.github.geniusdevam.vpnapp.engine.Server
import io.github.geniusdevam.vpnapp.net.Networks
import io.github.geniusdevam.vpnapp.vpn.HttpThroughputTester
import io.github.geniusdevam.vpnapp.vpn.TunnelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RunMode { QUICK, BEST, MANUAL }
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
)

data class VpnUi(
    val status: ConnState = ConnState.DISCONNECTED,
    val server: Server? = null,
    val cipher: String? = null,
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

/** Orchestrates connection runs. One run at a time; the UI, notification and tile all observe [ui]. */
object Session {
    val ui = MutableStateFlow(SessionUi())

    private lateinit var app: Application
    lateinit var tunnel: TunnelManager
        private set
    private val catalog = VpnGateCatalog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runJob: Job? = null
    private var bestControl: BestControl? = null
    private var inited = false

    @Synchronized
    fun init(application: Application) {
        if (inited) return
        inited = true
        app = application
        Repo.init(app)
        tunnel = TunnelManager(app) { Repo.settings.value }
        scope.launch {
            tunnel.state.collect { t ->
                ui.update { s ->
                    val status = when (t.level) {
                        ConnectionStatus.LEVEL_CONNECTED -> ConnState.CONNECTED
                        ConnectionStatus.LEVEL_NOTCONNECTED, ConnectionStatus.LEVEL_AUTH_FAILED, ConnectionStatus.UNKNOWN_LEVEL -> ConnState.DISCONNECTED
                        else -> ConnState.CONNECTING
                    }
                    s.copy(vpn = VpnUi(status, t.server, t.cipher, t.connectedAtMs, t.rxRate, t.txRate, t.stateName))
                }
            }
        }
    }

    val isRunning: Boolean get() = runJob?.isActive == true

    fun dismissBanner() = ui.update { it.copy(banner = null) }

    fun startQuick() = start(RunMode.QUICK)
    fun startBest() = start(RunMode.BEST)
    fun connectTo(server: Server) = start(RunMode.MANUAL, server)

    fun cancel() {
        runJob?.cancel(CancellationException("cancelled by user"))
    }

    fun finishBestNow() {
        bestControl?.finishNow()
    }

    fun disconnect() {
        cancel()
        scope.launch {
            tunnel.disconnect()
            ui.update { it.copy(banner = Banner("Disconnected", false)) }
        }
    }

    private fun start(mode: RunMode, manual: Server? = null) {
        if (isRunning) return
        val previous = tunnel.state.value.server
        ui.update { it.copy(run = RunUi(mode, System.currentTimeMillis()), banner = null) }
        RunService.start(app)
        runJob = scope.launch {
            try {
                when (mode) {
                    RunMode.QUICK -> runQuick(previous)
                    RunMode.BEST -> runBest(previous)
                    RunMode.MANUAL -> runManual(manual!!, previous)
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    restore(previous)
                    ui.update { it.copy(banner = Banner("Cancelled", false)) }
                }
                throw e
            } catch (e: Exception) {
                restore(previous)
                ui.update { it.copy(banner = Banner("Something went wrong: ${e.message}", true)) }
            } finally {
                bestControl = null
                ui.update { it.copy(run = null) }
                Repo.saveLearning()
            }
        }
    }

    private fun runContext(previous: Server?): RunContext = RunContext(
        userCountry = Networks.userCountry(app),
        fingerprint = Networks.fingerprint(app),
        previous = previous,
        favorites = Repo.settings.value.favorites,
    )

    private fun prober(): NetworkProber {
        fun bind(bindFn: (android.net.Network) -> Unit) {
            try { Networks.physicalNetwork(app)?.let(bindFn) } catch (_: Exception) {}
        }
        return NetworkProber(protectUdp = { s -> bind { it.bindSocket(s) } }, protectTcp = { s -> bind { it.bindSocket(s) } })
    }

    private suspend fun runQuick(previous: Server?) {
        val st = Repo.settings.value
        val engine = QuickConnectEngine(
            catalog, prober(), tunnel, Repo.history, Repo.reputation,
            cfg = QuickConfig(mode = st.encryption),
        )
        when (val out = engine.run(st.quickScope, runContext(previous), ::onEvent)) {
            is QuickOutcome.Connected -> ui.update {
                it.copy(banner = Banner("Connected to ${Countries.flag(out.server.countryCode)} ${out.server.id} in ${out.elapsedMs / 1000} s", false))
            }
            is QuickOutcome.Failed -> {
                ui.update { it.copy(banner = Banner(failText(out.reason, st.quickScope), true)) }
                restore(out.restore)
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
        val engine = BestConnectEngine(catalog, prober(), tunnel, HttpThroughputTester(), Repo.history, Repo.reputation, cfg = cfg)
        when (val out = engine.run(st.bestScope, runContext(previous), control, ::onEvent)) {
            is BestOutcome.Connected -> ui.update {
                it.copy(
                    lastReport = out.report,
                    banner = Banner("Best server: ${Countries.flag(out.report.winner.countryCode)} ${out.report.winner.id}. ${out.report.reason}", false),
                )
            }
            is BestOutcome.Failed -> {
                ui.update { it.copy(banner = Banner(failText(out.reason, st.bestScope), true)) }
                restore(out.restore)
            }
        }
    }

    private suspend fun runManual(server: Server, previous: Server?) {
        ui.update { it.copy(run = it.run?.copy(connectingTo = server, phase = PhaseId.CONNECTING)) }
        val r = tunnel.connect(server, 30_000)
        if (r.success) {
            Repo.reputation.recordSuccess(server.id)
            ui.update { it.copy(banner = Banner("Connected to ${Countries.flag(server.countryCode)} ${server.id}", false)) }
        } else {
            Repo.reputation.recordFailure(server.id, r.hard)
            ui.update { it.copy(banner = Banner("Could not connect to ${server.id}: ${r.error ?: "failed"}", true)) }
            restore(previous)
        }
    }

    private suspend fun restore(previous: Server?) {
        if (previous == null) { tunnel.disconnect(); return }
        if (tunnel.currentServerId == previous.id) return
        ui.update { it.copy(banner = Banner("Reconnecting to ${previous.id}…", false)) }
        val r = tunnel.connect(previous, 20_000)
        if (!r.success) {
            tunnel.disconnect()
            ui.update { it.copy(banner = Banner("Could not restore your previous connection.", true)) }
        }
    }

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

    private fun onEvent(ev: EngineEvent) {
        ui.update { s ->
            val r = s.run ?: return@update s
            s.copy(
                run = when (ev) {
                    is EngineEvent.Phase -> r.copy(phase = ev.phase, detail = ev.detail)
                    is EngineEvent.ProbeProgress -> r.copy(done = ev.done, total = ev.total, top = ev.top)
                    is EngineEvent.Connecting -> r.copy(connectingTo = ev.server, phase = PhaseId.CONNECTING)
                    is EngineEvent.Leaderboard ->
                        r.copy(leaderboard = ev.entries, tested = ev.tested, planned = ev.planned, bytesUsed = ev.bytesUsed)
                },
            )
        }
    }
}
