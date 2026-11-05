# Security notes and status of review findings

This is the honest picture: what is enforced, what is only mitigated, what cannot be guaranteed by an app, and what was **not**
verified. "Verified" means demonstrated by a test that was actually run.

| # | Finding | Status | How it was checked |
|---|---|---|---|
| B1 | Public signing key | **Open until the owner acts**, repository side **done and verified**. The key can now be read only by one job in a protected environment, tag releases only | `tools/check-workflow-security.py` + 13 mutation tests in CI (each re-creates a leak and must be rejected); the re-signing script is exercised on every build with a throw-away key (signer changes, 116/116 code entries identical, v2/v3 verify, alignment intact); `release-gate`/`sign`/`publish` were skipped on branch pushes. **Not yet run with a real key.** Old key still in git history; the old public release must be deleted by the owner |
| B2 | No server certificate verification | **Fixed** | Real OpenVPN 2.6.19 client/server test (`tools/cert-policy-test.sh`): genuine cert connects; wrong name, non-server role, untrusted CA rejected; control accepts wrong name without the policy. **Live relays (CI, 2026-11-05): 15/24 connected with the policy and 15/24 without; every relay that reached TLS presented `CN=opengw.net`** |
| B3 | HTTP fallback for the directory | **Fixed** (HTTPS only; cleartext disabled app-wide) | Unit tests; network-security config |
| B4 | Leak protection | Documented; cannot be guaranteed by the app | Analysis below; not testable without a device |
| B5 | Disconnect vs cancel race | **Fixed** | Deterministic and 500-seed randomised tests, shown to fail on the old behaviour; the Quick timeout and exception restore paths were also closed; the emulator test confirmed an explicit disconnect stayed down for 20 s (run 22) |
| B6 | Cipher policy gaps | **Fixed** | Unit tests of every path through one gate |
| B7 | Compression | **Removed** | Source review of the pinned OpenVPN; real OpenVPN accepts the options |
| B8 | Address/profile validation | **Fixed** | Address matrix, adversarial cases, 600-seed sanitizer fuzz, CSV fuzz |
| B9 | Logging/privacy, public CI output | **Fixed** (redacted publishing); owner action: delete old branch | Redactor self-tested |
| B10 | Dependency/licence audit | **Attack surface reduced and gated**; advisory scan **not possible here** (docs/AUDIT.md) | The built APK had 18/23 components reachable by other apps (upstream's external-control API, boot receiver, aliases). Now 5/23, each needed or system-guarded, enforced by `tools/manifest-policy.json` as a hard CI gate; unused permissions removed; split-tunnel picker verified live without `QUERY_ALL_PACKAGES` (18 apps listed). A removed permission that turned out to be needed (`RECEIVE_BOOT_COMPLETED`) crashed the VPN and was caught by the emulator test |
| C7 | Persistence atomicity and ordering | **Fixed** | `PersistenceTest`; see below |
| C9 | Misleading failure messages | **Fixed** | Directory-unavailable, no-server-in-country and settings-exclusion are separate outcomes (engine tests; the emulator test confirmed an honest, country-naming failure) |

## C7. Persistence
Every stored file is replaced with fsync + atomic rename and the old file is never deleted first (`AtomicFiles`). Writes of each file go
through one ordered, coalescing writer (`LatestWinsWriter`), so an older snapshot can never overwrite a newer one. The previous
design (one coroutine per save behind a Mutex) reordered writes in 30 of 30 rounds of the new race test; the new one in none. Tested in
`PersistenceTest` (crash between temp write and commit keeps the old content; 30 rounds x 400 racing submissions end on the newest).
Not tested: Android Keystore failures and power loss on a real device (the JVM tests cannot exercise `SecureVault`'s key handling).

## B5 follow-up
The Quick Connect watchdog-timeout path and the generic-exception path used to restore the previous connection without asking the
coordinator whether an explicit disconnect had happened since. Both are now epoch-guarded, and the post-timeout restore is
cancellable, so a disconnect always wins. The emulator test additionally asserts that an explicit disconnect stays disconnected for 20 s.

## B2. Relay identity
`ConfigParser.convertProfile()` in the pinned ics-openvpn clears `mCheckRemoteCN` and `mExpectTLSCert`, so a profile without those
lines produced OpenVPN's *"No server certificate verification method has been enabled"* in all 16 connection attempts of the CI
emulator log. The chain was still checked against the profile's own `<ca>`, which proves nothing when the profile comes from the
same untrusted record as the address.

Policy now (`ServerAuthPolicy`, `CertInspector`, `ConfigSanitizer`):
1. Every self-signed certificate in `<ca>` must be a root that Android's **system** store already trusts (user-installed CAs do not
   count), plus two pinned Let's Encrypt roots; all other certificates must chain to them by verified signature. A directory cannot
   introduce its own CA.
2. `remote-cert-tls server` (server key usage/EKU) and `verify-x509-name opengw.net name` are appended by the app and always replace
   whatever the profile asks for. `TunnelManager` additionally forces the profile fields and asserts the final config text; if the
   engine ever logs the "no verification" warning the connection is refused.
3. The relay's hostname is never resolved: the profile dials the vetted public IP literal, which must equal the directory's address.

Evidence for the name: in all 15 handshakes that reached TLS in the emulator log the chain was ISRG Root X1 > Root YR > YR2 and the
leaf was `CN=opengw.net` (the domain VPN Gate controls).

**What this does not prove.** It authenticates "a holder of VPN Gate's `opengw.net` certificate", i.e. VPN Gate's relay software,
against on-path attackers and rogue directories. It does **not** vouch for an individual volunteer: the relay software holds the
certificate, so any operator can present it. Volunteers can always see your traffic's destinations (README). **Compatibility risk:**
relays that use another certificate scheme are now excluded and counted in the diagnostics as "failed relay-identity check".
**Measured live** (workflow `vpngate-check`, real OpenVPN 2.7.0 against 24 of 96 listed relays, run twice): 15/24 connected without the policy and 15/24 with it; the failures were volunteers that never completed TLS or timed out, and the set of failing relays differed between the runs. Depth-0 certificate names seen: `CN=opengw.net` for every relay that reached TLS (22 and 21). So the policy cost no measurable compatibility on that sample. It is a sample of one day's partial list, not a guarantee.

## B3. Directory authenticity
HTTPS only, no redirects followed, 20 MB streamed cap, per-line cap, no downgrade. A TLS-authenticated response still comes from a
service whose content is untrusted; the relay-identity policy is what stops a tampered list from steering the user to an
impersonator. HTTP was removed because nothing in an HTTP response authenticates who wrote it.

## B4. Leak protection (what the app can and cannot guarantee)
Cannot detect: whether the user enabled Always-on or "Block connections without VPN" (the app cannot read it; the Settings keys are
not public API), so the UI never claims a kill switch. By itself the app: keeps the interface up while the engine reconnects
(`persist-tun`), blocks IPv6 when the relay has none, forces its own DNS resolvers over the tunnel, and honours per-app exclusions
(excluded apps are unprotected by definition).

| Situation | App alone | With Always-on + Block |
|---|---|---|
| Initial connection, before the tunnel is up | not protected (normal traffic flows) | blocked |
| Failure / cancel / switching candidates (Best, worldwide) | not protected or protected by a test relay; no continuous protection | blocked while no VPN |
| Reconnect inside the engine | interface persists | same |
| Wi-Fi/cellular switch | engine reconnects; short gap possible | blocked in gap |
| VPN process crash / app killed / reboot | not protected | blocked (Always-on restarts it) |
| Per-app exclusions | those apps bypass | those apps are blocked only if outside the exclusion |

Probe sockets are bound to the physical network, so every relay that is probed learns your real IP address (as connecting would, minus
payload). Under "Block connections without VPN" such sockets may be refused by Android, in which case probes fail instead of leaking;
this was not tested on a device. The worldwide search says so before it starts.

## B6. Encryption policy
All paths go through `ConnectionGate`: Quick, Best (including a winner already connected from its speed test), manual connect, and
restoring the previous connection. A violating tunnel is disconnected before the caller sees success. The engine is also given an
allowlist (`data-ciphers`) that never contains a weak cipher. `TunnelManager` no longer substitutes the relay's *declared* cipher when
the negotiated one was not observed; such a connection is shown as "not verified". Strong mode requires a verified AEAD cipher.
Observed in all successful CI connections (emulator and live OpenVPN): `AES-128-CBC` with `SHA1` (no relay negotiated AEAD). The negotiated cipher arrives through an asynchronous log channel, so the app waits up to 1.5 s for it after "connected" before labelling a connection "not verified".

## B7. Compression
Pinned OpenVPN (`c29ee42`, banner "2.7-icsopenvpn", `version.m4` 2.8_git; built with LZO and LZ4): `comp-lzo` sets the algorithm but the
code comment states *"We never compress"*, so VORACLE-style attacks that need compressed **outbound** data do not apply; the option
only enables decompression of inbound packets. Without any compression option the default for >= 2.6 is stub-only. The live profile
in the CI log connected without `comp-lzo`. The app therefore drops `comp-lzo`/`compress`, adds `allow-compression no`, and
asserts the final config has no compression. Not verified against a relay that insists on compression framing (none seen).

## B8. Profile validation
Reserved/private/loopback/link-local/CGNAT/multicast/documentation/6to4/Teredo/IPv4-mapped destinations are refused; the profile's
`remote` must match the directory IP; exactly one `remote`; no `<connection>` or `tls-crypt-v2`; only udp/tcp client transports;
non-numeric ports refused; control characters, oversize blocks/profiles refused; the allowlist architecture is unchanged
(scripts, plugins, management, routes, DNS pushes, proxies are dropped). Found along the way: the old sanitizer emitted a bare
`resolv-retry`, which real OpenVPN rejects (ICS's parser tolerated it); fixed.

## B9. Logging and public CI output
* Production builds log nothing from the app unless the debug tag is switched on with adb. The old dump of the sanitized config is
  gone; only a short allowlist of engine verdicts (with IPs masked) is logged in debug.
* The CI used to publish the **entire emulator logcat** and UI dumps to the public `emulator-results` branch. It now publishes only a
  redacted summary (`tools/redact-results.py`: result, package info, screenshots, on-screen text, app-tagged lines, app crash traces;
  addresses masked, key-like lines dropped). **Owner action:** delete the existing branch (`git push origin --delete emulator-results`);
  the next run recreates it with redacted content. Old commits can remain fetchable by hash for a while.
* Network contexts are salted hashes; nothing leaves the device.
