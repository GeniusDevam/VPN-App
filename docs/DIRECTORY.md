# Server directory, history and selection (A1 to A4)

## A1. How many relays can we legitimately see?
**Facts established this session**
* The app reads one endpoint, `https://www.vpngate.net/api/iphone/` (the website's "CSV" link points at the same place). It returns
  only rows that carry an embedded OpenVPN profile; the app parses and counts exactly those.
* Earlier emulator runs (project history) saw roughly 100 profiles per download, about 84% TCP, all `AES-128-CBC`. Those numbers
  were not re-measured here: **vpngate.net is unreachable from the sandbox this work was done in** (the proxy answers 403), so no
  live count, no mirror probing and no comparison with the website total could be done.
* VPN Gate states that the public list is deliberately partial. **No complete or documented "full API" is known, and none is used.**
  Nothing in the app bypasses a limit: one polite request at a time, at most every 10 minutes, exponential back-off up to 1 hour,
  `Retry-After` honoured, 429/503 treated as "slow down".

**What the app now does to get the broadest legitimate view**
1. Keeps the union of what recent listings contained (`DirectoryPool`, 72 h). If VPN Gate rotates its sample, different downloads
   add up; if it does not, nothing is gained and nothing is lost. The Home screen shows **"N new in that refresh"**, which tells you
   empirically which it is. This is the measurement that decides whether anything more is worth building.
2. Deduplicates by relay name and by `ip:port/transport`; a relay whose address changes keeps its counters but loses stale
   latency/throughput (they described another network position).
3. Counts only **usable** relays: valid OpenVPN profile, public unicast address, valid ISO country, and a server-identity check
   that passes (see SECURITY.md). Diagnostics show rows received, valid profiles, usable servers, countries, per-reason rejections,
   last successful refresh, new-in-last-refresh, whether the list is served from cache, and a "known partial" notice. The service's
   advertised total is not available through the API, so it is never shown as if it were ours.
4. Cache fallback: if the refresh fails, the saved pool is served and labelled as such. A tiny, collapsed or oversized download is
   treated as a failed download and changes nothing (see below).

**Not done, on purpose or for lack of access**
* Scraping the website's HTML list pages (not an API; anti-abuse risk; would need verification against VPN Gate's terms).
* VPN Gate mirror sites. They exist for regions where the main site is blocked, but their addresses could not be verified from here,
  so none are hard-coded. The pipeline is source-agnostic (`DirectoryProcessor`), so a verified HTTPS mirror is a one-line addition.
  HTTP mirrors are refused: nothing in an HTTP response authenticates its source, and sanitizing directives does not either.
* SoftEther/L2TP/SSTP-only relays: not OpenVPN-compatible with this engine and not in this API.

## A4. Permanent history and the seven-day rule
Stored (encrypted, local only) per relay: first/last seen in the directory, successes and failures with the failure kind, last
success/failure, average connect time, last observed cipher and whether it was verified, protocol, sessions, total session time,
unexpected disconnects, and per network context: latency, jitter, loss, probe method, download/upload, loaded latency, with timestamps.
A network context is a **salted hash** (random per-install salt in the encrypted vault) of connection type, DNS, gateway and mobile
operator; at most 8 are kept per relay.

**Exact meaning of "has not appeared for seven days"** (`ServerLedger.observeDirectory`):
* A relay's saved data is deleted once it has been **absent from successfully obtained, usable directory listings for 7 days of
  observed time**. Each usable listing adds the time since the previous usable listing to every saved relay it omitted; the gap
  counted per listing is capped at **36 hours**. Any appearance resets the counter and refreshes "last seen".
* **Nothing advances on a failure.** Offline, DNS/TLS/HTTP errors, 429s, oversize or unparsable responses, and implausibly small
  listings (< 10 valid profiles, or under 25% of the previous one) are ignored entirely. A phone left off for a month does not
  lose history, because that gap counts for at most 36 hours.
* It is not the age of a measurement. **Old measurements are never deleted for being old**; they only weigh less when used as a
  prior (half-life 3 days, floor 5%).
* Because the public list is partial and rotating, absence is evidence about the *list*, not proof the relay is gone. That is why
  the clock counts observed time rather than wall time, and why it is the only deletion rule.
* Kept on purpose: your explicit favourites (just an id you chose) and the settings. "Clear saved server history" wipes measurements
  and counters but keeps the retention bookkeeping.
* Updates: the pre-ledger reputation/latency/throughput state is imported once on first start, so nothing is lost on update.

## A2 and A3. Selection
* **Quick Connect** (per-mode country, never falls back to another country, 35 s budget): probes a prioritised sample, then connects.
  The banner says how many were checked and answered, and that this is a latency estimate, not a speed test. Probes of weak
  quality (a UDP relay with tls-auth/crypt can only be checked with TCP 443, which shows the host is up, not the VPN service) are
  penalised 60 ms and flagged. The budget is also enforced by a watchdog on the elapsed-realtime clock, because coroutine timers
  stop while the CPU sleeps.
* **Best Connect** (per-mode country): probes **every** relay in scope (the old silent 400 cap is gone; a 3000 safety cap reports
  partial coverage if it ever bites), re-probes the leaders, speed-tests a shortlist through real tunnels, re-tests the top three.
  The result carries a claim: *confirmed best of those tested*, *best of tested*, *partial coverage*, *early finish* or *latency only*.
  It never says "fastest overall".
* **Worldwide search** (optional, separate card): explicit consent dialog (time, data budget 50/150/300 MB, battery, that every probed
  relay sees your IP, no continuous protection while it tests, metered-network switch). Batched probing with a hard concurrency limit,
  every country sampled first, adaptive stop when more probing stops improving the leaders, exploration slots, pause / "connect to best
  so far" / cancel, automatic pause on no network or battery < 15% (refuses to start < 20% unless charging), progress and leaderboard
  with honest coverage, and a saved summary that an interrupted search can resume from for 6 hours. Runs in a foreground service;
  Android may still pause it in Doze, in which case it resumes when it can. It cannot survive the process being killed beyond that
  saved summary.

## Why a run can end without a connection (C9)
The three reasons are reported separately and never merged (`PoolSelection`): *the server list could not be loaded* (says nothing about
any country), *the latest list has no server in the chosen country*, and *servers exist there but your settings exclude them*
(Strong-only encryption, or servers set aside after failures). The country picker shows the same states (loading / unavailable /
stale / empty) and keeps a previously chosen country visible even when it has no server right now. The scope is never widened.

## Measurement notes (C3, C4, C5)
* **Probe quality (C3).** `OPENVPN_UDP_RESET` = the VPN service answered (high confidence). `TCP_CONNECT` = the VPN port accepts a
  TCP handshake (medium). `TCP_FALLBACK` = for a UDP relay that requires tls-auth/crypt the only unauthenticated check is TCP 443 on
  the same host, and even a *refused* connection counts as a round trip: this shows the host is alive, nothing about the VPN
  service (low). Low-confidence results take a 60 ms penalty and are flagged in the Quick Connect message. **Update:** relays with an
  inline `tls-auth` key are now probed with a real HMAC-signed hard reset whose reply HMAC is verified (`TlsAuthKey`), which counts
  as a high-confidence `OPENVPN_UDP_RESET`. This was checked against real OpenVPN 2.6.19 servers (`tools/probe-test.sh`, also a CI
  step): key-direction 1/0 and bidirectional keys are answered; a probe holding the wrong key gets no answer. `tls-crypt` relays
  (whole-packet encryption) still use the weak TCP fallback; not built.
* **Speed test (C4).** Uses Cloudflare's public speed-test endpoints; their availability and terms can change and they are not a
  contract with this project (consider self-hosting if usage grows). Fixed in this pass: results are discarded unless the test's
  egress IP differs from the phone's own IP captured while no tunnel was up (skipped, not failed, when that IP is unknown);
  discarded if the Wi-Fi/cellular network changed mid-test; the fixed 600 ms "settle" sleep is replaced by waiting up to 6 s for the
  route; data counted includes a 6% allowance for TLS/HTTP overhead and budgets are computed from that. Still approximate: bytes in
  flight when a transfer is cut and bytes buffered but not acknowledged on uploads are not counted exactly, and TLS handshake
  bytes are estimated. Mobile data uses 8 MB per relay and a 60 MB cap in Best Connect; the worldwide search has a user-chosen cap.
  Not verified: the VPN-route check on a real device.
* **Quick Connect time (C5).** The engine's 35 s limit is proven in virtual time (1000 random scenarios). In real use two things could
  stretch it: coroutine timers do not count CPU sleep, and cleanup could block. Both are addressed: the engine uses an
  elapsed-realtime clock, a watchdog cancels the run 3 s past the budget, and binding to the engine service for cleanup is capped at
  3 s. Not covered: the Android "VPN permission" dialog (excluded from the budget by design) and the post-failure restore of the
  previous connection (up to 20 s, reported separately). Not verified on a device.
