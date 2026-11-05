#!/usr/bin/env python3
"""Fails when a GitHub Actions workflow could expose the release-signing key outside the protected `release` environment.

    python3 -I tools/check-workflow-security.py [.github/workflows]

Rules (each violation is printed; exit status 1 if there is any):
  R1  Workflow-level permissions are exactly {contents: read}.
  R2  Any secret other than GITHUB_TOKEN, and any `vars.RELEASE_*`, appears ONLY in the job named `sign`.
  R3  The `sign` job runs in the protected environment `release`, only for version tags, and needs `release-gate`.
  R4  Only `emulator` (contents: write, publishes redacted results) and `publish` (contents/attestations/id-token: write, holds
      no secrets) may request write permissions; no other job may.
  R5  Every `uses:` is pinned to a full 40-hex commit SHA, except the explicit, justified allow-list below.
  R6  No `pull_request_target` / `workflow_run` triggers (they run with secrets and write tokens on untrusted code).
  R7  No `${{ github.event.* }}`, `github.head_ref` or `github.ref*` interpolated directly into a `run:` script (injection):
      pass them through `env:` instead.
  R8  Every actions/checkout sets `persist-credentials: false`.
  R9  The jobs that touch a release (release-gate, sign, publish) run only for version tags.
"""
import json, re, sys, glob, os

try:
    import yaml
except ImportError:
    sys.exit("PyYAML is required (apt-get install python3-yaml)")

# Not pinned by SHA: its commit could not be resolved and checked from the environment this was written in. GitHub-owned; used only in
# the `publish` job, which holds no signing secrets. Resolve and pin it, then delete this entry.
UNPINNED_ALLOWED = {"actions/attest-build-provenance@v2"}
WRITERS = {"emulator": {"contents"}, "publish": {"contents", "attestations", "id-token"}}
SHA = re.compile(r"^[^@\s]+@[0-9a-f]{40}$")


def walk_strings(x):
    if isinstance(x, str):
        yield x
    elif isinstance(x, dict):
        for k, v in x.items():
            yield from walk_strings(k); yield from walk_strings(v)
    elif isinstance(x, list):
        for v in x:
            yield from walk_strings(v)


def check(path):
    bad = []
    wf = yaml.safe_load(open(path))
    triggers = wf.get(True, wf.get("on", {}))                # PyYAML reads the key `on` as boolean True
    names = set(triggers) if isinstance(triggers, (dict, list)) else {triggers}
    for t in ("pull_request_target", "workflow_run"):
        if t in names:
            bad.append(f"R6 trigger {t} is not allowed")
    if wf.get("permissions") != {"contents": "read"}:
        bad.append(f"R1 workflow permissions must be {{contents: read}}, got {wf.get('permissions')}")

    for jid, job in wf.get("jobs", {}).items():
        text = "\n".join(walk_strings(job))
        secrets = set(re.findall(r"secrets\.([A-Za-z0-9_]+)", text)) - {"GITHUB_TOKEN"}
        release_vars = set(re.findall(r"vars\.(RELEASE_[A-Za-z0-9_]+)", text))
        if (secrets or release_vars) and jid != "sign":
            bad.append(f"R2 job '{jid}' references {sorted(secrets | release_vars)}; only 'sign' may")
        if job.get("secrets") == "inherit":
            bad.append(f"R2 job '{jid}' inherits all secrets")

        perms = job.get("permissions") or {}
        if isinstance(perms, str):
            perms = {"*": perms}
        writes = {k for k, v in perms.items() if v == "write" or k == "*"}
        if writes - WRITERS.get(jid, set()):
            bad.append(f"R4 job '{jid}' requests write permission(s) {sorted(writes - WRITERS.get(jid, set()))}")

        for step in job.get("steps", []):
            use = step.get("uses")
            if use:
                if use.startswith("./") or use.startswith("docker://"):
                    bad.append(f"R5 job '{jid}' uses unpinned {use}")
                elif not SHA.match(use) and use not in UNPINNED_ALLOWED:
                    bad.append(f"R5 job '{jid}': {use} is not pinned to a commit SHA")
                if use.startswith("actions/checkout@") and (step.get("with") or {}).get("persist-credentials") is not False:
                    bad.append(f"R8 job '{jid}': checkout must set persist-credentials: false")
            run = step.get("run")
            if run and re.search(r"\$\{\{\s*(github\.event\.|github\.head_ref|github\.ref)", run):
                bad.append(f"R7 job '{jid}' interpolates github context into a run script; use env:")

        cond = str(job.get("if", ""))
        if jid in ("release-gate", "sign") and "refs/tags/v" not in cond:
            bad.append(f"R9 job '{jid}' must be conditioned on a version tag")
        if jid == "publish" and "sign" not in (job.get("needs") or []):
            bad.append("R9 job 'publish' must need 'sign' (which is tag-only)")
        if jid == "sign":
            env = job.get("environment")
            envname = env.get("name") if isinstance(env, dict) else env
            if envname != "release":
                bad.append(f"R3 job 'sign' must run in environment 'release', has {envname!r}")
            if "release-gate" not in (job.get("needs") or []):
                bad.append("R3 job 'sign' must need 'release-gate'")
    return bad


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else ".github/workflows"
    files = sorted(glob.glob(os.path.join(root, "*.yml")) + glob.glob(os.path.join(root, "*.yaml")))
    allbad = []
    for f in files:
        b = check(f)
        print(f"{f}: {'OK' if not b else str(len(b)) + ' violation(s)'}")
        allbad += [f"{f}: {x}" for x in b]
    for x in allbad:
        print("  -", x)
    return 1 if allbad else 0


if __name__ == "__main__":
    sys.exit(main())
