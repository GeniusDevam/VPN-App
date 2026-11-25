# VPN App

A free VPN app for Android with two ways to connect, each with its own country choice:

- **Quick Connect** finds a fast server and connects, in 35 seconds or less (usually a few seconds). It compares response times of a sample, so the pick is an estimate.
- **Best Connect** takes its time: it probes every server in the chosen country, speed-tests the most promising ones through real tunnels, re-tests the leaders, and connects to the fastest it measured.
- **Worldwide search** (optional, you start it explicitly) samples every country and speed-tests a shortlist. It can take up to 45 minutes and uses data and battery, and it says how much of the network it actually covered.

Servers are the free, volunteer-run relays of the [VPN Gate](https://www.vpngate.net) project (University of Tsukuba, Japan).

## Install on your phone

**Current release: [v1.0.0](https://github.com/GeniusDevam/VPN-App/releases/tag/v1.0.0)** (25 November 2026), the first release signed
with the project's permanent signing key.

1. On your Android phone, open this link: **[Download VPN-App.apk](https://github.com/GeniusDevam/VPN-App/releases/latest/download/VPN-App.apk)**
   (always the newest release; this exact version: [v1.0.0/VPN-App.apk](https://github.com/GeniusDevam/VPN-App/releases/download/v1.0.0/VPN-App.apk)).
2. When the download finishes, tap **Open**.
3. If Android asks, allow your browser to **install unknown apps** (one-time switch), go back, and tap **Install**.
4. Open **VPN App**, read the short privacy notice, and tap **Quick Connect**. Android shows a one-time "Connection request" for VPN access: tap **OK**.

Updates: open the same link again and install over the top. Your settings are kept, because every release from v1.0.0 on is signed
with the same key.

> **Installed a build from before v1.0.0? Uninstall it first.** Earlier builds (including the former `latest` release, build #17, now
> deleted) were signed with a key that was published in this repository, so anyone could have made a look-alike. Android will not
> install v1.0.0 over them ("App not installed" / `INSTALL_FAILED_UPDATE_INCOMPATIBLE`), and it should not: keep nothing signed with
> the old key. Uninstalling deletes that build's settings and server history; they cannot be carried over.

**Works on Android 6.0 or newer** (about 99% of Android devices in use), on every kind of processor (64- and 32-bit ARM, 64- and
32-bit x86) and on phones, small phones with large text, tablets, foldables, Chromebooks and Android TV. `VPN-App.apk` is the one
to download: it contains all four processor types. Each release also lists four smaller files, one per processor type
(`VPN-App-arm64-v8a.apk` fits nearly every phone from the last eight years). Which devices were actually tested, and how: see
[Device support](#device-support).

## Verify the download (recommended)

| File (v1.0.0) | SHA-256 |
|---|---|
| `VPN-App.apk` | `57c68ef88de49d793033da7ecf037dec85e5c7de1d5858a33aab801087ee2649` |
| `VPN-App-arm64-v8a.apk` | `ecbc197e88be1f0b7524f241aa8faf37e0fed6a38a097cb2961183affb57d770` |
| `VPN-App-armeabi-v7a.apk` | `af003044a864f68dc902116fcbb9c2278f2f1dbd375aba882c0427c3d5daff2e` |
| `VPN-App-x86.apk` | `d5464526faf5c9d7db8689f253b134ebdfb075ead996e14303508b8bf8e448f6` |
| `VPN-App-x86_64.apk` | `75043559bff377eccacb80fcbd0ce36f6a83393bbb49bad5d60025323a8c25a1` |

Every release also lists these in `SHA256SUMS` and in its release notes. Three independent checks, on a computer:

1. **The file is the published one:** `sha256sum VPN-App.apk` (Windows PowerShell: `Get-FileHash VPN-App.apk -Algorithm SHA256`)
   must print the value above.
2. **It is signed by this project's key:** `apksigner verify --print-certs VPN-App.apk` (Android SDK build-tools) must report
   `Signer #1 certificate SHA-256 digest: 0838262d4e4d35da8527a5555c90552221b07db3804d17966e273f2fe1e5998a`. With only Java installed,
   `keytool -printcert -jarfile VPN-App.apk` shows the same certificate as `SHA256: 08:38:26:2D:...:99:8A`. Any other certificate:
   **do not install**.
3. **It was built by this repository's release workflow:** `gh attestation verify VPN-App.apk -R GeniusDevam/VPN-App` checks the
   build-provenance attestation (which workflow run and commit produced the file). It proves origin, not that the code is safe.

## Privacy: what encryption does and doesn't do

- Your traffic to the server is **encrypted** (modern ciphers only; weak ones are refused). Wi-Fi owners, your carrier and anyone on the network cannot read it.
- Everything the app stores on your phone (server list, settings, history) is **encrypted** with a key in the Android Keystore.
- The app has **no analytics, ads or trackers**.
- **But** each free server is run by an anonymous volunteer who can see which sites and addresses you connect to, and who can read any non-HTTPS traffic. VPN Gate states that it keeps connection logs for at least three months. Don't log in to banking or other important accounts through a free server, and use the Settings option to make such apps bypass the VPN.

## Known risks (read before relying on it)

- **Not yet tested on a real phone.** Physical-device testing has started ([docs/MANUAL_QA.md](docs/MANUAL_QA.md)); until it is done, treat real-device behaviour as unverified. Every build is unit-tested (including the 35-second guarantee) and run in CI emulators against live VPN Gate servers: the Android 16 phone that gates releases, plus Android 6.0, 7.0, 8.0, a small phone at 200% font, a tablet, a foldable and Android TV (see [Device support](#device-support)). The emulator job reports **PASS, FAIL or INCONCLUSIVE** (the last means the live volunteer servers did not allow a verdict, and is never shown as success). Two of three recent runs passed fully: country picker, a Quick Connect inside the chosen country (25-26 s, in Austria and Japan), a disconnect that stayed down, and a Best Connect with a named winner. One run was INCONCLUSIVE because a country with a single volunteer relay may simply not answer. Real-world behaviour on mobile data, when switching networks, over long sessions, with Always-on lockdown, for the worldwide search and after the app process is killed is **not tested**. Full matrix: [docs/STATUS.md](docs/STATUS.md).
- **"Strong only" finds no servers today.** All 96 relays in the latest live download declare AES-128-CBC; the setting exists for when that changes. The app only calls a cipher "verified" when the engine reported it; otherwise it says "not verified".
- **No kill switch by itself.** The app keeps its interface up while reconnecting, but only Android's *Always-on VPN* plus *Block connections without VPN* guarantees nothing leaks; the app cannot tell whether you enabled them. Details in docs/SECURITY.md.
- **Relay identity.** Servers must present a certificate for `opengw.net` from a trusted public CA. This stops impersonation by third parties and tampered lists; it does not make an individual volunteer trustworthy. Relays that don't meet it are skipped and counted in the Server list card.
- **The server list is partial by VPN Gate's design.** The app shows how many usable servers it has, not how many exist. Measured on 2026-11-05: one download holds about 96 OpenVPN servers (90 TCP, 6 UDP), almost all in Japan (53) and South Korea (31); most other countries have one. "Fastest in a country" is therefore only meaningful for a few countries.
- **Free servers are unreliable.** VPN Gate relays are volunteers' home PCs: they come and go hourly, many are slow, and some countries have few or none at a given moment. Quick Connect fails over to the next server automatically; if a country has no working server the app says so instead of silently picking another country.
- **Privacy is limited by the servers**, not by the app (see above).
- **Speed tests** use Cloudflare's public speed-test endpoints and use data (about 30 MB per server tested on Wi-Fi; far less on mobile data; figures are estimates). Their availability and terms can change.
- **Not included yet:** home-screen widget, app shortcuts, automatic failover while connected, Always-on action, Tor mode, VPN Gate mirror support. See `PLAN.md`.
- **Android layer is verified by CI only** (the development machine has no Android SDK). The engine has 129 unit tests plus real-OpenVPN tests for the certificate policy and the tls-auth probe. A build is only meaningful if its Actions run is green.

## Device support

| | |
|---|---|
| Android versions | 6.0 (API 23) to 16 (API 36); the app targets the newest. Lint fails the build if code calls an API newer than 6.0 without a version check. On 6.0 and 7.0, which predate the Let's Encrypt roots, the app adds the two roots itself (pinned by fingerprint) for its own HTTPS requests. |
| Processors | arm64-v8a, armeabi-v7a, x86_64, x86. One build produces the universal APK and one APK per processor type; all are re-signed and published together. |
| Screens | Bottom navigation on phones; a side navigation rail and centred, width-limited content at 600 dp and wider (tablets, unfolded foldables, Chromebook windows, TV). Rotating, folding and resizing keep the current tab and open dialogs. Text up to 200% is supported on 320 dp phones. |
| TV | Appears in the TV launcher (leanback banner); touchscreen not required. Buttons, chips and list rows are standard focusable controls, but D-pad navigation itself has not been tested (the TV test taps the screen). |

Tested in CI emulators against live VPN Gate servers, on every build: Android 6.0, 7.0, 8.0 phones, a 320 dp Android 10 phone at 200%
font, an Android 13 tablet, an Android 16 foldable, Android TV 11 (32-bit x86) and the Android 16 phone that gates releases.
Results per device, with screenshots: the `compat-results` branch. Speed and memory figures: [docs/PERFORMANCE.md](docs/PERFORMANCE.md).
Emulators are not phones; see "Known risks".

## How it works

| Piece | What it does |
|---|---|
| `engine/` | Pure-Kotlin logic with unit tests: VPN Gate list parser, config sanitizer and relay-identity policy (volunteer configs are untrusted and rebuilt from an allowlist), the permanent server ledger, crash-safe ordered persistence, latency probes (including an HMAC-signed probe for tls-auth relays), the candidate-pool and failure-reason logic, the Quick, Best and worldwide search engines, the command coordinator. Randomized tests prove Quick Connect finishes within 35 s and that a disconnect can never be undone by older cleanup. |
| `app/overlay/` | The Android app (Jetpack Compose UI, encrypted storage, tunnel control), overlaid onto the [OpenVPN for Android](https://github.com/schwabe/ics-openvpn) engine at the commit pinned in `tools/ICS_OPENVPN_REF`. |
| `tools/prepare.sh` | Clones the pinned engine, overlays the app, and appends the build configuration. |
| `.github/workflows/ci.yml` | Runs the engine tests, real-OpenVPN tests (certificate policy, tls-auth probe), runs Lint's Android 6.0 API check, builds the universal and per-processor APKs, runs the Android 16 emulator test (PASS / FAIL / INCONCLUSIVE; FAIL blocks a release) and the device matrix (published on the `compat-results` branch) and, only if the release key secrets exist, publishes a versioned release with checksum and provenance. |
| `tools/` | `emulator-test.sh` (live UI assertions), `cert-policy-test.sh` and `probe-test.sh` (real OpenVPN), `vpngate-check.py` (live relays with and without the identity policy), `manifest-exports.py` (exported components of the built APK), release-key and result-redaction helpers. |
| `PLAN.md` | The original design plan. |

Build locally: `tools/prepare.sh && cd build-tree && ./gradlew :main:assembleSkeletonOvpn2Release` (needs the Android SDK and NDK).

## Signing key

| | Certificate SHA-256 | Status |
|---|---|---|
| **Release key** (since v1.0.0): `CN=VPN App release, O=GeniusDevam`, RSA 4096, valid until 2051 | `0838262d4e4d35da8527a5555c90552221b07db3804d17966e273f2fe1e5998a`<br>`08:38:26:2D:4E:4D:35:DA:85:27:A5:55:5C:90:55:22:21:B0:7D:B3:80:4D:17:96:6E:27:3F:2F:E1:E5:99:8A` | **Trusted.** Every official release is signed with it. |
| Old key, formerly committed as `tools/release.jks` | `e73acd75265ca67ef3fb295538c49a8a81be0b122bacf72006f46691fe0efae4` (`E7:3A:CD:75:...:FA:E4`) | **Compromised. Never trust an APK signed with it.** CI refuses to sign with it. |

Release builds are signed only by a protected CI job: the key lives only as secrets of the protected GitHub environment `release`
(required reviewer, version tags only), and ordinary builds, on any branch, are signed with a throw-away key and cannot read it. The
released APK is the one that was built and tested in the same run, re-signed; the release job refuses any certificate other than the
pinned one above. Design, limits and recovery procedures: [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md).

## More documentation
[docs/STATUS.md](docs/STATUS.md) (project status and handoff: what is done, verified and pending), [docs/MANUAL_QA.md](docs/MANUAL_QA.md) (physical-device test plan), [docs/CI.md](docs/CI.md) (CI behaviour and optimisation proposals), [docs/FUTURE.md](docs/FUTURE.md) (future platform ideas, not planned work), [docs/PERFORMANCE.md](docs/PERFORMANCE.md) (speed, memory and size measurements), [docs/SECURITY.md](docs/SECURITY.md) (what is enforced and what is not, per finding), [docs/DIRECTORY.md](docs/DIRECTORY.md) (server list coverage, history and the 7-day rule, selection, measurement), [docs/AUDIT.md](docs/AUDIT.md) (dependencies and licences).

## License

Because it embeds OpenVPN for Android, this app is licensed under the **GNU GPL v2** (see `LICENSE`). OpenVPN for Android is © Arne Schwabe and contributors. Server list data is © the VPN Gate Academic Experiment. VPN use is restricted in some countries; you are responsible for following local law.
