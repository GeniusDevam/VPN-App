# VPN App — Implementation Plan

> **Audience:** the agent that will implement this app.
> **Owner's device:** Android 16 smartphone. The app is sideloaded first; Google Play later is optional.
> **Status:** plan only. No code exists yet. Work through the milestones in §17 in order.

The owner asked for "the best VPN app available" with two connection modes:

1. **Quick Connect.** Connects to the fastest server it can find, and is connected within **35 seconds** of the tap.
2. **Best Connect.** Takes as long as it needs to find the truly fastest server, then connects.

In **each** mode the user can choose a **country**: "Any country" or one specific country. The two modes remember their country choice separately.

---

## 1. Goals, non-goals, definitions

### Goals
- A fast, modern, privacy-respecting WireGuard VPN client for Android 16 (it also works on older versions; see §14).
- Quick Connect: a **hard guarantee** of ≤ 35 s from tap to *Connected*. The typical case should be 3–8 s.
- Best Connect: real throughput testing of the leading candidates, with adaptive stopping, so the result is the fastest server *for this user, on this network, right now*.
- A country picker inside each mode, saved per mode.
- Leak protection, a clear status display and a polished Material 3 UI.

### Non-goals (for v1)
- Running a commercial server network. An optional backend is specified in §19, but the app must be useful without it.
- OpenVPN or IPSec support. WireGuard only, for speed and simplicity.
- iOS or desktop.

### Definitions
- **Server:** one WireGuard peer (endpoint, public key, country, city and optional coordinates and load).
- **Scope:** `Any` or `Country(isoCode)`. Optional later: `City`.
- **RTT:** round-trip time to the server, measured with a real WireGuard handshake (§7.1).
- **"Fastest":**
  - *Quick Connect:* lowest **latency score**: RTT median plus jitter, loss and load penalties, plus a bonus from past throughput history.
  - *Best Connect:* highest **composite score**: download, upload, loaded latency, idle latency, jitter and loss (§9.4).
- **Network fingerprint:** a privacy-preserving hash identifying the current network (§7.4). History and priors are stored per network.

---

## 2. Key technical decisions

| Topic | Decision |
|---|---|
| Protocol | WireGuard |
| Tunnel engine | `com.wireguard.android:tunnel` (official, Apache-2.0) using `GoBackend` (userspace wireguard-go, works without root). Use the latest version on Maven Central at implementation time. `1.0.20260102` was the newest seen while planning. |
| Language / UI | Kotlin, Jetpack Compose, Material 3 (Expressive components where stable), dynamic color |
| SDK levels | `compileSdk 36`, `targetSdk 36` (Android 16), `minSdk 26` |
| Architecture | MVVM + unidirectional data flow, Coroutines/Flow, Hilt (KSP) |
| Persistence | Room (servers, measurements, history) and DataStore (settings, per-mode scope) |
| Networking | OkHttp, kotlinx.serialization |
| Crypto for probes | BouncyCastle (X25519, ChaCha20-Poly1305, BLAKE2s) in a pure-JVM module |
| Server sources | Pluggable `ServerSource`. v1 ships **WireGuard config import**. A **signed remote catalog** is added later (§5). |
| Build/CI | Gradle Kotlin DSL with a version catalog, GitHub Actions builds the signed APK and attaches it to a GitHub Release (§15) |

---

## 3. Decision the owner must make: where the servers come from

**A VPN app is only as good as its servers.** The app cannot create servers. It must connect to WireGuard servers that someone operates. The app is built so the source is pluggable, and every option below uses the same Quick/Best Connect engine:

- **Option A: Import WireGuard configs (v1 default, no backend).**
  - The user imports a `.zip` of `.conf` files, single `.conf` files, or QR codes.
  - Sources: any VPN provider that offers WireGuard config downloads (many paid providers offer one config per server across many countries), or the owner's own servers.
  - The app derives each server's country automatically (§5.3).
- **Option B: The owner's own server network (§19).**
  - VPS servers in the chosen countries, plus a small control-plane API that publishes a signed catalog and registers device keys.
  - Typical cost is roughly US$5–10 per month per location.
  - This is the "real product" path and is required to publish to other users.
- **Option C (rejected for v1): free volunteer networks.** These are OpenVPN-based and have weak trust guarantees and GPL licensing friction. Reconsider only if the owner insists.

**Recommendation:** build with Option A first (Milestones M0–M5). Option B is Milestone M6, only if the owner wants it. The implementing agent should **ask the owner which provider or servers they will use** before M3 acceptance testing, because real servers are needed to verify on the device.

---

## 4. Architecture

```
┌──────────────────────────── :app (Android) ────────────────────────────┐
│ ui/        Compose screens + ViewModels                                │
│ vpn/       VpnSessionManager (state machine, single source of truth)   │
│            TunnelController (GoBackend wrapper)                        │
│            VpnSessionService (foreground service + notification)      │
│            QuickConnectTileService, AlwaysOn handling                  │
│ data/      Room DB, DataStore, ServerSources (import, remote), GeoIP   │
│ net/       NetworkMonitor, NetworkFingerprint, VPN-bound OkHttp        │
│ speed/     ThroughputTester (OkHttp, multi-stream)                     │
│ widget/    Glance home-screen widget                                   │
└───────────────┬────────────────────────────────────┬───────────────────┘
                │ depends on                         │ depends on
┌───────────────▼──────────────┐     ┌───────────────▼──────────────────┐
│ :engine  (pure Kotlin/JVM)   │────▶│ :probe  (pure Kotlin/JVM)        │
│ models, scoring, budgets     │     │ WireGuard handshake prober       │
│ QuickConnectEngine           │     │ (Noise IK initiation, mac1),     │
│ BestConnectEngine            │     │ TCP-connect & ICMP fallbacks     │
│ interfaces: LatencyProber,   │     └──────────────────────────────────┘
│ ThroughputTester, Tunnel,    │
│ Clock, Catalog               │
└──────────────────────────────┘
```

- `:engine` and `:probe` have **no Android dependencies**. They are fully unit-testable on the JVM with fake probers, tunnels and a **virtual clock** (`kotlinx-coroutines-test`). This is how the 35 s guarantee is proven (§16).
- `:app` provides the Android implementations of the engine interfaces.

### 4.1 Core interfaces (in `:engine`)

```kotlin
data class Endpoint(val host: String, val port: Int)           // host may be IPv4/IPv6 literal or DNS name
data class Server(
    val id: String, val sourceId: String,
    val countryCode: String, val city: String?, val lat: Double?, val lon: Double?,
    val endpoints: List<Endpoint>, val publicKey: String,      // base64
    val load: Float?,                                          // 0..1, null if unknown
    val tags: Set<String> = emptySet()
)
sealed interface Scope { data object Any : Scope; data class Country(val iso: String) : Scope }

data class LatencyStats(val medianMs: Double, val p90Ms: Double, val jitterMs: Double,
                        val loss: Double, val samples: Int, val method: ProbeMethod, val underLoad: Boolean)
data class ThroughputStats(val downMbps: Double, val upMbps: Double, val idleRttMs: Double,
                           val loadedRttMs: Double, val bytesUsed: Long, val noisy: Boolean)

interface Catalog { suspend fun servers(maxAge: Duration, fetchTimeout: Duration): List<Server> }
interface LatencyProber {
    fun probe(servers: List<Server>, cfg: ProbeConfig): Flow<Pair<Server, LatencyStats?>>
}
interface TunnelController {
    suspend fun connectMain(server: Server, handshakeTimeout: Duration): ConnectResult
    suspend fun openTestSession(server: Server, timeout: Duration): TestSession?   // §6.2
    suspend fun disconnect()
    val current: StateFlow<TunnelState>
}
interface ThroughputTester { suspend fun run(session: TestSession, limits: TestLimits): ThroughputStats }
interface History { fun latencyPrior(serverId: String, fp: String): Prior?; fun throughputPrior(...): Prior? }
```

Both engines expose `fun run(...): Flow<EngineEvent>`. The UI and notification render the events: phases, progress, leaderboard, result and error.

### 4.2 Connection state machine (`VpnSessionManager`)

States:

- `Disconnected`
- `NeedsPermission`
- `Selecting(mode, progress)`
- `Connecting(server)`
- `Connected(server, since, stats)`
- `Switching(from, to)`
- `Disconnecting`
- `Error(reason, recoverable)`

Rules:
- There is one global `StateFlow`. The UI, tile, widget and notification all observe it.
- Only the manager mutates the tunnel. All commands (connect, disconnect, quick, best, cancel) are serialized through a single `Mutex` or actor.
- **If a switch fails, restore the previous connection.** For example, if the user is connected to server X, runs Quick Connect and every candidate fails, reconnect to X instead of leaving the device disconnected.

---

## 5. Server sources and catalog

### 5.1 `ServerSource` abstraction

```kotlin
interface ServerSource {
    val id: String
    suspend fun refresh(): List<Server>
    suspend fun tunnelConfig(server: Server, purpose: TunnelPurpose): com.wireguard.config.Config
}
```

The catalog is the union of all enabled sources, cached in Room. Refreshes are cache-first, with a background refresh through WorkManager every 24 h. Quick Connect never waits on the network if a cache exists.

### 5.2 `ImportedConfigSource` (v1)

**Inputs:**
- `.conf` files and `.zip` archives, opened with the Storage Access Framework (`ACTION_OPEN_DOCUMENT`; no storage permission needed).
- QR codes (CameraX + ZXing core).
- Pasted text.

**Parsing:**
- Use `com.wireguard.config.Config.parse()`.
- Each config's `[Peer]` becomes one `Server`.
- The `[Interface]` (private key, addresses, DNS, MTU) is stored as a *profile* linked to the server. Many providers share one interface key across all of their servers.

**Hardening:**
- Protect against zip-slip.
- Limits: ≤ 5,000 entries, ≤ 64 KB per file, ≤ 50 MB total.
- Reject configs with multiple peers or show a warning. Ignore unknown keys.

**De-duplication:** treat an identical (endpoint, publicKey) pair as the same server.

**Editing:** the user can rename a server, change its country or city, delete it or favorite it.

### 5.3 Country derivation for imported servers

Apply these steps in order:
1. **Filename heuristics:**
   - A leading ISO-3166 alpha-2 code: `se-got-wg-001.conf`, `US_NY_12.conf`, `de-fra.conf`.
   - A country name anywhere in the filename (match against `Locale.getISOCountries()` display names in English and the device locale).
   - A city name from a bundled city→country table (top ~500 cities) to fill in `city`.
2. **Offline GeoIP lookup of the endpoint IP.** Resolve hostnames once. Use a bundled **DB-IP "IP to Country Lite"** database (CC BY 4.0, so attribution is required on the About/licenses screen). Convert it at build time with `tools/geoip/build.py` into a compact sorted binary range table in `assets/` (target < 6 MB), and look it up with binary search.
3. **User correction.** Unknown servers are grouped under "Unknown country" and the import summary prompts the user to fix them.

### 5.4 `RemoteCatalogSource` (M6, for the owner's own network)

Endpoints:
- `GET https://<api>/v1/catalog` returns JSON. The signature is in `X-Signature` (an Ed25519 detached signature over the raw body). The public key is pinned in the app.
- The response also includes the client's approximate geo, used for prioritization.

```json
{
  "version": 1,
  "generatedAt": "2026-11-04T12:00:00Z",
  "client": { "country": "DE", "lat": 52.5, "lon": 13.4 },
  "servers": [
    { "id": "de-fra-001", "country": "DE", "city": "Frankfurt", "lat": 50.11, "lon": 8.68,
      "endpoints": ["203.0.113.10:51820", "[2001:db8::10]:51820"],
      "publicKey": "base64...", "load": 0.37, "tags": ["p2p"],
      "tunnelDns": ["10.64.0.1"], "speedTest": "https://10.64.0.1:8443" }
  ]
}
```

- `POST /v1/devices {publicKey}` (with the account token) returns `{ "addresses": ["10.64.12.34/32", "fd00:64::c22/128"], "dns": [...] }`. The backend installs the peer on **all** servers, so one device key works everywhere. This is required for handshake probing (§7.1).
- The device keypair is generated on the device and the private key never leaves it.

---

## 6. Tunnel layer

### 6.1 GoBackend usage
- There is one `GoBackend` instance (singleton) and one logical `Tunnel` object (`"main"`). Switching servers means `setState(tunnel, UP, newConfig)`.
- **Measure the gap during a switch** (milestone M1 verification). If GoBackend tears down before re-establishing, document it and recommend Android's *Always-on VPN + Block connections without VPN* in onboarding (§12, kill switch).
- **Connection success** means the backend statistics show a **completed handshake** after connect start (`latestHandshake > startTime`) within the timeout. `rxBytes > 0` is a fallback signal. See §6.2 for why the app does not run an HTTP check through the main tunnel.
- **VPN permission:** call `VpnService.prepare()`. The consent dialog is shown once and is **not** counted in the 35 s budget; the timer starts after consent.
- **`onRevoke`** (another VPN took over): move to `Disconnected` with the message "Another VPN app took over".
- **Always-on:** register `GoBackend.setAlwaysOnCallback`. When the system starts the VPN, perform the "Always-on action" setting: *Reconnect last server* (default) or *Quick Connect with the saved Quick scope*.

### 6.2 Tunnel purposes and app self-exclusion (important)

| Purpose | Applications routed | Why |
|---|---|---|
| `MAIN` | All apps (respecting the user's split-tunnel settings) **except this app** | This app's own probes must reach servers **directly** over the physical network, even while the user stays connected and protected. This avoids needing `VpnService.protect()`, which GoBackend does not expose. |
| `TEST` (Best Connect stage 2) | All apps **including this app** (respecting split-tunnel settings) | Speed tests run by this app must go *through* the candidate server. Other apps stay protected because their traffic also goes through the candidate. Contamination is detected (§7.3). |

Implementation notes:
- Use `Interface.Builder.excludeApplication(packageName)` for `MAIN`.
- If the user configured "only these apps use VPN" (`includeApplication`), this app is simply not in the list.
- Consequence: the app cannot "check my IP" through the main tunnel. Show the **exit server address** (endpoint IP or catalog value) instead.
- **Verify on the device (M1):** with Always-on and "Block connections without VPN" enabled, the VPN-owner app's direct (excluded) traffic must still work for probes. If it does not, use the M7 fallback: vendor the tunnel module and use `protect()`.

### 6.3 Foreground service and notification
- `VpnSessionService` is a foreground service with `foregroundServiceType="specialUse"`. Declare `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE`, and add `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="vpn"/>`. This mirrors Google's ToyVpn sample.
- It runs while state ∈ {`Selecting`, `Connecting`, `Connected`, `Switching`}, so a long Best Connect survives screen-off and backgrounding.
- **Connected notification:**
  - Flag, server and country, session timer, live ↓/↑ rate.
  - Actions: **Disconnect**, **Quick Connect** (re-run with the saved scope), **Pause 5 min** (optional).
- **Best Connect progress notification:**
  - On API 36, use `Notification.ProgressStyle` with segments for the stages "Scan / Speed-test / Confirm / Connect".
  - On older APIs, fall back to `NotificationCompat` with determinate progress.
  - Action: **Connect to current best** and **Cancel**.
- `POST_NOTIFICATIONS` is requested at onboarding. The app must still work if it is denied.

---

## 7. Measurement layer

### 7.1 WireGuard handshake probe (primary latency method, `:probe`)

WireGuard servers ignore unauthenticated packets and many servers block ICMP. The most accurate and reliable RTT measurement is therefore a **real WireGuard handshake initiation** sent from a plain `DatagramSocket`, timing the **handshake response**. It requires the client's key to be a registered peer on that server. This holds for imported configs and for the owner's backend.

**Message 1, handshake initiation (148 bytes, little-endian indices):**
`type=1 (1 B) | reserved 0 (3 B) | sender_index (4 B, random) | ephemeral (32) | enc_static (32+16) | enc_timestamp (12+16) | mac1 (16) | mac2 (16)`

**Construction (Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s):**
```
CONSTRUCTION = "Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s"
IDENTIFIER   = "WireGuard v1 zx2c4 Jason@zx2c4.com"
LABEL_MAC1   = "mac1----"
C  = HASH(CONSTRUCTION);           H = HASH(C || IDENTIFIER);  H = HASH(H || S_r_pub)
(E_priv, E_pub) = X25519 keypair;  C = KDF1(C, E_pub);         H = HASH(H || E_pub)
(C, k) = KDF2(C, DH(E_priv, S_r_pub));  enc_static = AEAD(k, 0, S_i_pub, H);   H = HASH(H || enc_static)
(C, k) = KDF2(C, DH(S_i_priv, S_r_pub)); enc_ts    = AEAD(k, 0, TAI64N(now), H); H = HASH(H || enc_ts)
mac1 = MAC(HASH(LABEL_MAC1 || S_r_pub), msg[0 until 116]);  mac2 = 16 zero bytes
```
- `HASH` = BLAKE2s-256.
- `MAC(key, x)` = keyed BLAKE2s with a 16-byte output.
- `HMAC` = HMAC-BLAKE2s (64-byte block size). `KDFn` is HKDF over that HMAC, as in the WireGuard paper.
- `AEAD` = ChaCha20-Poly1305 with nonce = 4 zero bytes followed by an 8-byte little-endian counter.
- `TAI64N` = 8-byte big-endian `0x400000000000000A + unixSeconds`, followed by 4-byte big-endian nanoseconds. **Whiten** the nanoseconds the way wireguard-go does (clear the low 24 bits).
- The **preshared key is not needed** for the initiation. It is only mixed in during response processing.

**Responses:**
- **Type 2, handshake response (92 B):** `type | reserved | sender_index | receiver_index | ephemeral(32) | enc_nothing(16) | mac1 | mac2`.
  - Accept it if `receiver_index == our sender_index`.
  - Also verify the response's mac1 with key `HASH(LABEL_MAC1 || S_i_pub)` over bytes `[0, 60)`. This is cheap authenticity. Full decryption is not needed.
- **Type 3, cookie reply (64 B):** the server is *under load*. Count the RTT, but set `underLoad = true`, which adds a score penalty.

**Probe scheduling rules (pitfalls):**
- **Never probe the server you are currently connected to with the same key.** WireGuard roaming would move the server's endpoint for your peer to the probe socket and break the live tunnel. For the current server, reuse its last stats or use ICMP.
- **Space probes to the same server ≥ 250 ms apart.** Servers rate-limit initiations per peer (~20 ms), and timestamps must strictly increase.
- Do not probe server X less than ~100 ms before connecting the main tunnel to X.
- Use a fresh ephemeral key per probe. Use one socket per server, or a shared socket with demultiplexing by `receiver_index`.
- Timing uses `System.nanoTime()`. Exclude DNS resolution time; resolve all hostnames first, concurrently.
- **Dual-stack:** probe the address family the tunnel will actually use. On IPv6-only networks, rely on DNS64/NAT64 by resolving hostnames, or synthesize addresses.
- The private key is read from secure storage, used in memory, and never logged.

**Fallbacks** (per server, if the handshake probe is impossible or fails):
- TCP connect time to `endpoint:443`.
- ICMP via `/system/bin/ping -c 3 -W 1 <ip>`.
- Mark the method used, and prefer handshake results when ranking.

**Detecting UDP-blocked networks:** if ≥ 10 servers were probed and **zero** handshake responses arrive within 3 s, but the TCP/ICMP fallback works, show "UDP appears to be blocked on this network". WireGuard needs UDP. Obfuscation is a later feature (§12).

### 7.2 Latency statistics
- From *n* samples compute the median, p90, jitter (mean absolute difference of consecutive samples), and loss (timeouts / sent).
- **Quick score (lower is better):** `q = median + 2·jitter + 400·loss + loadPenalty + underLoad·30 − historyBonus`.
  - `loadPenalty = 0` if load is unknown, else `480 · max(0, load − 0.5)²` ms. That is 0 at 50% load and 120 ms at 100%.
  - `historyBonus`: up to 15 ms for servers with high recent throughput on this network (decayed with a 24 h half-life).
  - Put all weights in a single `ScoringConfig` data class.

### 7.3 Throughput tester (`:app/speed`, used by Best Connect)

1. **Bind to the test tunnel.**
   - Find the VPN `Network` with `ConnectivityManager` (`TRANSPORT_VPN`).
   - Build a **fresh** OkHttp client per session with `network.socketFactory` and a `Dns` using `network.getAllByName`. Use no connection-pool reuse across sessions.
2. **Idle RTT:** 5 sequential small HTTPS requests (HEAD or `bytes=0`). Use the TCP-connect plus TTFB timing from OkHttp `EventListener`. Take the median.
3. **Download:**
   - 4 parallel streams. Sample the aggregate bytes every 250 ms and discard the first 1 s (TCP ramp-up).
   - Stop at whichever comes first: stable (coefficient of variation < 10% over the last 1.5 s), 10 s, or the per-candidate byte cap.
4. **Loaded RTT:** measure while the download is running (bufferbloat). Use a separate keep-alive connection pinging small requests every 250 ms.
5. **Upload:** 2 parallel streams of random bytes for up to 5 s or the cap.
6. **Contamination check:** compare the tunnel's byte counters (backend statistics) to the bytes this app transferred. If foreign traffic is more than 15%, set `noisy = true` and the engine re-tests once.
7. **Endpoints:**
   - Prefer `server.speedTest` if the catalog provides one (in-tunnel endpoint on the server itself).
   - Otherwise use the configurable default **Cloudflare speed endpoints** (`https://speed.cloudflare.com/__down?bytes=N`, `__up`). These are used by many open-source tools, but **review their terms before any public release** and keep the URL configurable with a fallback list.
8. **Data caps** (settings, with these defaults):
   - Unmetered network: 60 MB per candidate, 600 MB per run.
   - Metered network: 10 MB per candidate, 80 MB per run.
   - On metered networks, show a confirmation dialog with the estimate before Best Connect starts.

### 7.4 Network fingerprint and history
- **Fingerprint:** `SHA-256(transport type + LinkProperties DNS servers + default gateway + domains + (cellular) MCC/MNC)`, truncated to 16 hex characters. It needs **no location permission**. The SSID is not used.
- **Room tables:**
  - `servers`
  - `latency_runs(serverId, fp, ts, median, jitter, loss, method)`
  - `throughput_runs(serverId, fp, ts, down, up, idleRtt, loadedRtt, bytes)`
  - `sessions(serverId, mode, start, end, selectionMs, rx, tx)`
  - Sessions are local only and can be cleared. A setting disables session history.
- **Priors:** an exponentially weighted average per (server, fp) with a 24 h half-life.
- **Rough user location**, used for prioritization only:
  - The catalog's `client` geo, else `TelephonyManager.networkCountryIso`, else the time zone region (ICU), else the locale region.
  - Map the country to a centroid from a bundled `country_centroids.json`.
  - No location permission.

---

## 8. Quick Connect (≤ 35 s, guaranteed)

### 8.1 Budget (measured from tap, after VPN consent)

| Phase | Normal | Hard cap |
|---|---|---|
| Catalog | 0 s (cache) | 6 s only if the cache is empty |
| Prioritize candidates | < 50 ms | — |
| Probe | 1.5–4 s (adaptive early stop) | 6 s |
| Connect + handshake (with failover) | 0.3–2 s | the remainder, ≤ 5 s per attempt |
| **Total** | **~3–8 s** | **35 s** (enforced by an outer `withTimeout`) |

### 8.2 Algorithm

```
quickConnect(scope, budget = 35.s):
  deadline = now + budget                              // virtual-clock aware
  servers  = catalog.servers(maxAge = 7.days, fetchTimeout = if cacheEmpty 6.s else 0)
               .filter(scope).filter { it.load == null || it.load < 0.95 }
  if servers.isEmpty -> Error(NoServersInScope)

  candidates = prioritize(servers, maxCount = 48):
      est(s) = history.latencyPrior(s, fp)?.median ?: (10 + distanceKm(userGeo, s) / 70) ms
      take 40 lowest est  +  4 random others (exploration)  +  favorites in scope (max 4)

  probe(candidates, attempts = 3, spacing = 250ms, timeout = 1000ms/attempt, concurrency = 64)
    stop early when: elapsed ≥ 1.5s AND responded ≥ min(6, candidates) AND best q improved < 5% in last 750ms
    or elapsed ≥ 6s or all done
  ranked = responders sorted by q
  if ranked.isEmpty -> try top-3 by est directly (blind connect)   // e.g. UDP-probe-hostile network

  for s in ranked.take(5):
     remaining = deadline - now; if remaining < 2.s break
     r = tunnel.connectMain(s, handshakeTimeout = min(5.s, remaining - 0.5.s))
     if r.success -> record session; return Connected(s, elapsed)
     markTemporarilyBad(s, 10.min)
  restorePreviousConnectionIfAny()
  -> Error(CouldNotConnect, suggestions = [Retry, Try another country, Run Best Connect])
```

- If the user is **already connected**, the main tunnel stays up while probing. This app is excluded from it (§6.2), so the probes go out directly. Only the final step switches the tunnel.
- **Effort setting** (Advanced): *Fast* (early-stop thresholds halved), *Balanced* (default), *Thorough* (probe up to 96 candidates and use the probe cap of 12 s). Every setting still respects 35 s.
- The UI shows a countdown ring ("Finding the fastest server… 4 s") and a live mini-list of the best three found so far.

---

## 9. Best Connect (as long as it needs)

### 9.1 Stages

1. **Refresh catalog.** Force a refresh with a 15 s timeout, falling back to the cache. Filter by scope.
2. **Full latency scan.**
   - Probe **every** server in scope: 5 rounds, 400 ms apart per server, 1.5 s timeout, concurrency 48.
   - Show "Scanned 214 / 612 servers" and the top 5 live.
   - Drop servers with loss > 40%.
3. **Throughput tests (adaptive).**
   - Pool = servers with `median ≤ max(1.6·best, best + 40 ms)`, ordered by q. Minimum 4 if available, maximum `maxCandidates` (default 12, setting 4–30).
   - For each candidate in order:
     1. `openTestSession` (timeout 8 s; on failure record it and continue).
     2. Run `ThroughputTester`.
     3. Update the leaderboard.
   - **Stop early** when tested ≥ 4 **and** the last 3 consecutive candidates did not beat the leader's score by ≥ 5% **or** the data budget is exhausted.
4. **Confirm.**
   - Re-test the top 3 once more, interleaved (A, B, C) to spread time-of-day noise.
   - If the leader's margin over the runner-up is < 5% after averaging, run one more round (maximum 2 extra rounds).
5. **Connect.** `connectMain(winner)` with the normal failover to the runner-up.
6. **Report.**
   - Save a `BestConnectReport` (all tested servers, their metrics and why the winner won).
   - Offer "Reconnect to last best" on the home screen for the same network fingerprint.

### 9.2 Duration expectations
- 1 country with 20 servers: about 1–2 min.
- "Any country" with 600 servers: about 3–6 min. The scan takes ~40–60 s and each throughput test ~10–15 s.
- The ETA is computed from stage progress and the average test duration.

### 9.3 User controls during the run
- **Connect to current leader now:** ends the run and connects to the leader.
- **Cancel:** restores the previous state.
- The run continues in the background (foreground service, §6.3) with the progress notification.
- Errors per server never abort the run. The run aborts only if the network drops, which shows "Network lost — resume?". Results so far are kept.

### 9.4 Composite score (higher is better)

Within the tested set:
```
S = w_down·(down / max_down) + w_up·(up / max_up)
  + w_loaded·(min_loadedRtt / loadedRtt) + w_idle·(min_idleRtt / idleRtt)
  + w_jit·(min_jitter+1)/(jitter+1) − w_loss·loss − 0.05·underLoad
```

Profiles (setting "Optimize for"):

| Profile | w_down | w_up | w_loaded | w_idle | w_jit | w_loss |
|---|---|---|---|---|---|---|
| Balanced (default) | 0.50 | 0.15 | 0.20 | 0.10 | 0.05 | 0.5 |
| Download speed | 0.70 | 0.15 | 0.08 | 0.05 | 0.02 | 0.5 |
| Low latency (gaming, calls) | 0.20 | 0.10 | 0.35 | 0.20 | 0.15 | 1.0 |

- If one candidate has several runs, average the runs and drop `noisy` runs if a clean one exists.

---

## 10. Country selection (per mode)

- Each mode card on the home screen has its own **country chip**: "🌍 Any country ▾" or "🇩🇪 Germany ▾".
- Selections are persisted separately in DataStore: `quickScope` and `bestScope`.
- **Country picker** (modal bottom sheet, full height, searchable):
  - The top row is **Any country — fastest overall**. Then *Recent* (last 3) and *Favorites*. Then an A–Z list.
  - Each row shows:
    - The flag emoji, built from the ISO code with regional-indicator characters, so no flag assets are needed.
    - The localized name (`Locale("", iso).displayCountry`).
    - The server count.
    - A latency badge with the last known median for the current network fingerprint, colour-coded.
  - Only countries that have servers are listed. "Unknown country" appears at the end if it applies.
  - Search matches the localized name, the English name and the ISO code.
- Optional (Should): expand a country to choose a **city**, which becomes `Scope.City`.
- **Validation:** if the saved country no longer has servers (catalog changed), show the chip in an error state and fall back to "Any" only after the user confirms.
- **Engine guarantee:** a run with `Scope.Country(X)` never connects to a server outside X. This is unit-tested.

---

## 11. UI / UX specification

**Navigation:** bottom bar with **Home · Servers · Settings**. Compose Navigation.

### 11.1 Home
- **Status header:**
  - A large state indicator (Disconnected, Connecting, Connected) with an animated shield and colour.
  - When connected: flag, server name and city, exit address, session timer, live ↓/↑ graph (last 60 s, from backend statistics every 1 s), and a **Disconnect** button.
- **Quick Connect card:**
  - A big primary button "Quick Connect".
  - Subtitle "Fastest server in ≤ 35 s".
  - Country chip.
  - While running: a countdown ring, a phase label and the top 3 so far.
- **Best Connect card:**
  - A tonal button "Best Connect".
  - Subtitle "Thorough speed test · usually 1–5 min".
  - Country chip.
  - While running: a stage stepper (Scan → Speed-test → Confirm → Connect), the progress, a live leaderboard (top 5 with ↓ Mbps and ms), ETA, data used, and the buttons **Connect to leader now** and **Cancel**.
- A "Reconnect to last best: 🇳🇱 Amsterdam-07 · 412 Mbps · 2 h ago" chip, shown when the history matches this network.
- **Empty state** (no servers):
  - Explain the options and offer **Import WireGuard configs** (zip, conf, QR).
  - Later, also offer **Sign in** for the M6 backend.

### 11.2 Servers
- A list grouped by country (collapsible), with search, a favorites filter, and sort by Name, Latency or Country.
- Tap a server to connect directly. Long-press for Favorite, Edit country/city, Delete, or Test speed (runs a single-server Best Connect).
- A "Scan latency" pull-to-refresh runs a quick probe of the visible servers.

### 11.3 Results (from Best Connect)
- A table of the tested servers (rank, flag, name, ↓, ↑, ping, loaded ping, jitter, score) with the winner highlighted.
- A "Why this server?" explanation.
- History of past reports (per network).

### 11.4 Settings
- **Connection:**
  - Always-on action.
  - Auto-failover (default on): if no handshake for 3 min while traffic is flowing, re-run Quick Connect in the same scope.
  - Quick Connect effort.
- **Best Connect:** optimize for, max candidates, data caps (Wi-Fi and mobile), speed-test endpoint (advanced).
- **Kill switch:** an explanation, plus a deep link to the system VPN settings (`Settings.ACTION_VPN_SETTINGS`) to enable *Always-on VPN* and *Block connections without VPN*. Apps cannot set these themselves, so show the current status if detectable.
- **Split tunneling:**
  - Modes: off, exclude selected apps, or only selected apps.
  - The app list uses a `<queries>` launcher-intent declaration, **not** `QUERY_ALL_PACKAGES`.
- **Network:** allow LAN access (computes AllowedIPs excluding RFC1918 and link-local ranges), DNS (server DNS by default, or custom inside the tunnel), MTU override.
- **Sources:** import configs, manage imported servers, catalog sources (M6).
- **Privacy:** clear history, disable session history, export debug log (redacted).
- **Appearance:** system, light or dark theme; dynamic colour toggle.
- **About:** version, open-source licenses (WireGuard, BouncyCastle, DB-IP attribution), a link to the plan and source repo.

### 11.5 Onboarding (first launch, 3 short screens)
1. What the app does.
2. Add servers (import).
3. Permissions: notifications, then the VPN consent dialog the first time the user connects. Include an optional kill-switch tip.

### 11.6 Platform polish
- **Quick Settings tile:**
  - Tap: Quick Connect with the saved scope when disconnected, or disconnect when connected.
  - The subtitle shows the country or state.
  - If VPN consent is missing, open the app.
- **Home-screen widget (Glance):** status plus **Quick** and **Best** buttons.
- **Static app shortcuts** (long-press the icon): "Quick Connect" and "Best Connect".
- **Accessibility:**
  - 48 dp touch targets, content descriptions and TalkBack live-region announcements for progress.
  - Respect font scaling up to 200%. No information conveyed by colour alone.
- Strings live in `strings.xml`. Ship English only at first.

---

## 12. Feature list by priority

- **Must (v1):**
  - Quick Connect, Best Connect, per-mode country selection.
  - Config import (zip, conf) with automatic country detection.
  - Server list with manual connect and favorites.
  - Status with live stats; foreground notification with actions; Quick Settings tile.
  - DNS and IPv6 leak protection (§13).
  - Kill-switch guidance.
  - Restore the previous connection when a switch fails; auto-failover.
  - Material 3 with dark mode and dynamic colour.
- **Should (v1.x):**
  - Split tunneling, LAN access, custom DNS.
  - Widget, app shortcuts, QR import.
  - Best Connect report history; "Reconnect to last best".
  - Optional background latency pre-scan. WorkManager, every 6 h, **unmetered and battery-not-low only**, default **off**. It keeps Quick Connect priors fresh.
  - City selection.
- **Could (later):**
  - Own server network and accounts (M6).
  - Obfuscation for UDP-blocked networks (e.g., AmneziaWG-compatible transport or WireGuard-over-TCP/WebSocket via the owner's servers).
  - Multi-hop.
  - Scheduled Best Connect.
  - Auto-connect on untrusted networks (keyed by network fingerprint; needs a lightweight always-running component, so evaluate the battery cost).
  - Wear OS tile; large-screen two-pane layout.

---

## 13. Security and privacy requirements

**No tracking:**
- No analytics, ads or third-party SDKs that phone home.
- No traffic logging.
- Network calls only for the catalog, probes and speed tests.

**Key storage:**
- Encrypt private keys and imported interface configs with an AES-256-GCM key held in the **Android Keystore**. Do not use the deprecated `androidx.security:security-crypto`.
- Set `android:allowBackup="false"`, and add `dataExtractionRules` that exclude everything sensitive.

**Leak protection:**
- Always route `0.0.0.0/0` **and** `::/0` into the tunnel, even if the server has no IPv6. IPv6 is then black-holed instead of leaking. Verify on the device that the route is accepted without an IPv6 interface address; if it is not, add a ULA placeholder address.
- DNS servers are set in the tunnel config and are reachable only through the tunnel.
- Private DNS note: on "Automatic" it may upgrade to DoT through the tunnel, which is fine. On "Strict", DNS still goes through the tunnel; document this in Settings.

**Imports and catalog:**
- Import hardening as in §5.2.
- Ed25519-signed catalog with pinned TLS for the M6 API.
- HTTPS-only network security config. Allow cleartext only for explicit in-tunnel speed-test IPs if one is ever plain HTTP.

**Logs and screens:**
- Logs never contain private keys or full configs.
- The debug log is a ring buffer in memory or a file, redacted, and exported only by the user.
- Use `FLAG_SECURE` on screens that show keys or QR codes.

**Play Store (if published later):**
- The VpnService policy declaration and the `specialUse` FGS justification.
- A privacy policy and the Data Safety form.
- The VPN must be the app's core function.

**Legal note in About:** VPN use is restricted in some countries; users are responsible for local law.

---

## 14. Android 16 (API 36) platform checklist

- `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`.
- **Edge-to-edge is mandatory** on target 36, because the opt-out was removed. Call `enableEdgeToEdge()` and handle insets in every screen.
- **Predictive back:** use `BackHandler` or Navigation and never override `onBackPressed`. Test the back animation.
- Foreground service type `specialUse` with subtype `vpn`, plus `FOREGROUND_SERVICE_SPECIAL_USE` (§6.3).
- Runtime `POST_NOTIFICATIONS`; notification channels: `status` (low importance), `progress`, `alerts`.
- `Notification.ProgressStyle` for Best Connect on API 36, with a fallback below it. Live Update promotion depends on the platform version; treat it as optional.
- **16 KB page size:**
  - Run `zipalign -c -P 16 -v 4` on the release APK in CI.
  - If `libwg-go.so` from the AAR is not 16 KB-aligned, it still works on the 4 KB-page phones most people have. It **blocks Play upload** and fails on 16 KB devices or developer mode.
  - The fix is M7: build `libwg-go` from source with `-Wl,-z,max-page-size=16384`.
- Do not lock orientation; target-36 apps on large screens ignore those locks anyway. Layouts must be adaptive.
- Quick Settings `TileService` with `BIND_QUICK_SETTINGS_TILE`. The GoBackend VpnService manifest entry comes from the library manifest merge; verify the merged manifest.

---

## 15. Project setup, build and delivery to the phone

### 15.1 Repository layout
```
PLAN.md
settings.gradle.kts, build.gradle.kts, gradle.properties
gradle/libs.versions.toml, gradle/wrapper/*
app/      (com.android.application)
engine/   (kotlin("jvm"))
probe/    (kotlin("jvm"))
tools/geoip/build.py            # DB-IP Lite CSV -> assets/geoip-country.bin
tools/wg-test/                  # scripts for probe integration test
.github/workflows/android.yml
```

- `applicationId`: `io.github.geniusdevam.vpnapp`. The working title is "VPN App"; the owner can rename it.
- Use the latest **stable** AGP, Kotlin, Compose BOM, Hilt, Room, OkHttp and BouncyCastle (`bcprov-jdk18on`), and pin them in the version catalog. Use JDK 17 or later.
- `versionCode` = CI run number; `versionName` = `0.<milestone>.<run>`.

### 15.2 CI (GitHub Actions)
- **On every push:**
  1. `./gradlew :engine:test :probe:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`
  2. Check 16 KB alignment.
  3. Upload the APK as a workflow artifact.
- **Job `probe-integration`** (ubuntu runner):
  1. `sudo apt-get install wireguard-tools`.
  2. Create a `wg0` interface with a generated server key, and add a generated client key as a peer.
  3. Run `:probe:integrationTest`, which probes `127.0.0.1:<port>` and asserts it receives a valid type-2 response.
- **On tag `v*`:** create a GitHub Release with the signed APK attached.
- **Signing:**
  - Read the release keystore from repository secrets: `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
  - If the secrets are missing, sign with a committed **dev** keystore (`app/dev.keystore`, not secret, clearly labelled) so that updates install over each other.
  - The owner should add real secrets before sharing the app with anyone. Changing keys later forces an uninstall.
- **Cloud environment note:** the implementing agent's container may not have the Android SDK. Try installing the command-line tools. If the network blocks it, rely on GitHub Actions to build, and read the job logs through the GitHub MCP actions tools. Do not consider a milestone done until CI is green.

### 15.3 Installing on the owner's Android 16 phone
1. Open the repository's **Releases** page on the phone and download the APK.
2. Allow "Install unknown apps" for the browser when prompted, then install.
3. **Developer verification:** since 30 Sep 2026, certified devices in Brazil, Indonesia, Singapore and Thailand require apps from **verified developers**. Global rollout is planned for 2027. Unverified apps need Android's "advanced flow" (a one-time setup with a 24 h wait) or ADB.
   - If the owner is affected, they should register as a developer (a free option exists for limited distribution), or use the advanced flow.
   - Check the current rules at implementation time.

---

## 16. Testing strategy and acceptance criteria

### 16.1 Automated
- **`:engine` unit tests** (virtual time with `runTest` and `TestScope`):
  - **35 s guarantee property test.** Run 1,000 randomized scenarios: random RTTs, 0–100% timeouts, slow or empty catalog, hanging `connectMain`, all failures. Assert every run terminates in ≤ 35 s virtual time and ends in `Connected` or `Error` (never hanging).
  - The early-stop rule and candidate prioritization (favorites, exploration, history priors).
  - **Scope guarantee:** country X never yields a server outside X.
  - Best Connect: adaptive stop, data budget, confirmation rounds, cancel and "connect to leader now", and the composite score for each profile.
  - Restore the previous connection on failure.
- **`:probe`:**
  - Unit tests for BLAKE2s, HMAC and KDF, TAI64N and the message layout (lengths, offsets).
  - A round-trip test against a minimal in-test responder built from the same primitives.
  - Plus the CI integration test against real WireGuard (§15.2).
- **`:app`:**
  - Config parsing and import hardening (zip-slip, limits).
  - Country derivation (filename heuristics, GeoIP lookup).
  - Room migrations.
  - ViewModel state rendering.
  - Compose UI tests for the home cards and the country picker.
- **Optional instrumented tests** on an emulator in CI (`reactivecircus/android-emulator-runner`). Pre-grant VPN consent with `adb shell appops set <pkg> ACTIVATE_VPN allow`.

### 16.2 Manual QA on the owner's phone (each release)
- First run, import and auto-countries.
- Quick Connect with Any and with a specific country, on Wi-Fi and on mobile data. Time it: the median should be ≤ 8 s, never over 35 s.
- Quick Connect while already connected: a smooth switch.
- Best Connect with Any and a country, cancel, connect to leader, screen off during the run, and the data cap prompt on mobile data.
- Airplane-mode toggle and Wi-Fi ↔ mobile handover (the tunnel stays up, because WireGuard roams).
- Always-on with "Block connections without VPN": probes still work and there are no leaks. Check with a browser leak test site for IP, DNS and IPv6.
- QS tile, widget and shortcuts; a dead server triggers auto-failover; another VPN app takes over (onRevoke).

### 16.3 Acceptance criteria (v1)
1. Quick Connect always reaches `Connected` or a clear error in ≤ 35 s. On the real device with ≥ 30 servers: median ≤ 8 s, p95 ≤ 15 s.
2. Quick Connect's chosen server has a quick score within 10% (or 5 ms) of the best among the probed set.
3. Best Connect is repeatable: on the same network, two consecutive runs pick the same server or one within 10% of its score in ≥ 80% of trials.
4. Country scope is honoured in 100% of runs in both modes, and each mode remembers its own country.
5. No DNS or IPv6 leaks while connected. With Always-on and Block, there is zero leakage during switches.
6. Best Connect survives screen-off and backgrounding, and the progress notification works.
7. CI is green, the release APK is signed, and the 16 KB check is reported.

---

## 17. Milestones (implement in order)

**M0: Scaffold and delivery pipeline**
- Gradle multi-module skeleton, theme, navigation shell, CI workflow, signing (dev fallback), and a GitHub Release on tag.
- *Done when:* the owner can install a "hello" APK from a Release on their phone.

**M1: Tunnel core**
- GoBackend integration, VPN consent, `VpnSessionManager` state machine.
- `MAIN` config with self-exclusion, connect/disconnect a chosen server, foreground service and notification, Quick Settings tile, onRevoke, Always-on callback.
- Import of a single `.conf`.
- *Done when:* you can connect and disconnect manually on the device. Also measure the switch gap and the Always-on/lockdown probe behaviour (§6.2) and record the results in `docs/verification.md`.

**M2: Server catalog and import**
- Zip/conf/QR import with hardening, country derivation (filename and GeoIP tool and asset), Room catalog, the Servers screen (list, search, favorites, edit country).
- *Done when:* importing a 500-file zip works in < 5 s and countries are detected for > 95% of well-named files.

**M3: Probe module and Quick Connect**
- WireGuard handshake prober with fallbacks and its CI integration test.
- Network fingerprint, history, `QuickConnectEngine` with the 35 s property tests.
- Home screen Quick card with the country picker and countdown UI.
- *Done when:* acceptance criteria 1, 2 and 4 (Quick part) pass on CI and on the device.

**M4: Best Connect**
- `TEST` sessions, `ThroughputTester`, `BestConnectEngine` (stages, adaptive stop, confirm, data caps, profiles).
- Best card UI, ProgressStyle notification, Results screen, "Reconnect to last best".
- *Done when:* acceptance criteria 3, 4 and 6 pass.

**M5: "Best VPN" polish**
- Auto-failover, restore on failed switch, split tunneling, LAN access, custom DNS, MTU.
- Widget, shortcuts, onboarding, kill-switch guidance, settings completeness, accessibility pass, leak tests.
- *Done when:* all v1 acceptance criteria pass, and a v1.0.0 Release is published.

**M6 (optional, the owner decides): own server network**
- See §19. Build `RemoteCatalogSource` and device registration in the app.

**M7 (only if needed): hardening and Play readiness**
- Vendor the `tunnel` module from wireguard-android (Apache-2.0), with these changes:
  1. Expose `protect()` on the service instance.
  2. Build `libwg-go` with 16 KB alignment.
  3. Optionally host the foreground notification in the VpnService itself.
- Required if the AAR fails the 16 KB check and Play distribution is wanted, or if the lockdown verification in M1 fails.
- Then complete the Play policy items (§13).

For each milestone:
- Keep commits small and descriptive and push to the working branch.
- Update `docs/verification.md` with on-device findings.
- Do not start the next milestone with a red CI.

---

## 18. Risks and mitigations

- **No servers means a useless app.**
  - Mitigation: an import-first design, a clear empty state, and the M6 backend option. Ask the owner which provider or servers to use before M3 testing.
- **UDP blocked on some networks.**
  - Mitigation: detect it and explain (§7.1). Obfuscation is on the roadmap.
- **Providers rate-limiting or flagging scans.**
  - Mitigation: polite limits (≤ 5 probes per server per Best Connect run, ≤ 3 per Quick Connect, concurrency caps). Background pre-scan is off by default.
- **Handshake probe edge cases** (roaming of the live peer, timestamp monotonicity, cookie replies).
  - Mitigation: the rules in §7.1, an integration test, and fallbacks.
- **GoBackend limitations** (no `protect()`, a possible switch gap).
  - Mitigation: app self-exclusion (§6.2), on-device verification in M1, and vendoring in M7 if required.
- **Speed-test endpoint availability or terms.**
  - Mitigation: a configurable list, in-tunnel endpoints on the owner's servers in M6, and a terms review before public release.
- **Mobile data use by Best Connect.**
  - Mitigation: caps, a confirmation on metered networks, and data used shown live.
- **Sideload restrictions (developer verification).**
  - Mitigation: §15.3 guidance; register as a developer if needed.
- **Battery.**
  - Mitigation: no persistent keepalive by default (respect the config value), no background work by default, and a Best Connect that is user-initiated only.

---

## 19. Optional own server network (M6 outline)

**Servers** (one VPS per location, Ubuntu LTS):
- Kernel WireGuard, with `nftables` masquerade and IP forwarding.
- `unbound` DNS on the tunnel IP, with an optional ad/tracker blocklist.
- A small **node agent** that syncs peers from the control plane and reports load (CPU, bandwidth, peers).
- An **HTTPS speed endpoint** on the tunnel IP (`/down?bytes=N`, `/up`).
- Provisioned with Terraform plus cloud-init or Ansible, across several providers for country coverage.

**Control plane:**
- A small API (Ktor or Go) with Postgres.
- Anonymous account numbers, device registration (one IP per device, valid on all servers), signed catalog generation (Ed25519) and load aggregation.
- No traffic logs; minimal metadata, documented in a privacy policy.

**Repository:**
- Lives under `server/` in this repo or in a separate repository.
- Its own CI. Secrets are managed only through the cloud provider and GitHub secrets.

**App side:**
- `RemoteCatalogSource`, account sign-in and device-key registration (§5.4).
- Per-server `speedTest` endpoints used by Best Connect.

---

## 20. Verify at implementation time (not confirmed while planning)

1. The current `com.wireguard.android:tunnel` version and whether its `libwg-go.so` is 16 KB-aligned. A 2025 mailing-list report said `1.0.20230706` was not.
2. The exact backend `Statistics` API in that version, for the latest-handshake timestamp and the per-peer rx/tx.
3. The GoBackend config-switch gap (M1).
4. Whether the VPN-owner app's own excluded traffic is allowed under Always-on with "Block connections without VPN" (M1).
5. Whether an IPv6 default route without an IPv6 interface address is accepted by `VpnService.Builder` on Android 16.
6. The current `Notification.ProgressStyle` and Live Update promotion requirements.
7. The terms of use of the default speed-test endpoint.
8. The current Android developer verification rules for the owner's country.
9. The latest stable AGP, Kotlin and Compose versions compatible with `compileSdk 36`.
