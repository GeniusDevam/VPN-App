# Performance review

Measured 2026-11-07. **No physical phone was used.** Every figure below comes from one of three
sources, named in each table:

| Source | What it measures | How to repeat it |
|---|---|---|
| Engine benchmarks (JVM) | CPU and memory cost of the app's own logic: parsing and validating the server list, the server ledger, Quick Connect's selection | `cd engine && gradle -q benchCli --args="1500 5"` (also printed by every CI build, job `engine`) |
| Compatibility matrix (CI emulators) | Does the app work, and how fast, on each Android version and form factor, against **live** VPN Gate volunteers | every CI build, job `compat`; per-device results and screenshots on the `compat-results` branch |
| Speed runs (CI emulators) | Repeated launches, memory, Quick Connect ×6 and Best Connect ×1, with a per-phase timeline from the app's own log | workflow `speedrun` (Actions → speedrun → Run workflow, give it a `build` run id) |

Emulators run on x86_64 CI machines with software graphics. They say nothing exact about a given phone; they are good for comparing
devices and code versions with each other, and for finding where time goes.

## Verdict

* **Quick Connect is fast and reliable in these tests.** In the speed runs it connected **12 times out of 12** (6 on Android 16, 6 on
  Android 6.0). By the app's own log the tunnel was up **3.5-5.2 s after the tap**: about 1.5 s to probe the candidates, 2-3.6 s for the
  OpenVPN handshake. The promise is 35 s. In the device matrix, where the time also includes the test script's own overhead and the
  one-time VPN permission dialog, Quick Connect took 17-25 s (one earlier run: 36 s on Android 8.0).
* **The app starts quickly everywhere tested:** cold start 0.8-2.3 s, warm start 0.1-0.2 s, on Android 6.0 to 16, phone to TV.
* **Memory is modest:** 43-68 MB for the app's screen process, 10-19 MB for the separate VPN process while connected.
* **Every Android version and form factor in the matrix passes**, Android 6.0 included (details below).
* **Best Connect is deliberately slow and data-hungry:** 2.5-8 minutes (most runs 3-5) and **60-300 MB** per run (it speed-tests real tunnels). It
  probed all 99 pooled servers in under 10 s; the speed tests are the rest. This is the design, but the data cost is the number users
  will notice.
* **Size is the weakest point:** the universal APK is 38.5 MB and takes 85-123 MB once installed. About 8 MB of it is code that is
  not shrunk at all, because the upstream engine turns R8 off. The per-processor APKs are about 16 MB each. See recommendations.

## Compatibility matrix and device timings (CI run 42, commit `53c6c99`)
Live VPN Gate volunteers, so a country with one relay can fail on any run; that is INCONCLUSIVE, never PASS. The Android 6.0 device
installs the APK **as re-signed by the release script** (v1 signature), so the release path itself is tested there.

| Device (emulator) | Verdict | Cold start | Quick Connect | Best Connect | Memory: app / VPN process |
|---|---|---|---|---|---|
| Android 16 phone (release gate) | PASS | 1.29 s | 22 s, inside Japan | 4:55 | 50 / 13 MB |
| Android 16 foldable, unfolded (841×700 dp, navigation rail) | PASS | 1.06 s | 21 s, inside Japan | 4:25 | 46 / 16 MB |
| Android 13 tablet (1280×800 dp, navigation rail) | PASS | 2.32 s | 24 s, inside Japan | 8:03 | 55 / 10 MB |
| Android TV 11, 32-bit x86 (960×540 dp) | PASS | 0.84 s | 25 s, inside Japan | 4:29 | 68 / 19 MB |
| Android 10, 320 dp phone at 200% font | <!-- API29-VERDICT --> | 0.90 s | Canada's only relay failed honestly in 36 s; Any country connected in 17 s | <!-- API29-BEST --> | 58 / 16 MB |
| Android 8.0 phone | PASS | 0.92 s | 19 s, inside Japan | 5:58 | 59 / 16 MB |
| Android 7.0 phone | PASS | 1.08 s | 18 s, inside Japan | 2:58 | 60 / 12 MB |
| Android 6.0 phone | PASS | 1.23 s | 21 s, inside Japan | 2:23 | 65 / 10 MB |

Getting here took four matrix runs; each found something real (details in `docs/STATUS.md`): a crash on Android 6.0 when the
country picker opened, then the keyboard covering the picker on Android 6.0-8.1, then the Disconnect button off-screen at 200% font,
plus emulator and test-script problems that were not the app.

How to read the numbers: "Quick Connect" here is measured by the test script from its tap to seeing "Connected", for a Quick Connect
restricted to the largest country, right after the app was killed and restarted (to prove settings survive), and it includes the
one-time Android VPN permission dialog. The speed runs below remove those effects.

## Speed runs (workflow `speedrun`, run 1, APK of CI run 39)

| | Android 16 phone (1080×2400) | Android 6.0 phone (1080×1920) |
|---|---|---|
| First launch after install | 1.70 s | 1.05 s |
| Cold start, median of 5 | 1.86 s (1.09-2.60) | 1.26 s (1.25-1.29) |
| Warm start, median of 3 | 0.20 s | 0.12 s |
| Memory idle / connected, app process | 43 / 57 MB | 66 / 66 MB |
| Memory connected, VPN process | 16 MB | 10 MB |
| Installed size (APK + extracted native code + compiled code) | 85 MB | 123 MB |
| **Quick Connect ×6 (Any country)** | **6/6 connected** | **6/6 connected** |
| Tap to tunnel up, app's own log (each run) | 5.2, 4.8, 4.4, 5.1, 5.1, 4.5 s | 4.6, 5.0, 3.5, 4.6, 3.6, 4.4 s |
| of which: probing until the first attempt | 1.5-1.9 s | 1.5-1.6 s |
| of which: OpenVPN handshake (always the first server tried) | 2.9-3.6 s | 2.0-3.4 s |
| Tap to "Connected" on screen, test script, median | 12.1 s | 10.6 s |
| **Best Connect ×1 (Any country)** | 4:17, 99 probed (85 answered) in 6 s, 9 speed-tested, **300 MB** | 2:35, 99 probed (81 answered), 7 speed-tested, **60 MB** |
| Best Connect winner | 88 ↓ / 28 ↑ Mbit/s, 180 ms, margin 5% | 17 ↓ / 13 ↑ Mbit/s, 147 ms, margin 4% |

Notes. The server list was already cached, so "list ready" was instant; a first-ever start also waits for one download of about
1.1 MB. The 6-7 s between "tunnel up" and the script seeing "Connected" is mostly the script: before tapping it scrolls to the top
and reads the screen (several seconds on an emulator), and it reads the screen again every 1-2 s. Every connection negotiated
AES-128-CBC/SHA1 (all current VPN Gate relays declare it) and the app reported it as verified. Best Connect's data use follows the
servers' speed: fast relays move more bytes in the same test time, hence 300 MB against 60 MB. Android 6.0 compiles the whole app
ahead of time at install, which is why it uses more storage than Android 16.

## APK size

| File | Size | Contents (compressed) |
|---|---|---|
| `VPN-App.apk` (universal) | 38.5 MB | code (dex) 8.1 MB, native code 7.4 (arm64) + 6.5 (armv7) + 8.0 (x86_64) + 7.5 (x86) MB, resources 0.8 MB |
| `VPN-App-arm64-v8a.apk` and the other three | about 15-17 MB each | the same code and resources, one processor's native code |

The native code is OpenVPN, OpenSSL and the upstream helpers, and must stay. It is stored compressed and extracted at install
because upstream starts the OpenVPN binary from the library folder.

## Recommendations, by expected gain

1. **Enable R8 for this app's code, Compose and AndroidX** (upstream ships with `optimization { enable = false }`, so the 8.1 MB of
   code is not shrunk at all). Expected: a markedly smaller download and code footprint, and a faster cold start, which is where
   unshrunk Compose costs most. Not done in this pass on purpose: upstream relies on Java serialization of its profile classes and on
   native callbacks, so its packages (`de.blinkt.openvpn.**`, `net.openvpn.**`) must be kept whole, and the change must go through the
   full device matrix before a release. A one-file change in `tools/extra.gradle.kts` plus a keep-rules file.
2. **Point most people to `VPN-App-arm64-v8a.apk`** (about 16 MB instead of 38.5 MB); the release already publishes it and the README
   explains which to choose.
3. **Best Connect's data use** (up to 300 MB on fast relays). It is reported in the results; consider stopping a server's test as soon
   as its speed is clear, or a smaller default on metered networks.
4. **The 20 MB listing cap** (engine section): keep what was read instead of refusing the whole list. Not urgent.
5. **Measure on a real low-end phone.** Emulators on CI hardware hide slow storage and slow CPUs; cold start and the first list
   download are the two numbers most likely to be worse on a cheap phone.


## Engine (JVM micro-benchmarks)
4-core Xeon 2.1 GHz, JDK 21, median of 5 runs after a warm-up. The synthetic listing has the shape of a real VPN Gate one (CA chain,
client certificate and key, the usual directives and comment header); every row is usable, so the full validation path runs.

| Case | 100 rows | 1,000 rows | 1,500 rows |
|---|---|---|---|
| Listing size | 1.2 MB | 11.9 MB | 17.8 MB |
| Parse the CSV and decode each profile | 15 ms | 68 ms | 120 ms |
| Validate, rebuild (sanitize) and identity-check every profile | 61 ms | 182 ms | 270 ms |
| Heap kept by the pooled servers | 1.0 MB (9.3 KB each) | 9.6 MB | 14.3 MB |
| Merge into the 72 h pool | 1.2 ms | 18 ms | 51 ms |
| Ledger: save (JSON) / load | 2.9 / 2.9 ms (35 KB) | 12 / 16 ms (353 KB) | 24 / 19 ms (529 KB) |
| Quick Connect's selection logic (probes and tunnel faked) | 9.9 ms | 5.3 ms | 3.6 ms |

| Single operation | Time |
|---|---|
| Rebuild one profile from the allowlist | 0.17-0.20 ms |
| Identity check of one `<ca>` chain | 0.05-0.11 ms |
| Base64 MIME decode, 2 MB: the app's portable decoder / the JDK's | 6.2 / 6.3 ms |

What this means for the real list (about 96 OpenVPN rows, about 1.1 MB, measured live on 2026-11-05):

* **Processing a download costs about 75 ms of CPU on this machine.** Phones run this kind of code several times slower, so expect
  a few hundred milliseconds on a low-end phone. It runs on a background thread (`Dispatchers.IO`, streamed as the bytes arrive) and
  once per download, so it is not what the user waits for: the download itself and the volunteers' handshakes are (see the device
  results).
* **Validation, not parsing, is the larger cost** (0.18 ms per row): every untrusted profile is rebuilt from an allowlist and its
  certificate chain checked. That is the security design working; nothing to optimise at today's list size.
* **Memory is small:** 9.3 KB per pooled server, so under 1 MB for the 98 pooled servers seen live.
* **Quick Connect's own logic takes under 10 ms**, so its time is network time.
* **The portable Base64 decoder** (written so the engine runs on Android 6.0/7.0, which lack `java.util.Base64`) is as fast as the
  JDK's, and a test checks that it decodes exactly like the JDK on 25,000 inputs including malformed ones.
* **Finding: the 20 MB download cap.** A listing over 20 MB is refused as a whole and the cached list is kept (a 2,000-row listing,
  23.8 MB, gave 0 servers). With today's profile size that is about 1,680 rows, 17 times the real list, so it does not bite now. If
  VPN Gate ever served that many OpenVPN rows in one response, the app would ignore every new download. Recommendation (low): parse
  up to the cap and keep the rows read so far instead of refusing all of them.
