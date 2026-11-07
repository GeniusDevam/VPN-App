#!/usr/bin/env bash
# Speed runs for the performance review (docs/PERFORMANCE.md). Runs in an Android emulator against LIVE VPN Gate volunteers:
#   - APK size, first launch, cold start x5, warm start x3, idle memory
#   - Quick Connect x QUICK_RUNS (Any country), each followed by an explicit disconnect; memory while connected
#   - Best Connect x1 (Any country) with its results dialog (speed-tested throughput, data used)
#   - a per-phase timeline of every run from the app's own debug log (device clock): list load, probing, each handshake
# Writes $OUT/speedrun.txt (raw key=value) and $OUT/speedrun.md (tables). Never fails the job: numbers are reported, not judged.
set -u
EMU_LIB_ONLY=1 source "$(dirname "$0")/emulator-test.sh" "$1"
QUICK_RUNS="${QUICK_RUNS:-6}"
R="$OUT/speedrun.txt"; : > "$R"
kv() { echo "$1=$2" | tee -a "$R"; }
ms() { date +%s%3N; }
median() { tr ' ' '\n' | grep -E '^[0-9]+$' | sort -n | awk '{a[NR]=$1} END{if(NR){print (NR%2)?a[(NR+1)/2]:int((a[NR/2]+a[NR/2+1])/2)} else print "?"}'; }

kv apk_kib "$(du -k "$APK" | cut -f1)"
# Where the download size goes (compressed bytes inside the APK), for the size findings in docs/PERFORMANCE.md.
python3 -I - "$APK" <<'PY' | while read -r k v; do kv "$k" "$v"; done
import sys, zipfile, collections
z = zipfile.ZipFile(sys.argv[1]); c = collections.Counter()
for i in z.infolist():
    n = i.filename
    k = ("apk_part_dex" if n.endswith(".dex") else f"apk_part_lib_{n.split('/')[1]}" if n.startswith("lib/") and n.count("/") >= 2
         else "apk_part_res" if n.startswith("res/") or n == "resources.arsc" else "apk_part_assets" if n.startswith("assets/")
         else "apk_part_other")
    c[k] += i.compress_size
for k, v in sorted(c.items()): print(k, v // 1024)
PY
adb install -r "$APK" >/dev/null || { kv error "install failed"; exit 0; }
# Installed footprint: the APK plus native libraries extracted next to it (upstream needs them extracted: it runs the OpenVPN
# binary from the library folder). Needs a root-capable emulator image; "?" otherwise.
adb root >/dev/null 2>&1; sleep 2; adb wait-for-device
CODE=$(adb shell dumpsys package $PKG | tr -d '\r' | sed -n 's/^ *codePath=//p' | head -1)
kv installed_kib "$(adb shell du -sk "$CODE" 2>/dev/null | tr -d '\r' | awk '{print $1}' | grep -E '^[0-9]+$' || echo '?')"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
read -r W H <<< "$(adb shell wm size | tr -d '\r' | awk -F'[: x]+' '/size/{w=$(NF-1); h=$NF} END{print w, h}')"
case "${W:-}${H:-}" in ''|*[!0-9]*) W=1080; H=2400 ;; esac
kv device "Android $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r')), $(adb shell getprop ro.product.cpu.abi | tr -d '\r'), ${W}x${H}"
adb shell setprop log.tag.VPNAPP DEBUG
adb logcat -c

# ---- launch performance -----------------------------------------------------------------------------------------------
kv first_launch_ms "$(adb shell am start -W -n $PKG/.MainActivity | tr -d '\r' | sed -n 's/^TotalTime: *//p')"
sleep 6
for i in $(seq 1 10); do dump; xy=$(find_xy "I understand, continue"); [ -n "$xy" ] && { adb shell input tap $xy; break; }; swipe_up; done
sleep 8   # let the first server-list download finish before measuring starts
cold=""; for i in 1 2 3 4 5; do
  adb shell am force-stop $PKG; sleep 2
  cold="$cold $(adb shell am start -W -n $PKG/.MainActivity | tr -d '\r' | sed -n 's/^TotalTime: *//p')"; sleep 4
done
kv cold_start_ms_all "$(echo $cold)"; kv cold_start_ms_median "$(echo $cold | median)"
warm=""; for i in 1 2 3; do
  adb shell input keyevent KEYCODE_HOME; sleep 2
  warm="$warm $(adb shell am start -W -n $PKG/.MainActivity | tr -d '\r' | sed -n 's/^TotalTime: *//p')"; sleep 3
done
kv warm_start_ms_all "$(echo $warm)"; kv warm_start_ms_median "$(echo $warm | median)"
sleep 5; kv mem_idle_app_pss_kb "$(pss_kb $PKG)"

# ---- Quick Connect x N --------------------------------------------------------------------------------------------------
ok_n=0; dropped_n=0; times=""
for run in $(seq 1 "$QUICK_RUNS"); do
  adb logcat -c 2>/dev/null; echo "--- quick run $run" >> "$OUT/runs.log"
  t0=$(ms); tap_scrolling id quick_connect >/dev/null || { kv "quick_${run}" "no-button"; continue; }
  res=hung
  while [ $(( $(ms) - t0 )) -lt 150000 ]; do
    dump
    if has_text "Connection request|wants to set up a VPN"; then tap_text "OK" >/dev/null; continue; fi
    if has_text "^.{0,2}\s?Connected$"; then res=connected; break; fi
    # success banner without a "Connected" headline: the tunnel came up and the server then dropped it (see emulator-test.sh)
    if has_text "^Connected to .* in [0-9]+ s"; then res=dropped; break; fi
    if has_text "went wrong|could not be loaded|no usable servers|No server in|Could not connect"; then res=failed; break; fi
    sleep 0.5
  done
  dt=$(( $(ms) - t0 ))
  where=$(text_nth "· " 1); enc=$(text_nth "^Encryption: " 1 | cut -d'·' -f1)
  kv "quick_${run}" "$res ${dt}ms | ${where:-?} | ${enc:-?}"
  adb logcat -d -v threadtime -s VPNAPP:D | tr -d '\r' >> "$OUT/runs.log"
  if [ "$res" = connected ]; then
    ok_n=$((ok_n+1)); times="$times $dt"
    if [ "$ok_n" = 1 ]; then sleep 4; kv mem_connected_app_pss_kb "$(pss_kb $PKG)"; kv mem_connected_vpn_pss_kb "$(pss_kb $PKG:openvpn)"; fi
    tap_text "Disconnect" >/dev/null; sleep 4
  elif [ "$res" = dropped ]; then
    dropped_n=$((dropped_n+1)); tap_text "Stop" >/dev/null || tap_text "Disconnect" >/dev/null || true; sleep 4
  else
    tap_text "OK" >/dev/null || true
  fi
  sleep 6
done
kv quick_success "$ok_n/$QUICK_RUNS"; kv quick_dropped "$dropped_n"; kv quick_ms_median "$(echo $times | median)"; kv quick_ms_all "$(echo $times)"

# ---- Best Connect x1 ----------------------------------------------------------------------------------------------------
adb logcat -c 2>/dev/null; echo "--- best run" >> "$OUT/runs.log"
t0=$(ms); tap_scrolling id best_connect >/dev/null; bres=timeout
while [ $(( $(ms) - t0 )) -lt 900000 ]; do
  sleep 5; dump
  if has_text "Connection request|wants to set up a VPN"; then tap_text "OK" >/dev/null; continue; fi
  if has_text "Best server:"; then bres=done; break; fi
  if has_text "went wrong|could not be loaded|no usable servers|No server in"; then bres=failed; break; fi
done
kv best "$bres $(( $(ms) - t0 ))ms"
adb logcat -d -v threadtime -s VPNAPP:D | tr -d '\r' >> "$OUT/runs.log"
if [ "$bres" = done ]; then
  tap_scrolling re "^View last Best Connect results$" >/dev/null && sleep 2 && dump
  python3 - "$OUT/ui.xml" <<'PY' | sed 's/^/best_report: /' | tee -a "$R"
import sys,xml.etree.ElementTree as ET
try:
    for n in ET.parse(sys.argv[1]).getroot().iter('node'):
        t=(n.get('text') or '').strip()
        if t and ('Mbps' in t or 'Probed' in t or 'Winner' in t or 'Margin' in t): print(t.replace('\n',' ')[:300])
except Exception as e: print('no dialog', e)
PY
  tap_text "Close" >/dev/null || adb shell input keyevent KEYCODE_BACK
  scroll_top; tap_text "Disconnect" >/dev/null || true
fi
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null
python3 -I "$(dirname "$0")/speedrun-report.py" "$R" "$OUT/runs.log" > "$OUT/speedrun.md"
cat "$OUT/speedrun.md"
exit 0
