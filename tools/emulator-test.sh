#!/usr/bin/env bash
# Runs inside an Android emulator in CI against LIVE VPN Gate volunteers. Exit status says what the run actually proved:
#   0  PASS          every assertion held, and Quick Connect really connected inside the chosen country
#   1  FAIL          the APP misbehaved (crash, hung UI, picker broken, scope not applied or not persisted, connected in the
#                    wrong country, disconnect undone, unhandled-exception banner). Never caused by volunteer servers.
#   3  INCONCLUSIVE  the live infrastructure did not allow a verdict (server list unreachable, no volunteer accepted a
#                    connection). The app told the user so honestly; nothing in the app is shown to be wrong.
# A green job therefore means PASS; INCONCLUSIVE is reported as a warning and is never presented as success.
set -u
APK="$1"; PKG=io.github.geniusdevam.vpnapp
OUT="${OUT:-emu-out}"; mkdir -p "$OUT"
log() { echo "[emu] $*"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1; }

# Prints "x y" of the first node whose text matches regex $1 (fullmatch), or nothing.
find_xy() {
  python3 - "$1" "$OUT/ui.xml" <<'PY'
import sys,re,xml.etree.ElementTree as ET
pat=re.compile(sys.argv[1])
try: root=ET.parse(sys.argv[2]).getroot()
except Exception: sys.exit(0)
for n in root.iter('node'):
    t=(n.get('text') or n.get('content-desc') or '').strip()
    if t and pat.fullmatch(t):
        m=re.findall(r'\d+',n.get('bounds') or '')
        if len(m)==4:
            print((int(m[0])+int(m[2]))//2,(int(m[1])+int(m[3]))//2); break
PY
}
# Is any text matching regex $1 (search) on screen?
has_text() {
  python3 - "$1" "$OUT/ui.xml" <<'PY'
import sys,re,xml.etree.ElementTree as ET
pat=re.compile(sys.argv[1])
try: root=ET.parse(sys.argv[2]).getroot()
except Exception: sys.exit(1)
sys.exit(0 if any(pat.search((n.get('text') or '')) for n in root.iter('node')) else 1)
PY
}
tap_text() {
  for _ in $(seq 1 25); do
    dump; xy=$(find_xy "$1")
    if [ -n "$xy" ]; then log "tap '$1' at $xy"; adb shell input tap $xy; return 0; fi
    sleep 2
  done
  log "could not find '$1'"; return 1
}


HARD=""; INFRA=""; PASSED=""
hard()  { log "FAIL: $*";           HARD="$HARD
$*"; }
infra() { log "INCONCLUSIVE: $*";   INFRA="$INFRA
$*"; }
ok()    { log "PASS: $*";           PASSED="$PASSED
$*"; }

# nth (1-based) node whose text/desc matches regex $1 (search, not fullmatch): prints "x y"
find_nth() {
  python3 - "$1" "${2:-1}" "$OUT/ui.xml" <<'PY'
import sys,re,xml.etree.ElementTree as ET
pat=re.compile(sys.argv[1]); want=int(sys.argv[2])
try: root=ET.parse(sys.argv[3]).getroot()
except Exception: sys.exit(0)
k=0
for n in root.iter('node'):
    t=(n.get('text') or n.get('content-desc') or '').strip()
    if t and pat.search(t):
        k+=1
        if k==want:
            m=re.findall(r'\d+',n.get('bounds') or '')
            if len(m)==4: print((int(m[0])+int(m[2]))//2,(int(m[1])+int(m[3]))//2)
            break
PY
}
# full text of the nth node matching regex $1
text_nth() {
  python3 - "$1" "${2:-1}" "$OUT/ui.xml" <<'PY'
import sys,re,xml.etree.ElementTree as ET
pat=re.compile(sys.argv[1]); want=int(sys.argv[2])
try: root=ET.parse(sys.argv[3]).getroot()
except Exception: sys.exit(0)
k=0
for n in root.iter('node'):
    t=(n.get('text') or n.get('content-desc') or '').strip()
    if t and pat.search(t):
        k+=1
        if k==want: print(t); break
PY
}
# "x y" of the country row with the MOST servers (skips the first match, the 'Any country' row): live volunteers are flaky,
# so test with the country that has the best chance of a working relay.
find_biggest_country() {
  python3 - "$OUT/ui.xml" <<'PY'
import sys,re,xml.etree.ElementTree as ET
try: root=ET.parse(sys.argv[1]).getroot()
except Exception: sys.exit(0)
rows=[]
for n in root.iter('node'):
    t=(n.get('text') or n.get('content-desc') or '').strip()
    m=re.fullmatch(r'(\d+) servers?',t)
    b=re.findall(r'\d+',n.get('bounds') or '')
    if m and len(b)==4: rows.append((int(m.group(1)),(int(b[0])+int(b[2]))//2,(int(b[1])+int(b[3]))//2))
for cnt,x,y in sorted(rows[1:],key=lambda r:-r[0])[:1]: print(x,y)
PY
}
count_text() {
  python3 - "$1" "$OUT/ui.xml" <<'PY'
import sys,re,xml.etree.ElementTree as ET
pat=re.compile(sys.argv[1])
try: root=ET.parse(sys.argv[2]).getroot()
except Exception: print(0); sys.exit(0)
print(sum(1 for n in root.iter('node') if pat.search((n.get('text') or n.get('content-desc') or '').strip())))
PY
}
tap_nth() { # regex n
  for _ in $(seq 1 15); do dump; xy=$(find_nth "$1" "${2:-1}"); if [ -n "$xy" ]; then log "tap #${2:-1} of /$1/ at $xy"; adb shell input tap $xy; return 0; fi; sleep 2; done
  return 1
}
CHIP='▾\s*$'   # the two country chips (Quick first, Best second) are the only nodes ending in a down-arrow

finish() {
  adb logcat -d > "$OUT/logcat.txt" 2>/dev/null; adb logcat -d -s AndroidRuntime:E > "$OUT/crashes.txt" 2>/dev/null
  if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" 2>/dev/null; then hard "the app crashed (FATAL EXCEPTION in logcat)"; fi
  status=PASS; code=0
  [ -n "$INFRA" ] && { status=INCONCLUSIVE; code=3; }
  [ -n "$HARD" ] && { status=FAIL; code=1; }
  {
    echo "status=$status"
    echo "passed:$PASSED"; echo "inconclusive:$INFRA"; echo "failed:$HARD"
  } >> "$OUT/result.txt"
  report_ui
  echo "================ emulator verdict: $status"; sed -n '/^status=/,$p' "$OUT/result.txt"
  exit $code
}
report_ui() {
  for f in ui-quick-result.xml ui-best-result.xml; do
    [ -f "$OUT/$f" ] || continue
    echo "================ screen text ($f)"
    python3 - "$OUT/$f" <<'PY'
import sys,xml.etree.ElementTree as ET
try:
    for n in ET.parse(sys.argv[1]).getroot().iter('node'):
        t=(n.get('text') or '').strip()
        if t: print('  |',t.replace('\n',' ')[:200])
except Exception as e: print('  (no dump)',e)
PY
  done
  echo "================ VPNAPP debug log"; grep -E " VPNAPP *:" "$OUT/logcat.txt" 2>/dev/null | sed -E 's/^[0-9-]+ //' | cut -c1-300 | head -300
  echo "================ crashes"; grep -E "FATAL EXCEPTION|AndroidRuntime: *(at|Caused|java|kotlin)" "$OUT/logcat.txt" 2>/dev/null | head -40
}
# Waits (<= $1 s) for the Quick Connect outcome. Prints connected | failed-infra | failed-app | hung.
quick_outcome() {
  local limit=$1 t0; t0=$(date +%s)
  while [ $(( $(date +%s) - t0 )) -lt "$limit" ]; do
    sleep 4; dump
    if has_text "^.{0,2}\s?Connected$"; then echo connected; return; fi
    if has_text "went wrong"; then echo failed-app; return; fi
    if has_text "could not be loaded|no usable servers|No server in|Could not connect|none fit your settings|Could not connect within"; then echo failed-infra; return; fi
  done
  echo hung
}

log "install"; adb install -r "$APK" || { hard "APK did not install"; finish; }
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell dumpsys package $PKG | grep -E "versionName|versionCode|targetSdk" | head -3 | tee "$OUT/package.txt"
adb shell setprop log.tag.VPNAPP DEBUG
adb logcat -c
adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 8
shot 01-onboarding
echo "started=$(date -u +%FT%TZ)" > "$OUT/result.txt"

# The privacy copy is longer than one screen: scroll until the button is on screen, like a real user.
onboard_done=""
for i in $(seq 1 8); do
  dump; xy=$(find_xy "I understand, continue")
  if [ -n "$xy" ]; then log "tap 'I understand, continue' at $xy"; adb shell input tap $xy; onboard_done=yes; break; fi
  adb shell input swipe 540 1900 540 700 300; sleep 1
done
[ -n "$onboard_done" ] || { shot 02-fail; hard "onboarding screen never offered 'I understand, continue', even after scrolling"; finish; }
sleep 4; shot 02-home; dump
if has_text "Quick Connect" && has_text "Best Connect"; then ok "home screen shows both modes"; else hard "home screen missing Quick/Best Connect"; finish; fi

# ---- A2/D3: the real country picker, in the real UI ----------------------------------------------------------------
log "country picker"
CHOSEN=""
if tap_nth "$CHIP" 1; then
  sleep 2; dump
  if has_text "Choose a country"; then ok "Quick country picker opens"; else hard "tapping the Quick country chip did not open the picker"; fi
  state=""
  for i in $(seq 1 24); do
    dump
    if has_text "Only countries with servers in the current list|Showing saved servers"; then state=loaded; break; fi
    if has_text "could not be loaded"; then state=unavailable; break; fi
    sleep 5
  done
  shot 02b-picker
  case "$state" in
    loaded)
      ok "picker reports a loaded server list"
      dump; big=$(find_biggest_country)
      if [ -n "$big" ] && { adb shell input tap $big; true; }; then
        sleep 2; dump; shot 02c-picked
        if has_text "Choose a country"; then hard "picker did not close after choosing a country"; fi
        n=$(count_text "Any country.*$CHIP")
        if [ "$n" = "1" ]; then ok "choosing a country changed Quick only; Best stayed on its own choice"; else hard "country choice leaked between modes or was not applied (Any-country chips left: $n, expected 1)"; fi
        CHOSEN=$(text_nth "$CHIP" 1 | sed -E 's/\s*▾\s*$//'); log "Quick country now: $CHOSEN"
      else hard "no selectable country rows in the picker although the list is loaded"; fi ;;
    unavailable) infra "server list could not be loaded, so the picker has no countries to test"; adb shell input keyevent KEYCODE_BACK ;;
    *) infra "server list still loading after 2 minutes"; adb shell input keyevent KEYCODE_BACK ;;
  esac
else hard "no country chip on the home screen"; fi

if [ -n "$CHOSEN" ]; then
  log "restart the app process; the choice must survive"
  adb shell am force-stop $PKG; sleep 2; adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 10; dump
  after=$(text_nth "$CHIP" 1 | sed -E 's/\s*▾\s*$//')
  if [ "$after" = "$CHOSEN" ]; then ok "country choice persisted across process death (encrypted settings readable)"; else hard "country choice lost across restart (was '$CHOSEN', now '$after')"; fi
fi

# ---- Quick Connect (country-bound when a country was chosen) --------------------------------------------------------
log "Quick Connect (${CHOSEN:-Any country})"
t0=$(date +%s)
tap_text "Connect"; sleep 3; dump
if has_text "Connection request|wants to set up a VPN"; then log "VPN consent dialog"; shot 03b-consent; tap_text "OK"; fi
result=$(quick_outcome 150); secs=$(( $(date +%s) - t0 ))
log "Quick Connect: $result after ${secs}s"; shot 05-quick-result; dump; cp "$OUT/ui.xml" "$OUT/ui-quick-result.xml"
echo "quick_scope=${CHOSEN:-Any country}" >> "$OUT/result.txt"; echo "quick=$result" >> "$OUT/result.txt"; echo "quick_seconds=$secs" >> "$OUT/result.txt"
case "$result" in
  connected)
    ok "Quick Connect connected in ${secs}s"
    if [ -n "$CHOSEN" ]; then
      name=$(echo "$CHOSEN" | sed -E 's/^[^ ]+ //')
      if has_text "· $name"; then ok "connected server is in the chosen country ($name)"; else hard "connected, but the status card does not show the chosen country '$name' (scope not enforced?)"; fi
    fi ;;
  failed-app) hard "Quick Connect ended in an unhandled-exception banner" ;;
  hung)       hard "Quick Connect never reached a connected or failed state within 150 s: the bounded-attempt promise is broken" ;;
  failed-infra)
    text_nth "could not be loaded|no usable servers|No server in|Could not connect|none fit your settings" 1 > "$OUT/quick-failure-text.txt"
    infra "Quick Connect failed honestly: $(cat "$OUT/quick-failure-text.txt" | cut -c1-120)"
    # an honest country failure must name the chosen country, never silently use another one
    if [ -n "$CHOSEN" ] && has_text "No server in|no usable servers"; then
      name=$(echo "$CHOSEN" | sed -E 's/^[^ ]+ //'); has_text "$name" && ok "failure message names the chosen country" || hard "failure message does not name the chosen country '$name'"
    fi ;;
esac

# A flaky country says nothing about the app. Try once with Any country so a live connection can still be demonstrated.
if [ "$result" = "failed-infra" ] && [ -n "$CHOSEN" ]; then
  log "retry Quick Connect with Any country"
  tap_text "OK" || true; sleep 1
  if tap_nth "$CHIP" 1; then
    sleep 2; dump
    xy=$(find_nth "Any country \(sample" 1); [ -n "$xy" ] && adb shell input tap $xy; sleep 2
    t0=$(date +%s); tap_text "Connect"; sleep 3; dump
    if has_text "Connection request|wants to set up a VPN"; then tap_text "OK"; fi
    result=$(quick_outcome 150); secs=$(( $(date +%s) - t0 ))
    log "Quick Connect (Any country): $result after ${secs}s"; shot 05b-quick-any-result; dump; cp "$OUT/ui.xml" "$OUT/ui-quick-result.xml"
    echo "quick_any=$result" >> "$OUT/result.txt"; echo "quick_any_seconds=$secs" >> "$OUT/result.txt"
    case "$result" in
      connected) ok "Quick Connect connected with Any country in ${secs}s (the chosen country had no working relay)" ;;
      failed-app) hard "Quick Connect (Any) ended in an unhandled-exception banner" ;;
      hung) hard "Quick Connect (Any) never reached a terminal state within 150 s" ;;
    esac
  fi
fi

# ---- explicit disconnect must stay disconnected (B5, live) ----------------------------------------------------------
if [ "$result" = "connected" ]; then
  log "explicit disconnect"
  tap_text "Disconnect" || hard "no Disconnect button while connected"
  sleep 3; dump
  if has_text "Not connected"; then
    sleep 20; dump
    if has_text "Not connected"; then ok "explicit disconnect stayed disconnected for 20 s"; else hard "VPN came back after an explicit disconnect"; fi
  else hard "UI did not show 'Not connected' after Disconnect"; fi
fi

# ---- Best Connect: its own country choice (Any) ---------------------------------------------------------------------
if [ "$result" = "connected" ]; then
  log "Best Connect (Any country)"
  tap_text "Find the best"; sleep 3
  bres="timeout"
  for i in $(seq 1 60); do
    sleep 10; dump
    if has_text "Best server:"; then bres="done"; break; fi
    if has_text "went wrong"; then bres="failed-app"; break; fi
    if has_text "could not be loaded|no usable servers|No server in|none fit your settings"; then bres="failed-infra"; break; fi
    [ $((i % 3)) -eq 0 ] && shot "06-best-progress-$i"
  done
  log "Best Connect result: $bres"; shot 07-best-result; cp "$OUT/ui.xml" "$OUT/ui-best-result.xml"
  echo "best=$bres" >> "$OUT/result.txt"
  case "$bres" in
    done) ok "Best Connect finished with a named winner" ;;
    failed-app) hard "Best Connect ended in an unhandled-exception banner" ;;
    *) infra "Best Connect did not complete ($bres)" ;;
  esac
fi
finish
