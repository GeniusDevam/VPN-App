#!/usr/bin/env python3
"""For every Lint NewApi finding in upstream ics-openvpn code, print the finding's enclosing method and every call site of that
method (with surrounding lines), so a reviewer can check by hand whether a runtime Build.VERSION.SDK_INT guard protects it.
Usage: lint-upstream-context.py <lint-results.txt>"""
import re, sys

findings = sorted({(m[1], int(m[2])) for m in re.finditer(r"^(/[^:\n]*de/blinkt/[^:\n]*):(\d+): Error", open(sys.argv[1]).read(), re.M)})
DECL = re.compile(r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:public|private|protected|static|final|synchronized|\s)+[\w<>\[\], ?]+\s+(\w+)\s*\([^;]*$")
for path, line in findings:
    src = open(path, errors="replace").read().split("\n")
    name = None
    for i in range(line - 1, -1, -1):
        m = DECL.match(src[i])
        if m and m.group(1) not in ("if", "for", "while", "switch", "catch", "return", "new"):
            name, decl = m.group(1), i; break
    print(f"=== {path.split('/main/java/')[-1]}:{line} is inside method {name}()")
    if not name:
        continue
    for j in range(max(0, decl - 3), decl + 1):
        print(f"    {j + 1:5d}  {src[j]}")
    for i, l in enumerate(src):
        if re.search(rf"\b{name}\s*\(", l) and i != decl:
            print(f"  --- call site at line {i + 1}:")
            for j in range(max(0, i - 8), min(len(src), i + 2)):
                print(f"    {j + 1:5d}  {src[j]}")
