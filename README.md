# VPN App

A free VPN app for Android with two ways to connect, each with its own country choice:

- **Quick Connect** finds a fast server and connects, in 35 seconds or less (usually a few seconds). It compares response times of a sample, so the pick is an estimate.
- **Best Connect** takes its time: it probes every server in the chosen country, speed-tests the most promising ones through real tunnels, re-tests the leaders, and connects to the fastest it measured.
- **Worldwide search** (optional, you start it explicitly) samples every country and speed-tests a shortlist. It can take up to 45 minutes and uses data and battery, and it says how much of the network it actually covered.

Servers are the free, volunteer-run relays of the [VPN Gate](https://www.vpngate.net) project (University of Tsukuba, Japan).

## Install on your phone

1. On your Android phone, open this link: **[Download VPN-App.apk](https://github.com/GeniusDevam/VPN-App/releases/latest/download/VPN-App.apk)**
2. When the download finishes, tap **Open**.
3. If Android asks, allow your browser to **install unknown apps** (one-time switch), go back, and tap **Install**.
4. Open **VPN App**, read the short privacy notice, and tap **Quick Connect**. Android shows a one-time "Connection request" for VPN access: tap **OK**.

Updates: open the same link again and install over the top; your settings are kept when the signing key is unchanged.

**Verify before installing.** Each release lists the APK's SHA-256 and the signing certificate's SHA-256 (published below once the owner has created the new release key), and carries a build-provenance attestation: `gh attestation verify VPN-App.apk -R GeniusDevam/VPN-App`. Earlier builds were signed with a key that was published in this repository and must be treated as untrusted; see [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md) to migrate.

Requires Android 8.0 or newer and a 64-bit phone (any phone from the last several years).

## Privacy: what encryption does and doesn't do

- Your traffic to the server is **encrypted** (modern ciphers only; weak ones are refused). Wi-Fi owners, your carrier and anyone on the network cannot read it.
- Everything the app stores on your phone (server list, settings, history) is **encrypted** with a key in the Android Keystore.
- The app has **no analytics, ads or trackers**.
- **But** each free server is run by an anonymous volunteer who can see which sites and addresses you connect to, and who can read any non-HTTPS traffic. VPN Gate states that it keeps connection logs for at least three months. Don't log in to banking or other important accounts through a free server, and use the Settings option to make such apps bypass the VPN.

## Known risks (read before relying on it)

- **Not tested on a real phone.** The build environment had no device. Every build is unit-tested (including the 35-second guarantee) and run in an Android 16 emulator in CI against live VPN Gate servers: onboarding, VPN permission, Quick Connect (8-10 s in CI) and Best Connect all worked (see the `emulator` job in the Actions tab). Real-world behaviour on mobile data, when switching networks, and over long sessions is untested.
- **"Strong only" finds no servers today.** Every VPN Gate server seen so far negotiates AES-128-CBC; the setting exists for when that changes. The app only calls a cipher "verified" when the engine reported it; otherwise it says "not verified".
- **No kill switch by itself.** The app keeps its interface up while reconnecting, but only Android's *Always-on VPN* plus *Block connections without VPN* guarantees nothing leaks; the app cannot tell whether you enabled them. Details in docs/SECURITY.md.
- **Relay identity.** Servers must present a certificate for `opengw.net` from a trusted public CA. This stops impersonation by third parties and tampered lists; it does not make an individual volunteer trustworthy. Relays that don't meet it are skipped and counted in the Server list card.
- **The server list is partial by VPN Gate's design.** The app shows how many usable servers it has, not how many exist.
- **Free servers are unreliable.** VPN Gate relays are volunteers' home PCs: they come and go hourly, many are slow, and some countries have few or none at a given moment. Quick Connect fails over to the next server automatically; if a country has no working server the app says so instead of silently picking another country.
- **Privacy is limited by the servers**, not by the app (see above).
- **Speed tests** use Cloudflare's public speed-test endpoints and use data (about 30 MB per server tested on Wi-Fi; far less on mobile data; figures are estimates). Their availability and terms can change.
- **Not included yet:** home-screen widget, app shortcuts, automatic failover while connected, Always-on action, Tor mode. See `PLAN.md`.
- **Android layer is verified by CI only** (the Android SDK is not available in the authoring sandbox). The engine has 129 unit tests plus real-OpenVPN tests for certificate policy and the tls-auth probe. A build is only meaningful if its Actions run is green; the emulator step reports PASS, FAIL or INCONCLUSIVE (see docs/HANDOFF_STATUS.md).

## How it works

| Piece | What it does |
|---|---|
| `engine/` | Pure-Kotlin logic with unit tests: VPN Gate list parser, config sanitizer and relay-identity policy (volunteer configs are untrusted and rebuilt from an allowlist), the permanent server ledger, latency probes, the Quick, Best and worldwide search engines, the command coordinator. Randomized tests prove Quick Connect finishes within 35 s and that a disconnect can never be undone by older cleanup. |
| `app/overlay/` | The Android app (Jetpack Compose UI, encrypted storage, tunnel control), overlaid onto the [OpenVPN for Android](https://github.com/schwabe/ics-openvpn) engine at the commit pinned in `tools/ICS_OPENVPN_REF`. |
| `tools/prepare.sh` | Clones the pinned engine, overlays the app, and appends the build configuration. |
| `.github/workflows/ci.yml` | Runs the engine tests and a real-OpenVPN certificate test, builds the APK, and, only if the release key secrets exist, publishes a versioned release with checksum and provenance. |
| `PLAN.md` | The original design plan. |

Build locally: `tools/prepare.sh && cd build-tree && ./gradlew :main:assembleSkeletonOvpn2Release` (needs the Android SDK and NDK).

## Signing key
Releases are signed with a key held only in encrypted CI secrets. **The key that used to be committed here (`tools/release.jks`) is public and compromised; ignore any APK signed with it.** CI publishes nothing until the owner has created the new key (`tools/generate-release-key.sh`). The current release certificate SHA-256 will be listed here: *(not yet published: the new key has not been created)*. Details and the migration options, with their limits: [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md).

## More documentation
[docs/SECURITY.md](docs/SECURITY.md) (what is enforced and what is not, per finding), [docs/DIRECTORY.md](docs/DIRECTORY.md) (server list coverage, history and the 7-day rule, selection, measurement), [docs/AUDIT.md](docs/AUDIT.md) (dependencies and licences).

## License

Because it embeds OpenVPN for Android, this app is licensed under the **GNU GPL v2** (see `LICENSE`). OpenVPN for Android is © Arne Schwabe and contributors. Server list data is © the VPN Gate Academic Experiment. VPN use is restricted in some countries; you are responsible for following local law.
