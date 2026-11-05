package io.github.geniusdevam.vpnapp.data

import android.content.Context
import io.github.geniusdevam.vpnapp.engine.Codec
import io.github.geniusdevam.vpnapp.engine.DirectoryDiagnostics
import io.github.geniusdevam.vpnapp.engine.DirectoryPool
import io.github.geniusdevam.vpnapp.engine.DirectoryResult
import io.github.geniusdevam.vpnapp.engine.EncryptionMode
import io.github.geniusdevam.vpnapp.engine.EndpointInfo
import io.github.geniusdevam.vpnapp.engine.ListingStats
import io.github.geniusdevam.vpnapp.engine.OptimizeFor
import io.github.geniusdevam.vpnapp.engine.Scope
import io.github.geniusdevam.vpnapp.engine.Server
import io.github.geniusdevam.vpnapp.engine.ServerLedger
import io.github.geniusdevam.vpnapp.engine.ThroughputStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

enum class DnsChoice(val label: String, val primary: String, val secondary: String) {
    QUAD9("Quad9 (9.9.9.9)", "9.9.9.9", "149.112.112.112"),
    CLOUDFLARE("Cloudflare (1.1.1.1)", "1.1.1.1", "1.0.0.1"),
}

data class Settings(
    val quickScope: Scope = Scope.Any,
    val bestScope: Scope = Scope.Any,
    val encryption: EncryptionMode = EncryptionMode.COMPATIBLE,
    val optimize: OptimizeFor = OptimizeFor.BALANCED,
    val dns: DnsChoice = DnsChoice.QUAD9,
    val maxCandidates: Int = 10,
    val favorites: Set<String> = emptySet(),
    /** Apps that bypass the VPN entirely (e.g. banking). */
    val excludedApps: Set<String> = emptySet(),
    val acknowledged: Boolean = false,
    /** Data the worldwide search may spend on speed tests (MB). */
    val worldwideDataMb: Int = 150,
    /** Worldwide search runs on mobile data / metered Wi-Fi only if this is on. */
    val worldwideAllowMetered: Boolean = false,
)

/** A compact record of the last worldwide search, so an interrupted one can be reviewed or resumed. */
data class SavedSearch(
    val at: Long,
    val finished: Boolean,
    val poolSize: Int,
    val probed: Int,
    val tested: Int,
    val bytes: Long,
    val stop: String?,
    val entries: List<SavedEntry>,
)

data class SavedEntry(val id: String, val cc: String, val down: Double, val up: Double, val idle: Double, val loaded: Double, val bytes: Long, val score: Double)

private fun Scope.encode(): String = when (this) { Scope.Any -> "*"; is Scope.Country -> iso }
private fun decodeScope(s: String?): Scope = if (s.isNullOrEmpty() || s == "*") Scope.Any else Scope.Country(s)

/**
 * Single source of truth for persisted state. All writes are encrypted through [SecureVault]:
 *  - "state"   settings
 *  - "ledger"  the permanent per-server history (see [ServerLedger] for the seven-day absence rule)
 *  - "servers" the directory pool (relays seen in recent listings) and directory diagnostics
 *  - "search"  the last worldwide search
 * Older installs kept reputation/latency/throughput inside "state"; they are imported once, so updating loses nothing.
 */
object Repo {
    private lateinit var ctx: Context
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private var inited = false

    /** Success/failure history, reputation, latency and throughput priors. Never sent anywhere. */
    val ledger = ServerLedger()
    val reputation: ServerLedger get() = ledger
    val history: ServerLedger get() = ledger
    private val pool = DirectoryPool()

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings

    /** Relays selectable right now (union of recent listings). */
    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers

    private val _directory = MutableStateFlow(DirectoryDiagnostics(source = "vpngate.net (HTTPS)"))
    val directory: StateFlow<DirectoryDiagnostics> = _directory

    private val _lastSearch = MutableStateFlow<SavedSearch?>(null)
    val lastSearch: StateFlow<SavedSearch?> = _lastSearch

    @Volatile var serversFetchedAt: Long = 0L
        private set

    private const val FRESH_MS = 24L * 3_600_000

    @Synchronized
    fun init(context: Context) {
        if (inited) return
        ctx = context.applicationContext
        inited = true
        val state = SecureVault.read(ctx, "state")?.let { runCatching { JSONObject(String(it)) }.getOrNull() }
        state?.let { runCatching { loadSettings(it) } }

        val ledgerJson = SecureVault.read(ctx, "ledger")?.let { runCatching { JSONObject(String(it)) }.getOrNull() }
        if (ledgerJson != null) {
            runCatching { ledger.restore(Codec.ledgerFromJson(ledgerJson)) }
        } else if (state != null && (state.has("rep") || state.has("lat") || state.has("thr"))) {
            // One-time import of the pre-ledger state. Written to its own file BEFORE "state" is rewritten without it.
            runCatching {
                ledger.restore(Codec.importLegacy(state.optJSONObject("rep"), state.optJSONObject("lat"), state.optJSONObject("thr"), System.currentTimeMillis()))
                SecureVault.write(ctx, "ledger", Codec.ledgerToJson(ledger.snapshot()).toString().toByteArray())
            }
        }
        SecureVault.read(ctx, "servers")?.let { b ->
            runCatching {
                val o = JSONObject(String(b))
                val (entries, at) = Codec.poolFromJson(o)
                pool.restore(entries)
                serversFetchedAt = at ?: 0L
                o.optJSONObject("diag")?.let { _directory.value = diagFromJson(it) }
            }
        }
        SecureVault.read(ctx, "search")?.let { b -> runCatching { _lastSearch.value = searchFromJson(JSONObject(String(b))) } }
        refreshServerList()
    }

    // ------------------------------------------------------------------ settings

    fun update(f: (Settings) -> Settings) {
        _settings.update(f)
        saveState()
    }

    private fun saveState() {
        val snapshot = stateJson()
        io.launch { writeLock.withLock { runCatching { SecureVault.write(ctx, "state", snapshot.toString().toByteArray()) } } }
    }

    private fun stateJson(): JSONObject {
        val s = _settings.value
        return JSONObject()
            .put("quick", s.quickScope.encode()).put("best", s.bestScope.encode())
            .put("enc", s.encryption.name).put("opt", s.optimize.name).put("dns", s.dns.name)
            .put("maxc", s.maxCandidates).put("fav", JSONArray(s.favorites.toList()))
            .put("excl", JSONArray(s.excludedApps.toList())).put("ack", s.acknowledged)
            .put("wwmb", s.worldwideDataMb).put("wwmet", s.worldwideAllowMetered)
    }

    private fun loadSettings(o: JSONObject) {
        fun <T : Enum<T>> en(name: String?, all: Array<T>, def: T) = all.firstOrNull { it.name == name } ?: def
        _settings.value = Settings(
            quickScope = decodeScope(o.optString("quick")),
            bestScope = decodeScope(o.optString("best")),
            encryption = en(o.optString("enc"), EncryptionMode.values(), EncryptionMode.COMPATIBLE),
            optimize = en(o.optString("opt"), OptimizeFor.values(), OptimizeFor.BALANCED),
            dns = en(o.optString("dns"), DnsChoice.values(), DnsChoice.QUAD9),
            maxCandidates = o.optInt("maxc", 10).coerceIn(4, 25),
            favorites = strings(o.optJSONArray("fav")),
            excludedApps = strings(o.optJSONArray("excl")),
            acknowledged = o.optBoolean("ack", false),
            worldwideDataMb = o.optInt("wwmb", 150).coerceIn(30, 1000),
            worldwideAllowMetered = o.optBoolean("wwmet", false),
        )
    }

    private fun strings(a: JSONArray?): Set<String> = buildSet { if (a != null) for (i in 0 until a.length()) add(a.getString(i)) }

    // ------------------------------------------------------------------ ledger

    /** Persist the ledger after a run or any change to reputation/history. */
    fun saveLearning() {
        val snap = ledger.snapshot()
        io.launch { writeLock.withLock { runCatching { SecureVault.write(ctx, "ledger", Codec.ledgerToJson(snap).toString().toByteArray()) } } }
    }

    /** Settings > Clear history: forgets measurements and counters; retention bookkeeping stays. */
    fun clearHistory() {
        ledger.clearMeasurements()
        saveLearning()
    }

    // ------------------------------------------------------------------ directory

    /** Relays to use: those seen in the last 24 h, or everything cached if none are that recent (offline fallback). */
    fun pooledServers(): List<Server> {
        val fresh = pool.servers(FRESH_MS, System.currentTimeMillis())
        return fresh.ifEmpty { pool.servers(0, System.currentTimeMillis()) }
    }

    fun lastValidProfiles(): Int = _directory.value.listing.validProfiles

    private fun refreshServerList() { _servers.value = pooledServers() }

    fun directoryOk(startedAt: Long, result: DirectoryResult) {
        val now = System.currentTimeMillis()
        val listing = result.usable.associate { it.id to EndpointInfo(it.ip, it.port, it.transport, it.countryCode) }
        ledger.observeDirectory(listing, usable = true, at = now)
        val merge = pool.merge(result.usable, now)
        serversFetchedAt = now
        refreshServerList()
        val pooled = pooledServers()
        _directory.value = DirectoryDiagnostics(
            source = "vpngate.net (HTTPS)", lastSuccessfulRefreshAt = now, lastAttemptAt = startedAt, lastError = null,
            listing = result.stats, pooledUsable = pooled.size, pooledCountries = pooled.map { it.countryCode }.toSet().size,
            newInLastRefresh = merge.added, servingStaleCache = false, knownPartial = true, savedServerRecords = ledger.size(),
        )
        savePool(); saveLearning()
    }

    fun directoryFailed(startedAt: Long, error: String, stats: ListingStats? = null) {
        val pooled = pooledServers()
        _directory.update {
            it.copy(
                lastAttemptAt = startedAt, lastError = error, servingStaleCache = pooled.isNotEmpty(),
                pooledUsable = pooled.size, pooledCountries = pooled.map { s -> s.countryCode }.toSet().size, savedServerRecords = ledger.size(),
                listing = stats ?: it.listing,
            )
        }
        savePool() // keeps diagnostics (and the last error) across restarts; relays and ledger are untouched
    }

    private fun savePool() {
        val diag = diagToJson(_directory.value)
        val o = Codec.poolToJson(pool.entries(), serversFetchedAt.takeIf { it > 0 }).put("diag", diag)
        io.launch { writeLock.withLock { runCatching { SecureVault.write(ctx, "servers", o.toString().toByteArray()) } } }
    }

    private fun diagToJson(d: DirectoryDiagnostics): JSONObject {
        val rej = JSONObject(); d.listing.rejected.forEach { (k, v) -> rej.put(k, v) }
        return JSONObject()
            .put("src", d.source).put("ok", d.lastSuccessfulRefreshAt ?: JSONObject.NULL).put("att", d.lastAttemptAt ?: JSONObject.NULL)
            .put("err", d.lastError ?: JSONObject.NULL).put("new", d.newInLastRefresh)
            .put("rows", d.listing.rowsReceived).put("valid", d.listing.validProfiles).put("usable", d.listing.usableServers)
            .put("cc", d.listing.countries).put("rej", rej)
    }

    private fun diagFromJson(j: JSONObject): DirectoryDiagnostics {
        val rej = HashMap<String, Int>()
        j.optJSONObject("rej")?.let { r -> r.keys().forEach { k -> rej[k] = r.optInt(k) } }
        fun l(k: String) = if (j.isNull(k) || !j.has(k)) null else j.getLong(k)
        return DirectoryDiagnostics(
            source = j.optString("src", "vpngate.net (HTTPS)"), lastSuccessfulRefreshAt = l("ok"), lastAttemptAt = l("att"),
            lastError = if (j.isNull("err")) null else j.optString("err"), newInLastRefresh = j.optInt("new"),
            listing = ListingStats(j.optInt("rows"), j.optInt("valid"), j.optInt("usable"), j.optInt("cc"), rej),
            pooledUsable = pooledServers().size, pooledCountries = pooledServers().map { it.countryCode }.toSet().size,
            servingStaleCache = false, savedServerRecords = ledger.size(),
        )
    }

    // ------------------------------------------------------------------ worldwide search record

    fun saveSearch(s: SavedSearch) {
        _lastSearch.value = s
        val o = searchToJson(s)
        io.launch { writeLock.withLock { runCatching { SecureVault.write(ctx, "search", o.toString().toByteArray()) } } }
    }

    /** Throughput results from an interrupted search that are recent enough (6 h) to reuse without re-measuring. */
    fun searchSeeds(): Map<String, ThroughputStats> {
        val s = _lastSearch.value ?: return emptyMap()
        if (s.finished || System.currentTimeMillis() - s.at > 6 * 3_600_000L) return emptyMap()
        return s.entries.associate { it.id to ThroughputStats(it.down, it.up, it.idle, it.loaded, it.bytes) }
    }

    private fun searchToJson(s: SavedSearch) = JSONObject()
        .put("at", s.at).put("fin", s.finished).put("pool", s.poolSize).put("pr", s.probed).put("te", s.tested).put("by", s.bytes)
        .put("stop", s.stop ?: JSONObject.NULL)
        .put("e", JSONArray().also { a ->
            s.entries.forEach {
                a.put(JSONObject().put("id", it.id).put("cc", it.cc).put("d", it.down).put("u", it.up).put("i", it.idle).put("l", it.loaded).put("b", it.bytes).put("s", it.score))
            }
        })

    private fun searchFromJson(o: JSONObject): SavedSearch {
        val a = o.optJSONArray("e") ?: JSONArray()
        val entries = (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { j -> SavedEntry(j.getString("id"), j.optString("cc"), j.optDouble("d"), j.optDouble("u"), j.optDouble("i"), j.optDouble("l"), j.optLong("b"), j.optDouble("s")) }
        }
        return SavedSearch(o.optLong("at"), o.optBoolean("fin"), o.optInt("pool"), o.optInt("pr"), o.optInt("te"), o.optLong("by"), if (o.isNull("stop")) null else o.optString("stop"), entries)
    }
}
