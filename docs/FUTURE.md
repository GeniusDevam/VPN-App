# Future directions: research notes, not planned work

Recorded 25 November 2026. **Nothing here is implemented, scheduled or approved.** The current priority is stabilising and verifying the
existing Android app (`docs/MANUAL_QA.md`). Statements about third-party policies below are starting points to verify with primary
sources and, where noted, legal advice; they are not conclusions.

## Questions that block every option
1. **VPN Gate's terms.** The servers are volunteers' machines in an academic experiment (University of Tsukuba). Whether a commercial
   product, a paid tier, or a server-side service may use or resell access to them is unknown. Ask the VPN Gate operators in writing
   before building anything commercial on them.
2. **GPL v2.** The Android app embeds OpenVPN for Android and is therefore GPL v2: selling it is allowed, but every recipient must be able
   to get the corresponding source, and no extra restrictions may be added. App-store terms that restrict redistribution have been
   considered incompatible with the GPL in the past; get legal advice per store before publishing there.
3. **Privacy promises.** The app currently promises no analytics, no ads and no trackers. Ad-based monetisation would break that promise
   and needs a new privacy policy, and probably a legal entity.
4. **Regulation.** VPN use and VPN distribution are restricted in some countries; app stores add their own VPN rules; any service that
   handles user traffic on servers you operate brings data-protection and abuse-handling obligations.

## Distribution channels

| Channel | What would change | Reusable from today | New work | Main risks and open questions |
|---|---|---|---|---|
| Direct APK from an own website | Website plus an in-app "new version available" check | Everything; the release pipeline and signing key | Website; update check against the release feed | Android's developer verification for sideloaded apps (rolling out from 2026 in some countries, globally planned for 2027 per `PLAN.md` notes) may require registering as a verified developer. Users must still allow unknown sources |
| Google Play | Store listing and policy declarations | App, engine, signing key (if enrolled with the existing key) | Play's VPN-service policy declaration, data-safety form, target-SDK upkeep, store review | Whether volunteer-run servers with logging are acceptable under Play's VPN policy; GPL source obligations; keeping one signing identity for both sideloaded and Play installs (decide before enrolling) |
| Apple App Store | A new iOS app | The server-selection logic, if ported (Kotlin Multiplatform or a rewrite in Swift) | iOS UI; a Network Extension packet-tunnel provider; an OpenVPN implementation for iOS | Apple's rules for VPN apps (to verify: offered by organisations, not individuals); licence compatibility of the OpenVPN library with the App Store; large effort |
| Installation-free web proxy | A server-side service that fetches web pages on the user's behalf | Directory parsing, sanitising, ranking, history (server-side, JVM) | Backend, URL rewriting, accounts or rate limits, abuse desk | **Not equivalent to a VPN**: only the browser tab is covered, not the device. To rewrite pages the proxy must see them decrypted, so the operator can read users' traffic, a much weaker privacy position than today's app. Many sites break behind rewriting proxies. All traffic costs bandwidth on your servers. Abuse (fraud, illegal content, takedown requests) arrives at your address. Reselling VPN Gate capacity this way is exactly question 1 |
| Browser extensions | An extension that sets the browser's proxy | Ranking logic (as a service) | A proxy gateway (browsers speak HTTP(S)/SOCKS proxies, not OpenVPN), the extension, store reviews | Same backend cost and abuse exposure as the web proxy; covers only the browser |
| Desktop clients (Windows, macOS, Linux) | A desktop app driving an OpenVPN client | Engine logic on the JVM (or Kotlin Multiplatform) | Desktop UI; OpenVPN client and driver installation (Windows needs a virtual network driver and administrator rights); per-OS signing and notarisation | Maintenance across three operating systems; code-signing certificates cost money |

## Reusable pieces of today's code
* **Portable (pure Kotlin, `engine/`):** VPN Gate list parser and validator, configuration sanitiser and relay-identity policy, server
  ledger (history, reputation, the 7-day absence rule), Quick Connect, Best Connect and worldwide search engines, country handling,
  persistence. 129 unit tests. Could become a Kotlin Multiplatform module or run server-side on the JVM.
* **Android-specific:** the tunnel (OpenVPN for Android), the Compose UI, encrypted storage (Android Keystore), the foreground service and tile.

## If this is pursued, a sensible order
1. Written answer from VPN Gate on commercial use and redistribution.
2. Legal advice on GPL obligations per channel, the business form, and data protection for any server-side component.
3. A cost model for anything that carries user traffic on your own servers (bandwidth, abuse handling, support).
4. Only then a prototype, starting with the lowest-risk channel (direct APK with an update check, or Google Play).
