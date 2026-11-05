#!/usr/bin/env python3
"""Mutation tests for tools/check-workflow-security.py: each mutation re-creates a real way the signing key could leak or a release
could be produced from untrusted code, and the checker MUST reject it (while accepting the unmodified workflow)."""
import importlib.util, os, re, sys, tempfile

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("chk", os.path.join(here, "check-workflow-security.py"))
chk = importlib.util.module_from_spec(spec); spec.loader.exec_module(chk)
src = open(os.path.join(here, "..", ".github", "workflows", "ci.yml")).read()


def violations(text):
    with tempfile.NamedTemporaryFile("w", suffix=".yml", delete=False) as f:
        f.write(text)
    try:
        return chk.check(f.name)
    finally:
        os.unlink(f.name)


def must_reject(name, text, rule):
    v = violations(text)
    ok = any(x.startswith(rule) for x in v)
    print(("PASS " if ok else "FAIL ") + f"{name}: " + (v[0][:100] if v else "NOT DETECTED"))
    return ok


def sub(old, new, count=1):
    assert src.count(old) >= 1, old
    return src.replace(old, new, count)


results = [len(violations(src)) == 0]
print(("PASS " if results[0] else "FAIL ") + "unmodified ci.yml is accepted")

# the review finding: real secrets reachable from the ordinary build job (every branch)
results.append(must_reject(
    "secret injected into the ordinary apk job",
    sub("      - name: Prepare build tree\n", "      - name: leak\n        env:\n          K: ${{ secrets.RELEASE_KEYSTORE_B64 }}\n        run: echo hi\n      - name: Prepare build tree\n"), "R2"))
results.append(must_reject(
    "RELEASE_ variable read outside the sign job",
    sub("      - name: Prepare build tree\n", "      - name: pin\n        env:\n          P: ${{ vars.RELEASE_CERT_SHA256 }}\n        run: echo hi\n      - name: Prepare build tree\n"), "R2"))
results.append(must_reject("sign job loses its protected environment", sub("    environment: release\n", ""), "R3"))
results.append(must_reject("sign job runs on every ref", sub("  sign:\n    needs: [release-gate]\n    if: startsWith(github.ref, 'refs/tags/v')", "  sign:\n    needs: [release-gate]"), "R9"))
results.append(must_reject("sign job no longer needs the release gate", sub("  sign:\n    needs: [release-gate]", "  sign:\n    needs: []"), "R3"))
results.append(must_reject("write token on the build job", sub("  apk:\n    runs-on: ubuntu-latest\n", "  apk:\n    permissions:\n      contents: write\n    runs-on: ubuntu-latest\n"), "R4"))
results.append(must_reject("workflow-wide write permission", sub("permissions:\n  contents: read\n", "permissions:\n  contents: write\n"), "R1"))
results.append(must_reject("secrets: inherit", sub("  engine:\n", "  engine:\n    secrets: inherit\n"), "R2"))
results.append(must_reject("pull_request_target trigger", sub("on:\n  push:", "on:\n  pull_request_target:\n  push:"), "R6"))
results.append(must_reject("action pinned only by a movable tag", sub("actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3 # v4", "actions/setup-java@v4"), "R5"))
results.append(must_reject("checkout persists the token", sub("  workflow-security:\n    runs-on: ubuntu-latest\n    timeout-minutes: 5\n    steps:\n      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4\n        with: { persist-credentials: false }", "  workflow-security:\n    runs-on: ubuntu-latest\n    timeout-minutes: 5\n    steps:\n      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4"), "R8"))
results.append(must_reject("branch name interpolated into a script", sub('echo "release gate OK: $TAG at $GITHUB_SHA"', 'echo "${{ github.head_ref }}"'), "R7"))
print(f"\n{sum(results)}/{len(results)} checks behaved as required")
sys.exit(0 if all(results) else 1)
