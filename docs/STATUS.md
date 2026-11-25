# Project status

Audit baseline `c1bc238`. This file separates **confirmed fixes**, **tested behaviour**, **open issues** and
**unverified risks**. "Verified" means demonstrated by a test or CI run that actually executed; evidence is named for each row.

## Current state (25 November 2026), read this first

**Released:** [v1.0.0](https://github.com/GeniusDevam/VPN-App/releases/tag/v1.0.0), the first release signed with the permanent key,
built from commit `f95296a` on `main` (CI run [#48](https://github.com/GeniusDevam/VPN-App/actions/runs/45664646878), deployment
approved by the key holder). Universal APK SHA-256 `57c68ef8...2649`; signing certificate SHA-256
`0838262d4e4d35da8527a5555c90552221b07db3804d17966e273f2fe1e5998a`. These were checked independently against the published APK
itself (`keytool -printcert -jarfile`, `jarsigner -verify`), the release notes, GitHub's asset digests, the `sign` job log and the README.

**Done:** release-signing setup (protected environment `release` with required reviewer, no administrator bypass, `v*` tags only;
tag ruleset; default-branch ruleset; read-only workflow token; outside-contributor approval), permanent key generated and backed up by
the key holder and uploaded with `tools/upload-release-key.sh`, old leaked-key release deleted, default branch switched to `main`.
**The signing setup needs no further work; never generate a replacement key unless the current one is lost or compromised**
(`docs/RELEASE_SIGNING.md` has the recovery procedure).

**Verified (by CI, not on real phones):** unit tests, real-OpenVPN tests, Android 6.0-16 emulator matrix including tablet, foldable,
small phone at 200% font and Android TV, key tools on Linux, macOS and Windows. See sections 4 and 4b.

**Pending:** physical-device testing, run by the maintainer: [docs/MANUAL_QA.md](MANUAL_QA.md). **Nothing has been tested on a real
phone yet.** Do not describe the app as production-ready before that is done.

**Open issues (details in [docs/CI.md](CI.md)):**
1. Suspected Best Connect stall: one speed test has no time limit, and a stalled upload can block for many minutes (Android TV run in
   #48, INCONCLUSIVE). Fix proposed, not implemented (needs approval and a new release). Manual test C7 checks it.
2. Build #48 shows "failure" only because the macOS `keygen` job never got a runner within its 15-minute limit; the release path does
   not depend on it. Workflow options proposed, not implemented.
3. GitHub release immutability is off (`immutable: false`); recommended to enable in repository settings.
4. C8 (below): after the app process is killed while the VPN stays up, the UI may show "Connected" without server and cipher.
5. The old branch `ccr-49f3cc12-faye7c` (pre-cleanup history) still exists; deleting it is the maintainer's decision.

**Document map:** `README.md` (users: install, verify, risks), `docs/RELEASE_SIGNING.md` (signing design, release procedure, recovery),
`docs/MANUAL_QA.md` (device test plan and results), `docs/CI.md` (CI behaviour, findings, optimisation proposals),
`docs/PERFORMANCE.md`, `docs/SECURITY.md`, `docs/DIRECTORY.md`, `docs/AUDIT.md`, `docs/FUTURE.md` (ideas only), `PLAN.md` (original
design, annotated).

## 0. Maintainer actions
Done (25 November 2026): delete the old `latest` release (build #17); GitHub environment and rulesets; create, back up and upload
the release key; publish v1.0.0; switch the default branch to `main`. The `emulator-results` and `compat-results` branches are now
replaced by a single redacted commit on every run, so the old unredacted commits are no longer reachable from them.

Remaining:
1. Run the physical-device tests in [docs/MANUAL_QA.md](MANUAL_QA.md) and report results.
2. Decide on the proposals in [docs/CI.md](CI.md): the Best Connect stall fix, release-gate hardening, CI speed-ups.
3. Enable release immutability (*Settings > General > Releases*).
4. Decide whether to delete the branch `ccr-49f3cc12-faye7c`.

## 1. Problems found during testing
| Problem | What happened and what changed |
|---|---|
| Android layer did not compile at `c290a38` | CI run 18 on that commit failed to compile on both ABIs (`Session.kt:131`: a trailing lambda bound to the new `authPolicy` parameter). Fixed in `a7ffb1b`; run 19 built. |
| Emulator job reported green after failures | The emulator script ended in `exit 0` after any Quick/Best failure and the job was `continue-on-error`, so run 19's emulator (which could not get past the onboarding screen because the longer privacy text pushed the button off-screen) was reported green. Now assertions decide the status (section 4). |
| tls-auth probe has no live target | Built and verified (C3), but see section 3: **0 of 96 live relays currently use tls-auth/crypt**, so it helps nothing today. |
| Removing an "unused" permission broke the VPN | `RECEIVE_BOOT_COMPLETED` looked unused, but upstream's persisted keep-alive job needs it. Removing it crashed the VPN process on every connect (emulator run 27 FAIL, `IllegalArgumentException: Requested job cannot be persisted without holding RECEIVE_BOOT_COMPLETED`). Restored; the emulator test is what caught it. |
| Release signing lacked a v1 signature | `tools/resign-apk.sh` signed releases **without a v1 signature** and checked them only for Android 8.0+. When support was widened to Android 6.0, which accepts only v1, a real release would have been refused there (or stopped by the publish job's own minSdk-23 check). The APKs CI tested were build-signed with v1, which hid it. Fixed in `c95481a`: v1+v2+v3, all verified for minSdk 23, and the Android 6.0 test device now installs the re-signed APK. |
| Android 10 small phone: Quick Connect reported as hung (compat run 35) | Two different things. A real layout bug (at 200% font the country rows were squeezed off the picker) and a harness misreading: Quick Connect **had connected** in 23 s (its banner said so), then the volunteer dropped the tunnel and OpenVPN was reconnecting; the harness only looked for "Connected" and reported a hang. Layout fixed; the harness now reports "connected, then dropped" separately (INCONCLUSIVE, not PASS). |
| Android 6.0: picker crash, then keyboard over the picker | Run 39 found a **crash on Android 6.0** the moment the country picker opened (Material 3 `ModalBottomSheet`: "offset read before being initialized"). The first fix, a dialog, then made the **keyboard pop up over the list on Android 6.0-8.1** (pre-Android 9 windows focus their first control, which was the search field); run 41 caught that too. Fixed in `ebea947` and `8f59f14`. Everything else on Android 6.0 (list download over HTTPS with the bundled roots, Quick Connect 6/6, Best Connect) already worked in the 6.0 speed run. |
| Small phone at 200% font failed (run 41) | Not the app: the emulator's system server died 15 s after launch (the Nexus S profile has ~343 MB RAM). The device now runs with 2 GB. |
| Published logs were over-redacted | Redaction was too aggressive: every line containing "token" and every UI dump (uiautomator marks each node `password="false"`) was dropped, which hid the reason above. Now only lines that can carry keys or credentials are dropped; a CI test checks both directions. |
| Emulator FAILs caused by the test itself | Two FAILs were bugs in the test steps: the onboarding button below the fold (run 19), and the app-picker button below the fold, which left the test stranded on Settings (run 30). Both now scroll like a user and return Home. A FAIL must be read, not assumed. |

## 2. Findings matrix
Legend: **Fixed** = changed and tested; **Mitigated** = reduced, not eliminated; **Open**; **Unverified** = no evidence either way.

### A. Requested features
| Item | Status | Evidence / caveat |
|---|---|---|
| A1 directory coverage | **Best compliant effort, no measured gain.** One official HTTPS endpoint (`/api/iphone/`), 72 h union pool, polite refresh, diagnostics, stale-cache fallback. | Live: **96 rows, 90 TCP + 6 UDP, all AES-128-CBC** in one download; pooled view in the emulator showed **98 usable** (union added 2). The "before" figure was about 100. **No increase.** The list is deliberately partial; no mirror or hidden endpoint was used or found. Total advertised relays (thousands) are mostly SoftEther/L2TP-only and not OpenVPN-usable through this API. |
| A2 fastest in a chosen country | **Built; verified in the real UI and with a live connection.** | Emulator runs 21-22: picker opened with real counts (Japan 53, South Korea 31, ...), the choice applied to Quick only (Best stayed on Any) and persisted across process death. **Run 22: Quick Connect inside the chosen country (Austria) connected in 25 s, and run 23 inside Japan in 26 s; the status card showed that country both times.** Run 21: India (1 server) failed honestly in 32 s with a message naming India. Scope is enforced in `PoolSelection`; tests prove no other country is ever probed or connected. Note "fastest" here is a latency estimate for Quick and a measured throughput for Best; most countries have 1 server, so there is nothing to rank. |
| A3 optional global search | **Built and engine-tested; not exercised live.** | `GlobalSearchEngine` tests (batching, budgets, pause/finish/cancel, resume). Consent dialog, foreground service, metered/battery guards exist. No emulator or device run of the worldwide search; background survival under Doze/process kill is **NOT TESTED**. |
| A4 history + 7-day absence | **Fixed.** | `LedgerAndDirectoryTest` (absence counted only on usable listings, 36 h cap per listing, failed fetch never advances it, old measurements never deleted). New per-server history lines (`RecordSummary`, 7 tests). Encrypted at rest; clear-history in Settings. |
| A5 every Android version and device type (2026-11-07) | **Built; verified on an emulator matrix, not on phones.** | `minSdk 23` (Android 6.0); universal APK plus one per processor type (arm64-v8a, armeabi-v7a, x86_64, x86); navigation rail and width-limited content at 600 dp and wider; picker usable at 200% font on a 320 dp phone; Android TV launcher entry, no touchscreen requirement. Lint NewApi is a hard gate (two upstream helpers annotated so Lint verifies their callers). Android 6.0/7.0: Let's Encrypt roots added in code for the app's own HTTPS, upstream `java.util.Base64` desugared, portable engine code. Per-device verdicts: section 4b. Speed and memory: `docs/PERFORMANCE.md`. |

### B. Security
| Item | Status | Evidence / caveat |
|---|---|---|
| B1 signing key | **Fixed (25 November 2026)** | Permanent key created by the key holder and stored only in the protected environment `release`; v1.0.0 signed with it and published (certificate `0838262d...998a`, checked against the published APK). The release key is readable only by the `sign` job (tag releases only, manual approval, read-only token); every other build uses a throw-away key and no other job references a secret (checked on every build; 13 mutation tests). Releases re-sign the already-tested APK; the certificate is pinned and the leaked key is refused by fingerprint. Old key remains in git history and must never be trusted (README). |
| B2 server identity | **Fixed, live-checked** | Real OpenVPN: genuine cert connects; wrong name / wrong EKU / untrusted CA rejected; control without policy accepts the wrong name (CI, 7/7, OpenVPN 2.7.0). **Live relays: 15/24 connected with the policy and 15/24 without; every relay that reached TLS presented `CN=opengw.net`.** Residual: authenticates VPN Gate's software, not an individual volunteer. |
| B3 HTTP fallback | **Fixed** | HTTPS only; cleartext disabled app-wide. |
| B4 leak protection | **Not guaranteed by an app; documented.** | `persist-tun`, IPv6 blocking, forced DNS. Always-on + "Block connections without VPN" were **not tested** (no device). Probe sockets bypass the tunnel by design. |
| B5 disconnect vs restore | **Fixed** | `RunCoordinator` + epoch guard; deterministic and 500-seed tests. Two remaining paths were closed later (Quick watchdog timeout, generic exception). **Runs 22 and 23 (emulator): after an explicit Disconnect the VPN stayed down for 20 s.** |
| B6 cipher policy | **Fixed** | One gate for every path; declared cipher never reported as verified. Added a bounded wait for the negotiated-cipher log line so a good tunnel is not shown as unverified. Live: every connection negotiated AES-128-CBC/SHA1. |
| B7 compression | **Fixed** (removed) | Source reading plus real-OpenVPN acceptance. |
| B8 validation | **Fixed** | Address matrix, 600-seed sanitizer fuzz, CSV fuzz. |
| B9 logs and CI output | **Fixed** (owner: delete old branch) | Redacted publishing; debug-gated logging. |
| B10 dependencies / licences | **Attack surface reduced and gated; vulnerability scan still not done** | Components reachable by other apps: **18/23 -> 5/23** (the 5 are the launcher activity and four that need a system-only permission), enforced as a hard CI gate by `tools/manifest-policy.json`; unused permissions removed; unused upstream external-control API and boot receiver removed. Verified live (run 31): Quick/Best/disconnect work and the split-tunnel picker lists 18 apps without `QUERY_ALL_PACKAGES`. `ACCESS_LOCAL_NETWORK` removal cannot be tested before Android 17. **No advisory database was reachable, so "no known vulnerabilities" is not claimed.** |

### C. Functional
| Item | Status | Evidence / caveat |
|---|---|---|
| C1 error in wrong field | **Fixed** | `ConnectResult` only via `connected()`/`failed()`. |
| C2 false "scan every server" | **Fixed** | No silent 400 cap; coverage and confidence claims carried in reports. |
| C3 probe comparability | **Improved** | tls-auth relays get an HMAC-signed reset with verified reply (real OpenVPN: answered; wrong key not). tls-crypt still weak TCP fallback (penalised, labelled). |
| C4 speed-test cost | **Mitigated** | Tunnel-egress check, overhead allowance, caps. Real device cost not measured. |
| C5 honest 35 s | **Partly verified** | Engine proof in virtual time; watchdog on elapsed-realtime. Emulator: a country-bound success took 25-26 s from the tap (runs 22-23, includes the VPN consent dialog and a cold list), and a country-bound failure ended honestly at 32 s (run 21). Earlier any-country runs connected in 8-10 s. Wall-clock on a real phone and network: not measured. |
| C6 reputation | **Fixed** (unit-tested) | |
| C7 atomicity / ordering | **Fixed** | `PersistenceTest`: crash between temp write and commit keeps old file; 30 rounds x 400 racing saves always end on the newest. The old design reordered writes in 30/30 rounds. Keystore failure / power loss **NOT TESTED**. |
| C8 lifecycle | **Partly** | Tile and service logic reviewed. **Open:** after the app process is killed while the VPN stays up, the UI may show "Connected" without the server/cipher (not reconstructed). Not tested on a device. |
| C9 UX truthfulness | **Improved** | Three distinct failure reasons; picker states (loading / unavailable / stale / empty); chosen-but-empty country stays visible; screen-reader selectable rows. No TalkBack run. |
| C10 documentation | **Updated** | README, docs/. |

### D. CI and release
| Item | Status |
|---|---|
| D1 tests fail when behaviour fails | **Fixed (new)**: emulator exit 0/1/3 = PASS/FAIL/INCONCLUSIVE; the job is no longer `continue-on-error`; the release needs it and prints the verdict in its notes. INCONCLUSIVE is a warning, never success. Both PASS and INCONCLUSIVE have now been produced by real runs; a FAIL verdict path has not been exercised on the live job (the script's hard-failure branches were exercised only by the onboarding bug in an earlier run). |
| D2 supply chain | **Mitigated and verified in CI**: read-only workflow token; secrets only in the protected `release` environment and only in the `sign` job (checked every build, with 13 mutation tests); tag-only releases gated on default-branch history; actions pinned by commit SHA (one documented exception, `actions/attest-build-provenance@v2`, in the secret-free `publish` job); versioned releases with checksum and attestation; script-injection rule (no `github.*` context in `run:`). **Open (GitHub settings, owner):** required reviewer, tag-only deployment rule, tag and branch rulesets (docs/RELEASE_SIGNING.md). |
| D3 tests | See section 5. |
| D4 physical device | **NOT TESTED.** |

### E. Backlog (not built)
Widget, shortcuts, auto-failover, easier Always-on setup, optional Tor, background pre-scan, mirror support. None claimed.

## 3. Live measurements (CI, real VPN Gate volunteers, 2026-11-05)
* Directory: 96 rows; 90 TCP, 6 UDP; 96/96 `AES-128-CBC`; 96 with embedded certificate; **0 with tls-auth/tls-crypt**.
* Pooled usable relays seen by the app in the emulator: **98**, in at least 10 countries (Japan 53, South Korea 31, Thailand 4, United States 4, India/Mexico/Romania/Russia/Taiwan/Vietnam 1 each). Because Japan and Korea hold about 85%, "fastest in country" is only meaningful there.
* Stock OpenVPN: 15/24 connected; with the app's identity policy: 15/24. Failures were volunteers that never finished a TLS handshake or timed out. Not a population estimate (24-relay sample, different relays failing each run).

## 4. Emulator verdicts (Android 16 x86_64, CI)
| Run | Verdict | Notes |
|---|---|---|
| 19 (`a7ffb1b`) | reported green by the old script | Could not find the onboarding button; no connection was tested. |
| 21 (`b6610cb`) | **INCONCLUSIVE**, 6 assertions passed, 0 failed | Both modes shown, picker opens, list loaded, per-mode scope, persistence across process death, honest failure naming the country. Inconclusive: the single relay in India did not answer within 32 s. |
| 22 (`793ef28`) | **PASS** (9 assertions, 0 failed, 0 inconclusive) | The above, plus: Quick Connect connected in 25 s, the connected server was in the chosen country (Austria), an explicit disconnect stayed down for 20 s, and Best Connect finished with a named winner. (This run's overall CI status was still red because of the probe-test harness bug fixed in `cfc4508`; the emulator job itself passed.) |
| 23 (`cfc4508`) | **PASS** (9 assertions, 0 failed, 0 inconclusive); the whole CI run was green | First run with the fixed probe step and the "largest country" script. Quick Connect inside **Japan** (53 servers) connected in 26 s and the status card showed Japan; disconnect stayed down for 20 s; Best Connect named a winner. |
| 24, 25 (docs-only commits) | not read | Same code as run 23; their emulator results were not inspected. |
| 27 (`31aee81`, label "run 27") | **FAIL** (genuine app bug) | VPN process crashed on connect after `RECEIVE_BOOT_COMPLETED` was removed (see corrections). |
| 30 (`8c41185`) | **FAIL** (harness bug, no crash) | The new app-picker step did not scroll and stranded the test on Settings. |
| 31 (`d29abbc`) | **PASS** (10 assertions, 0 failed); the whole CI run was green | Includes: app picker lists 18 apps without `QUERY_ALL_PACKAGES`; Quick Connect inside **Japan** in 22 s; disconnect stayed down 20 s; Best Connect named a winner; no crash. First PASS on the cleaned manifest (5 reachable components). |

(Run numbers in the `emulator-results` branch are GitHub's run numbers, so they can differ from the labels used in older notes.)

Two PASS and one INCONCLUSIVE out of three runs with real assertions is **not** a reliability figure; live volunteers vary from run to run, and the one INCONCLUSIVE used a country with a single relay.

## 4b. Compatibility matrix (CI emulators, live VPN Gate)
Release run #48 (`f95296a`, the v1.0.0 commit); full numbers and history in `docs/PERFORMANCE.md`, per-device screenshots on the
`compat-results` branch.

| Device | Verdict | Quick Connect | Best Connect |
|---|---|---|---|
| Android 16 phone (release gate) | PASS | connected (Japan) | done |
| Android 16 foldable | PASS | 23 s (Japan) | 4:16 |
| Android 13 tablet | PASS | 23 s (Japan) | 5:19 |
| Android 10, 320 dp, 200% font | PASS | 19 s (Indonesia) | 3:07 |
| Android 8.0 phone | PASS | 18 s (Japan) | 3:37 |
| Android 7.0 phone | PASS | 17 s (Japan) | 2:32 |
| Android 6.0 phone (installs the release-re-signed APK) | PASS | 19 s (Japan) | 3:56 |
| Android TV 11 (32-bit x86) | **INCONCLUSIVE** | 25 s (Japan) | did not finish in 26 min: suspected stall, `docs/CI.md` section 3 |

The job of an INCONCLUSIVE device still shows green in the Actions list; read the verdict, not the tick.

## 5. Test matrix
* Engine unit tests (JVM): **129**, all passing locally and in CI. Added during hardening: persistence 7, pool/failure reasons 8+5, record summary 7, tls-auth probe 6.
* Real OpenVPN in CI: relay-identity 7/7 (OpenVPN 2.7.0, runs 19-23). tls-auth probe: 4/4 locally on 2.6.19 and **passed in CI (run 23, OpenVPN 2.7.0)** after its first CI run failed for a harness reason (the step ran Gradle as a different user than the previous root-owned step, and hid the error; fixed in `cfc4508`).
* Live OpenVPN against VPN Gate (workflow `vpngate-check`): see section 3.
* Emulator UI assertions: section 4.
* Documentation: README, PLAN.md (status table and dated notes on every superseded passage), SECURITY, DIRECTORY, AUDIT and RELEASE_SIGNING were updated to match; the plan's original text is kept and annotated.
* Not covered: country picker with zero or stale directory in the UI (engine state logic is unit-tested, the screens are not), worldwide search, Quick Settings tile, notification actions, process recreation with the VPN up, network switching, screen-off, Doze, Always-on / lockdown, split tunnelling, IPv6/DNS leaks, updates, TalkBack.

## 6. Physical-device testing
The full plan, with procedures, pass criteria, evidence to collect and a results table, is [docs/MANUAL_QA.md](MANUAL_QA.md).
**Status: not started (all NOT RUN).**

## 7. Deferred, by priority
1. Physical-device testing (`docs/MANUAL_QA.md`), then fix what it finds.
2. Best Connect per-test deadline (suspected stall; `docs/CI.md` section 3).
3. Reconstruct connection details after process death (C8).
4. Release-gate hardening and CI speed-ups (`docs/CI.md`), after approval.
5. Dependency vulnerability scan (needs network access to advisory databases).
6. In-app licence notices; mirror of upstream sources for GPL.
7. tls-crypt-aware probe (no live relay uses it today, so low value).
8. Backlog E. Future platforms are research notes only (`docs/FUTURE.md`).
