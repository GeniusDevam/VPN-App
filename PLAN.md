# VPN App — Implementation Plan

> **Audience:** the agent that will implement this app.
> **Owner's device:** Android 16 smartphone. The app is sideloaded first; Google Play later is optional.
> **Status:** the plan has been implemented; this file is the original design plus dated notes where the build differs. **The code and `docs/HANDOFF_STATUS.md` win over any statement here.** Last updated 2026-11-05.

The owner asked for "the best VPN app available" with two connection modes:

1. **Quick Connect.** Connects to the fastest server it can find, and is connected within **35 seconds** of the tap.
2. **Best Connect.** Takes as long as it needs to find the fastest server *it can measure* (it reports how much it covered and how confident it is; it never claims a proven global winner), then connects.

In **each** mode the user can choose a **country**: "Any country" or one specific country. The two modes remember their country choice separately.

### Implementation status and deviations from this plan (read first)

The app was built from this plan. Where the build differs, the code wins:

| Plan | What was built |
|---|---|
| ics-openvpn as a git submodule / library module (§2, §4, §15) | `tools/prepare.sh` clones the pinned upstream commit and **overlays** the app onto upstream's own `:main` module (skeleton flavor, OpenVPN 2.x, arm64 only). No upstream files are patched except an appended Gradle block. |
| Separate `:engine` / `:probe` modules | One pure-Kotlin `engine/` project with its own unit tests; the same sources are compiled into the app. |
| Room + SQLCipher + Tink (§13.1) | Same guarantee with less code: every file is AES-256-GCM encrypted with a non-exportable Android Keystore key (`SecureVault`). |
| Probe sockets excluded from the tunnel (§6.2) | Probe sockets are bound to the physical network (`Network.bindSocket`), so they bypass an active tunnel. |
| Cipher shown from negotiated data channel | Parsed from the engine log; falls back to the cipher declared in the server config. |
| Widget, app shortcuts, auto-failover, background pre-scan, mirror URLs, Always-on action, results history, Tor mode | **Not implemented.** Quick Settings tile, notification progress, split tunneling (apps that skip the VPN), DNS choice, Strong/Compatible encryption mode, favorites and "report & avoid" are implemented. |
| Hardening pass (review findings A1-C5) | See `docs/`: ledger with 7-day observed-absence retention, HTTPS-only directory pool with diagnostics, relay-identity policy (system-trusted roots + `remote-cert-tls server` + `verify-x509-name opengw.net`), no compression, one encryption gate for every connection path, `RunCoordinator` (disconnect/cancel races), honest Quick/Best claims, optional worldwide search, secret-based signing with provenance, redacted CI publishing. Engine behaviour is unit-tested; the Android layer is verified by CI builds and the emulator test below. |
| Persistence (§13.1) | `AtomicFiles` (fsync + atomic rename, the old file is never deleted first) and `LatestWinsWriter` (one ordered, coalescing writer per file, so an older snapshot can never overwrite a newer one). The earlier Mutex-per-save design reordered writes in 30 of 30 rounds of the race test. |
| Why a run can fail (§8, §10, §11) | Three distinct outcomes, never merged: *server list could not be loaded*, *no server in the chosen country*, *servers exist but your settings exclude them* (`PoolSelection`). The country picker shows loading / unavailable / stale / empty states and keeps a chosen-but-empty country visible. The scope is never widened. |
| Latency probes (§7.1) | UDP relays with an inline `tls-auth` key are now probed with an HMAC-signed hard reset whose reply HMAC is verified (checked against real OpenVPN). `tls-crypt` relays still use the weak TCP-443 fallback, penalised and labelled. No ICMP fallback was built. |
| History (§5.4, §7.4, §13.1) | One permanent encrypted ledger per server (counts, last success, connect time, cipher, per-network latency/throughput, last seen). The **only** deletion rule is the seven-day observed-absence rule (see `docs/DIRECTORY.md`); old measurements are down-weighted (3-day half-life), never deleted for age. Shown per server on the Servers screen. |
| Release engineering (§15.2) | No committed keystore. Ordinary builds (every branch) use a throw-away key and no job except `sign` can reference a secret (checked in CI, with mutation tests). Releases are tag-only: `release-gate` (tag on default-branch history) > `sign` (protected environment `release`, read-only token, re-signs the already built and tested APK with `tools/resign-apk.sh`, pins the certificate, refuses the leaked key) > `publish` (write token, no secrets; checksum + attestation). Actions are pinned by commit SHA (one documented exception). The emulator job decides **PASS / FAIL / INCONCLUSIVE** (exit 0 / 1 / 3); FAIL blocks the release. The final APK's manifest is held to `tools/manifest-policy.json` (5 reachable components, each needed or system-guarded; 6 permissions; backup off) as a hard gate. |
| On-device verification (§19) | **Partly done; no physical device was used.** Every push is built and run in an Android 16 (API 36, x86_64) emulator against live VPN Gate servers. Runs 22 and 23 passed every assertion: the country picker, a country-bound Quick Connect (25 s in Austria, 26 s in Japan, each inside the chosen country), a disconnect that stayed down for 20 s, and a Best Connect with a named winner. Run 21 was INCONCLUSIVE because the chosen country (India) had a single volunteer relay that did not answer. **Not done:** a real phone, mobile data, Always-on lockdown, long sessions, switching networks, the worldwide search, process kill while connected. Live directory (2026-11-05): 96 OpenVPN profiles in one download (90 TCP, 6 UDP), all `AES-128-CBC` ("Strong only" matches none), none using tls-auth/tls-crypt; 15/24 sampled relays connected with and without the identity policy. |

### Owner decisions (already made)
- **Servers:** free volunteer servers from the **VPN Gate** network (University of Tsukuba academic project). There are no paid subscriptions and no owner-run servers.
- **Encryption and privacy:** a top priority. Every link and every stored byte the app controls is encrypted, and the user is told honestly what cannot be protected (§3, §13).

---

## 1. Goals, non-goals, definitions

### Goals
- A fast, encrypted, privacy-respecting VPN client for Android 16 (it also works on older versions; see §14), using VPN Gate's free volunteer servers.
- Quick Connect: a **hard guarantee** of ≤ 35 s from tap to *Connected*. The typical case should be about 5–12 s; OpenVPN handshakes are slower than WireGuard.
- Best Connect: real throughput testing of the leading candidates, with adaptive stopping, so the result is the fastest server *for this user, on this network, right now*.
- A country picker inside each mode, saved per mode.
- Strong encryption, leak protection, encrypted local storage, honest privacy disclosure, and a polished Material 3 UI.

### Non-goals (for v1)
- Paid providers, accounts, or running servers.
- WireGuard. VPN Gate does not offer it; it can be added later as another source.
- iOS or desktop.

### Definitions
- **Server:** one VPN Gate relay (hostname, IP, OpenVPN config, country, the reported line speed, session count, uptime, log policy).
- **Scope:** `Any` or `Country(isoCode)`.
- **RTT:** round-trip time to the server, measured from the phone (§7.1). The `Ping` column in the VPN Gate list is measured from Japan and is **not** used for ranking.
- **"Fastest":**
  - *Quick Connect:* lowest **latency score**: RTT median plus jitter, loss, load and slow-line penalties, plus a bonus from past throughput history.
  - *Best Connect:* highest **composite score**: download, upload, loaded latency, idle latency, jitter and loss (§9.4).
- **Network fingerprint:** a privacy-preserving hash identifying the current network (§7.4).

---

## 2. Key technical decisions

| Topic | Decision |
|---|---|
| Server network | VPN Gate public relay list (§5) |
| Protocol | OpenVPN (UDP preferred, TCP when the server only offers TCP), because VPN Gate's API publishes OpenVPN configs |
| VPN engine | **ics-openvpn** ("OpenVPN for Android", GPL) embedded from source as a library module. It is pinned to a release tag as a git submodule and built with NDK **r28 or newer**, which defaults to 16 KB page alignment. |
| Licence consequence | The app is distributed under the **GPL**: keep the repo's source public and add a `LICENSE` file. Review dependency licence compatibility before any public distribution. |
| Language / UI | Kotlin, Jetpack Compose, Material 3 (Expressive components where stable), dynamic colour |
| SDK levels | `compileSdk 36`, `targetSdk 36` (Android 16), `minSdk 26` |
| Architecture | MVVM + unidirectional data flow, Coroutines/Flow, Hilt (KSP) |
| Local storage | Room on **SQLCipher** (encrypted DB) and DataStore encrypted with **Tink** AEAD. All keys are wrapped by the Android Keystore (§13). |
| Networking | OkHttp, kotlinx.serialization |
| Build/CI | Gradle Kotlin DSL with a version catalog. GitHub Actions builds the signed APK and attaches it to a GitHub Release (§15). |

---

## 3. Privacy reality of free volunteer servers (must be shown to the user)

Free volunteer servers are the owner's choice, and the app must be **honest** about what encryption can and cannot do with them:

- **Protected by the app's encryption:**
  - Everything between the phone and the VPN server. Public Wi-Fi snoopers, the mobile carrier and the ISP cannot read the traffic and only see that a VPN is being used.
  - DNS lookups, when encrypted DNS is on (§13.3).
  - Everything the app stores on the phone.
- **Not protected by the VPN alone:**
  - Each VPN Gate server is run by an unknown volunteer, who can see **which sites and IP addresses** the user connects to.
  - Volunteers can also see the content of any **unencrypted (non-HTTPS)** traffic.
  - VPN Gate's published policies say connection logs are kept for **≥ 3 months**, and that destination hostnames, IPs and ports may be recorded. Each server also declares its own log policy (the `LogType` column).
- **Mitigations the app provides:**
  - Strongest negotiable encryption, with weak ciphers rejected (§13.2).
  - Encrypted DNS status and guidance (§13.3).
  - Per-server log policy shown in the UI.
  - Split tunneling, so banking and other sensitive apps can bypass the volunteer server (§11.4).
  - A "Safety tips" screen covering HTTPS-only browsing and not logging into important accounts.
  - Server reputation tracking, which avoids servers that misbehave (§5.4).
- **Onboarding:** a short **privacy disclosure** that the user must acknowledge once, with a link to VPN Gate's own policy pages. Keep it in plain language with no fear-mongering.
- **Future (Could):** a "Maximum privacy" mode routed over Tor, which is designed so no single volunteer sees both who you are and where you go. It is much slower. This is out of scope for v1.

---

## 4. Architecture

```
┌──────────────────────────── :app (Android) ────────────────────────────┐
│ ui/        Compose screens + ViewModels                                │
│ vpn/       VpnSessionManager (state machine, single source of truth)   │
│            TunnelController (wraps ics-openvpn), notifications        │
│            QuickConnectTileService, Always-on handling                 │
│ data/      Room+SQLCipher, encrypted DataStore, VpnGateSource,         │
│            config sanitizer, server reputation                         │
│ net/       NetworkMonitor, NetworkFingerprint, VPN-bound OkHttp        │
│ speed/     ThroughputTester (OkHttp, multi-stream)                     │
│ widget/    Glance home-screen widget                                   │
└──────┬───────────────────────────┬──────────────────────┬──────────────┘
       │                           │                      │
┌──────▼───────────────┐  ┌────────▼──────────────┐  ┌────▼─────────────────────┐
│ :engine (pure JVM)   │─▶│ :probe (pure JVM)     │  │ :openvpn (Android lib)   │
│ models, scoring,     │  │ TCP-connect probe,    │  │ ics-openvpn submodule,   │
│ budgets,             │  │ OpenVPN UDP           │  │ skeleton flavour, OpenVPN│
│ QuickConnectEngine,  │  │ hard-reset probe,     │  │ 2.x only, NDK r28+       │
│ BestConnectEngine,   │  │ ICMP fallback         │  │                          │
│ interfaces           │  └───────────────────────┘  └──────────────────────────┘
└──────────────────────┘
```

- `:engine` and `:probe` have **no Android dependencies**. They are fully unit-testable on the JVM with fakes and a **virtual clock** (`kotlinx-coroutines-test`). This is how the 35 s guarantee is proven (§16).
- `:openvpn` is the ics-openvpn `main` module consumed as a library, following the approach of the eduVPN fork's "librarify" change.
  - Build the **skeleton** UI flavour with **OpenVPN 2.x only**, which avoids the OpenVPN3/SWIG dependency. Verify the current flavour names in the pinned tag.
  - Keep local patches minimal and list them in `openvpn/PATCHES.md`.

### 4.1 Core interfaces (in `:engine`)

```kotlin
enum class Transport { UDP, TCP }
data class Server(
    val id: String,                    // VPN Gate HostName
    val ip: String, val port: Int, val transport: Transport,
    val countryCode: String, val countryName: String,
    val lat: Double?, val lon: Double?,          // from bundled country centroids
    val reportedSpeedBps: Long?, val sessions: Int?, val uptimeMs: Long?,
    val score: Long?, val logPolicy: String?, val operator: String?,
    val configRef: String              // key into encrypted config store (sanitized .ovpn)
)
sealed interface Scope { data object Any : Scope; data class Country(val iso: String) : Scope }

data class LatencyStats(val medianMs: Double, val p90Ms: Double, val jitterMs: Double,
                        val loss: Double, val samples: Int, val method: ProbeMethod)
data class ThroughputStats(val downMbps: Double, val upMbps: Double, val idleRttMs: Double,
                           val loadedRttMs: Double, val bytesUsed: Long, val noisy: Boolean,
                           val cipher: String?)

interface Catalog { suspend fun servers(maxAge: Duration, fetchTimeout: Duration): List<Server> }
interface LatencyProber { fun probe(servers: List<Server>, cfg: ProbeConfig): Flow<Pair<Server, LatencyStats?>> }
interface TunnelController {
    suspend fun connectMain(server: Server, timeout: Duration): ConnectResult   // includes negotiated cipher
    suspend fun openTestSession(server: Server, timeout: Duration): TestSession?
    suspend fun disconnect()
    val current: StateFlow<TunnelState>
}
interface ThroughputTester { suspend fun run(session: TestSession, limits: TestLimits): ThroughputStats }
interface History { fun latencyPrior(serverId: String, fp: String): Prior?; fun throughputPrior(...): Prior? }
interface Reputation { fun isBlocked(serverId: String): Boolean; fun penaltyMs(serverId: String): Double }
```

Both engines expose `fun run(...): Flow<EngineEvent>`. The UI and notification render the events: phases, progress, leaderboard, result and error.

### 4.2 Connection state machine (`VpnSessionManager`)

States:

- `Disconnected`
- `NeedsPermission`
- `Selecting(mode, progress)`
- `Connecting(server)`
- `Connected(server, since, stats, cipher)`
- `Switching(from, to)`
- `Disconnecting`
- `Error(reason, recoverable)`

Rules:
- There is one `StateFlow`. The UI, tile, widget and notification all observe it.
- All commands are serialized through a single `Mutex` or actor.
- **If a switch fails, restore the previous connection.**

---

## 5. Server source: VPN Gate

### 5.1 Fetching the list
- **Endpoint:** `https://www.vpngate.net/api/iphone/`.
  - Community clients use plain `http://`. **Try HTTPS first.** Fall back to HTTP only if HTTPS does not work, and record which one works in `docs/verification.md`.
  - When on HTTP, treat the list as untrusted input. The sanitizer (§5.3) and the reputation system (§5.4) still apply. If the user is already connected, fetch the list **through the current tunnel** so a local attacker cannot tamper with it.
  - > **Superseded:** HTTPS works (confirmed live, 96 rows), so the HTTP fallback was **removed** and cleartext is disabled app-wide. If HTTPS fails the app serves its cached pool and says so. Nothing is fetched through the tunnel specially. The list is still treated as untrusted (sanitizer + relay-identity policy).
- **Format:** CSV.
  - The first line is `*vpn_servers`, the header line starts with `#HostName,…`, and the last line is `*`.
  - Columns: `HostName, IP, Score, Ping, Speed, CountryLong, CountryShort, NumVpnSessions, Uptime, TotalUsers, TotalTraffic, LogType, Operator, Message, OpenVPN_ConfigData_Base64`.
  - Parse defensively: skip malformed rows and cap the response at 20 MB.
- **Caching and politeness:**
  - Cache in the encrypted DB.
  - Quick Connect uses the cache if it is ≤ 30 min old. Otherwise it fetches with a 4 s timeout and falls back to the stale cache.
  - Best Connect always fetches (15 s timeout).
  - Never fetch more than once per 10 min automatically.
  - Send a descriptive `User-Agent` (`VPN-App/<version> (Android)`) with no personal data.
  - Read and respect VPN Gate's terms and anti-abuse policy.
- **Mirrors (Should):** VPN Gate publishes mirror sites for regions where the main site is blocked. Allow a configurable list of mirror base URLs in Advanced settings.
  - > **Not implemented.** No verified HTTPS mirror address was available, and a mirror may simply serve the same partial list. The pipeline is source-agnostic (`DirectoryProcessor`), so adding one is small. The list VPN Gate serves is deliberately partial: the app merges recent downloads (72 h) and reports how many usable servers it really has, not how many exist.
- **Volatility:** the list changes constantly. Servers are often home PCs and come and go hourly, and country coverage is uneven, with many servers in Japan and Korea. The UI must say "available now" and failover must be robust.

### 5.2 Mapping to `Server`
- Decode the base64 config. Extract `proto` (udp or tcp), `remote <ip> <port>`, the inline `<ca>`, `<cert>` and `<key>`, and `cipher`/`auth`.
- Some VPN Gate configs may contain commented alternative remotes or protocols. Ignore commented lines; one row is one server.
- The country comes from `CountryShort` (ISO-2), which is authoritative. Coordinates come from a bundled `country_centroids.json`, so no GeoIP database is needed.
- The `id` is the VPN Gate `HostName`. If the same server reappears with a new IP, update it in place.

### 5.3 Config sanitizer (security-critical)
Do not hand the server-supplied config verbatim to the engine. Rebuild it from an **allowlist**.

- **Keep:** `client`, `dev tun`, `proto`, `remote`, `cipher`, `auth`, `resolv-retry`, `nobind`, `persist-key`, `persist-tun`, `verb`, the inline `<ca>`, `<cert>` and `<key>`, and `<tls-auth>`/`<tls-crypt>` with `key-direction` if present.
- **Drop** everything else, especially `script-security`, `up`, `down`, `route-up`, `plugin`, `management*`, `setenv`, `pull-filter` overrides, `redirect-gateway` variants, `route` lines, `dhcp-option`, `http-proxy*`, `socks-proxy*`, `log*`, `status`, `daemon`, `cd` and `chroot`.
- **Append the app's own hardened settings (§13.2):**
  - `tls-version-min 1.2`
  - `data-ciphers`, chosen according to the encryption mode
  - The app's own DNS servers
  - Full routing of IPv4 and IPv6 into the tunnel
  - `auth-nocache`
  - `pull-filter ignore "dhcp-option DNS"`, so a malicious server cannot push its own DNS
- **Reject** the server if the config has `cipher none`, `auth none`, or a weak cipher (BF-CBC, DES, 3DES, RC2, CAST5, any 64-bit block cipher), or if it has no `<ca>`.
- If the server requests credentials, use VPN Gate's documented public credentials (`vpn`/`vpn`). Verify this on the device.
- Store sanitized configs only in the encrypted store, and never log them.

### 5.4 Server reputation (protects against bad volunteers)
Keep a per-server record in the encrypted DB of failures, handshake errors, abnormal behaviour and user reports.

- **Temporary block (30 min):** after 2 consecutive connection failures.
- **Long block (7 days):** for any of the following:
  - Certificate or TLS errors.
  - The negotiated cipher is below policy.
  - The tunnel is up but DNS answers fail validation. Send a known DNS query through the tunnel and check the answer against a well-known record over encrypted DNS when possible.
  - The user taps "Report & avoid this server".
- **Also show and respect:**
  - `LogType`, plus an optional filter "Prefer servers with the shortest declared log retention".
  - The operator `Message`, shown as untrusted text: render it as plain text, never as HTML or links.

---

## 6. Tunnel layer (ics-openvpn)

### 6.1 Integration (verify the exact APIs against the pinned tag)
- **Parse** the sanitized config with ics-openvpn's `ConfigParser` and convert it to a `VpnProfile`.
- **Profile settings:**
  - Persistent tun on.
  - Block unused address families on, so IPv6 is blocked when the server is IPv4-only.
  - The allowed-apps settings (§6.2).
  - The app's DNS.
- **Register** the profile as a temporary profile with `ProfileManager`.
- **Start** it with `VPNLaunchHelper.startOpenVpn(...)`, or the equivalent in the pinned version.
- **Observe:**
  - `VpnStatus` state listeners. `LEVEL_CONNECTED` means success.
  - Byte-count listeners for live stats.
  - The log listener, to capture the **negotiated data-channel cipher** (the line starting `Data Channel: cipher …`).
- **Disconnect** through the service's internal binder (`stopVPN`).
- **Switching servers:** start the new profile with "replace connection". **Measure the gap** during the switch (M1). Persistent tun should keep the interface up so there are no leaks during reconnects.
- **VPN permission:** call `VpnService.prepare()`. The consent dialog is not counted in the 35 s budget.
- **Revoke and Always-on:**
  - Handle a revoke from another VPN app by moving to `Disconnected` with an explanation.
  - When the system starts the VPN as Always-on, run the "Always-on action" setting: *Reconnect last server* (default) or *Quick Connect with the saved Quick scope*.

### 6.2 Tunnel purposes and app self-exclusion

| Purpose | Applications routed | Why |
|---|---|---|
| `MAIN` | All apps (respecting the user's split-tunnel settings) **except this app** | This app's latency probes must reach servers **directly**, even while the user stays connected and protected. |
| `TEST` (Best Connect stage 2) | All apps **including this app** | Speed tests must go *through* the candidate. Other apps stay protected through the candidate too. Contamination is detected (§7.3). |

- Implement this with the profile's allowed/disallowed-apps setting.
- **Alternative:** expose `protect()` from `OpenVPNService` with a small patch. Use it if self-exclusion causes problems with Always-on lockdown (verify in M1).
- The app's own small requests (list fetch, probes) therefore go outside the tunnel when the tunnel is `MAIN`. The exception is the list fetch over HTTP, which goes through `TEST` or a protected path when the user is connected (§5.1).

### 6.3 Foreground service and notification
- ics-openvpn's `OpenVPNService` runs in the foreground.
- **Merged manifest:**
  - Ensure it declares `foregroundServiceType="specialUse"`, with `FOREGROUND_SERVICE_SPECIAL_USE` and `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="vpn"/>`. This mirrors Google's ToyVpn sample.
  - Patch the manifest or `tools:replace` it if upstream differs.
- **Notifications:** route ics-openvpn's notification through the app's own `NotificationFactory` (a small patch), so there is one consistent notification.
  - **Connected:** flag, server and country, 🔒 the cipher (for example "AES-256-GCM"), session timer, live ↓/↑. Actions: **Disconnect** and **Quick Connect**.
  - **Best Connect progress:** `Notification.ProgressStyle` on API 36 with segments "Scan / Speed-test / Confirm / Connect", and a `NotificationCompat` fallback below it. Actions: **Connect to current best** and **Cancel**.
- During a long Best Connect scan with **no** tunnel up, a separate `SelectionService` foreground service (type `specialUse`) keeps the process alive.
- `POST_NOTIFICATIONS` is requested at onboarding. The app must still work without it.

---

## 7. Measurement layer

### 7.1 Latency probes (`:probe`)

Choose the method by the server's transport.

**TCP servers: TCP-connect RTT (primary).**
- Time `Socket.connect(ip:port)`. The SYN to SYN-ACK round trip is answered by the server's kernel, so it is an accurate network RTT.
- Optionally, send an OpenVPN hard reset framed with TCP's 2-byte length prefix and wait for the reply. This confirms the VPN service is actually alive; a port can be open while the service is dead.
- Close the connection immediately.

**UDP servers: OpenVPN hard-reset probe (primary).**
- Send a 14-byte `P_CONTROL_HARD_RESET_CLIENT_V2`:
  ```
  byte 0      : 0x38                 (opcode 7 << 3 | key_id 0)
  bytes 1..8  : random session_id
  byte 9      : 0x00                 (ACK array length)
  bytes 10..13: 0x00000000           (message packet-id)
  ```
- **Valid reply:** the first byte `>> 3 == 8` (`P_CONTROL_HARD_RESET_SERVER_V2`), and the echoed remote session id in the ACK section equals ours. The RTT is from send to receive (`System.nanoTime()`).
- **Configs with `tls-auth` or `tls-crypt`:** the server will not answer an unauthenticated reset. Use the fallback for those servers.
  - > **Update:** for an inline `tls-auth` key the reset is now HMAC-signed with the profile's key (key-direction and `auth` digest honoured) and the reply's HMAC is verified (`TlsAuthKey`, `tools/probe-test.sh` against real OpenVPN). `tls-crypt` still uses the fallback. In the live list, 0 of 96 relays use either today.
- This is the same well-known probe used by network scanners. The server discards the half-open session after its handshake window.

**Fallbacks:**
- ICMP via `/system/bin/ping -c 2 -W 1 <ip>`. Many home PCs drop ICMP. *(Not built.)*
- For UDP servers, a TCP connect to 443 if the server also listens there (SoftEther often does).
- Record the method used. Rank servers measured with a primary method above fallback-only results. *(Built: TCP-443 fallback results are labelled low confidence and take a 60 ms penalty; a refusal there proves only that the host is up.)*

**Scheduling rules:**
- ≥ 250 ms between probes to the same server.
- Do not probe the currently connected server; reuse its stats.
- Exclude DNS time (the VPN Gate list gives IP literals).
- Quick Connect: ≤ 3 probes per server. Best Connect: ≤ 5. These are volunteer machines, so be polite.

**Detecting UDP-blocked networks:** if ≥ 10 UDP servers were probed with zero replies but TCP servers reply, show "UDP appears blocked; using TCP servers" and restrict to TCP automatically.

### 7.2 Latency statistics and Quick score
- From *n* samples compute the median, p90, jitter (mean absolute difference of consecutive samples) and loss.
- **Quick score (lower is better):**
  ```
  q = median + 2·jitter + 400·loss
      + sessionPenalty            // 30 ms · min(1, sessions / 50)
      + slowLinePenalty           // +60 ms if reportedSpeed < 10 Mbps, +25 ms if < 50 Mbps
      + tcpPenalty                // +15 ms for TCP transport (TCP-over-TCP is slower)
      + reputation.penaltyMs
      − historyBonus              // ≤ 15 ms for high recent throughput on this network (24 h half-life)
  ```
- All weights live in one `ScoringConfig`.

### 7.3 Throughput tester (`:app/speed`, used by Best Connect)
1. **Bind to the test tunnel.** Find the VPN `Network` (`TRANSPORT_VPN`), and build a **fresh** OkHttp client per session with `network.socketFactory` and a `Dns` using `network.getAllByName`.
2. **Idle RTT:** 5 small HTTPS requests. Take the median of TCP-connect plus TTFB from OkHttp `EventListener`.
3. **Download:**
   - 4 parallel streams. Sample every 250 ms and discard the first 1 s.
   - Stop at whichever comes first: stable (coefficient of variation < 10% over 1.5 s), **6 s**, or the per-candidate byte cap.
   - Keep tests short to respect volunteers' bandwidth.
4. **Loaded RTT:** small requests every 250 ms during the download.
5. **Upload:** 2 streams, up to 3 s or the cap.
6. **Contamination:** compare the tunnel byte counters with the app's own bytes. If foreign traffic is more than 15%, set `noisy` and re-test once.
7. **Endpoint:**
   - Configurable. The default is Cloudflare's speed endpoints (`https://speed.cloudflare.com/__down?bytes=N`, `__up`), which are HTTPS.
   - Review their terms before any public release, and keep a fallback list.
8. **Data caps** (settings, with these defaults):
   - Unmetered network: 30 MB per candidate, 300 MB per run.
   - Metered network: 8 MB per candidate, 60 MB per run.
   - On metered networks, show a confirmation dialog with the estimate first.

### 7.4 Network fingerprint, history and rough location
- **Fingerprint:** `SHA-256(transport + LinkProperties DNS servers + gateway + domains + cellular MCC/MNC)`, truncated to 16 hex characters. It needs no location permission.
- **Encrypted DB tables:**
  - `servers`
  - `latency_runs`
  - `throughput_runs` (including the negotiated cipher)
  - `reputation`
  - `sessions`
  - Sessions are local only and can be cleared, and a setting disables session history.
- **Priors:** an exponentially weighted average per (server, fp) with a 24 h half-life. VPN Gate servers change often, so history must expire gracefully.
  - > **Superseded:** measurements are down-weighted with a 3-day half-life (floor 5%) but **never deleted for age**. A server's saved record is deleted only after it has been absent from successfully obtained, usable directory listings for seven days of observed time (failed fetches never count). Network fingerprints are salted hashes; at most 8 per server.
- **User country**, used for prioritization only:
  - `TelephonyManager.networkCountryIso`, else the time zone region, else the locale region.
  - Map it to a centroid from the bundled table. No location permission.

---

## 8. Quick Connect (≤ 35 s, guaranteed)

### 8.1 Budget (measured from tap, after VPN consent)

| Phase | Normal | Hard cap |
|---|---|---|
| Server list | 0 s (cache ≤ 30 min) | 4 s fetch, then stale cache |
| Prioritize candidates | < 50 ms | — |
| Probe | 1.5–4 s (adaptive early stop) | 6 s |
| Connect (OpenVPN TLS handshake, with failover) | 3–8 s | the remainder; attempt 1 ≤ 12 s, later attempts ≤ 10 s |
| **Total** | **~5–12 s** | **35 s** (enforced by an outer `withTimeout`) |

### 8.2 Algorithm

```
quickConnect(scope, budget = 35.s):
  deadline = now + budget
  servers  = catalog.servers(maxAge = 30.min, fetchTimeout = 4.s)
               .filter(scope).filterNot(reputation::isBlocked)
               .filter(encryptionPolicy::allows)            // §13.2, uses cached cipher knowledge
  if servers.isEmpty -> Error(NoServersInScope, "VPN Gate has no servers in <country> right now")

  candidates = prioritize(servers, maxCount = 64):
      est(s) = history.latencyPrior(s, fp)?.median ?: (10 + distanceKm(userCountry, s.country) / 70) ms
      sort by est + slowLinePenalty; take 56 + 4 random others (exploration) + favorites (max 4)

  probe(candidates, attempts = 2 (3 if time allows), spacing = 250ms, timeout = 1000ms, concurrency = 64)
    stop early when: elapsed ≥ 1.5s AND responded ≥ min(6, candidates) AND best q improved < 5% in last 750ms
    or elapsed ≥ 6s or all done
  ranked = responders sorted by q
  if ranked.isEmpty -> try top-3 by est directly

  for s in ranked.take(4):
     remaining = deadline - now; if remaining < 6.s break     // OpenVPN needs a few seconds
     r = tunnel.connectMain(s, timeout = min(if first 12.s else 10.s, remaining - 0.5.s))
     if r.success && encryptionPolicy.accepts(r.cipher) -> return Connected(s, r.cipher, elapsed)
     if r.success -> disconnect; reputation.cipherBelowPolicy(s)   // only in Strong mode
     else reputation.failure(s)
  restorePreviousConnectionIfAny()
  -> Error(CouldNotConnect, suggestions = [Retry, Try another country, Run Best Connect])
```

- If the user is **already connected**, the tunnel stays up while probing (§6.2). Only the final step switches.
- **Effort setting** (Advanced): *Fast*, *Balanced* (default) or *Thorough*. All of them respect 35 s.
- The UI shows a countdown ring, the phase ("Finding the fastest server…", "Securing connection…") and the best three so far.

---

## 9. Best Connect (as long as it needs)

### 9.1 Stages
1. **Refresh the list.** Force a refresh (15 s timeout, falling back to the cache), filter by scope, remove blocked servers, apply the encryption policy.
2. **Full latency scan.**
   - Probe **every** server in scope: 5 rounds, 400 ms apart per server, 1.5 s timeout, concurrency 48.
     *(Built as a two-pass probe with no silent cap; a 3,000-server safety cap reports partial coverage if it ever applies. The result carries a claim such as "confirmed best of those tested" or "partial coverage", never "fastest overall".)*
   - Show "Scanned 87 / 143 servers" and the top 5 live.
   - Drop servers with loss > 40%.
3. **Throughput tests (adaptive).**
   - Pool = servers with `median ≤ max(1.6·best, best + 40 ms)`, ordered by q, minimum 4 and maximum `maxCandidates` (default 10, setting 4–25).
   - Deprioritize servers whose reported line speed is < 10 Mbps.
   - For each candidate in order:
     1. `openTestSession` (timeout 15 s; on failure record reputation and continue).
     2. Record the **negotiated cipher**.
     3. Run `ThroughputTester` and update the leaderboard.
   - **Stop early** when tested ≥ 4 **and** the last 3 candidates did not beat the leader by ≥ 5%, **or** the data budget is exhausted.
4. **Confirm.**
   - Re-test the top 3 once more, interleaved.
   - If the leader's margin is < 5%, run one more round (maximum 2 extra rounds).
5. **Connect.** `connectMain(winner)`, with failover to the runner-up.
6. **Report.**
   - Save a `BestConnectReport` (each server's metrics, cipher and log policy, and why the winner won).
   - Offer "Reconnect to last best" while the same network fingerprint is active **and the server is still in the list**.

### 9.2 Duration expectations
- One country: about 1–3 min.
- "Any country": about 3–6 min. The VPN Gate list is usually a few hundred servers or fewer.
- Show an ETA computed from stage progress.

### 9.3 User controls during the run
- **Connect to current leader now.**
- **Cancel:** restores the previous state.
- The run continues in the background (§6.3).
- Per-server errors never abort the run. Losing the network pauses it with "Network lost — resume?".

### 9.4 Composite score (higher is better)

Within the tested set:
```
S = w_down·(down / max_down) + w_up·(up / max_up)
  + w_loaded·(min_loadedRtt / loadedRtt) + w_idle·(min_idleRtt / idleRtt)
  + w_jit·(min_jitter+1)/(jitter+1) − w_loss·loss + 0.03·aeadCipher
```

Profiles (setting "Optimize for"):

| Profile | w_down | w_up | w_loaded | w_idle | w_jit | w_loss |
|---|---|---|---|---|---|---|
| Balanced (default) | 0.50 | 0.15 | 0.20 | 0.10 | 0.05 | 0.5 |
| Download speed | 0.70 | 0.15 | 0.08 | 0.05 | 0.02 | 0.5 |
| Low latency (gaming, calls) | 0.20 | 0.10 | 0.35 | 0.20 | 0.15 | 1.0 |

- `aeadCipher` is a small tie-breaker in favour of servers that negotiated AES-GCM or ChaCha20-Poly1305.
- If one candidate has several runs, average the runs and drop `noisy` runs if a clean one exists.

---

## 10. Country selection (per mode)

- Each mode card on the home screen has its own **country chip** ("🌍 Any country ▾" or "🇯🇵 Japan ▾"). The choices are persisted separately (`quickScope`, `bestScope`) in encrypted DataStore.
- **Country picker** (modal bottom sheet, searchable):
  - The top row is **Any country — fastest overall**. Then *Recent*, *Favorites*, and an A–Z list.
  - Each row shows:
    - The flag emoji, built from the ISO code.
    - The localized name.
    - The **number of servers available now**.
    - The best known latency on this network, if any.
  - Countries are built from the **current** VPN Gate list.
  - A country the user saved earlier that currently has 0 servers is shown greyed with "none right now".
- **No servers in the saved country at run time:** show "VPN Gate has no servers in Germany right now. Try again later, or use Any country." Do not silently switch countries.
- **Engine guarantee:** a run with `Scope.Country(X)` never connects outside X. This is unit-tested.

---

## 11. UI / UX specification

**Navigation:** bottom bar with **Home · Servers · Settings**.

### 11.1 Home
- **Status header:**
  - A large state indicator with an animated shield.
  - When connected: flag, server and country, a **🔒 encryption badge** (cipher, for example "AES-256-GCM", and TLS version), an **encrypted DNS badge** (On/Off), session timer, live ↓/↑ graph, and **Disconnect**.
- **Quick Connect card:**
  - A primary button.
  - Subtitle "Fastest server in ≤ 35 s".
  - Country chip.
  - While running: countdown ring, phase and top 3.
- **Best Connect card:**
  - A tonal button.
  - Subtitle "Thorough speed test · usually 1–6 min".
  - Country chip.
  - While running: stage stepper, live leaderboard, ETA, data used, **Connect to leader now** and **Cancel**.
- A "Reconnect to last best" chip, when it applies.
- A banner when encrypted DNS is off: "Turn on encrypted DNS for more privacy →" (§13.3).

### 11.2 Servers
- The current VPN Gate list, grouped by country, with search, favorites, and sort by Name, Latency, Country or Reported speed.
- Each row shows the transport (UDP/TCP), reported speed, sessions, uptime and **log policy**.
- Tap to connect. Long-press for Favorite, Test speed, or Report & avoid.
- Pull to refresh: refresh the list (rate-limited) and probe the visible servers.

### 11.3 Results (from Best Connect)
- A table of the tested servers (rank, flag, ↓, ↑, ping, loaded ping, jitter, cipher, score), a "Why this server?" explanation, and report history.

### 11.4 Settings
- **Connection:** Always-on action, auto-failover (default on), Quick Connect effort.
- **Encryption & privacy:**
  - Encryption mode: **Strong (AEAD only)** or **Compatible** (default; also allows AES-CBC with HMAC). See §13.2.
  - Encrypted DNS status, with step-by-step guidance.
  - "Prefer servers with shorter log retention".
  - Safety tips.
  - Privacy disclosure (re-readable).
- **Kill switch:** an explanation and a deep link to the system VPN settings to enable *Always-on VPN* and *Block connections without VPN*.
- **Split tunneling:**
  - Off, exclude selected apps, or only selected apps. The app list uses a `<queries>` launcher-intent declaration.
  - Suggest excluding banking apps from volunteer servers.
- **Best Connect:** optimize for, max candidates, data caps, speed-test endpoint (advanced).
- **Network:** allow LAN access, VPN Gate mirror URLs (advanced).
- **Privacy data:** clear history, disable session history, export redacted debug log.
- **Appearance:** theme, dynamic colour.
- **About:** version, **GPL licence and source link**, open-source licences (ics-openvpn, OpenVPN, OpenSSL, SQLCipher, Tink and others), and a VPN Gate credit and policy links.

### 11.5 Onboarding (first launch)
1. What the app does.
2. The **privacy disclosure** for volunteer servers (§3). The user must acknowledge it.
3. Permissions: notifications, and the VPN consent dialog the first time the user connects.
4. The encrypted DNS tip (optional, with a step-by-step guide).

### 11.6 Platform polish
- **Quick Settings tile:** Quick Connect with the saved scope, or disconnect.
- **Glance widget:** status plus **Quick** and **Best** buttons.
- **Static app shortcuts:** Quick and Best.
- **Accessibility:** 48 dp targets, content descriptions, TalkBack live regions for progress, font scale up to 200%, and no information conveyed by colour alone.
- Strings live in `strings.xml`. Ship English first.

---

## 12. Feature list by priority

- **Must (v1):**
  - Quick Connect, Best Connect, per-mode country selection.
  - VPN Gate list with the config sanitizer and reputation tracking.
  - Strong encryption policy with the cipher badge.
  - Encrypted local storage.
  - Encrypted DNS status and guidance.
  - Privacy disclosure.
  - Server list with manual connect and favorites.
  - Status with live stats, notification, Quick Settings tile.
  - DNS and IPv6 leak protection.
  - Kill-switch guidance.
  - Restore the previous connection when a switch fails; auto-failover.
  - Material 3 with dark mode.
- **Should (v1.x):**
  - Split tunneling, LAN access.
  - Widget, shortcuts.
  - Best Connect report history.
  - Mirror URLs.
  - "Prefer short log retention" filter.
  - Optional background latency pre-scan (unmetered and battery-not-low only, default **off**).
- **Could (later):**
  - "Maximum privacy" Tor mode (§3).
  - Import `.ovpn` files from other providers through the same engine.
  - WireGuard support as another source.
  - Notify when servers become available in a saved country.
  - Scheduled Best Connect.

---

## 13. Encryption, security and privacy requirements

### 13.1 Data at rest (everything the app stores is encrypted)
- **Room database on SQLCipher** (`net.zetetic:sqlcipher-android`).
  - The 256-bit passphrase is random and generated on first run.
  - It is stored only **wrapped** by an AES-256-GCM key in the **Android Keystore**, which is non-exportable and hardware-backed (StrongBox when available).
- **DataStore** (settings, per-mode scopes) is serialized through **Tink AEAD** (AES-256-GCM). The keyset is wrapped by a Keystore master key.
- Do not use the deprecated `androidx.security:security-crypto`.
- Sanitized server configs, history, reputation and logs are all in the encrypted stores. Nothing sensitive goes to plain files or caches.
- Set `android:allowBackup="false"`, and add `dataExtractionRules` that exclude all app data from cloud backup and device transfer.
- Use `FLAG_SECURE` on screens that show server certificates or configs.

### 13.2 Data in transit (VPN tunnel)
- **TLS control channel:** `tls-version-min 1.2`. The server is authenticated against the `<ca>` in its config.
  - That config is protected by the HTTPS list fetch and the sanitizer.
  - Add `remote-cert-tls server` only if VPN Gate certificates carry the server usage flag (verify on the device).
- **Data channel, with two modes:**
  - **Compatible (default):** `data-ciphers AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305:AES-128-CBC` and `data-ciphers-fallback AES-128-CBC`. The engine negotiates the strongest cipher the server supports.
  - **Strong:** AEAD only (AES-GCM or ChaCha20-Poly1305). Servers that cannot negotiate AEAD fail to connect and are remembered, then skipped.
  - In M1, measure what share of VPN Gate servers negotiate AEAD and record it in `docs/verification.md`. That number decides whether Strong is practical as the default.
- **Never allowed:** `none` ciphers or auth, 64-bit block ciphers, or compression with encryption (VORACLE). Force `compress` off or `allow-compression no` where supported.
- **The cipher is shown** in the UI and notification, so the user can see their connection is encrypted.
- The app's own HTTP requests are HTTPS-only (a network security config with cleartext disabled). The only possible exception is the VPN Gate list endpoint, if HTTPS proves unavailable (§5.1).

### 13.3 DNS privacy
- The tunnel uses the app's chosen resolvers (default Quad9 `9.9.9.9`, with Cloudflare `1.1.1.1` as an option). Server-pushed DNS is ignored (§5.3).
- **Encrypted DNS:** apps cannot turn on Android's *Private DNS* themselves.
  - Detect its status on the VPN network with `LinkProperties.isPrivateDnsActive()` and `getPrivateDnsServerName()`.
  - Show the badge, and guide the user to Settings → Network & internet → Private DNS → `dns.quad9.net` (or `one.one.one.one`).
  - When it is on, DNS queries are encrypted end-to-end through the tunnel, so the volunteer server cannot read or alter them.
- Be honest in the UI: even with encrypted DNS, the server can see destination IP addresses and, for most sites, the site name during the HTTPS handshake.

### 13.4 Leak protection
- Route `0.0.0.0/0` and `::/0` into the tunnel, and enable "block unused address families" so IPv6 is blocked when the server is IPv4-only.
- Persistent tun during reconnects. Recommend Always-on with "Block connections without VPN" as the kill switch.

### 13.5 Application hygiene
- **No tracking:** no analytics, ads, crash reporters or SDKs that phone home. No traffic logging.
- **Network use:** only the list fetch, probes, speed tests and the tunnel.
- **Untrusted input:** treat the CSV, configs and operator messages as untrusted. Parse defensively, set size limits, render text as plain text, and never execute anything.
- **Logs:**
  - Keep logs in an in-memory ring buffer, redacting IPs to /24 and dropping certificates and keys.
  - Export them only on the user's request.
- **Supply chain:** pin ics-openvpn to a tagged commit and verify submodule hashes in CI. Use no prebuilt third-party VPN binaries.
- **Play Store (if published later):** the VpnService policy declaration, the `specialUse` justification, the privacy policy and the Data Safety form.
- **Legal note in About:** VPN use is restricted in some countries; users are responsible for local law. VPN Gate is an academic experiment with its own terms.

---

## 14. Android 16 (API 36) platform checklist

- `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`.
- **Edge-to-edge is mandatory** on target 36: call `enableEdgeToEdge()` and handle insets everywhere.
- **Predictive back:** use `BackHandler` or Navigation and never override `onBackPressed`.
- Foreground service type `specialUse`, subtype `vpn`, with `FOREGROUND_SERVICE_SPECIAL_USE` (§6.3).
- Runtime `POST_NOTIFICATIONS`. Notification channels: `status` (low importance), `progress`, `alerts`.
- `Notification.ProgressStyle` on API 36, with a fallback below it. Live Update promotion is optional.
- **16 KB page size:** build all native code (OpenVPN, OpenSSL and other ics-openvpn dependencies) with NDK r28+. Run `zipalign -c -P 16 -v 4` on the release APK in CI.
- Do not lock orientation; layouts must be adaptive.
- Quick Settings `TileService` with `BIND_QUICK_SETTINGS_TILE`.
- Verify the merged manifest: VpnService intent filter, `BIND_VPN_SERVICE`, FGS type.

---

## 15. Project setup, build and delivery to the phone

### 15.1 Repository layout
```
PLAN.md
LICENSE                          # GPL (matches ics-openvpn)
settings.gradle.kts, build.gradle.kts, gradle.properties
gradle/libs.versions.toml, gradle/wrapper/*
app/                             # com.android.application
engine/                          # kotlin("jvm")
probe/                           # kotlin("jvm")
openvpn/                         # wrapper module; ics-openvpn as git submodule at openvpn/ics-openvpn
openvpn/PATCHES.md               # every local change to upstream, with reason
tools/openvpn-test/              # scripts for the probe integration test
.github/workflows/android.yml
docs/verification.md             # on-device findings
```

- `applicationId`: `io.github.geniusdevam.vpnapp`. The working title is "VPN App"; the owner can rename it.
- Use the latest **stable** AGP, Kotlin, Compose BOM, Hilt, Room, OkHttp, SQLCipher and Tink, pinned in the version catalog. Use JDK 17 or later, NDK r28 or later, and CMake from the SDK.
- `versionCode` = CI run number.

### 15.2 CI (GitHub Actions)
- Check out with `submodules: recursive`. Cache Gradle, NDK and the native build outputs, because the native build is slow.
- **On every push:**
  1. `./gradlew :engine:test :probe:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`
  2. The 16 KB alignment check.
  3. Upload the APK artifact.
- **Job `probe-integration`** (ubuntu runner; built as the `cert-policy` job, which runs `tools/cert-policy-test.sh` and `tools/probe-test.sh` against real OpenVPN):
  1. `sudo apt-get install -y openvpn easy-rsa`.
  2. Generate a throwaway PKI, and start OpenVPN servers on UDP 1194 and TCP 1195 (no tls-auth).
  3. Run `:probe:integrationTest`, asserting a valid hard-reset reply over UDP and TCP and a correct TCP-connect RTT.
- **On tag `v*`:** create a GitHub Release with the signed APK. *(Built: on the default branch or a `v*` tag, only with the release key, only after the engine tests, the real-OpenVPN tests and the emulator verdict, as an immutable versioned release with checksum and attestation.)*
- **Signing:**
  - Read the release keystore from secrets: `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
  - If the secrets are missing, use a committed, clearly labelled **dev** keystore so updates install over each other. Switch to real secrets before sharing.
  - > **Superseded (security finding B1):** a committed keystore with its passwords in the workflow made the signing identity public and compromised. It was removed, and the secrets are no longer repository-wide: they exist only as environment secrets of the protected environment `release` (`RELEASE_KEYSTORE_B64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, plus the public variable `RELEASE_CERT_SHA256`), readable only by the `sign` job, which runs for `vX.Y.Z` tags after a reviewer approves. With no secrets nothing is published. The owner must still configure the environment, create the key (`tools/generate-release-key.sh`) and delete the old public release. See `docs/RELEASE_SIGNING.md`.
- **Cloud environment note:** the implementing agent's container may lack the Android SDK and NDK. Try installing them. If the network blocks it, rely on GitHub Actions, and read the job logs through the GitHub MCP actions tools. A milestone is not done until CI is green.

### 15.3 Installing on the owner's Android 16 phone
1. Open the repo's **Releases** page on the phone and download the APK.
2. Allow "Install unknown apps" for the browser, then install.
3. **Developer verification:** since 30 Sep 2026, certified devices in Brazil, Indonesia, Singapore and Thailand require apps from verified developers. Global rollout is planned for 2027.
   - Unverified apps need Android's "advanced flow" (a one-time setup with a 24 h wait) or ADB.
   - Check the current rules for the owner's country at implementation time.

---

## 16. Testing strategy and acceptance criteria

### 16.1 Automated
- **`:engine` unit tests** (virtual time):
  - **35 s guarantee property test.** Run 1,000 randomized scenarios: random RTTs, timeouts, slow, empty or stale list, connects that hang for up to 30 s, and all failures. Assert every run ends in ≤ 35 s in `Connected` or `Error`.
  - Early stop, prioritization and reputation blocking.
  - **Scope guarantee:** country X never yields a server outside X.
  - Encryption-policy filtering.
  - Best Connect: adaptive stop, data caps, confirmation, cancel and "connect to leader now", and the scoring profiles.
  - Restore the previous connection on failure.
- **`:probe`:**
  - The hard-reset packet layout and reply validation, including rejecting a reply with a mismatched session id.
  - TCP framing.
  - The CI integration test against real OpenVPN (§15.2).
- **`:app`:**
  - VPN Gate CSV parsing with fuzzed and malformed input.
  - **Config sanitizer tests:** every dangerous directive is stripped, weak ciphers are rejected, and hardening is appended.
  - Encrypted storage round-trips; the DB is unreadable without the Keystore key.
  - Room migrations, ViewModel state, Compose UI tests for the home cards and the country picker.
- **Optional emulator tests in CI.** Pre-grant VPN consent with `adb shell appops set <pkg> ACTIVATE_VPN allow`.
  - > **Built** (`tools/emulator-test.sh`, live VPN Gate, UI driven through uiautomator; the consent dialog is tapped rather than pre-granted). Asserts: both modes shown; the country picker opens and reports a loaded list; choosing a country changes Quick only; the choice survives process death; Quick Connect connects inside the chosen country (or fails with a message that names it); an explicit disconnect stays disconnected for 20 s; Best Connect names a winner; no crash. Exit 0 = PASS, 1 = FAIL (the app misbehaved), 3 = INCONCLUSIVE (live servers unreachable). Not covered: the worldwide search, the Quick Settings tile, notification actions, network switching, process kill while connected.
  - Engine test count: 129 (including persistence ordering, pool/failure reasons, tls-auth probe, history text, disconnect races, 600-seed sanitizer fuzz).

### 16.2 Manual QA on the owner's phone (each release)
- Onboarding, including the privacy disclosure.
- Quick Connect with Any and with specific countries, on Wi-Fi and on mobile data. Time it: the median should be ≤ 12 s, never over 35 s.
- Quick Connect while already connected.
- Best Connect: Any or a country, cancel, connect to leader, screen off during the run, and the metered-data prompt.
- The cipher badge appears; Strong mode rejects non-AEAD servers.
- Private DNS on and off is detected correctly.
- A leak test in a browser: IP, DNS, IPv6.
- Always-on with "Block connections without VPN".
- Wi-Fi ↔ mobile handover; a dead server triggers failover; another VPN app takes over.
- QS tile, widget and shortcuts.

### 16.3 Acceptance criteria (v1)
1. Quick Connect always reaches `Connected` or a clear error in ≤ 35 s. On the real device: median ≤ 12 s, p95 ≤ 25 s.
2. Quick Connect's chosen server has a quick score within 10% (or 5 ms) of the best among the probed servers it could connect to.
3. Best Connect is repeatable: consecutive runs on the same network pick the same server or one within 10% of its score in ≥ 80% of trials, given that the servers are still listed.
4. Country scope is honoured in 100% of runs in both modes, and each mode remembers its own country.
5. Every connection uses an allowed cipher and shows it. There are no DNS or IPv6 leaks while connected.
6. All persisted data is encrypted at rest (verified by inspecting the app's data directory on an emulator).
7. Best Connect survives screen-off. CI is green, the APK is signed, and the 16 KB check passes.

---

## 17. Milestones (implement in order)

> **Milestone status (2026-11-05):** M0-M4 are built and CI-verified as described in `docs/HANDOFF_STATUS.md`; M2/M3/M4 acceptance on a *physical device* is **not done**. From M5: the Quick Settings tile, split tunneling, onboarding and kill-switch guidance exist; auto-failover, widget, shortcuts and an Always-on action do **not**. The `docs/verification.md` this plan asks for was never created; `docs/HANDOFF_STATUS.md`, `docs/DIRECTORY.md` and `docs/SECURITY.md` hold those findings.

**M0: Scaffold and delivery pipeline**
- Gradle multi-module skeleton, theme, navigation shell.
- The ics-openvpn submodule building in CI (NDK r28+), signing, a Release on tag, `LICENSE`.
- *Done when:* the owner installs a "hello" APK that contains the OpenVPN engine from a Release.

**M1: Tunnel core and VPN Gate list**
- List fetch and parsing, config sanitizer, encrypted storage (SQLCipher, Tink, Keystore).
- `VpnSessionManager`, manual connect and disconnect to a chosen VPN Gate server, notification with cipher badge, Quick Settings tile, revoke and Always-on handling.
- *Done when:* manual connect works on the device. Also record in `docs/verification.md`:
  - Whether HTTPS works for the API.
  - The switch gap.
  - Self-exclusion under lockdown.
  - The AEAD share.
  - Whether the `vpn`/`vpn` credentials are needed.
  - Whether `remote-cert-tls` works.
  - Typical connect times.

**M2: Servers screen, reputation, privacy UX**
- Servers list (filters, log policy, favorites, report & avoid).
- Reputation rules, privacy disclosure, safety tips, encrypted DNS detection and guidance.
- *Done when:* the list renders hundreds of servers smoothly and the reputation tests pass.

**M3: Probe module and Quick Connect**
- UDP hard-reset and TCP probes with fallbacks and the CI integration test.
- Network fingerprint and history.
- `QuickConnectEngine` with the 35 s property tests.
- Home Quick card with the country picker and countdown.
- *Done when:* acceptance criteria 1, 2 and 4 (Quick part) pass in CI and on the device.

**M4: Best Connect**
- `TEST` sessions, `ThroughputTester`, `BestConnectEngine`.
- Best card UI, ProgressStyle notification, Results screen, "Reconnect to last best".
- *Done when:* acceptance criteria 3, 4 and 7 pass.

**M5: Polish and v1.0**
- Auto-failover, restore on failed switch, split tunneling, LAN access, widget, shortcuts, onboarding, kill-switch guidance.
- Accessibility pass, leak tests, encryption audit (acceptance criteria 5 and 6).
- *Done when:* all v1 acceptance criteria pass and a v1.0.0 Release is published.

**M6 (later, optional):** the Could items in §12, for example Tor "Maximum privacy" mode and `.ovpn` import.

For each milestone:
- Keep commits small and descriptive and push to the working branch.
- Update `docs/verification.md`.
- Never start the next milestone with a red CI.

---

## 18. Risks and mitigations

- **Volunteer servers can spy or misbehave.**
  - Mitigation: honest disclosure, strong ciphers, encrypted DNS guidance, the DNS-tamper check, reputation blocking, split tunneling for sensitive apps, and safety tips.
- **The VPN Gate list is volatile and coverage is uneven** (many servers in a few countries).
  - Mitigation: show "available now", robust failover, short cache, and clear empty-country messages.
- **Logging by VPN Gate** (≥ 3 months, per their policy).
  - Mitigation: disclose it, show per-server `LogType`, and offer the "prefer short retention" filter.
- **The API is only available over HTTP, or is blocked in some countries.**
  - Mitigation: try HTTPS first, fetch through the tunnel when connected, sanitize configs, and support mirror URLs.
  - *Outcome:* HTTPS works, so HTTP was removed. Blocked-country access and mirrors are not handled (no verified mirror).
- **Many servers are slow home connections.**
  - Mitigation: reported-speed penalties and Best Connect's real throughput tests.
- **OpenVPN connects more slowly than WireGuard.**
  - Mitigation: the Quick budget is designed around 3–12 s handshakes, with fewer but longer attempts.
- **UDP is blocked on some networks.**
  - Mitigation: detect it and switch to TCP servers automatically.
- **The ics-openvpn embedding effort and the GPL obligations.**
  - Mitigation: pin a tag, keep minimal documented patches, follow the eduVPN "librarify" precedent, publish the source and add `LICENSE`.
- **Volunteer bandwidth used by speed tests.**
  - Mitigation: short tests, data caps, and probe limits.
- **Speed-test endpoint terms.**
  - Mitigation: a configurable list and a review before public release.
- **Sideload restrictions.**
  - Mitigation: §15.3 guidance.

---

## 19. Verify at implementation time (not confirmed while planning)

> **Answers so far (2026-11-05, live CI against VPN Gate):** (1) HTTPS works; the CSV parses (96 rows in one download). (2) 90 TCP / 6 UDP; **0 of 96** use tls-auth/tls-crypt; the UDP hard-reset probe against SoftEther relays was not measured separately (UDP connects succeeded). (3) **0 of 96** negotiate AEAD (all `AES-128-CBC`); `remote-cert-tls server` plus `verify-x509-name opengw.net` is compatible: every relay that reached TLS presented `CN=opengw.net`, and 15/24 connected both with and without it. (4) The `vpn`/`vpn` login is supplied by the app and works (the configs carry no `auth-user-pass`). (5) ics-openvpn pinned by commit in `tools/ICS_OPENVPN_REF`. **Still unanswered:** 6 (switch gap, lockdown self-exclusion: needs a device), 7, 8 (speed-test endpoint terms), 9, and the version question in 10 beyond what `docs/AUDIT.md` records.

1. Whether `https://www.vpngate.net/api/iphone/` works; the current CSV columns and format.
2. The share of VPN Gate servers using UDP vs TCP, and using tls-auth/tls-crypt (which affects the UDP probe); whether SoftEther servers answer the UDP hard-reset probe.
3. The share of servers negotiating AEAD ciphers, and whether `remote-cert-tls server` is compatible.
4. Whether the `vpn`/`vpn` credentials are required for OpenVPN connections.
5. ics-openvpn: the current repo and tag, the flavour names (skeleton, OpenVPN 2 only), the library-module approach, the exact APIs (`ConfigParser`, `ProfileManager`, `VPNLaunchHelper`, `VpnStatus`, allowed-apps fields, block-unused-address-families), its FGS type declaration, and its licence text.
6. The switch gap during replace-connection, and the self-exclusion behaviour under Always-on lockdown.
7. The `Notification.ProgressStyle` and Live Update promotion requirements.
8. The default speed-test endpoint terms of use.
9. The Android developer verification rules for the owner's country.
10. The latest stable AGP, Kotlin, Compose, NDK, SQLCipher and Tink versions compatible with `compileSdk 36`.
