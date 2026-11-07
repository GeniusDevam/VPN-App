#!/usr/bin/env python3
"""Checks tools/redact-results.py: secrets never reach the public branch, and ordinary diagnostic lines are not thrown away.

    python3 -I tools/test-redact-results.py
"""
import os, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
LOG = [
    # must be dropped whole
    ("11-07 06:27:51.200  6405  6422 D VPNAPP  : engine: PUSH: Received control message: 'PUSH_REPLY,auth-token SESS_ID_AT_abcdef'", None),
    ("11-07 06:27:51.300  6405  6422 D VPNAPP  : engine: -----BEGIN PRIVATE KEY-----", None),
    ("11-07 06:27:51.300  6405  6422 D VPNAPP  : engine: -----BEGIN CERTIFICATE-----", None),
    ("11-07 06:27:51.400  6405  6422 D VPNAPP  : engine: using password vpn", None),
    ("11-07 06:27:51.400  6405  6422 D VPNAPP  : engine: shared secret loaded", None),
    # kept, with addresses and handles masked
    ("11-07 06:27:51.145  6405  7924 D VPNAPP  : connect vpn584377130 -> up", "connect vpn584377130 -> up"),
    ("11-07 06:28:02.010  6405  6422 D VPNAPP  : engine: Connection reset, restarting [0]", "Connection reset, restarting"),
    ("11-07 06:28:02.020  6405  6422 D VPNAPP  : engine: TCP: connect to [AF_INET]219.100.37.12:443 failed", "219.100.x.x:443"),
    ("11-07 06:28:03.000  1917  1917 I WindowManager: addWindow token=Token{7f3a9c1 ActivityRecord{io.github.geniusdevam.vpnapp}}", "token=[masked]"),
    ("11-07 06:28:03.000  6405  6422 D VPNAPP  : engine: peer fe80::1c2b:3dff:fe4e:5f60 ok", "[ipv6]"),
    ("11-07 06:28:03.000  6405  6422 D VPNAPP  : engine: blob " + "QUJD" * 20, "[data]"),
    ("11-07 06:28:03.000  6405  6422 D VPNAPP  : engine: fp 3f2a9c0d11e45b7a8c9d0e1f", "[hex]"),
]
UI = '<node index="0" text="🔒 Connected" password="false" bounds="[0,0][10,10]" /><node text="Connecting…" password="false" />'


def main():
    bad = []
    with tempfile.TemporaryDirectory() as d:
        raw, pub = os.path.join(d, "raw"), os.path.join(d, "pub")
        os.makedirs(raw)
        open(os.path.join(raw, "logcat.txt"), "w").write("\n".join(l for l, _ in LOG) + "\n")
        open(os.path.join(raw, "ui-quick-result.xml"), "w").write(UI + "\n")
        open(os.path.join(raw, "speedrun.md"), "w").write("| case | ms |\n|---|---|\n|" + "-" * 50 + "|\n| cold start | 1126 |\n")
        open(os.path.join(raw, "runs.log"), "w").write("run 1: connect to 219.100.37.12 up in 21 s\nauth-token SESS\n")
        subprocess.run([sys.executable, "-I", os.path.join(HERE, "redact-results.py"), raw, pub], check=True, capture_output=True)
        out = open(os.path.join(pub, "app-log.txt")).read().splitlines()
        if len(out) != len(LOG):
            bad.append(f"expected {len(LOG)} log lines, got {len(out)}")
        for (src, want), got in zip(LOG, out):
            dropped = got.startswith("[line removed")
            if want is None and not dropped:
                bad.append(f"NOT dropped: {got}")
            if want is not None and (dropped or want not in got):
                bad.append(f"over-redacted or wrong (wanted {want!r}): {got}")
        for got in out:
            for leak in ("SESS_ID", "PRIVATE KEY", "37.12", "7f3a9c1", "QUJDQUJD", "3f2a9c0d11e45b7a8c9d0e1f"):
                if leak in got:
                    bad.append(f"leak {leak!r}: {got}")
        ui = open(os.path.join(pub, "ui-quick-result.xml")).read()
        if "🔒 Connected" not in ui or "Connecting…" not in ui:
            bad.append(f"UI dump over-redacted: {ui[:120]}")
        md = open(os.path.join(pub, "speedrun.md")).read()
        if "-" * 50 not in md or "1126" not in md:
            bad.append(f"speedrun.md damaged: {md!r}")
        runs = open(os.path.join(pub, "runs.log")).read()
        if "37.12" in runs or "SESS" in runs or "up in 21 s" not in runs:
            bad.append(f"runs.log not redacted correctly: {runs!r}")
    for b in bad:
        print("FAIL", b)
    print("redact-results:", "OK" if not bad else f"{len(bad)} failure(s)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
