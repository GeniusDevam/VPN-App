# Security notes and status of review findings

This is the honest picture: what is enforced, what is only mitigated, what cannot be guaranteed by an app, and what was **not**
verified. "Verified" means demonstrated by a test that was actually run.

| # | Finding | Status | How it was checked |
|---|---|---|---|
| B1 | Public signing key | Mitigated in CI; **needs you** to create a new key (docs/RELEASE_SIGNING.md) | Key script run; workflow YAML parsed. The pipeline itself has not run yet |
| B2 | No server certificate verification | **Fixed** | Real OpenVPN 2.6.19 client/server test (`tools/cert-policy-test.sh`): genuine cert connects; wrong name, non-server role, untrusted CA rejected; control accepts wrong name without the policy. Not yet run against live relays |
| B3 | HTTP fallback for the directory | **Fixed** (HTTPS only; cleartext disabled app-wide) | Unit tests; network-security config |
| B4 | Leak protection | Documented; cannot be guaranteed by the app | Analysis below; not testable without a device |
| B5 | Disconnect vs cancel race | **Fixed** | Deterministic and 500-seed randomised tests, shown to fail on the old behaviour |
| B6 | Cipher policy gaps | **Fixed** | Unit tests of every path through one gate |
| B7 | Compression | **Removed** | Source review of the pinned OpenVPN; real OpenVPN accepts the options |
| B8 | Address/profile validation | **Fixed** | Address matrix, adversarial cases, 600-seed sanitizer fuzz, CSV fuzz |
| B9 | Logging/privacy, public CI output | **Fixed** (redacted publishing); owner action: delete old branch | Redactor self-tested |
| B10 | Dependency/licence audit | Partly done; advisory scan **not possible here** (docs/AUDIT.md) | |

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
The live effect could not be measured here (vpngate.net unreachable); the `vpngate-check` workflow now runs real OpenVPN against live
relays with and without the policy and prints the certificate names it sees, so the cost is measurable in one run.

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
Observed in all successful CI connections: `AES-128-CBC` with `SHA1` (no relay negotiated AEAD).

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
