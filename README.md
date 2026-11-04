# VPN App

A free VPN app for Android with two ways to connect, each with its own country choice:

- **Quick Connect** finds a fast server and connects, in 35 seconds or less (usually a few seconds).
- **Best Connect** takes its time: it scans every server, speed-tests the most promising ones through real tunnels, re-tests the leaders, and connects to the fastest.

Servers are the free, volunteer-run relays of the [VPN Gate](https://www.vpngate.net) project (University of Tsukuba, Japan).

## Install on your phone

1. On your Android phone, open this link: **[Download VPN-App.apk](https://github.com/GeniusDevam/VPN-App/releases/latest/download/VPN-App.apk)**
2. When the download finishes, tap **Open**.
3. If Android asks, allow your browser to **install unknown apps** (one-time switch), go back, and tap **Install**.
4. Open **VPN App**, read the short privacy notice, and tap **Quick Connect**. Android shows a one-time "Connection request" for VPN access: tap **OK**.

Updates: open the same link again and install over the top; your settings are kept (builds are signed with the same key).

Requires Android 8.0 or newer and a 64-bit phone (any phone from the last several years).

## Privacy: what encryption does and doesn't do

- Your traffic to the server is **encrypted** (modern ciphers only; weak ones are refused). Wi-Fi owners, your carrier and anyone on the network cannot read it.
- Everything the app stores on your phone (server list, settings, history) is **encrypted** with a key in the Android Keystore.
- The app has **no analytics, ads or trackers**.
- **But** each free server is run by an anonymous volunteer who can see which sites and addresses you connect to, and who can read any non-HTTPS traffic. VPN Gate states that it keeps connection logs for at least three months. Don't log in to banking or other important accounts through a free server, and use the Settings option to make such apps bypass the VPN.

## Known risks (read before relying on it)

- **Built without a physical phone.** The build environment had no device, so the app has been verified by unit tests (including the 35-second guarantee) and by running it in an Android emulator in CI against live VPN Gate servers (see the `emulator` job in the Actions tab), not on real hardware.
- **Free servers are unreliable.** VPN Gate relays are volunteers' home PCs: they come and go hourly, many are slow, and some countries have few or none at a given moment. Quick Connect fails over to the next server automatically; if a country has no working server the app says so instead of silently picking another country.
- **Privacy is limited by the servers**, not by the app (see above).
- **Speed tests** use Cloudflare's public speed-test endpoints and use data (about 30 MB per server tested on Wi-Fi; far less on mobile data). Their availability and terms can change.
- **Not included yet:** home-screen widget, app shortcuts, automatic failover while connected, Always-on action, Tor mode. See `PLAN.md`.

## How it works

| Piece | What it does |
|---|---|
| `engine/` | Pure-Kotlin logic with unit tests: VPN Gate list parser, config sanitizer (volunteer configs are untrusted and rebuilt from an allowlist), latency probes, the Quick Connect and Best Connect engines. A randomized test proves Quick Connect always finishes within 35 s. |
| `app/overlay/` | The Android app (Jetpack Compose UI, encrypted storage, tunnel control), overlaid onto the [OpenVPN for Android](https://github.com/schwabe/ics-openvpn) engine at the commit pinned in `tools/ICS_OPENVPN_REF`. |
| `tools/prepare.sh` | Clones the pinned engine, overlays the app, and appends the build configuration. |
| `.github/workflows/ci.yml` | Runs the engine tests, builds and signs the APK, and publishes it to the **latest** release. |
| `PLAN.md` | The original design plan. |

Build locally: `tools/prepare.sh && cd build-tree && ./gradlew :main:assembleSkeletonOvpn2Release` (needs the Android SDK and NDK).

## Signing key

Releases are signed with `tools/release.jks`, which is committed to this public repository because this project's CI cannot hold secrets. That is fine for personal use, but it means anyone could build an APK that Android treats as an update to this app. If you share the app beyond yourself, create a private key, store it as a repository secret, and reinstall once.

## License

Because it embeds OpenVPN for Android, this app is licensed under the **GNU GPL v2** (see `LICENSE`). OpenVPN for Android is © Arne Schwabe and contributors. Server list data is © the VPN Gate Academic Experiment. VPN use is restricted in some countries; you are responsible for following local law.
