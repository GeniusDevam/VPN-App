#!/usr/bin/env python3
"""Reduces raw emulator output to what is safe and useful to publish on the public `emulator-results` branch.

Raw output (full logcat, UI dumps) is never published. This keeps: the pass/fail summary, package info, screenshots,
the on-screen text dumps, the app's own debug lines (tag VPNAPP) and crash traces that mention the app, with addresses
masked and anything key-like removed. Usage: redact-results.py <raw-dir> <public-dir>
"""
import os, re, shutil, sys

raw, pub = sys.argv[1], sys.argv[2]
os.makedirs(pub, exist_ok=True)
V4 = re.compile(r"\b(\d{1,3})\.(\d{1,3})\.\d{1,3}\.\d{1,3}\b")
H = r"[0-9A-Fa-f]{1,4}"
# full form, compressed (::) form, leading :: form. Plain hh:mm:ss timestamps contain no "::" and do not match.
V6 = re.compile(rf"(?<![\w:])(?:(?:{H}:){{7}}{H}|(?:{H}:){{1,7}}:(?:{H}(?::{H}){{0,6}})?|::{H}(?::{H}){{0,6}})(?![\w:])")
B64 = re.compile(r"[A-Za-z0-9+/=_-]{40,}")
KEY = re.compile(r"PRIVATE KEY|BEGIN CERTIFICATE|BEGIN OpenVPN|password|passwd|secret|token", re.I)

def scrub(line: str) -> str:
    if KEY.search(line):
        return "[line removed: may contain key material or credentials]"
    line = V4.sub(lambda m: f"{m.group(1)}.{m.group(2)}.x.x", line)
    line = V6.sub("[ipv6]", line)
    return B64.sub("[data]", line)

def copy_scrubbed(src, dst, keep=lambda l: True):
    if not os.path.exists(src): return
    with open(src, errors="replace") as f, open(dst, "w") as o:
        for line in f:
            if keep(line): o.write(scrub(line.rstrip("\n")) + "\n")

for name in ("result.txt", "package.txt"):
    copy_scrubbed(os.path.join(raw, name), os.path.join(pub, name))
for name in ("ui-quick-result.xml", "ui-best-result.xml"):
    copy_scrubbed(os.path.join(raw, name), os.path.join(pub, name))
# App-tagged debug output and crashes that involve the app. Nothing else from logcat.
copy_scrubbed(os.path.join(raw, "logcat.txt"), os.path.join(pub, "app-log.txt"),
              keep=lambda l: " VPNAPP " in l or "io.github.geniusdevam" in l or "FATAL EXCEPTION" in l)
copy_scrubbed(os.path.join(raw, "crashes.txt"), os.path.join(pub, "crashes.txt"), keep=lambda l: "io.github.geniusdevam" in l or "Exception" in l)
for f in sorted(os.listdir(raw)):
    if f.endswith(".png"):
        shutil.copy(os.path.join(raw, f), os.path.join(pub, f))
print("published files:", sorted(os.listdir(pub)))
