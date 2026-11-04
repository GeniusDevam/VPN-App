#!/usr/bin/env bash
# Runs inside an Android emulator in CI: installs the app, walks through onboarding, runs a real
# Quick Connect against live VPN Gate servers, then a Best Connect, saving screenshots + logs.
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

log "install"; adb install -r "$APK" || { log "INSTALL FAILED"; exit 1; }
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell dumpsys package $PKG | grep -E "versionName|versionCode|targetSdk" | head -3 | tee "$OUT/package.txt"
adb shell setprop log.tag.VPNAPP DEBUG
adb logcat -c
adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 8
shot 01-onboarding

tap_text "I understand, continue" || { shot 02-fail; adb logcat -d > "$OUT/logcat.txt"; exit 1; }
sleep 4; shot 02-home

log "Quick Connect"
tap_text "Connect" ; sleep 3; shot 03-after-tap
dump
if has_text "Connection request|wants to set up a VPN"; then log "VPN consent dialog"; shot 03b-consent; tap_text "OK"; fi

result="timeout"
for i in $(seq 1 24); do
  sleep 5; dump
  if has_text "^.{0,2}\s?Connected$"; then result="connected"; break; fi
  if has_text "no usable servers|No server in|Could not connect|went wrong"; then result="failed"; break; fi
  [ $((i % 3)) -eq 0 ] && shot "04-quick-progress-$i"
done
if [ "$result" = "failed" ]; then
  log "first Quick Connect failed; retrying once with a warm server list"
  tap_text "OK" || true; sleep 2
  tap_text "Connect"; sleep 3
  for i in $(seq 1 24); do
    sleep 5; dump
    if has_text "^.{0,2}\s?Connected$"; then result="connected-on-retry"; break; fi
    if has_text "no usable servers|No server in|Could not connect|went wrong"; then result="failed-twice"; break; fi
  done
fi
log "Quick Connect result: $result"; shot 05-quick-result; dump; cp "$OUT/ui.xml" "$OUT/ui-quick-result.xml"
echo "quick=$result" > "$OUT/result.txt"

if [ "$result" = "connected" ] || [ "$result" = "connected-on-retry" ]; then
  log "Best Connect"
  tap_text "Find the best"; sleep 3
  bres="timeout"
  for i in $(seq 1 60); do
    sleep 10; dump
    if has_text "Best server:"; then bres="done"; break; fi
    if has_text "no usable servers|No server in|went wrong"; then bres="failed"; break; fi
    [ $((i % 3)) -eq 0 ] && shot "06-best-progress-$i"
  done
  log "Best Connect result: $bres"; shot 07-best-result; cp "$OUT/ui.xml" "$OUT/ui-best-result.xml"
  echo "best=$bres" >> "$OUT/result.txt"
fi

adb logcat -d > "$OUT/logcat.txt"
adb logcat -d -s AndroidRuntime:E > "$OUT/crashes.txt"
report() {
  echo "================ screen text after Quick Connect"
  python3 - "$OUT/ui-quick-result.xml" <<'PY'
import sys,xml.etree.ElementTree as ET
try:
    for n in ET.parse(sys.argv[1]).getroot().iter('node'):
        t=(n.get('text') or '').strip()
        if t: print('  |',t.replace('\n',' ')[:200])
except Exception as e: print('  (no dump)',e)
PY
  if [ -f "$OUT/ui-best-result.xml" ]; then
    echo "================ screen text after Best Connect"
    python3 - "$OUT/ui-best-result.xml" <<'PY'
import sys,xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).getroot().iter('node'):
    t=(n.get('text') or '').strip()
    if t: print('  |',t.replace('\n',' ')[:200])
PY
  fi
  echo "================ VPNAPP debug log"
  grep -E " VPNAPP *:" "$OUT/logcat.txt" | sed -E 's/^[0-9-]+ //' | cut -c1-300 | head -400
  echo "================ crashes"
  grep -E "FATAL EXCEPTION|AndroidRuntime: *(at|Caused|java|kotlin)" "$OUT/logcat.txt" | head -40
}
report
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then log "APP CRASHED"; echo "crash=yes" >> "$OUT/result.txt"; exit 1; fi
cat "$OUT/result.txt"
exit 0
