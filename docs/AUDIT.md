# Dependency and licence audit (B10)

## What could and could not be done
**Not possible from the environment this was written in:** querying vulnerability databases. OSV (`api.osv.dev`), openvpn.net's
security-advisories page, openssl-library.org's vulnerability list and the GitHub advisories API were all unreachable or not
permitted. **So no claim of "no known vulnerabilities" is made.** Nothing below says a package is vulnerable merely because it is old.
Run these yourself (a few minutes) and attach the results to the release:
```
# Android/Gradle dependencies
osv-scanner scan --lockfile build-tree/gradle.lockfile   # or: gradle dependencyCheckAnalyze (OWASP Dependency-Check)
# OpenVPN and OpenSSL: compare the versions below with
#   https://community.openvpn.net/openvpn/wiki/SecurityAnnouncements   and   https://openssl-library.org/news/vulnerabilities/
# ics-openvpn: https://github.com/schwabe/ics-openvpn/security/advisories   (SECURITY.md says only the latest version is supported)
```

## Exactly what is shipped (from primary sources)
| Component | Version / pin | Source of the fact |
|---|---|---|
| ics-openvpn (OpenVPN for Android) | commit `660901ce320539aec476bd04bd1999c396d5e778`, 2026-11-03 "Do not enable debug in OpenVPN 2.x" | `tools/ICS_OPENVPN_REF`, cloned and read |
| OpenVPN (native, 2.x flavour) | submodule `c29ee42e48df0de5993f7b256829196e67e76006` (2026-10-25); runtime banner `OpenVPN 2.7-icsopenvpn [git:c29ee42]`; `version.m4` says 2.8_git | CI emulator log; cloned and read |
| OpenSSL | runtime banner `OpenSSL 4.1.0 23 Oct 2026`; submodule `b280e0c0` | CI emulator log; ics `.gitmodules` |
| LZO / LZ4 | LZO 2.10 (vendored), LZ4 submodule `5ff83968` | CI log; ics tree |
| Kotlin / coroutines | 2.4.10 / 1.11.0 | `engine/build.gradle.kts`, `tools/extra.gradle.kts`, ics `libs.versions.toml` |
| Compose BOM / Activity Compose | 2026.09.00 / 1.13.0 | `tools/extra.gradle.kts` |
| OkHttp, AppCompat, Material, Core | 5.5.0, 1.8.0, 1.14.0, 1.19.0 | ics `libs.versions.toml` (upstream's choice) |
| Android Gradle plugin / NDK | upstream's; CI installs NDK 30.0.16248370 | ics build files, `tools/extra.gradle.kts` |
**CI toolchain observed on 2026-11-05:** Ubuntu 26.04 runners; the distro's OpenVPN 2.7.0 with OpenSSL 3.5.5 is used for the real-OpenVPN tests (the embedded engine is a different build, see above). GitHub Actions are pinned by version tag (`@v4`), **not by commit SHA**: a moved tag would change what runs. SHAs were not pinned because they could not be resolved and checked from the authoring environment; do it before public distribution (Dependabot can keep them current).

**Exported components.** Upstream's manifest is merged into the app, so the attack surface cannot be read from this repository. The CI step "Inspect APK" now prints every component of the final merged manifest that other apps can reach, with its guarding permission (`tools/manifest-exports.py`). Its first output has not been reviewed yet; any component the app does not need should be removed or guarded.

Observation: the engine is built from the **current upstream tip at the pin time** (two days old), which is the support policy
upstream states ("only the latest version is supported"). The pin is by commit, so it does not drift silently; updating it is a
deliberate edit of `tools/ICS_OPENVPN_REF`. There is no Gradle dependency locking in this repository's own overlay; adding
`dependencyLocking` and a scheduled scan job is recommended.

## Licensing
* Upstream ics-openvpn: **GPL v2** with additional terms (`doc/LICENSE.txt`): any use of the `de.blinkt.openvpn` classes creates a
  derivative work; a special exception permits linking with OpenSSL and with Apache-2.0 libraries. This app overlays that module, so it
  is a derivative work and is distributed under **GPL v2** (`LICENSE`). OpenVPN carries its own OpenSSL linking exception (`COPYRIGHT.GPL`).
* LZO is GPL; LZ4 is BSD-2; OpenSSL 3+/4 is Apache-2.0; Compose, AndroidX, Kotlin, coroutines, OkHttp, Material are Apache-2.0.
  Apache-2.0 code is covered by upstream's exception for linking with Apache2 libraries. I am not a lawyer; this is a reading of
  the license files, not legal advice.
* **Source obligation.** The APK's corresponding source is this repository at the commit named in the release notes **plus** the
  upstream ics-openvpn commit and its submodules at the SHAs above. The release notes now state both. Keep the repository public and
  do not rewrite or delete those upstream pins. An in-app "About" already links the source and names upstream.
* **Not yet done:** an in-app third-party notices screen listing each component's license text (recommended before any store
  distribution), and mirroring the upstream sources you ship against (GitHub can remove or rename upstream repositories; GPL
  asks you to be able to supply the source).

## Other third parties
* VPN Gate data is (c) the VPN Gate Academic Experiment; the CSV is re-read, not redistributed.
* Cloudflare's public speed-test endpoints are used without an agreement; see DIRECTORY.md.
