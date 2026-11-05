#!/usr/bin/env python3
"""Audits the FINAL merged manifest of the built APK (finding B10): what other apps can reach, which permissions are requested,
and the application-level flags that matter for security.

    aapt2 dump xmltree app.apk --file AndroidManifest.xml | python3 -I manifest-exports.py [--policy tools/manifest-policy.json]

Without --policy it only prints (a report). With --policy it exits 1 when the APK does anything the policy does not list:
  * a component that other apps can reach (exported=true, or an intent filter with no explicit exported="false") that is not
    allow-listed by name, or is listed but not guarded by the permission the policy requires;
  * a requested permission that is not allow-listed;
  * an application flag that differs from the required value (debuggable, allowBackup, usesCleartextTraffic, ...).
A component guarded by a system-only permission (BIND_VPN_SERVICE, BIND_QUICK_SETTINGS_TILE, BIND_JOB_SERVICE, DUMP) can only be
reached by the system, so the policy lists the guard that makes each reachable component acceptable.
"""
import json, re, sys

COMPONENTS = ("activity", "activity-alias", "service", "receiver", "provider")
ANDROID = r"http://schemas\.android\.com/apk/res/android:"


def parse(lines):
    comps, perms, app = [], [], {}
    cur = None            # component being read
    last = None           # most recent element (tag, indent)
    for line in lines:
        m = re.match(r"^(\s*)E: (\S+)", line)
        if m:
            indent, tag = len(m.group(1)), m.group(2)
            last = (tag, indent)
            if tag in COMPONENTS:
                cur = dict(kind=tag, indent=indent, name="?", exported=None, perm=None, enabled=True, filter=False, actions=[])
                comps.append(cur)
            elif cur is not None and indent <= cur["indent"]:
                cur = None
            elif tag == "intent-filter" and cur is not None:
                cur["filter"] = True
            continue
        a = re.match(r"^(\s*)A: " + ANDROID + r"(\w+)\(0x[0-9a-f]+\)=(?:\"([^\"]*)\"|\(type 0x\w+\)0x([0-9a-f]+)|(true|false)|(\S+))", line)
        if not a or last is None:
            continue
        ind, key = len(a.group(1)), a.group(2)
        sval, hval, bval, other = a.group(3), a.group(4), a.group(5), a.group(6)
        val = sval if sval is not None else (("true" if int(hval, 16) else "false") if hval is not None else (bval or other))
        tag, tind = last
        if ind != tind + 2:
            continue                                 # an attribute of a deeper element than the one we track
        if tag in COMPONENTS and cur is not None:
            if key == "name": cur["name"] = val
            elif key == "exported": cur["exported"] = val
            elif key == "permission": cur["perm"] = val
            elif key == "enabled": cur["enabled"] = val != "false"
        elif tag == "action" and cur is not None and key == "name":
            cur["actions"].append(val)
        elif tag == "uses-permission" and key == "name":
            perms.append(val)
        elif tag == "application":
            app[key] = val
    return comps, perms, app


def reachable(c):
    if not c["enabled"]:
        return False
    if c["exported"] is not None:
        return c["exported"] == "true"
    return c["filter"]                               # implicit export through an intent filter


def main():
    policy = None
    if "--policy" in sys.argv:
        policy = json.load(open(sys.argv[sys.argv.index("--policy") + 1]))
    comps, perms, app = parse(sys.stdin)
    print(f"{'kind':<15}{'reachable':<10}{'guard':<45}name  [actions]")
    n = 0
    for c in comps:
        r = reachable(c); n += r
        print(f"{c['kind']:<15}{('YES' if r else 'no'):<10}{(c['perm'] or '-'):<45}{c['name']}  {c['actions'][:3] if c['actions'] else ''}")
    print(f"-- {n} of {len(comps)} components are reachable by other apps")
    print("-- permissions:", ", ".join(sorted(perms)))
    keys = ("debuggable", "allowBackup", "usesCleartextTraffic", "networkSecurityConfig", "dataExtractionRules", "fullBackupContent", "extractNativeLibs", "testOnly")
    print("-- application:", {k: app[k] for k in keys if k in app})
    if policy is None:
        return 0

    bad = []
    allowed = {e["name"]: e for e in policy["reachable_components"]}
    for c in comps:
        if not reachable(c):
            continue
        e = allowed.get(c["name"])
        if e is None:
            bad.append(f"unexpected reachable {c['kind']}: {c['name']} (guard {c['perm'] or 'none'}, actions {c['actions'][:3]})")
        elif e.get("guard") and c["perm"] != e["guard"]:
            bad.append(f"{c['name']} must be guarded by {e['guard']} but is guarded by {c['perm'] or 'nothing'}")
    ok_perms = set(policy["permissions"])
    for p in perms:
        if p not in ok_perms:
            bad.append(f"unexpected permission: {p}")
    for p in ok_perms - set(perms):
        if p in policy.get("required_permissions", []):
            bad.append(f"required permission missing: {p}")
    for k, v in policy.get("application", {}).items():
        got = app.get(k)
        if v is None:
            if got not in (None, "false"):
                bad.append(f"application flag {k} must be absent/false, is {got}")
        elif got != v:
            bad.append(f"application flag {k} must be {v}, is {got}")
    if bad:
        print("\nMANIFEST POLICY VIOLATIONS:")
        for b in bad:
            print("  -", b)
        return 1
    print("\nmanifest policy: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
