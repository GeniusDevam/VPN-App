# VPN App

A free VPN app for Android with two ways to connect, each with its own country choice:

- **Quick Connect** finds a fast server and connects, in 35 seconds or less (usually a few seconds). It compares response times of a sample, so the pick is an estimate.
- **Best Connect** takes its time: it probes every server in the chosen country, speed-tests the most promising ones through real tunnels, re-tests the leaders, and connects to the fastest it measured.
- **Worldwide search** (optional, you start it explicitly) samples every country and speed-tests a shortlist. It can take up to 45 minutes and uses data and battery, and it says how much of the network it actually covered.

Servers are the free, volunteer-run relays of the [VPN Gate](https://www.vpngate.net) project (University of Tsukuba, Japan).

## Install on your phone

> **Do not install the build that is currently attached to the `latest` release.** It was signed with a key that was published in this repository, so anyone could have produced a look-alike. A safe release exists only after the owner creates a new signing key and CI publishes the first build signed with it; until then there is nothing safe to download. See [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md).

1. On your Android phone, open this link: **[Download VPN-App.apk](https://github.com/GeniusDevam/VPN-App/releases/latest/download/VPN-App.apk)**
2. When the download finishes, tap **Open**.
3. If Android asks, allow your browser to **install unknown apps** (one-time switch), go back, and tap **Install**.
4. Open **VPN App**, read the short privacy notice, and tap **Quick Connect**. Android shows a one-time "Connection request" for VPN access: tap **OK**.

Updates: open the same link again and install over the top; your settings are kept when the signing key is unchanged.

**Verify before installing.** Each release lists the APK's SHA-256 and the signing certificate's SHA-256 (published below once the owner has created the new release key), and carries a build-provenance attestation: `gh attestation verify VPN-App.apk -R GeniusDevam/VPN-App`. Earlier builds were signed with a key that was published in this repository and must be treated as untrusted; see [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md) to migrate.

**Works on Android 6.0 or newer** (about 99% of Android devices in use), on every kind of processor (64- and 32-bit ARM, 64- and
32-bit x86) and on phones, small phones with large text, tablets, foldables, Chromebooks and Android TV. `VPN-App.apk` is the one
to download: it contains all four processor types. Each release also lists four smaller files, one per processor type
(`VPN-App-arm64-v8a.apk` fits nearly every phone from the last eight years). Which devices were actually tested, and how: see
[Device support](#device-support).

## Privacy: what encryption does and doesn't do

- Your traffic to the server is **encrypted** (modern ciphers only; weak ones are refused). Wi-Fi owners, your carrier and anyone on the network cannot read it.
- Everything the app stores on your phone (server list, settings, history) is **encrypted** with a key in the Android Keystore.
- The app has **no analytics, ads or trackers**.
- **But** each free server is run by an anonymous volunteer who can see which sites and addresses you connect to, and who can read any non-HTTPS traffic. VPN Gate states that it keeps connection logs for at least three months. Don't log in to banking or other important accounts through a free server, and use the Settings option to make such apps bypass the VPN.

## Known risks (read before relying on it)

- **Not tested on a real phone.** The build environment had no device. Every build is unit-tested (including the 35-second guarantee) and run in an Android 16 emulator in CI against live VPN Gate servers. The emulator job reports **PASS, FAIL or INCONCLUSIVE** (the last means the live volunteer servers did not allow a verdict, and is never shown as success). Two of three recent runs passed fully: country picker, a Quick Connect inside the chosen country (25-26 s, in Austria and Japan), a disconnect that stayed down, and a Best Connect with a named winner. One run was INCONCLUSIVE because a country with a single volunteer relay may simply not answer. Real-world behaviour on mobile data, when switching networks, over long sessions, with Always-on lockdown, for the worldwide search and after the app process is killed is **not tested**. Full matrix: [docs/HANDOFF_STATUS.md](docs/HANDOFF_STATUS.md).
- **"Strong only" finds no servers today.** All 96 relays in the latest live download declare AES-128-CBC; the setting exists for when that changes. The app only calls a cipher "verified" when the engine reported it; otherwise it says "not verified".
- **No kill switch by itself.** The app keeps its interface up while reconnecting, but only Android's *Always-on VPN* plus *Block connections without VPN* guarantees nothing leaks; the app cannot tell whether you enabled them. Details in docs/SECURITY.md.
- **Relay identity.** Servers must present a certificate for `opengw.net` from a trusted public CA. This stops impersonation by third parties and tampered lists; it does not make an individual volunteer trustworthy. Relays that don't meet it are skipped and counted in the Server list card.
- **The server list is partial by VPN Gate's design.** The app shows how many usable servers it has, not how many exist. Measured on 2026-11-05: one download holds about 96 OpenVPN servers (90 TCP, 6 UDP), almost all in Japan (53) and South Korea (31); most other countries have one. "Fastest in a country" is therefore only meaningful for a few countries.
- **Free servers are unreliable.** VPN Gate relays are volunteers' home PCs: they come and go hourly, many are slow, and some countries have few or none at a given moment. Quick Connect fails over to the next server automatically; if a country has no working server the app says so instead of silently picking another country.
- **Privacy is limited by the servers**, not by the app (see above).
- **Speed tests** use Cloudflare's public speed-test endpoints and use data (about 30 MB per server tested on Wi-Fi; far less on mobile data; figures are estimates). Their availability and terms can change.
- **Not included yet:** home-screen widget, app shortcuts, automatic failover while connected, Always-on action, Tor mode, VPN Gate mirror support. See `PLAN.md`.
- **Android layer is verified by CI only** (the Android SDK is not available in the authoring sandbox). The engine has 129 unit tests plus real-OpenVPN tests for the certificate policy and the tls-auth probe. One earlier commit did not compile and was reported as working; builds are only meaningful if their Actions run is green.

## Device support

| | |
|---|---|
| Android versions | 6.0 (API 23) to 16 (API 36); the app targets the newest. Lint fails the build if code calls an API newer than 6.0 without a version check. On 6.0 and 7.0, which predate the Let's Encrypt roots, the app adds the two roots itself (pinned by fingerprint) for its own HTTPS requests. |
| Processors | arm64-v8a, armeabi-v7a, x86_64, x86. One build produces the universal APK and one APK per processor type; all are re-signed and published together. |
| Screens | Bottom navigation on phones; a side navigation rail and centred, width-limited content at 600 dp and wider (tablets, unfolded foldables, Chromebook windows, TV). Rotating, folding and resizing keep the current tab and open dialogs. Text up to 200% is supported on 320 dp phones. |
| TV | Appears in the TV launcher (leanback banner); touchscreen not required; works with a D-pad. |

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
| `.github/workflows/ci.yml` | Runs the engine tests, real-OpenVPN tests (certificate policy, tls-auth probe), builds the APK, runs the Android 16 emulator test (PASS / FAIL / INCONCLUSIVE; FAIL blocks a release) and, only if the release key secrets exist, publishes a versioned release with checksum and provenance. |
| `tools/` | `emulator-test.sh` (live UI assertions), `cert-policy-test.sh` and `probe-test.sh` (real OpenVPN), `vpngate-check.py` (live relays with and without the identity policy), `manifest-exports.py` (exported components of the built APK), release-key and result-redaction helpers. |
| `PLAN.md` | The original design plan. |

Build locally: `tools/prepare.sh && cd build-tree && ./gradlew :main:assembleSkeletonOvpn2Release` (needs the Android SDK and NDK).

## Signing key
Release builds are signed only by a protected CI job. The release key lives only as secrets of the protected GitHub environment `release` (required reviewer, version tags only); every ordinary build, on any branch, is signed with a throw-away key and cannot read it. The released APK is the one that was built and tested, re-signed. **The key that used to be committed here (`tools/release.jks`) is public and compromised; ignore any APK signed with it** (fingerprint `E7:3A:CD:75:...:FA:E4`; CI refuses to sign with it). CI publishes nothing until the owner has created the new key (`tools/generate-release-key.sh`) and configured the environment. The current release certificate SHA-256 will be listed here: *(not yet published: the new key has not been created)*. Setup, design and limits: [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md).

## More documentation
[docs/HANDOFF_STATUS.md](docs/HANDOFF_STATUS.md) (status of every review finding, live measurements, owner actions, what is NOT tested), [docs/SECURITY.md](docs/SECURITY.md) (what is enforced and what is not, per finding), [docs/DIRECTORY.md](docs/DIRECTORY.md) (server list coverage, history and the 7-day rule, selection, measurement), [docs/AUDIT.md](docs/AUDIT.md) (dependencies and licences).

## License

Because it embeds OpenVPN for Android, this app is licensed under the **GNU GPL v2** (see `LICENSE`). OpenVPN for Android is © Arne Schwabe and contributors. Server list data is © the VPN Gate Academic Experiment. VPN use is restricted in some countries; you are responsible for following local law.
