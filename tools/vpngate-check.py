#!/usr/bin/env python3
"""Differential diagnosis: can a standard desktop OpenVPN client connect to VPN Gate servers from this network?
Fetches the live list, runs real `openvpn` against a sample of servers (routes/addresses are NOT applied), and
reports per server whether the tunnel came up, or the first error. Run in CI; never changes the host's routing."""
import base64, csv, concurrent.futures as cf, os, re, subprocess, sys, tempfile, urllib.request

N = int(os.environ.get("N", "16"))
raw = urllib.request.urlopen("https://www.vpngate.net/api/iphone/", timeout=60).read().decode("utf-8", "replace")
rows = [r for r in csv.reader(l for l in raw.splitlines() if l and l[0] not in "*#") if len(r) >= 15]
print(f"servers in list: {len(rows)}")
servers = []
for r in rows:
    try:
        cfg = base64.b64decode(r[-1]).decode("utf-8", "replace")
        proto = re.search(r"^proto\s+(\S+)", cfg, re.M); remote = re.search(r"^remote\s+(\S+)\s+(\d+)", cfg, re.M)
        cipher = re.search(r"^cipher\s+(\S+)", cfg, re.M); auth_up = bool(re.search(r"^auth-user-pass", cfg, re.M))
        servers.append(dict(id=r[0], ip=r[1], cc=r[6], speed=int(r[4] or 0), cfg=cfg, proto=(proto.group(1) if proto else "?"),
                            port=(remote.group(2) if remote else "?"), cipher=(cipher.group(1) if cipher else "-"),
                            has_cert="<cert>" in cfg, auth_up=auth_up, tlsauth=("tls-auth" in cfg or "tls-crypt" in cfg)))
    except Exception:
        pass
from collections import Counter
print("proto:", Counter(s["proto"] for s in servers)); print("cipher:", Counter(s["cipher"] for s in servers))
print("has <cert>:", sum(s["has_cert"] for s in servers), " auth-user-pass:", sum(s["auth_up"] for s in servers),
      " tls-auth/crypt:", sum(s["tlsauth"] for s in servers))
sample = sorted(servers, key=lambda s: -s["speed"])[: N // 2] + [s for s in servers if s["proto"].startswith("tcp")][: N // 4] + servers[:: max(1, len(servers) // (N // 4))][: N // 4]
seen, pick = set(), []
for s in sample:
    if s["id"] not in seen: seen.add(s["id"]); pick.append(s)
print(f"testing {len(pick)} servers with openvpn")
creds = tempfile.NamedTemporaryFile("w", delete=False); creds.write("vpn\nvpn\n"); creds.close()

def test(i_s):
    i, s = i_s
    f = tempfile.NamedTemporaryFile("w", suffix=".ovpn", delete=False); f.write(s["cfg"]); f.close()
    cmd = ["sudo", "timeout", "40", "openvpn", "--config", f.name, "--dev", f"tun{40+i}", "--verb", "3", "--auth-user-pass", creds.name,
           "--route-noexec", "--ifconfig-noexec", "--script-security", "0", "--connect-retry-max", "1", "--connect-timeout", "15",
           "--pull-filter", "ignore", "redirect-gateway", "--pull-filter", "ignore", "dhcp-option", "--nobind"]
    p = subprocess.run(cmd, capture_output=True, text=True)
    out = p.stdout + p.stderr
    ok = "Initialization Sequence Completed" in out
    why = ""
    if not ok:
        errs = [l for l in out.splitlines() if re.search(r"error|fail|VERIFY|AUTH|denied|refused|cannot|Options|unsupported|TLS", l, re.I)]
        why = (errs[0] if errs else (out.strip().splitlines()[-1] if out.strip() else "no output"))[:200]
    cipher = re.search(r"Data Channel: cipher '([^']+)'|Cipher '([^']+)' initialized", out)
    return s, ok, why, (cipher.group(1) or cipher.group(2)) if cipher else None

with cf.ThreadPoolExecutor(6) as ex:
    res = list(ex.map(test, enumerate(pick)))
good = 0
for s, ok, why, cipher in res:
    good += ok
    print(f"{'OK  ' if ok else 'FAIL'} {s['cc']} {s['id']:<28} {s['proto']:<11} cfg-cipher={s['cipher']:<12} negotiated={cipher} {'' if ok else '| ' + why}")
print(f"RESULT: {good}/{len(res)} connected")
