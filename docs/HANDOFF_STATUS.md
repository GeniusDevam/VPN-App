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
| "Unused permission: safe to remove" (my own, this session) | `RECEIVE_BOOT_COMPLETED` looked unused, but upstream's persisted keep-alive job needs it. Removing it crashed the VPN process on every connect (emulator run 27 FAIL, `IllegalArgumentException: Requested job cannot be persisted without holding RECEIVE_BOOT_COMPLETED`). Restored; the emulator test is what caught it. |
| "Emulator FAIL means the app broke" | Two FAILs were my own test steps: the onboarding button below the fold (run 19), and the app-picker button below the fold, which left the test stranded on Settings (run 30). Both now scroll like a user and return Home. A FAIL must be read, not assumed. |

## 2. Findings matrix
Legend: **Fixed** = changed and tested; **Mitigated** = reduced, not eliminated; **Open**; **Unverified** = no evidence either way.

### A. Requested features
| Item | Status | Evidence / caveat |
|---|---|---|
| A1 directory coverage | **Best compliant effort, no measured gain.** One official HTTPS endpoint (`/api/iphone/`), 72 h union pool, polite refresh, diagnostics, stale-cache fallback. | Live: **96 rows, 90 TCP + 6 UDP, all AES-128-CBC** in one download; pooled view in the emulator showed **98 usable** (union added 2). The "before" figure was about 100. **No increase.** The list is deliberately partial; no mirror or hidden endpoint was used or found. Total advertised relays (thousands) are mostly SoftEther/L2TP-only and not OpenVPN-usable through this API. |
| A2 fastest in a chosen country | **Built; verified in the real UI and with a live connection.** | Emulator runs 21-22: picker opened with real counts (Japan 53, South Korea 31, ...), the choice applied to Quick only (Best stayed on Any) and persisted across process death. **Run 22: Quick Connect inside the chosen country (Austria) connected in 25 s, and run 23 inside Japan in 26 s; the status card showed that country both times.** Run 21: India (1 server) failed honestly in 32 s with a message naming India. Scope is enforced in `PoolSelection`; tests prove no other country is ever probed or connected. Note "fastest" here is a latency estimate for Quick and a measured throughput for Best; most countries have 1 server, so there is nothing to rank. |
| A3 optional global search | **Built and engine-tested; not exercised live.** | `GlobalSearchEngine` tests (batching, budgets, pause/finish/cancel, resume). Consent dialog, foreground service, metered/battery guards exist. No emulator or device run of the worldwide search; background survival under Doze/process kill is **NOT TESTED**. |
| A4 history + 7-day absence | **Fixed.** | `LedgerAndDirectoryTest` (absence counted only on usable listings, 36 h cap per listing, failed fetch never advances it, old measurements never deleted). New per-server history lines (`RecordSummary`, 7 tests). Encrypted at rest; clear-history in Settings. |

### B. Security
| Item | Status | Evidence / caveat |
|---|---|---|
| B1 signing key | **Open for the owner; repository side done and verified in CI** | The release key is readable only by the `sign` job (protected environment `release`, tag releases only, read-only token); every other build uses a throw-away key and no other job references a secret (checked on every build; 13 mutation tests re-create the leaks and require rejection). Releases re-sign the already-tested APK (`tools/resign-apk.sh`, exercised on every build with a throw-away key). The certificate is pinned and the leaked key is refused by fingerprint. **Not run with a real key yet.** Owner: GitHub environment setup, delete the old public `latest` release, create the key (docs/RELEASE_SIGNING.md). Old key remains in git history. |
| B2 server identity | **Fixed, live-checked** | Real OpenVPN: genuine cert connects; wrong name / wrong EKU / untrusted CA rejected; control without policy accepts the wrong name (CI, 7/7, OpenVPN 2.7.0). **Live relays: 15/24 connected with the policy and 15/24 without; every relay that reached TLS presented `CN=opengw.net`.** Residual: authenticates VPN Gate's software, not an individual volunteer. |
| B3 HTTP fallback | **Fixed** | HTTPS only; cleartext disabled app-wide. |
| B4 leak protection | **Not guaranteed by an app; documented.** | `persist-tun`, IPv6 blocking, forced DNS. Always-on + "Block connections without VPN" were **not tested** (no device). Probe sockets bypass the tunnel by design. |
| B5 disconnect vs restore | **Fixed** | `RunCoordinator` + epoch guard; deterministic and 500-seed tests. This session closed two remaining paths (Quick watchdog timeout, generic exception). **Runs 22 and 23 (emulator): after an explicit Disconnect the VPN stayed down for 20 s.** |
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
| 19 (`d06b27a`) | reported green by the old script | Could not find the onboarding button; no connection was tested. |
| 21 (`af4d543`) | **INCONCLUSIVE**, 6 assertions passed, 0 failed | Both modes shown, picker opens, list loaded, per-mode scope, persistence across process death, honest failure naming the country. Inconclusive: the single relay in India did not answer within 32 s. |
| 22 (`9334d79`) | **PASS** (9 assertions, 0 failed, 0 inconclusive) | The above, plus: Quick Connect connected in 25 s, the connected server was in the chosen country (Austria), an explicit disconnect stayed down for 20 s, and Best Connect finished with a named winner. (This run's overall CI status was still red because of the probe-test harness bug fixed in `28193da`; the emulator job itself passed.) |
| 23 (`28193da`) | **PASS** (9 assertions, 0 failed, 0 inconclusive); the whole CI run was green | First run with the fixed probe step and the "largest country" script. Quick Connect inside **Japan** (53 servers) connected in 26 s and the status card showed Japan; disconnect stayed down for 20 s; Best Connect named a winner. |
| 24, 25 (docs-only commits) | not read | Same code as run 23; their emulator results were not inspected. |
| 27 (`a06079d`, label "run 27") | **FAIL** (genuine app bug) | VPN process crashed on connect after I removed `RECEIVE_BOOT_COMPLETED` (see corrections). |
| 30 (`c40531a`) | **FAIL** (harness bug, no crash) | The new app-picker step did not scroll and stranded the test on Settings. |
| 31 (`0f15fca`) | **PASS** (10 assertions, 0 failed); the whole CI run was green | Includes: app picker lists 18 apps without `QUERY_ALL_PACKAGES`; Quick Connect inside **Japan** in 22 s; disconnect stayed down 20 s; Best Connect named a winner; no crash. First PASS on the cleaned manifest (5 reachable components). |

(Run numbers in the `emulator-results` branch are GitHub's run numbers, so they differ from the labels I used in conversation.)

Two PASS and one INCONCLUSIVE out of three runs with real assertions is **not** a reliability figure; live volunteers vary from run to run, and the one INCONCLUSIVE used a country with a single relay.

## 5. Test matrix
* Engine unit tests (JVM): **129**, all passing locally and in CI. New this session: persistence 7, pool/failure reasons 8+5, record summary 7, tls-auth probe 6.
* Real OpenVPN in CI: relay-identity 7/7 (OpenVPN 2.7.0, runs 19-23). tls-auth probe: 4/4 locally on 2.6.19 and **passed in CI (run 23, OpenVPN 2.7.0)** after its first CI run failed for a harness reason (the step ran Gradle as a different user than the previous root-owned step, and hid the error; fixed in `28193da`).
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
