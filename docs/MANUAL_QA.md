# Manual QA on physical devices

Test plan for real Android devices, written for release **v1.0.0** (commit `f95296a`). **Nothing here has been run yet; every result
starts as NOT RUN.** Mark a test PASS only after you have performed it and kept the evidence. Report results incrementally; one
device at a time is fine.

Everything automated so far ran in emulators on CI machines (see `docs/STATUS.md`, `docs/PERFORMANCE.md`). These tests cover what
emulators cannot: real radios and network switches, Doze and battery, vendor Android variants, real leak behaviour.

## How to use this document
* Work top to bottom: **P0** (security and release integrity) first, then **P1** (core features), **P2** (robustness and resources),
  **P3** (UX, accessibility, long-running).
* For each test record: device, Android version, network (Wi-Fi / mobile data, operator), date and time, result
  (**PASS / FAIL / BLOCKED / NOT RUN**), and notes. A results table is at the end.
* A test whose feature is **Partial** checks the part that exists and records the gap; tests for **Not implemented** features are not
  listed (see the table below).

### Device record (fill once per device)
| Field | Value |
|---|---|
| Model, manufacturer | |
| Android version, security patch | |
| Processor type (Settings > About; or `adb shell getprop ro.product.cpu.abi`) | |
| APK installed (universal or which split) and its SHA-256 | |
| Battery health / charge at start | |

### Evidence
* **Screenshots** (power + volume down) and **screen recordings** (Android quick settings > Screen record) for anything visual or timed.
* **Leak-test pages:** save a screenshot of the full result.
* **Timings:** the app's own banner ("Connected to ... in N s") is the reference. A stopwatch is only a cross-check.
* **Detailed logs (optional, needs a computer with `adb` and USB debugging):**
  `adb shell setprop log.tag.VPNAPP DEBUG` once, reproduce, then `adb logcat -d -v threadtime > vpnapp-log.txt`.
  These lines contain server names and masked addresses. Run them through `python3 tools/redact-results.py` (it expects the
  emulator-test layout; at minimum remove anything that looks like a key or password) before sharing logs publicly.
* **Crashes:** `adb logcat -d -b crash > crash.txt`, plus the time it happened.

## What is implemented in v1.0.0
| Feature | Status | Notes |
|---|---|---|
| Quick Connect (any country, or one country) | Implemented | Promise: connected or an honest failure within 35 s |
| Best Connect (any country, or one country) | Implemented | Speed tests through real tunnels; data caps: about 300 MB per run on Wi-Fi, 60 MB on mobile data / metered Wi-Fi |
| Worldwide search ("global fastest") | Implemented | Explicit consent; 10-45 min; pauses on low battery or lost network; off on metered networks unless allowed |
| Country selection per mode | Implemented | Each mode remembers its own country |
| Server list (VPN Gate), 72-hour pool of recent lists, stale-cache fallback | Implemented | Only VPN Gate's official HTTPS API; no mirrors |
| Manual connection to a chosen server | Implemented | Servers tab: search, favourites, "Report & avoid", per-server history |
| Encryption modes: Compatible / Strong only | Implemented | All current VPN Gate servers use AES-128-CBC, so "Strong only" is expected to find none |
| DNS choice: Quad9 or Cloudflare | Implemented | Forced inside the tunnel |
| IPv6 handling | Partial | IPv6 is blocked when the server has none (current VPN Gate servers); no IPv6 through the tunnel |
| Split tunnelling (apps that skip the VPN) | Implemented | |
| Server history, encrypted at rest; 7-day absence rule | Implemented | Unit-tested; real-time device check is long-running |
| Notification during runs, with Cancel | Implemented | Foreground service while Quick / Best / worldwide runs |
| Quick Settings tile | Implemented (Android 7.0+) | Opens the app and starts Quick Connect |
| Kill switch | **Not provided by the app** | Relies on Android's Always-on VPN + "Block connections without VPN"; the app says so |
| Reconnect after a drop | Partial | OpenVPN reconnects to the **same** server; no switch to another server |
| Connection details after the app process is killed | Partial (known issue C8) | UI may show "Connected" without server and cipher |
| Automatic failover to another server while connected, home-screen widget, app shortcuts, Always-on shortcut action, Tor, VPN Gate mirrors, in-app licence screen | **Not implemented** | Not tested here |

---

## P0: security and release integrity

### S1. Install a verified APK (Implemented)
* **Procedure:** On a computer, download `VPN-App.apk` from the v1.0.0 release; check its SHA-256 and signing certificate as in README
  "Verify the download". Install on the phone (fresh, no earlier build).
* **Expected:** SHA-256 `57c68ef8...2649`; certificate SHA-256 `0838262d...998a`; install succeeds; app icon "VPN App".
* **Pass if:** both values match exactly and the install completes.
* **Evidence:** terminal output of both checks; screenshot of the installed app's App info page (version 1.0.1048-o2).

### S2. An old leaked-key build cannot be upgraded (Implemented)
* **Procedure:** Only if you still have a pre-v1.0.0 APK: install it, then try to install v1.0.0 over it. Then uninstall it and install v1.0.0.
* **Expected:** the update is refused ("App not installed" / conflict); after uninstalling, v1.0.0 installs.
* **Pass if:** Android refuses the update and the clean install works.
* **Evidence:** screenshots of the refusal and the successful install. Mark BLOCKED if no old APK is available.

### S3. VPN permission (Implemented)
* **Procedure:** Fresh install, finish onboarding, tap Quick Connect. (a) Tap **Cancel** on Android's "Connection request". (b) Tap
  Quick Connect again and tap **OK**.
* **Expected:** (a) no connection and a clear message, no crash; (b) the connection proceeds; Android shows the key icon in the status bar.
* **Pass if:** both outcomes as described; the dialog appears only until it is accepted once.
* **Evidence:** screen recording.

### S4. DNS leak (Implemented)
* **Procedure:** Connect (Quick Connect, any country). In a browser open a DNS leak test (for example dnsleaktest.com, "Extended test").
  Repeat with the DNS setting switched between Quad9 and Cloudflare (Settings > Privacy), reconnecting after each change. Repeat once on
  mobile data.
* **Expected:** only resolvers of the chosen provider appear; none of your ISP or mobile operator.
* **Pass if:** no ISP/operator resolver in any run.
* **Evidence:** screenshot of each result with the connected server visible (or noted).

### S5. IPv4 address leak (Implemented)
* **Procedure:** Note your IP without the VPN (for example on ipleak.net). Connect, reload.
* **Expected:** the shown IPv4 address is the VPN server's (its country matches the app), never your own.
* **Pass if:** your own IPv4 never appears while connected.
* **Evidence:** screenshots before and after.

### S6. IPv6 leak (Partial: IPv6 is blocked, not tunnelled)
* **Procedure:** On a network with IPv6 (many mobile networks; check test-ipv6.com without the VPN first), connect and open test-ipv6.com
  and ipleak.net.
* **Expected:** no IPv6 address shown, or only one that is not yours. Some sites will report "no IPv6", which is expected.
* **Pass if:** your own IPv6 address never appears while connected.
* **Evidence:** screenshots before and after. If your networks have no IPv6, mark BLOCKED and say so.

### S7. WebRTC leak in a browser (Implemented, browser-dependent)
* **Procedure:** Connected, open browserleaks.com/webrtc in Chrome (and Firefox if installed).
* **Expected:** no public IP address of yours (local private addresses like 192.168.x.x may appear and are not a leak).
* **Pass if:** no own public IPv4/IPv6.
* **Evidence:** screenshot.

### S8. Always-on VPN with "Block connections without VPN" (Android feature; app integration)
* **Procedure:** Settings > Open Android VPN settings > gear next to VPN App > turn on Always-on VPN and Block connections without VPN.
  Then: (a) tap Disconnect in the app; (b) start Best Connect and watch while it switches servers; (c) reboot the phone.
* **Expected:** (a) no internet while disconnected; (b) no traffic outside the VPN during switching (pages may stall); (c) after reboot
  Android starts the VPN or blocks traffic until it is up. Record exactly what happens; the app does not control this.
* **Pass if:** no traffic leaves outside the VPN in any of the three; any app misbehaviour (crash, stuck state) is a FAIL.
* **Evidence:** screen recordings; a leak-test page loaded during (b) if possible. Turn the settings off afterwards if you do not want them.

### S9. Leaks while reconnecting, without Always-on (documented limitation)
* **Procedure:** Always-on off. While connected, toggle airplane mode on for 10 s and off; and separately switch Wi-Fi off so the phone
  moves to mobile data. Immediately reload ipleak.net several times.
* **Expected:** the app keeps the VPN interface up while OpenVPN reconnects, so pages should stall rather than show your own IP. Android
  can still let traffic through at some moments; the app documents that it cannot guarantee this.
* **Pass if:** your own IP is never shown; if it is, record it as a **documented limitation observed** (not a crash) with timestamps.
* **Evidence:** screen recording including the leak page.

### S10. Explicit disconnect stays disconnected (Implemented)
* **Procedure:** Connect, tap Disconnect, wait 5 minutes with the app open, then 5 minutes with the screen off.
* **Expected:** "Not connected" throughout; the key icon disappears; nothing reconnects by itself.
* **Pass if:** no automatic reconnection in 10 minutes.
* **Evidence:** screenshots at start and end.

### S11. Split tunnelling (Implemented)
* **Procedure:** Settings > Apps that skip the VPN: select one browser (for example Firefox) and leave another (Chrome) unselected.
  Connect. Open ipleak.net in both.
* **Expected:** the excluded browser shows your own IP; the other shows the VPN server's.
* **Pass if:** exactly that. Then remove the exclusion, reconnect, and both show the VPN's IP.
* **Evidence:** screenshots of both browsers.

### S12. Encryption reporting and "Strong only" (Implemented)
* **Procedure:** Compatible mode: connect and read the status card. Then Settings > Strong only, run Quick Connect.
* **Expected:** Compatible: "Encryption: AES-128-CBC (verified)" (or "not verified" if the engine did not report it). Strong only: an
  honest message that no server fits the settings, within 35 s; no connection to a weaker server.
* **Pass if:** both as described. Switch back to Compatible afterwards.
* **Evidence:** screenshots.

### S13. Backups do not export app data (Implemented: backups disabled)
* **Procedure (optional, needs adb):** `adb backup -f vpnapp.ab io.github.geniusdevam.vpnapp` (older Android versions) or check that
  Google One / device backup lists no data for VPN App.
* **Expected:** no app data is included.
* **Pass if:** empty or refused backup. Mark BLOCKED if your Android no longer supports `adb backup`.
* **Evidence:** command output or screenshot.

---

## P1: core features

### C1. Onboarding and first launch (Implemented)
* **Procedure:** Fresh install, open the app. Read and scroll the privacy notice; tap "I understand, continue". On Android 13+, answer the
  notification permission prompt (try "Allow" on one device and "Don't allow" on another, or reinstall to repeat).
* **Expected:** the notice is readable and scrollable; the home screen appears with Quick Connect and Best Connect; with notifications
  denied the app still works (no progress notification).
* **Pass if:** no crash, nothing cut off, both choices handled.
* **Evidence:** screenshots; first-launch time (tap to home screen) by stopwatch.

### C2. Server list (Implemented)
* **Procedure:** Home > Server list card. Note usable servers and countries. Tap "Refresh list". Then airplane mode on, kill and reopen
  the app, open the country picker.
* **Expected:** about 90-100 usable servers in several countries (it varies); after refresh a timestamp updates; offline, the app keeps
  the saved list and says it could not refresh (it must not claim there are no servers anywhere).
* **Pass if:** counts shown, refresh works, offline state is explained honestly.
* **Evidence:** screenshots of the card online and offline.

### C3. Quick Connect, any country: time and success (Implemented)
* **Procedure:** Quick Connect with "Any country". Note the banner time ("Connected to ... in N s"). Disconnect, wait 10 s, repeat: 5 runs
  on Wi-Fi, 5 on mobile data.
* **Expected:** each run ends within 35 s with "Connected" or an honest failure message. Emulators measured 3.5-5.2 s from tap to tunnel.
* **Pass if:** no run exceeds 35 s without a result; at least 8 of 10 connect (volunteer servers fail sometimes; more than 2 failures is a
  finding to report, not automatically an app bug).
* **Evidence:** a table of the 10 banner times and servers; one screen recording.

### C4. Quick Connect in a chosen country (Implemented)
* **Procedure:** Quick Connect chip > choose Japan (many servers); connect. Then choose a country with 1 server (the picker shows counts);
  connect.
* **Expected:** Japan: connects to a Japanese server (status card shows the flag and country). One-server country: connects there or
  fails within 35 s with a message naming that country; it never silently connects elsewhere.
* **Pass if:** country always respected; failure messages name the country.
* **Evidence:** screenshots of the status card or message.

### C5. Best Connect (Implemented)
* **Procedure:** Best Connect with "Any country" on Wi-Fi; let it finish. Open "View last Best Connect results". Repeat once on mobile data.
  In a third run tap "Connect to leader now" after two servers are tested; in a fourth tap "Cancel" mid-run.
* **Expected:** finishes in a few minutes (emulators: 2.5-8 min) and connects to the winner; the results dialog shows speeds, data used and
  margin; data used at most about 300 MB on Wi-Fi and about 60 MB on mobile data; "Connect to leader now" connects promptly; "Cancel"
  stops and restores the previous state.
* **Pass if:** all four behave as described; data used stays within the caps.
* **Evidence:** screenshots of results dialogs; Android's per-app data usage before and after (Settings > Network > Data usage); screen
  recordings of runs 3 and 4 with times.

### C6. Best Connect in a chosen country (Implemented)
* **Procedure:** Best Connect chip > Japan; run. Then a 1-server country.
* **Expected:** only servers of that country are tested and the winner is there; with one server, it says so honestly.
* **Pass if:** no other country appears in the results.
* **Evidence:** results dialog screenshot.

### C7. Best Connect does not stall on one server (suspected issue, see `docs/CI.md` section 3)
* **Procedure:** During C5 and C6 runs, watch the progress: note the time each server's test starts (leaderboard count increases).
  If any single server takes more than 2 minutes, tap "Connect to leader now", wait 1 minute, then "Cancel", wait 1 minute.
* **Expected (after a fix):** no single server test exceeds about a minute. **Currently suspected:** a test can hang for many minutes when a
  server stalls mid-upload, and the buttons may not take effect until it ends.
* **Pass if:** no stall seen in all Best Connect runs. A stall is a FAIL: record how long, and whether either button worked.
* **Evidence:** screen recording with the clock visible; adb log if available.

### C8. Worldwide search (Implemented)
* **Procedure:** On unmetered Wi-Fi, charger connected: Home > Worldwide search > Start, read and accept the dialog. Let it run with the
  screen off for most of the time. Separately: (a) on mobile data, try to start it with "Allow on mobile data" off; (b) on battery below 20%
  and not charging, try to start it.
* **Expected:** completes within 10-45 min and reports a winner and how much of the network it covered; it keeps running with the screen
  off (notification visible); (a) refuses with an explanation; (b) refuses because of the battery; while running it pauses on low battery or
  lost network and resumes.
* **Pass if:** completes or reports honestly why not; (a) and (b) refuse as described; no crash.
* **Evidence:** screenshots of the consent dialog, progress, result; duration; battery before and after.

### C9. Manual connection from the Servers tab (Implemented)
* **Procedure:** Servers tab: search for a country and a server name; star a favourite and use "★ Favorites"; tap a server to connect;
  use "⋮ > Report & avoid" on one, then "Stop avoiding this server".
* **Expected:** search filters; favourites persist after restarting the app; tapping connects to exactly that server; an avoided server is
  marked "(avoided)", cannot be tapped and is not chosen by Quick or Best Connect until un-avoided.
* **Pass if:** all as described.
* **Evidence:** screenshots.

### C10. Stability over an hour (Partial: reconnects to the same server; no failover)
* **Procedure:** Connect; stream video or browse for 60 minutes on Wi-Fi; note every interruption and whether the app shows "Connection to
  the server was lost. Reconnecting…". Repeat on mobile data.
* **Expected:** stays connected, or recovers to the same server. If the volunteer server disappears, the app keeps trying that server; it
  does **not** move to another one (not implemented). Note how long until you intervened.
* **Pass if:** no crash and no silent loss of protection (the status always reflects reality). Drops caused by volunteers are findings,
  not failures, but report them.
* **Evidence:** notes with times; screenshots of any reconnecting state.

### C11. Speed and latency (Implemented, measurement only)
* **Procedure:** Without the VPN: run a speed test (for example speed.cloudflare.com or fast.com) 3 times. Connect with Quick Connect
  (Japan, then Any): 3 runs each. Then after Best Connect: 3 runs.
* **Expected:** results vary widely with volunteer servers; Best Connect's winner should usually beat a Quick Connect pick.
* **Pass if:** measurements recorded (no pass/fail threshold); a connection that reports "Connected" but passes no traffic is a FAIL.
* **Evidence:** screenshots of each speed test, with the server ID.

---

## P2: robustness, background and resources

### R1. Wi-Fi to mobile data and back while connected (Partial)
* **Procedure:** Connected on Wi-Fi, walk out of range or turn Wi-Fi off; wait 60 s; turn Wi-Fi on again. Reload a page after each change.
* **Expected:** traffic continues after a short pause (OpenVPN reconnects); the status card shows reconnecting, then Connected; no crash.
* **Pass if:** recovers within about 60 s each time or shows a clear state.
* **Evidence:** screen recording; times.

### R2. Airplane mode toggle while connected (Partial)
* **Procedure:** Connected; airplane mode on 30 s, off.
* **Expected:** recovers, or shows a clear reconnecting / not connected state.
* **Pass if:** no crash, no stuck "Connected" with no traffic.
* **Evidence:** screenshots.

### R3. Screen off and Doze (Implemented)
* **Procedure:** Connected, screen off for 30 minutes (phone still), then 2 hours overnight if possible. Wake, reload a page, check the IP.
* **Expected:** still connected (or reconnects quickly) and traffic goes through the VPN.
* **Pass if:** connected, traffic via VPN.
* **Evidence:** screenshots after waking; leak page.

### R4. Background operation (Implemented)
* **Procedure:** Connected; switch to other apps for an hour; open many apps to put memory under pressure.
* **Expected:** the VPN stays up (it runs in its own service process); Android's VPN key icon stays.
* **Pass if:** VPN stays up.
* **Evidence:** screenshot of the status bar and the app after an hour.

### R5. Battery use (Implemented)
* **Procedure:** Full charge. 1 hour connected, idle, screen off. Then 1 hour connected with continuous browsing. Read Settings > Battery >
  usage for VPN App.
* **Expected:** idle use small; active use dominated by the screen and network. No reference numbers exist yet.
* **Pass if:** recorded; anything that drains noticeably while idle is a finding.
* **Evidence:** battery usage screenshots with percentages and durations.

### R6. Memory and CPU (Implemented)
* **Procedure:** Developer options > Running services (or `adb shell dumpsys meminfo io.github.geniusdevam.vpnapp` and
  `...:openvpn`) idle and while connected; `adb shell top -n 1 | grep vpnapp` during Best Connect.
* **Expected:** roughly like the emulators: app process 43-68 MB, VPN process 10-19 MB; CPU busy only during probing and speed tests.
* **Pass if:** recorded; growth over an hour of use (possible leak) is a finding.
* **Evidence:** outputs or screenshots.

### R7. Reboot (Partial: no auto-start without Always-on)
* **Procedure:** Connected; reboot (Always-on off). After boot, open the app.
* **Expected:** not connected after reboot (the app does not start itself); settings, country choices and history are kept.
* **Pass if:** as described; no crash on first open.
* **Evidence:** screenshots.

### R8. History and cache persist (Implemented)
* **Procedure:** After a few connections, check Servers tab per-server history lines. Force-stop the app (App info > Force stop) and reopen;
  then reboot and reopen.
* **Expected:** server list, history, favourites and settings are unchanged.
* **Pass if:** nothing lost.
* **Evidence:** before/after screenshots of the same server's history.

### R9. Clear history (Implemented)
* **Procedure:** Settings > Clear saved server history.
* **Expected:** per-server history disappears; the server list itself and settings remain.
* **Pass if:** as described.
* **Evidence:** before/after screenshots.

### R10. Notifications (Implemented)
* **Procedure:** With notifications allowed, run Quick Connect, Best Connect and worldwide search; pull down the shade during each. Tap the
  notification; on another run tap its "Cancel" action.
* **Expected:** a "VPN App" progress notification while a run is active; tapping opens the app; "Cancel" stops the run.
* **Pass if:** all three runs show it; Cancel works.
* **Evidence:** screenshots of the shade.

### R11. Quick Settings tile (Implemented, Android 7.0+)
* **Procedure:** Edit Quick Settings, add the "VPN App" tile; with the app closed, tap it.
* **Expected:** the shade collapses, the app opens and Quick Connect starts.
* **Pass if:** as described, including from the lock screen after unlocking.
* **Evidence:** screen recording.

### R12. Error handling (Implemented)
* **Procedure:** (a) No internet at all (airplane mode), tap Quick Connect. (b) Start another VPN app while VPN App is connected. (c) Deny
  notification permission (Android 13+) and run Best Connect. (d) Choose a country whose only server fails.
* **Expected:** (a) a clear message within 35 s; (b) VPN App shows "Not connected" (Android revoked it) without crashing; (c) works without a
  notification; (d) an honest failure naming the country.
* **Pass if:** clear messages, no crash, no wrong "Connected".
* **Evidence:** screenshots.

### R13. Crash watch (all tests)
* **Procedure:** Throughout testing, note every "App has stopped" / "keeps stopping" dialog and any freeze over 5 s.
* **Expected:** none.
* **Pass if:** zero crashes over the whole test period.
* **Evidence:** time, what you were doing, `adb logcat -d -b crash` output if possible.

---

## P3: UX, accessibility, updates, long-running

### U1. TalkBack (Implemented, untested)
* **Procedure:** Turn on TalkBack. Complete onboarding, choose a country, run Quick Connect, disconnect, open Settings.
* **Expected:** every control is announced with a meaningful label; country rows are selectable; status changes are readable.
* **Pass if:** the whole flow is possible without sight.
* **Evidence:** notes of any unlabeled or unreachable control.

### U2. Large text and display size (Implemented; emulator-tested)
* **Procedure:** Font size and display size at maximum; repeat C1, C3, C4.
* **Expected:** nothing cut off; Disconnect reachable without hunting; the country picker usable.
* **Pass if:** all reachable and readable.
* **Evidence:** screenshots.

### U3. Rotation, dark mode, tablet/foldable/Chromebook (Implemented; emulator-tested)
* **Procedure:** Rotate during a run and while connected; switch dark mode; on a tablet or foldable, fold/unfold during a run.
* **Expected:** state is kept (tab, open dialog, run continues); side navigation rail on wide screens.
* **Pass if:** no lost state, no crash.
* **Evidence:** screenshots.

### U4. Android TV with a remote (Partial: launcher entry tested in emulators; D-pad not tested)
* **Procedure:** Install on a TV; open from the TV launcher; use only the remote's D-pad for onboarding, country choice, Quick Connect.
* **Expected:** every control can be focused and activated with the D-pad.
* **Pass if:** the whole flow works without touch.
* **Evidence:** photos or recordings of the TV screen.

### U5. Reinstall and future signed updates (Implemented)
* **Procedure:** (a) Install v1.0.0 over itself (same file). (b) When v1.0.1 or later is released: install it over v1.0.0 without
  uninstalling.
* **Expected:** (a) succeeds, data kept; (b) succeeds, settings and history kept; certificate unchanged (README check).
* **Pass if:** both succeed without "App not installed". (b) stays NOT RUN until the next release exists.
* **Evidence:** screenshots; certificate check output for the new APK.

### U6. Seven-day history eviction (Implemented; unit-tested; long-running on device)
* **Procedure:** Use the app normally for at least 9 days, refreshing the list at least daily. Track 2-3 servers in the Servers tab that
  stop appearing in the list (note the day each disappears).
* **Expected:** a server's saved history is deleted only after it has been missing from successful list downloads for 7 days; history of
  servers that remain listed is never deleted for being old.
* **Pass if:** observations match. This is slow and optional; the rule is covered by engine unit tests (`LedgerAndDirectoryTest`).
* **Evidence:** dated screenshots.

---

## Results

| ID | Test | Device | Network | Date | Result | Notes / evidence link |
|---|---|---|---|---|---|---|
| S1 | Install verified APK | | | | NOT RUN | |
| S2 | Old-key build cannot upgrade | | | | NOT RUN | |
| S3 | VPN permission | | | | NOT RUN | |
| S4 | DNS leak | | | | NOT RUN | |
| S5 | IPv4 leak | | | | NOT RUN | |
| S6 | IPv6 leak | | | | NOT RUN | |
| S7 | WebRTC leak | | | | NOT RUN | |
| S8 | Always-on + block | | | | NOT RUN | |
| S9 | Leaks while reconnecting | | | | NOT RUN | |
| S10 | Disconnect stays down | | | | NOT RUN | |
| S11 | Split tunnelling | | | | NOT RUN | |
| S12 | Encryption, Strong only | | | | NOT RUN | |
| S13 | Backups exclude data | | | | NOT RUN | |
| C1 | Onboarding | | | | NOT RUN | |
| C2 | Server list | | | | NOT RUN | |
| C3 | Quick Connect, any | | | | NOT RUN | |
| C4 | Quick Connect, country | | | | NOT RUN | |
| C5 | Best Connect | | | | NOT RUN | |
| C6 | Best Connect, country | | | | NOT RUN | |
| C7 | Best Connect stall check | | | | NOT RUN | |
| C8 | Worldwide search | | | | NOT RUN | |
| C9 | Manual connection | | | | NOT RUN | |
| C10 | Stability, 1 hour | | | | NOT RUN | |
| C11 | Speed and latency | | | | NOT RUN | |
| R1 | Wi-Fi / mobile switch | | | | NOT RUN | |
| R2 | Airplane toggle | | | | NOT RUN | |
| R3 | Screen off / Doze | | | | NOT RUN | |
| R4 | Background | | | | NOT RUN | |
| R5 | Battery | | | | NOT RUN | |
| R6 | Memory and CPU | | | | NOT RUN | |
| R7 | Reboot | | | | NOT RUN | |
| R8 | History persists | | | | NOT RUN | |
| R9 | Clear history | | | | NOT RUN | |
| R10 | Notifications | | | | NOT RUN | |
| R11 | Quick Settings tile | | | | NOT RUN | |
| R12 | Error handling | | | | NOT RUN | |
| R13 | Crash watch | | | | NOT RUN | |
| U1 | TalkBack | | | | NOT RUN | |
| U2 | Large text | | | | NOT RUN | |
| U3 | Rotation, dark mode, large screens | | | | NOT RUN | |
| U4 | Android TV remote | | | | NOT RUN | |
| U5 | Reinstall and updates | | | | NOT RUN | |
| U6 | 7-day eviction | | | | NOT RUN | |
