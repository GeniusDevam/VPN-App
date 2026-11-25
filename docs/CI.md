# CI: how it behaves, open findings, and optimisation proposals

Written 25 November 2026 from the evidence of build #47 (commit `f95296a` on `main`) and build #48 (tag `v1.0.0`, same commit).
**Nothing in the "proposals" section is implemented.** Every proposal that touches the release path needs the maintainer's approval.

## 1. Structure (`.github/workflows/ci.yml`)

| Job | Runs on | Needs | Gates a release? | Duration in #48 |
|---|---|---|---|---|
| `workflow-security` | ubuntu | - | yes | 5 s |
| `engine` (unit tests, benchmarks) | ubuntu | - | yes | 1 min 40 s |
| `cert-policy` (real OpenVPN) | ubuntu | - | yes | 2 min |
| `lint` (NewApi, Android 6.0) | ubuntu | - | yes | 3 min |
| `apk` (all ABIs, universal + splits, re-sign dry run) | ubuntu | - | yes | 8 min 16 s (Gradle build step alone 7 min 22 s) |
| `keygen` x3 (key tools, disposable keys) | ubuntu, macOS, Windows | - | **no** | 20-40 s each, plus runner queue time |
| `emulator` (Android 16 phone, live VPN Gate) | ubuntu | `apk` | yes | 10 min 30 s |
| `compat` x7 (device matrix, live VPN Gate) | ubuntu | `apk` | **no** (`continue-on-error`) | 8-31 min |
| `compat-report` | ubuntu | `compat` | no | 10 s |
| `release-gate` (tags only) | ubuntu | workflow-security, engine, cert-policy, apk, lint, emulator | - | 7 s |
| `sign` (tags only, environment `release`, manual approval) | ubuntu | `release-gate` | - | 27 s after approval |
| `publish` (tags only) | ubuntu | sign, release-gate, emulator | - | 26 s |

Critical path of an ordinary push: `apk` then `emulator`, about 19 minutes. The slowest `compat` job (Android TV) runs longer, but nothing
waits for it.

## 2. Finding: build #48 is "failure" because the macOS `keygen` job never ran

**Evidence** (GitHub API, job 139383390863): conclusion `cancelled`; **no runner was ever assigned** and **no step ran**; created and
"started" at 17:25:44, completed at 17:40:44, **exactly 15 minutes later**, which equals the job's `timeout-minutes: 15`. Nobody
cancelled it by hand and there is no `concurrency` setting that could have. In build #47 the same job waited about 5 minutes for a
macOS runner before it started, then passed (44/44 checks). The Linux and Windows `keygen` jobs passed in #48.

**Conclusion:** the job was waiting in the queue for a hosted macOS runner and was cancelled when its 15-minute limit ran out (the
timestamps show the limit counting from the moment it was queued). It is a runner-availability failure, not a test failure and not a
problem with the code.

**Why the release was still published:** `release-gate` needs `workflow-security`, `engine`, `cert-policy`, `apk`, `lint` and
`emulator` only. `keygen` and `compat` are not in that list, so the release path did not wait for them, and the run's overall
conclusion became "failure" afterwards because one job ended cancelled.

**Is that a gating weakness?** Not for the released files: `keygen` tests the key-generation and upload scripts that the maintainer runs
on their own computer; the release path never executes them (it re-signs with `tools/resign-apk.sh`, whose dry run is part of the
gating `apk` job). The real weaknesses are smaller: (a) a red run on a release tag is noise that could hide a genuine failure, and
(b) the release run itself does not require every check to pass; it relies on the procedure "tag only a commit whose `main` run was
green" (build #47 was fully green, so this release was fine), which nothing enforces.

**Options (not implemented; release-path changes need approval):**
* **A. Do not run `keygen` on tag pushes.** The tag's commit must already be on `main`, whose own push ran `keygen`. Removes the
  macOS queue from release runs; costs nothing in coverage.
* **B. Allow for queue time:** raise `timeout-minutes` of the macOS `keygen` job (for example to 45).
* **C. Enforce "fully green on main" in `release-gate`:** query the Actions API (read-only) for the push run of the tagged commit on
  the default branch and require conclusion `success`. This turns the documented procedure into a rule.
* **Recommended:** A and C together (B is harmless but only treats the symptom).

## 3. Finding: build #48's Android TV result was INCONCLUSIVE, not a pass

The `compat (Android TV 11)` job shows a green tick because an INCONCLUSIVE verdict deliberately does not fail the job, but its
verdict (published on the `compat-results` branch) is **INCONCLUSIVE: "Best Connect did not complete (timeout)"**, after 26 minutes.
All other devices in #48 were PASS, including the small phone at 200% font.

**What the app log shows:** Best Connect probed 97 servers, speed-tested two (54 and 53 Mbit/s), connected to the third
(`public-vpn-53`, tunnel up at 17:40:05), and then **logged nothing more for 25 minutes**, while the screen still showed the run in
progress ("Connect to leader now" / "Cancel"); the app was alive (its notification kept updating). 118 log lines in that window were
removed by the redactor (lines that can carry credentials, such as OpenVPN's `auth-token` lines, which appear when a tunnel
renegotiates), so the exact sequence is not visible.

**Suspected cause (not reproduced; app code was not changed):** a single speed test has no wall-clock limit. The download part is
bounded (5 s read timeouts, time caps), but the upload part writes to an `HttpURLConnection` output stream, and Java applies no
timeout to writes: if the tunnel stalls mid-upload (for example while OpenVPN reconnects to a volunteer that dropped it), the write can
block until the operating system abandons the TCP connection, which commonly takes 15 to 30 minutes. `BestConnectEngine` checks
"finish now" only between servers, and blocking I/O does not react to coroutine cancellation, so "Connect to leader now" and possibly
"Cancel" may not take effect until the write returns.

**Proposed fix (needs approval, then a new release):** give each speed test a deadline (for example 60 s) enforced by a watchdog that
closes the connection, which unblocks a stuck write; treat a timed-out test as a failed candidate; add an engine unit test with a
tester that blocks. Until then: physical-device test C7 in `docs/MANUAL_QA.md` checks for this.

## 4. Finding: releases are not immutable

The release API reports `"immutable": false` for v1.0.0. Earlier documentation called the published release "immutable"; that was
wrong and has been corrected. With immutability off, an account with write access could replace release assets after publication
(the published checksums and the attestation would then no longer match, which users can detect, but only if they check).
Recommended: *Settings > General > Releases > Enable release immutability*. It applies to releases published after it is enabled.

## 5. Optimisation proposals (not implemented)

Estimates below are estimates; measure before and after (for example with `./gradlew --profile`). Hard rule for all of them: **release
(tag) builds keep building from scratch, without caches, and keep the full test set.**

| # | Proposal | Expected benefit | Trade-offs and risks | Affects release security or coverage? |
|---|---|---|---|---|
| 1 | `concurrency` group per branch with `cancel-in-progress` for branch pushes only (never for tags) | Runner time: a quick second push stops the first run's 20+ minutes of jobs | None for the final commit; intermediate commits lose their result | No |
| 2 | Skip documentation-only pushes on branches (`paths-ignore` for `**/*.md`, `docs/**`; tags unaffected) | About 20 minutes and ~15 jobs saved per docs-only push | If status checks become required, skipped runs leave PRs waiting (use a tiny always-running "docs only" job then). Conflicts with option C of section 2 for docs-only commits on `main` (tag only commits that had a full run) | No (tags still run everything) |
| 3 | Gradle dependency and build cache in `apk` and `lint` (`gradle/actions/setup-gradle`, already used by `engine`); drop `--no-daemon` | Est. 1-2 min per build | Cache entries written by branch runs are scoped to that branch; a tag run could read `main`'s cache, so **disable cache reads for tag builds** (`cache-disabled` on tags) | No, if disabled on tags |
| 4 | Cache the native build (OpenSSL, OpenVPN and helpers for 4 ABIs, most of the 7 min 22 s), keyed on the upstream pin, NDK version, `tools/extra.gradle.kts` and `tools/prepare.sh` | Est. 4-6 min on branch builds while the upstream pin is unchanged | Stale objects if the key misses an input (keep the key conservative); same tag rule as 3 | No, if disabled on tags |
| 5 | Light checks on feature branches, full matrix on `main`, tags and a nightly schedule: run `compat` (7 emulators) only there | 7 emulator jobs (8-31 min each) saved per feature-branch push | Device-specific regressions are found at merge to `main` instead of on the branch | No (main and tags keep everything) |
| 6 | Cache the Android 16 emulator image and a clean boot snapshot for the gating `emulator` job only | Est. 1-2 min on the critical path | GitHub's 10 GB cache limit rules out caching all eight images; a snapshot must be taken before the app is installed | No |
| 7 | Run `keygen` only when `tools/*key*` or the workflow changes (plus on `main`) | Removes the macOS queue from most runs | Needs a change-detection step (a small `git diff` check; avoid adding third-party actions to the supply chain) | No |
| 8 | Reuse `main`'s APK for a tag instead of rebuilding | ~8 min per release | **Not recommended:** the release would no longer be "the APK built and tested in this run" | **Yes, weakens the release guarantee** |

With 1, 3, 4 and 5 on feature branches, the critical path would likely fall from about 19 to roughly 12-14 minutes (estimate), and
total runner time per push by far more because the compatibility matrix would not run. Release runs would stay as they are.
