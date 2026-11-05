package io.github.geniusdevam.vpnapp.engine

import org.json.JSONArray
import org.json.JSONObject

/** JSON (de)serialisation of the ledger and directory pool. The app encrypts the result; nothing here touches disk. */
object Codec {
    const val LEDGER_VERSION = 1

    private fun JSONObject.optLongOrNull(k: String): Long? = if (has(k) && !isNull(k)) getLong(k) else null
    private fun JSONObject.optDoubleOrNull(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null
    private fun JSONObject.optStr(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
    private inline fun <reified T : Enum<T>> enumOrNull(n: String?): T? = n?.let { v -> enumValues<T>().firstOrNull { it.name == v } }

    // ---- ledger ----
    fun ledgerToJson(s: LedgerSnapshot): JSONObject {
        val arr = JSONArray()
        for (r in s.records) {
            val nets = JSONObject()
            r.networks.forEach { (fp, n) ->
                nets.put(fp, JSONObject()
                    .put("l", n.latencyMs ?: JSONObject.NULL).put("j", n.jitterMs ?: JSONObject.NULL).put("x", n.loss ?: JSONObject.NULL)
                    .put("m", n.probeMethod.name).put("la", n.latencyAt)
                    .put("d", n.downMbps ?: JSONObject.NULL).put("u", n.upMbps ?: JSONObject.NULL).put("lr", n.loadedRttMs ?: JSONObject.NULL)
                    .put("ta", n.throughputAt).put("us", n.lastUsedAt))
            }
            val fk = JSONObject(); r.failuresByKind.forEach { (k, v) -> fk.put(k.name, v) }
            arr.put(JSONObject()
                .put("id", r.id).put("fs", r.firstSeenAt).put("ls", r.lastSeenInDirectoryAt).put("ab", r.absentObservedMs)
                .put("ep", r.endpoint ?: JSONObject.NULL).put("cc", r.country ?: JSONObject.NULL)
                .put("ok", r.successCount).put("ko", r.failureCount)
                .put("lok", r.lastSuccessAt ?: JSONObject.NULL).put("lko", r.lastFailureAt ?: JSONObject.NULL)
                .put("lk", r.lastFailure?.name ?: JSONObject.NULL).put("fk", fk)
                .put("cm", r.connectMsAvg ?: JSONObject.NULL)
                .put("ci", r.lastCipher ?: JSONObject.NULL).put("cv", r.lastCipherVerified)
                .put("tr", r.lastTransport?.name ?: JSONObject.NULL)
                .put("sc", r.sessionCount).put("st", r.totalSessionMs).put("ud", r.unexpectedDisconnects).put("sl", r.lastSessionMs ?: JSONObject.NULL)
                .put("cf", r.consecutiveFailures).put("bu", r.blockedUntilMs).put("pf", r.penaltyFailures)
                .put("net", nets))
        }
        return JSONObject().put("v", LEDGER_VERSION).put("obs", s.lastObservationAt ?: JSONObject.NULL).put("rec", arr)
    }

    fun ledgerFromJson(o: JSONObject): LedgerSnapshot {
        val out = ArrayList<ServerRecord>()
        val arr = o.optJSONArray("rec") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            val id = j.optStr("id") ?: continue
            val nets = HashMap<String, NetStats>()
            j.optJSONObject("net")?.let { n ->
                n.keys().forEach { fp ->
                    val x = n.getJSONObject(fp)
                    nets[fp] = NetStats(
                        latencyMs = x.optDoubleOrNull("l"), jitterMs = x.optDoubleOrNull("j"), loss = x.optDoubleOrNull("x"),
                        probeMethod = enumOrNull<ProbeMethod>(x.optStr("m")) ?: ProbeMethod.NONE, latencyAt = x.optLong("la"),
                        downMbps = x.optDoubleOrNull("d"), upMbps = x.optDoubleOrNull("u"), loadedRttMs = x.optDoubleOrNull("lr"),
                        throughputAt = x.optLong("ta"), lastUsedAt = x.optLong("us"),
                    )
                }
            }
            val fk = HashMap<FailureKind, Int>()
            j.optJSONObject("fk")?.let { f -> f.keys().forEach { k -> enumOrNull<FailureKind>(k)?.let { e -> fk[e] = f.getInt(k) } } }
            out.add(ServerRecord(
                id = id, firstSeenAt = j.optLong("fs"), lastSeenInDirectoryAt = j.optLong("ls"), absentObservedMs = j.optLong("ab"),
                endpoint = j.optStr("ep"), country = j.optStr("cc"),
                successCount = j.optInt("ok"), failureCount = j.optInt("ko"),
                lastSuccessAt = j.optLongOrNull("lok"), lastFailureAt = j.optLongOrNull("lko"),
                lastFailure = enumOrNull<FailureKind>(j.optStr("lk")), failuresByKind = fk,
                connectMsAvg = j.optDoubleOrNull("cm"), lastCipher = j.optStr("ci"), lastCipherVerified = j.optBoolean("cv"),
                lastTransport = enumOrNull<Transport>(j.optStr("tr")),
                sessionCount = j.optInt("sc"), totalSessionMs = j.optLong("st"), unexpectedDisconnects = j.optInt("ud"),
                lastSessionMs = j.optLongOrNull("sl"),
                consecutiveFailures = j.optInt("cf"), blockedUntilMs = j.optLong("bu"), penaltyFailures = j.optInt("pf"),
                networks = nets,
            ))
        }
        return LedgerSnapshot(out, o.optLongOrNull("obs"))
    }

    /**
     * Imports the pre-ledger state (reputation `rep`, latency `lat`, throughput `thr`) so an app update loses nothing.
     * Imported relays start with a fresh absence clock.
     */
    fun importLegacy(rep: JSONObject?, lat: JSONObject?, thr: JSONObject?, now: Long): LedgerSnapshot {
        val map = HashMap<String, ServerRecord>()
        fun rec(id: String) = map.getOrPut(id) { ServerRecord(id, firstSeenAt = now, lastSeenInDirectoryAt = now) }
        rep?.keys()?.forEach { id ->
            val a = rep.optJSONArray(id) ?: return@forEach
            if (a.length() < 3) return@forEach
            map[id] = rec(id).copy(consecutiveFailures = a.optInt(0), blockedUntilMs = a.optLong(1), penaltyFailures = a.optInt(2), failureCount = a.optInt(2))
        }
        fun split(k: String): Pair<String, String>? = k.lastIndexOf('|').takeIf { it > 0 }?.let { k.substring(0, it) to k.substring(it + 1) }
        lat?.keys()?.forEach { k ->
            val (id, fp) = split(k) ?: return@forEach
            val a = lat.optJSONArray(k) ?: return@forEach
            val r = rec(id)
            map[id] = r.copy(networks = r.networks + (fp to (r.networks[fp] ?: NetStats()).copy(latencyMs = a.optDouble(0), latencyAt = a.optLong(1), lastUsedAt = a.optLong(1))))
        }
        thr?.keys()?.forEach { k ->
            val (id, fp) = split(k) ?: return@forEach
            val a = thr.optJSONArray(k) ?: return@forEach
            val r = rec(id)
            map[id] = r.copy(networks = r.networks + (fp to (r.networks[fp] ?: NetStats()).copy(downMbps = a.optDouble(0), throughputAt = a.optLong(1))))
        }
        return LedgerSnapshot(map.values.toList(), null)
    }

    // ---- directory pool ----
    fun serverToJson(s: Server): JSONObject = JSONObject()
        .put("id", s.id).put("ip", s.ip).put("port", s.port).put("tr", s.transport.name)
        .put("cc", s.countryCode).put("cn", s.countryName)
        .put("spd", s.reportedSpeedBps ?: JSONObject.NULL).put("ses", s.sessions ?: JSONObject.NULL)
        .put("up", s.uptimeMs ?: JSONObject.NULL).put("log", s.logPolicy ?: JSONObject.NULL)
        .put("op", s.operator ?: JSONObject.NULL).put("msg", s.message ?: JSONObject.NULL)
        .put("ciph", s.declaredCipher ?: JSONObject.NULL).put("tls", s.tlsAuthOrCrypt).put("cfg", s.config)
        .put("vs", s.vpnGateScore ?: JSONObject.NULL).put("vp", s.vpnGatePingMs ?: JSONObject.NULL)
        .put("tu", s.totalUsers ?: JSONObject.NULL).put("tt", s.totalTrafficBytes ?: JSONObject.NULL)

    fun serverFromJson(j: JSONObject): Server = Server(
        id = j.getString("id"), ip = j.getString("ip"), port = j.getInt("port"),
        transport = Transport.valueOf(j.getString("tr")),
        countryCode = j.getString("cc"), countryName = j.getString("cn"),
        reportedSpeedBps = j.optLongOrNull("spd"), sessions = j.optLongOrNull("ses")?.toInt(), uptimeMs = j.optLongOrNull("up"),
        logPolicy = j.optStr("log"), operator = j.optStr("op"), message = j.optStr("msg"),
        declaredCipher = j.optStr("ciph"), tlsAuthOrCrypt = j.getBoolean("tls"), config = j.getString("cfg"),
        vpnGateScore = j.optLongOrNull("vs"), vpnGatePingMs = j.optLongOrNull("vp")?.toInt(),
        totalUsers = j.optLongOrNull("tu"), totalTrafficBytes = j.optLongOrNull("tt"),
    )

    fun poolToJson(entries: List<DirectoryPool.Entry>, at: Long?): JSONObject {
        val a = JSONArray()
        entries.forEach { a.put(JSONObject().put("fs", it.firstSeenAt).put("ls", it.lastSeenAt).put("s", serverToJson(it.server))) }
        return JSONObject().put("v", 2).put("at", at ?: JSONObject.NULL).put("servers", a)
    }

    /**
     * Reads either the current pool format or the v1 cache (`servers` = plain server objects, one fetch time `at`).
     * Entries that fail to parse are skipped, never fatal.
     */
    fun poolFromJson(o: JSONObject): Pair<List<DirectoryPool.Entry>, Long?> {
        val at = o.optLongOrNull("at")
        val a = o.optJSONArray("servers") ?: return emptyList<DirectoryPool.Entry>() to at
        val out = ArrayList<DirectoryPool.Entry>()
        for (i in 0 until a.length()) {
            val j = a.optJSONObject(i) ?: continue
            try {
                if (j.has("s")) out.add(DirectoryPool.Entry(serverFromJson(j.getJSONObject("s")), j.optLong("fs"), j.optLong("ls")))
                else out.add(DirectoryPool.Entry(serverFromJson(j), at ?: 0L, at ?: 0L))
            } catch (_: Exception) { }
        }
        return out to at
    }
}
