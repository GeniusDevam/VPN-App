package io.github.geniusdevam.vpnapp.data

import android.content.Context
import io.github.geniusdevam.vpnapp.engine.EncryptionMode
import io.github.geniusdevam.vpnapp.engine.InMemoryHistory
import io.github.geniusdevam.vpnapp.engine.InMemoryReputation
import io.github.geniusdevam.vpnapp.engine.OptimizeFor
import io.github.geniusdevam.vpnapp.engine.Scope
import io.github.geniusdevam.vpnapp.engine.Server
import io.github.geniusdevam.vpnapp.engine.Transport
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
)

private fun Scope.encode(): String = when (this) { Scope.Any -> "*"; is Scope.Country -> iso }
private fun decodeScope(s: String?): Scope = if (s.isNullOrEmpty() || s == "*") Scope.Any else Scope.Country(s)

/** Single source of truth for persisted state. All writes are encrypted through [SecureVault]. */
object Repo {
    private lateinit var ctx: Context
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private var inited = false

    val reputation = InMemoryReputation()
    val history = InMemoryHistory()

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings

    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers

    @Volatile var serversFetchedAt: Long = 0L
        private set

    @Synchronized
    fun init(context: Context) {
        if (inited) return
        ctx = context.applicationContext
        inited = true
        SecureVault.read(ctx, "state")?.let { runCatching { loadState(JSONObject(String(it))) } }
        SecureVault.read(ctx, "servers")?.let { runCatching { loadServers(JSONObject(String(it))) } }
    }

    fun update(f: (Settings) -> Settings) {
        _settings.update(f)
        saveState()
    }

    fun saveServers(list: List<Server>, at: Long = System.currentTimeMillis()) {
        _servers.value = list
        serversFetchedAt = at
        io.launch {
            writeLock.withLock {
                runCatching {
                    val o = JSONObject().put("at", at).put("servers", JSONArray().also { a -> list.forEach { a.put(serverToJson(it)) } })
                    SecureVault.write(ctx, "servers", o.toString().toByteArray())
                }
            }
        }
    }

    /** Persist reputation/history after a run. */
    fun saveLearning() = saveState()

    fun clearHistory() {
        history.restore(emptyMap(), emptyMap())
        saveState()
    }

    private fun saveState() {
        val snapshot = stateJson()
        io.launch { writeLock.withLock { runCatching { SecureVault.write(ctx, "state", snapshot.toString().toByteArray()) } } }
    }

    private fun stateJson(): JSONObject {
        val s = _settings.value
        val rep = JSONObject()
        reputation.export().forEach { (id, e) ->
            rep.put(id, JSONArray().put(e.consecutiveFailures).put(e.blockedUntilMs).put(e.failures))
        }
        val (lat, thr) = history.export()
        val latJ = JSONObject(); lat.forEach { (k, v) -> latJ.put(k, JSONArray().put(v.medianMs).put(v.atMs)) }
        val thrJ = JSONObject(); thr.forEach { (k, v) -> thrJ.put(k, JSONArray().put(v.downMbps).put(v.atMs)) }
        return JSONObject()
            .put("quick", s.quickScope.encode()).put("best", s.bestScope.encode())
            .put("enc", s.encryption.name).put("opt", s.optimize.name).put("dns", s.dns.name)
            .put("maxc", s.maxCandidates).put("fav", JSONArray(s.favorites.toList()))
            .put("excl", JSONArray(s.excludedApps.toList())).put("ack", s.acknowledged)
            .put("rep", rep).put("lat", latJ).put("thr", thrJ)
    }

    private fun loadState(o: JSONObject) {
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
        )
        val rep = HashMap<String, InMemoryReputation.Entry>()
        o.optJSONObject("rep")?.let { r -> r.keys().forEach { k -> val a = r.getJSONArray(k); rep[k] = InMemoryReputation.Entry(a.getInt(0), a.getLong(1), a.getInt(2)) } }
        reputation.restore(rep)
        val lat = HashMap<String, InMemoryHistory.Lat>()
        o.optJSONObject("lat")?.let { r -> r.keys().forEach { k -> val a = r.getJSONArray(k); lat[k] = InMemoryHistory.Lat(a.getDouble(0), a.getLong(1)) } }
        val thr = HashMap<String, InMemoryHistory.Thr>()
        o.optJSONObject("thr")?.let { r -> r.keys().forEach { k -> val a = r.getJSONArray(k); thr[k] = InMemoryHistory.Thr(a.getDouble(0), a.getLong(1)) } }
        history.restore(lat, thr)
    }

    private fun strings(a: JSONArray?): Set<String> = buildSet { if (a != null) for (i in 0 until a.length()) add(a.getString(i)) }

    private fun serverToJson(s: Server) = JSONObject()
        .put("id", s.id).put("ip", s.ip).put("port", s.port).put("tr", s.transport.name)
        .put("cc", s.countryCode).put("cn", s.countryName)
        .put("spd", s.reportedSpeedBps ?: JSONObject.NULL).put("ses", s.sessions ?: JSONObject.NULL)
        .put("up", s.uptimeMs ?: JSONObject.NULL).put("log", s.logPolicy ?: JSONObject.NULL)
        .put("op", s.operator ?: JSONObject.NULL).put("msg", s.message ?: JSONObject.NULL)
        .put("ciph", s.declaredCipher ?: JSONObject.NULL).put("tls", s.tlsAuthOrCrypt).put("cfg", s.config)

    private fun loadServers(o: JSONObject) {
        val a = o.getJSONArray("servers")
        val list = ArrayList<Server>(a.length())
        for (i in 0 until a.length()) {
            val j = a.getJSONObject(i)
            fun str(k: String) = if (j.isNull(k)) null else j.getString(k)
            list.add(
                Server(
                    id = j.getString("id"), ip = j.getString("ip"), port = j.getInt("port"),
                    transport = Transport.valueOf(j.getString("tr")),
                    countryCode = j.getString("cc"), countryName = j.getString("cn"),
                    reportedSpeedBps = if (j.isNull("spd")) null else j.getLong("spd"),
                    sessions = if (j.isNull("ses")) null else j.getInt("ses"),
                    uptimeMs = if (j.isNull("up")) null else j.getLong("up"),
                    logPolicy = str("log"), operator = str("op"), message = str("msg"),
                    declaredCipher = str("ciph"), tlsAuthOrCrypt = j.getBoolean("tls"), config = j.getString("cfg"),
                ),
            )
        }
        _servers.value = list
        serversFetchedAt = o.optLong("at", 0L)
    }
}
