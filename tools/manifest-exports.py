#!/usr/bin/env python3
"""Lists the components of the FINAL merged manifest (read from `aapt2 dump xmltree`) that other apps can reach (finding B10).
Usage: aapt2 dump xmltree app.apk --file AndroidManifest.xml | python3 -I manifest-exports.py
Prints: kind, name, exported flag, permission guarding it, whether it declares an intent filter, then the requested permissions."""
import re, sys

rows, perms = [], []
cur = None
for line in sys.stdin:
    m = re.match(r"^(\s*)E: (\S+)", line)
    if m:
        indent, tag = len(m.group(1)), m.group(2)
        if tag in ("activity", "activity-alias", "service", "receiver", "provider"):
            cur = dict(kind=tag, indent=indent, name="?", exported=None, perm=None, filter=False)
            rows.append(cur)
        elif tag == "intent-filter" and cur and indent > cur["indent"]:
            cur["filter"] = True
        elif tag == "uses-permission":
            cur = None; perms.append(None)
        elif cur and indent <= cur["indent"]:
            cur = None
        continue
    a = re.search(r':(name|exported|permission)\(0x[0-9a-f]+\)=(?:"([^"]*)"|\(type 0x\d+\)0x([0-9a-f]+)|(true|false))', line)
    if a:
        key, sval, hval, bval = a.groups()
        val = sval if sval is not None else ("true" if hval and int(hval, 16) else "false" if hval is not None else bval)
        if cur is not None:
            if key == "name": cur["name"] = val
            elif key == "exported": cur["exported"] = val
            elif key == "permission": cur["perm"] = val
        elif key == "name" and perms and perms[-1] is None:
            perms[-1] = val
print(f"{'kind':<15}{'exported':<10}{'guard':<45}{'filter':<7}name")
n = 0
for r in rows:
    exp = r["exported"] if r["exported"] is not None else ("(implicit:true)" if r["filter"] else "false")
    if exp.startswith("true") or exp.startswith("(implicit:true"):
        n += 1
    print(f"{r['kind']:<15}{exp:<10}{(r['perm'] or '-'):<45}{('yes' if r['filter'] else 'no'):<7}{r['name']}")
print(f"-- {n} of {len(rows)} components are reachable by other apps")
print("-- permissions:", ", ".join(sorted(p for p in perms if p)))
