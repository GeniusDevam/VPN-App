# Performance review

Measured 2026-11-07 on branch `ccr-49f3cc12-faye7c`. **No physical phone was used.** Every figure below comes from one of three
sources, named in each table:

| Source | What it measures | How to repeat it |
|---|---|---|
| Engine benchmarks (JVM) | CPU and memory cost of the app's own logic: parsing and validating the server list, the server ledger, Quick Connect's selection | `cd engine && gradle -q benchCli --args="1500 5"` (also printed by every CI build, job `engine`) |
| Compatibility matrix (CI emulators) | Does the app work, and how fast, on each Android version and form factor, against **live** VPN Gate volunteers | every CI build, job `compat`; per-device results and screenshots on the `compat-results` branch |
| Speed runs (CI emulators) | Repeated launches, memory, Quick Connect ×6 and Best Connect ×1, with a per-phase timeline from the app's own log | workflow `speedrun` (Actions → speedrun → Run workflow, give it a `build` run id) |

Emulators run on x86_64 CI machines with software graphics. They say nothing exact about a given phone; they are good for comparing
devices and code versions with each other, and for finding where time goes.

<!-- DEVICE-RESULTS -->

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
