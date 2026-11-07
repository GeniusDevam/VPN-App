#!/usr/bin/env python3
"""Turns tools/speedrun.sh output into Markdown tables, including a per-phase timeline of every run taken from the app's own
debug log (device clock, millisecond resolution). Usage: speedrun-report.py <speedrun.txt> <runs.log>"""
import re, sys

kv, extra = {}, []
for line in open(sys.argv[1], errors="replace"):
    line = line.rstrip("\n")
    if line.startswith("best_report: "):
        extra.append(line[len("best_report: "):])
    elif "=" in line:
        k, v = line.split("=", 1); kv[k] = v

TS = re.compile(r"^\d\d-\d\d (\d\d):(\d\d):(\d\d)\.(\d{3})\s+\d+\s+\d+\s+\w\s+VPNAPP\s*:\s*(.*)$")
runs, cur = [], None
for line in open(sys.argv[2], errors="replace"):
    line = line.rstrip("\n")
    if line.startswith("--- "):
        cur = {"name": line[4:], "events": []}; runs.append(cur); continue
    m = TS.match(line)
    if m and cur is not None:
        t = ((int(m[1]) * 60 + int(m[2])) * 60 + int(m[3])) * 1000 + int(m[4])
        cur["events"].append((t, m[5]))

def timeline(ev):
    """Seconds from the first app event of the run to each milestone, and the duration of every connection attempt."""
    if not ev: return None
    t0 = ev[0][0]; out = {}; attempts = []; start = None
    for t, msg in ev:
        rel = (t - t0) / 1000
        if msg.startswith("phase SCANNING") and "probing" not in out: out["probing"] = rel
        if msg.startswith("phase SPEED_TESTING") and "speed tests" not in out: out["speed tests"] = rel
        if msg.startswith("phase CONFIRMING") and "confirming" not in out: out["confirming"] = rel
        m = re.match(r"connect (\S+) (UDP|TCP)$", msg)
        if m: start = (t, m[1])
        m = re.match(r"connect (\S+) -> (.*)$", msg)
        if m and start:
            attempts.append(((t - start[0]) / 1000, m[2])); start = None
            if m[2] == "up": out["up"] = rel
    return out, attempts

def num(k): return kv.get(k, "?")
print(f"## Speed run: {num('device')}\n")
print("| launch / memory | value |\n|---|---|")
print(f"| APK size | {num('apk_kib')} KiB |")
parts = ", ".join(f"{k[len('apk_part_'):].replace('lib_', 'native ')} {int(v) / 1024:.1f} MB" for k, v in sorted(kv.items())
                  if k.startswith("apk_part_") and v.isdigit())
if parts: print(f"| APK contents (compressed) | {parts} |")
print(f"| installed on the device (APK + extracted native libraries) | {num('installed_kib')} KiB |")
print(f"| first launch (install, no data) | {num('first_launch_ms')} ms |")
print(f"| cold start, median (all) | {num('cold_start_ms_median')} ms ({num('cold_start_ms_all')}) |")
print(f"| warm start, median (all) | {num('warm_start_ms_median')} ms ({num('warm_start_ms_all')}) |")
for k, label in (("mem_idle_app_pss_kb", "memory idle, app process (PSS)"), ("mem_connected_app_pss_kb", "memory connected, app process"),
                 ("mem_connected_vpn_pss_kb", "memory connected, VPN engine process")):
    v = kv.get(k, "")
    print(f"| {label} | {int(v) / 1024:.0f} MB |" if v.isdigit() else f"| {label} | ? |")

print(f"\n### Quick Connect x{len([k for k in kv if re.fullmatch(r'quick_\d+', k)])}: {num('quick_success')} connected and stayed up{', ' + num('quick_dropped') + ' connected then dropped by the server' if kv.get('quick_dropped', '0') not in ('0', '') else ''}, median {num('quick_ms_median')} ms (tap to 'Connected' on screen)\n")
print("| run | result (screen) | list ready | probing done / first attempt | handshakes (s, result) | up at | server | encryption | app banner |\n|---|---|---|---|---|---|---|---|---|")
qruns = [r for r in runs if r["name"].startswith("quick run")]
for i, k in enumerate(sorted((k for k in kv if re.fullmatch(r"quick_\d+", k)), key=lambda x: int(x.split("_")[1]))):
    parts = [p.strip() for p in kv[k].split("|")]
    tl = timeline(qruns[i]["events"]) if i < len(qruns) else None
    if tl:
        out, att = tl
        first_attempt = next((t for t, m in [((e[0] - qruns[i]["events"][0][0]) / 1000, e[1]) for e in qruns[i]["events"]] if m.startswith("connecting #1")), None)
        hs = ", ".join(f"{d:.1f} {r}" for d, r in att) or "-"
        print(f"| {k.split('_')[1]} | {parts[0]} | {out.get('probing', '-')} s | {first_attempt if first_attempt is not None else '-'} s | {hs} | {out.get('up', '-')} s | {parts[1] if len(parts) > 1 else '?'} | {parts[2] if len(parts) > 2 else '?'} | {parts[3] if len(parts) > 3 else '-'} |")
    else:
        print(f"| {k.split('_')[1]} | {parts[0]} | - | - | - | - | {parts[1] if len(parts) > 1 else '?'} | {parts[2] if len(parts) > 2 else '?'} | {parts[3] if len(parts) > 3 else '-'} |")
print("\n(list ready / probing / up: seconds after the app started the run, from its own log; handshakes: each OpenVPN connect attempt in order.)")

print(f"\n### Best Connect: {num('best')}\n")
b = next((r for r in runs if r["name"] == "best run"), None)
if b and b["events"]:
    out, att = timeline(b["events"])
    print("| milestone | seconds after start |\n|---|---|")
    for key in ("probing", "speed tests", "confirming", "up"):
        if key in out: print(f"| {key} | {out[key]:.1f} |")
    print(f"| tunnels opened for tests + final | {len(att)} ({sum(1 for _, r in att if r == 'up')} up) |")
for line in extra:
    print(f"- {line}")
