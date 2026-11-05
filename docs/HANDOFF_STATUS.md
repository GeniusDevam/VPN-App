# Status against the development handoff

Branch `ccr-49f3cc12-faye7c`. Audit baseline `fcfba9e`. This file separates **confirmed fixes**, **tested behaviour**, **open issues** and
**unverified risks**. "Verified" means demonstrated by a test or CI run that actually executed; evidence is named for each row.

## 0. What needs you (owner actions)
1. **Delete the public `latest` release now.** It is build #17 (the audit baseline) and is signed with the leaked key. The pipeline would
   remove it only after a new signed release exists, and no tool available to this work could delete it. Until it is gone, anyone can
   download an APK that carries the compromised identity.
2. **Create the real release key** (`tools/generate-release-key.sh`, then the `gh secret set` commands it prints). Until then CI
   publishes nothing. This is the only way B1 can close; it cannot be done from the repository. Migration limits: `docs/RELEASE_SIGNING.md`.
3. `git push origin --delete emulator-results`, then let the next run recreate it (old commits on it hold unredacted logs).
4. Run the physical-device checks in section 6. **No physical device was used. Everything below is emulator, JVM or CI.**

## 1. Corrections to earlier claims (found this session)
| Earlier claim | Reality |
|---|---|
| "Android layer CI-compiled" at `e46cac1` | **False.** CI run 18 on that commit failed to compile on both ABIs (`Session.kt:131`: a trailing lambda bound to the new `authPolicy` parameter). Fixed in `d06b27a`; run 19 built. |
| "Emulator job green" | The emulator script ended in `exit 0` after any Quick/Best failure and the job was `continue-on-error`, so run 19's emulator (which could not get past the onboarding screen because the longer privacy text pushed the button off-screen) was reported green. Now assertions decide the status (section 4). |
| "tls-auth probe feasible but not built" | Built and verified (C3), but see section 3: **0 of 96 live relays currently use tls-auth/crypt**, so it helps nothing today. |

## 2. Findings matrix
Legend: **Fixed** = changed and tested; **Mitigated** = reduced, not eliminated; **Open**; **Unverified** = no evidence either way.

### A. Requested features
| Item | Status | Evidence / caveat |
|---|---|---|
| A1 directory coverage | **Best compliant effort, no measured gain.** One official HTTPS endpoint (`/api/iphone/`), 72 h union pool, polite refresh, diagnostics, stale-cache fallback. | Live: **96 rows, 90 TCP + 6 UDP, all AES-128-CBC** in one download; pooled view in the emulator showed **98 usable** (union added 2). The "before" figure was about 100. **No increase.** The list is deliberately partial; no mirror or hidden endpoint was used or found. Total advertised relays (thousands) are mostly SoftEther/L2TP-only and not OpenVPN-usable through this API. |
| A2 fastest in a chosen country | **Built; verified in the real UI and with a live connection.** | Emulator runs 21-22: picker opened with real counts (Japan 53, South Korea 31, ...), the choice applied to Quick only (Best stayed on Any) and persisted across process death. **Run 22: Quick Connect inside the chosen country (Austria) connected in 25 s and the status card showed that country.** Run 21: India (1 server) failed honestly in 32 s with a message naming India. Scope is enforced in `PoolSelection`; tests prove no other country is ever probed or connected. Note "fastest" here is a latency estimate for Quick and a measured throughput for Best; most countries have 1 server, so there is nothing to rank. |
| A3 optional global search | **Built and engine-tested; not exercised live.** | `GlobalSearchEngine` tests (batching, budgets, pause/finish/cancel, resume). Consent dialog, foreground service, metered/battery guards exist. No emulator or device run of the worldwide search; background survival under Doze/process kill is **NOT TESTED**. |
| A4 history + 7-day absence | **Fixed.** | `LedgerAndDirectoryTest` (absence counted only on usable listings, 36 h cap per listing, failed fetch never advances it, old measurements never deleted). New per-server history lines (`RecordSummary`, 7 tests). Encrypted at rest; clear-history in Settings. |

### B. Security
| Item | Status | Evidence / caveat |
|---|---|---|
| B1 signing key | **Open** (owner action) | Key removed from tree, CI uses secrets or ephemeral keys. Key and passwords remain in git history; the public `latest` release still exists. |
| B2 server identity | **Fixed, live-checked** | Real OpenVPN: genuine cert connects; wrong name / wrong EKU / untrusted CA rejected; control without policy accepts the wrong name (CI, 7/7, OpenVPN 2.7.0). **Live relays: 15/24 connected with the policy and 15/24 without; every relay that reached TLS presented `CN=opengw.net`.** Residual: authenticates VPN Gate's software, not an individual volunteer. |
| B3 HTTP fallback | **Fixed** | HTTPS only; cleartext disabled app-wide. |
| B4 leak protection | **Not guaranteed by an app; documented.** | `persist-tun`, IPv6 blocking, forced DNS. Always-on + "Block connections without VPN" were **not tested** (no device). Probe sockets bypass the tunnel by design. |
| B5 disconnect vs restore | **Fixed** | `RunCoordinator` + epoch guard; deterministic and 500-seed tests. This session closed two remaining paths (Quick watchdog timeout, generic exception). **Run 22 (emulator): after an explicit Disconnect the VPN stayed down for 20 s.** |
| B6 cipher policy | **Fixed** | One gate for every path; declared cipher never reported as verified. Added a bounded wait for the negotiated-cipher log line so a good tunnel is not shown as unverified. Live: every connection negotiated AES-128-CBC/SHA1. |
| B7 compression | **Fixed** (removed) | Source reading plus real-OpenVPN acceptance. |
| B8 validation | **Fixed** | Address matrix, 600-seed sanitizer fuzz, CSV fuzz. |
| B9 logs and CI output | **Fixed** (owner: delete old branch) | Redacted publishing; debug-gated logging. |
| B10 dependencies / licences | **Partly done** | Pins and licences documented (`docs/AUDIT.md`); **no vulnerability scan was possible** (databases unreachable). Exported-component listing of the final manifest was added to CI (`tools/manifest-exports.py`) but its output has not been reviewed yet. Actions are pinned by tag, not by SHA (SHAs could not be resolved safely from here). |

### C. Functional
| Item | Status | Evidence / caveat |
|---|---|---|
| C1 error in wrong field | **Fixed** | `ConnectResult` only via `connected()`/`failed()`. |
| C2 false "scan every server" | **Fixed** | No silent 400 cap; coverage and confidence claims carried in reports. |
| C3 probe comparability | **Improved** | tls-auth relays get an HMAC-signed reset with verified reply (real OpenVPN: answered; wrong key not). tls-crypt still weak TCP fallback (penalised, labelled). |
| C4 speed-test cost | **Mitigated** | Tunnel-egress check, overhead allowance, caps. Real device cost not measured. |
| C5 honest 35 s | **Partly verified** | Engine proof in virtual time; watchdog on elapsed-realtime. Emulator: a country-bound success took 25 s from the tap (run 22, includes the VPN consent dialog and a cold list), and a country-bound failure ended honestly at 32 s (run 21). Earlier any-country runs connected in 8-10 s. Wall-clock on a real phone and network: not measured. |
| C6 reputation | **Fixed** (unit-tested) | |
| C7 atomicity / ordering | **Fixed** | `PersistenceTest`: crash between temp write and commit keeps old file; 30 rounds x 400 racing saves always end on the newest. The old design reordered writes in 30/30 rounds. Keystore failure / power loss **NOT TESTED**. |
| C8 lifecycle | **Partly** | Tile and service logic reviewed. **Open:** after the app process is killed while the VPN stays up, the UI may show "Connected" without the server/cipher (not reconstructed). Not tested on a device. |
| C9 UX truthfulness | **Improved** | Three distinct failure reasons; picker states (loading / unavailable / stale / empty); chosen-but-empty country stays visible; screen-reader selectable rows. No TalkBack run. |
| C10 documentation | **Updated** | README, docs/. |

### D. CI and release
| Item | Status |
|---|---|
| D1 tests fail when behaviour fails | **Fixed (new)**: emulator exit 0/1/3 = PASS/FAIL/INCONCLUSIVE; the job is no longer `continue-on-error`; the release needs it and prints the verdict in its notes. INCONCLUSIVE is a warning, never success. Both PASS and INCONCLUSIVE have now been produced by real runs; a FAIL verdict path has not been exercised on the live job (the script's hard-failure branches were exercised only by the onboarding bug in an earlier run). |
| D2 supply chain | **Mitigated**: least-privilege permissions, secret-only signing, versioned releases with checksum and attestation. **Open:** action SHA pinning; branch protection / protected environment (owner). |
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
| 19 (`d06b27a`) | reported green by the old script | Could not find the onboarding button; no connection was tested. |
| 21 (`af4d543`) | **INCONCLUSIVE**, 6 assertions passed, 0 failed | Both modes shown, picker opens, list loaded, per-mode scope, persistence across process death, honest failure naming the country. Inconclusive: the single relay in India did not answer within 32 s. |
| 22 (`9334d79`) | **PASS** (9 assertions, 0 failed, 0 inconclusive) | The above, plus: Quick Connect connected in 25 s, the connected server was in the chosen country (Austria), an explicit disconnect stayed down for 20 s, and Best Connect finished with a named winner. (This run's overall CI status was still red because of the probe-test harness bug fixed in `28193da`; the emulator job itself passed.) |
| 23 (`28193da`) / 24 (`a8ed64d`) | *pending when this was written* | First runs with the fixed probe step and the "largest country, then Any country" emulator script. Check the Actions tab and the `emulator-results` branch. |

One PASS and one INCONCLUSIVE out of two runs with real assertions is **not** a reliability figure; live volunteers vary from run to run.

## 5. Test matrix
* Engine unit tests (JVM): **129**, all passing locally and in CI. New this session: persistence 7, pool/failure reasons 8+5, record summary 7, tls-auth probe 6.
* Real OpenVPN in CI: relay-identity 7/7 (OpenVPN 2.7.0, runs 19-22). tls-auth probe: 4/4 locally on 2.6.19; its first CI run failed for a harness reason (the step ran Gradle as a different user than the previous root-owned step, and hid the error); fixed in `28193da`, CI result pending.
* Live OpenVPN against VPN Gate (workflow `vpngate-check`): see section 3.
* Emulator UI assertions: section 4.
* Documentation: README, PLAN.md (status table and dated notes on every superseded passage), SECURITY, DIRECTORY, AUDIT and RELEASE_SIGNING were updated to match; the plan's original text is kept and annotated.
* Not covered: country picker with zero or stale directory in the UI (engine state logic is unit-tested, the screens are not), worldwide search, Quick Settings tile, notification actions, process recreation with the VPN up, network switching, screen-off, Doze, Always-on / lockdown, split tunnelling, IPv6/DNS leaks, updates, TalkBack.

## 6. Physical-device checklist (all NOT TESTED)
Wi-Fi and cellular; switch networks mid-session; screen off for 30+ minutes; background worldwide search and its notification; Always-on with and without "Block connections without VPN" (watch what happens during Best Connect candidate switching); split tunnelling; IPv6/DNS leak test while connected and while reconnecting; kill the app from recents while connected; reboot; Quick Settings tile; install over an older build.

## 7. Deferred, by priority
1. Reconstruct connection details after process death (C8), then device tests.
2. Pin Actions by SHA; protect the default branch and gate the signing secrets behind an approval environment.
3. Review the exported-component list printed by CI; remove anything the app does not need.
4. tls-crypt-aware probe (no live relay uses it today, so low value).
5. Dependency vulnerability scan (needs network access to advisory databases).
6. In-app licence notices; mirror of upstream sources for GPL.
7. Backlog E.
