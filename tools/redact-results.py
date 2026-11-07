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
HEX = re.compile(r"\b[0-9A-Fa-f]{16,}\b")
# Lines that can carry a key, certificate or credential are dropped whole.
KEY = re.compile(r"PRIVATE KEY|-----BEGIN|BEGIN OpenVPN|auth-token|auth-user-pass|password|passwd|secret", re.I)
# uiautomator marks every node password="false"/"true" (a field type, not a value: Android already masks password text).
UI_FLAG = re.compile(r'\s(?:password)="(?:true|false)"')
# "token" also names harmless system handles (window/activity tokens). Keep the line, mask the value.
TOKEN = re.compile(r"((?:token|Token)\s*[=:{]\s*)[^\s,}]+")

def scrub(line: str) -> str:
    line = UI_FLAG.sub("", line)
    if KEY.search(line):
        return "[line removed: may contain key material or credentials]"
    line = TOKEN.sub(lambda m: m.group(1) + "[masked]", line)
    line = V4.sub(lambda m: f"{m.group(1)}.{m.group(2)}.x.x", line)
    line = V6.sub("[ipv6]", line)
    line = HEX.sub("[hex]", line)
    return B64.sub(lambda m: m.group(0) if set(m.group(0)) <= set("-=_") else "[data]", line)   # keep Markdown rules

def copy_scrubbed(src, dst, keep=lambda l: True):
    if not os.path.exists(src): return
    with open(src, errors="replace") as f, open(dst, "w") as o:
        for line in f:
            if keep(line): o.write(scrub(line.rstrip("\n")) + "\n")

for name in ("result.txt", "package.txt", "speedrun.txt", "speedrun.md", "runs.log"):
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
