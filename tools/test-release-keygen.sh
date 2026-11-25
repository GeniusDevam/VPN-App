#!/usr/bin/env bash
# Tests tools/generate-release-key.sh and tools/upload-release-key.sh with DISPOSABLE keys only. Runs on Linux, macOS and
# Windows (Git Bash); CI runs it on all three. The real GitHub CLI is never called: a stand-in records what would be uploaded
# where (it answers with Windows line endings everywhere, as the real gh.exe does on Windows).
#
#   bash tools/test-release-keygen.sh
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
. "$ROOT/tools/release-key-lib.sh"
T="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/keygen-test.XXXXXX")"
trap 'rm -rf "$T"' EXIT
PASS=0; FAILS=0
ok()  { PASS=$((PASS + 1)); echo "PASS  $*"; }
bad() { FAILS=$((FAILS + 1)); echo "FAIL  $*"; }
check() { local d="$1"; shift; if "$@"; then ok "$d"; else bad "$d"; fi; }
not() { ! "$@"; }
gen() { bash "$ROOT/tools/generate-release-key.sh" "$@"; }
up()  { bash "$ROOT/tools/upload-release-key.sh" "$@"; }
b64d() { base64 --decode 2>/dev/null || base64 -d; }
find_keytool
REAL_KEYTOOL="$KEYTOOL"
echo "platform $(uname -s), keytool $REAL_KEYTOOL, openssl $(command -v openssl || echo none) $(openssl version 2>/dev/null)"

no_secret_in() {  # password file, files... : the password must not appear in any of them
  local pw; pw="$(cat "$1")"; shift
  ! grep -qF "$pw" "$@"
}
only_owner() {  # file or dir is private to the current account
  local p="$1" acl n m
  if [ "$RK_WINDOWS" = 1 ]; then
    acl="$(MSYS_NO_PATHCONV=1 icacls "$(cygpath -w "$p")" | tr -d '\r')"
    n="$(printf '%s\n' "$acl" | grep -o ':(' | wc -l | tr -d ' ')"
    echo "      icacls: $(printf '%s' "$acl" | head -1)"
    [ "$n" -eq 1 ] && ! printf '%s\n' "$acl" | grep -qiE 'Everyone|BUILTIN\\Users|Authenticated Users|BUILTIN\\Administrators'
  else
    m="$(stat -c '%a' "$p" 2>/dev/null || stat -f '%Lp' "$p")"
    if [ -d "$p" ]; then [ "$m" = 700 ]; else [ "$m" = 600 ]; fi
  fi
}
keystore_opens() {  # dir
  MSYS_NO_PATHCONV=1 "$REAL_KEYTOOL" -J-Duser.language=en -list -v -keystore "$(native "$1/release.p12")" -storetype PKCS12 \
    -storepass:file "$(native "$1/password.txt")" -alias vpnapp-release 2>/dev/null | tr -d '\r' > "$T/list.txt" \
    && grep -q "Entry type: PrivateKeyEntry" "$T/list.txt" && grep -q "4096-bit RSA" "$T/list.txt"
}

# ---- 1. a key is generated, verified and protected ----------------------------------------------------------------------
K1="$T/keys1"
gen "$K1" > "$T/out1.txt" 2> "$T/err1.txt"; rc=$?
check "generator exits 0" [ "$rc" -eq 0 ]
[ "$rc" -eq 0 ] || { echo "---- generator stderr:"; cat "$T/err1.txt"; }
check "release.p12 and password.txt exist" [ -s "$K1/release.p12" -a -s "$K1/password.txt" ]
check "password is exactly 64 hex characters, no CR or LF" grep -qxE '[0-9a-f]{64}' "$K1/password.txt"
check "password file has no line break at all" [ "$(wc -c < "$K1/password.txt" | tr -d ' ')" -eq 64 ]
check "keystore opens with password.txt; private key entry, RSA 4096" keystore_opens "$K1"
FP_PRINTED="$(grep -oE '^  [0-9a-f]{64}$' "$T/out1.txt" | tr -d ' ')"
MSYS_NO_PATHCONV=1 "$REAL_KEYTOOL" -exportcert -keystore "$(native "$K1/release.p12")" -storepass:file "$(native "$K1/password.txt")" \
  -alias vpnapp-release -file "$(native "$T/cert.der")" >/dev/null 2>&1
check "printed fingerprint equals SHA-256 of the exported certificate" [ -n "$FP_PRINTED" -a "$FP_PRINTED" = "$(sha256_of "$T/cert.der")" ]
check "output has no carriage returns (the fingerprint is pasted into GitHub)" not grep -q $'\r' "$T/out1.txt"
check "password never appears in the output" no_secret_in "$K1/password.txt" "$T/out1.txt" "$T/err1.txt"
check "no staging folder left behind" [ -z "$(ls -A "$K1" | grep keygen-incomplete)" ]
check "folder is private to the current account" only_owner "$K1"
check "release.p12 is private to the current account" only_owner "$K1/release.p12"
check "password.txt is private to the current account" only_owner "$K1/password.txt"

# ---- 2. an existing key is never replaced ----------------------------------------------------------------------------------
H1="$(sha256_of "$K1/release.p12")$(sha256_of "$K1/password.txt")"
gen "$K1" > "$T/out2.txt" 2> "$T/err2.txt"; rc=$?
check "second run in the same folder is refused" [ "$rc" -ne 0 ]
check "refusal says why" grep -q "refusing to overwrite" "$T/err2.txt"
check "existing key and password unchanged" [ "$H1" = "$(sha256_of "$K1/release.p12")$(sha256_of "$K1/password.txt")" ]
K3="$T/keys3"; mkdir -p "$K3"; printf 'orphan' > "$K3/password.txt"
gen "$K3" > /dev/null 2> "$T/err3.txt"; rc=$?
check "a lone password.txt is not overwritten either" [ "$rc" -ne 0 -a "$(cat "$K3/password.txt")" = orphan -a ! -e "$K3/release.p12" ]

# ---- 3. failures name the step and leave nothing behind ----------------------------------------------------------------------
SHIM="$T/shim"; mkdir -p "$SHIM"
cat > "$SHIM/keytool" <<EOF
#!/usr/bin/env bash
for a in "\$@"; do if [ "\$a" = "-\${FAKE_KEYTOOL_FAIL:-none}" ]; then echo "keytool error: simulated failure in \$a" >&2; exit 1; fi; done
exec "$REAL_KEYTOOL" "\$@"
EOF
chmod +x "$SHIM/keytool"
for mode in genkeypair list; do
  K="$T/fail-$mode"
  PATH="$SHIM:$PATH" FAKE_KEYTOOL_FAIL="$mode" gen "$K" > "$T/out-$mode.txt" 2> "$T/err-$mode.txt"; rc=$?
  check "keytool failing at -$mode: generator exits non-zero" [ "$rc" -ne 0 ]
  check "keytool failing at -$mode: the failing step is named" grep -q "FAILED at step" "$T/err-$mode.txt"
  check "keytool failing at -$mode: keytool's own message is shown" grep -q "simulated failure in -$mode" "$T/err-$mode.txt"
  check "keytool failing at -$mode: no key, no password, no staging folder left" \
    [ ! -e "$K/release.p12" -a ! -e "$K/password.txt" -a -z "$(ls -A "$K" 2>/dev/null)" ]
  check "keytool failing at -$mode: no 64-hex value in the output" not grep -qE '[0-9a-fA-F]{64}' "$T/out-$mode.txt" "$T/err-$mode.txt"
done
sed -n '/FAILED at step/,$p' "$T/err-list.txt" | sed 's/^/      | /'

# ---- 4. refuses a folder inside a git repository -----------------------------------------------------------------------------
gen "$ROOT/not-a-key-folder" > /dev/null 2> "$T/err-git.txt"; rc=$?
check "folder inside a git repository is refused and not created" [ "$rc" -ne 0 -a ! -e "$ROOT/not-a-key-folder" ]

# ---- 5. uploader: only the protected environment, values never on a command line --------------------------------------------
STATE="$T/gh-state"; mkdir -p "$STATE"
cat > "$SHIM/gh" <<'EOF'
#!/usr/bin/env bash
# Stand-in for the GitHub CLI. Records argv; stores secret values by scope; answers like gh.exe (CR LF line endings).
st="$FAKE_GH_STATE"; printf '%s\n' "$*" >> "$st/argv.log"
out() { printf '%s\r\n' "$@"; }
scope=repo; name=""; body=""; args=("$@")
for ((i = 0; i < ${#args[@]}; i++)); do
  case "${args[$i]}" in --env) scope="env-${args[$((i + 1))]}" ;; --body) body="${args[$((i + 1))]}" ;; esac
done
case "$1 ${2:-}" in
  "auth status") exit 0 ;;
  "secret set") cat > "$st/secret.$scope.$3"; exit 0 ;;
  "secret list") for f in "$st"/secret."$scope".*; do [ -e "$f" ] && out "${f##*.}"; done; exit 0 ;;
  "variable set") printf '%s' "$body" > "$st/var.$scope.$3"; exit 0 ;;
  "variable get") [ -e "$st/var.$scope.$3" ] && out "$(cat "$st/var.$scope.$3")"; exit 0 ;;
esac
if [ "$1" = api ]; then
  case "$2" in
    */deployment-branch-policies) out "tag v*"; [ "${FAKE_GH_SCENARIO:-}" = branch-allowed ] && out "branch main"; exit 0 ;;
    */environments/*)
      case "${FAKE_GH_SCENARIO:-}" in
        no-environment) echo "HTTP 404: Not Found" >&2; exit 1 ;;
        no-reviewer) out "false false true" ;;
        admin-bypass) out "true true true" ;;
        *) out "false true true" ;;
      esac; exit 0 ;;
  esac
fi
echo "fake gh: unexpected call: $*" >&2; exit 2
EOF
chmod +x "$SHIM/gh"
export FAKE_GH_STATE="$STATE"
PATH="$SHIM:$PATH" up "$K1" --repo test/repo > "$T/up-out.txt" 2> "$T/up-err.txt"; rc=$?
check "uploader exits 0" [ "$rc" -eq 0 ]
[ "$rc" -eq 0 ] || { echo "---- uploader stderr:"; cat "$T/up-err.txt"; }
check "every 'gh secret set' targets --env release" [ -z "$(grep '^secret set' "$STATE/argv.log" | grep -v -- '--env release')" ]
check "four secret set calls, one variable set call" \
  [ "$(grep -c '^secret set' "$STATE/argv.log")" -eq 4 -a "$(grep -c '^variable set' "$STATE/argv.log")" -eq 1 ]
check "nothing was stored repository-wide" [ -z "$(ls "$STATE" | grep '^secret\.repo\.' )" ]
check "no password on any gh command line" no_secret_in "$K1/password.txt" "$STATE/argv.log"
check "no long base64 value on any gh command line" not grep -qE '[A-Za-z0-9+/=]{100,}' "$STATE/argv.log"
check "password never appears in the uploader output" no_secret_in "$K1/password.txt" "$T/up-out.txt" "$T/up-err.txt"
# What the release job will see: decode exactly as the sign job does and compare with its fingerprint normalisation.
b64d < "$STATE/secret.env-release.RELEASE_KEYSTORE_B64" > "$T/decoded.p12"
check "uploaded keystore decodes to the identical file" [ "$(sha256_of "$T/decoded.p12")" = "$(sha256_of "$K1/release.p12")" ]
check "uploaded keystore password equals password.txt byte for byte" cmp -s "$STATE/secret.env-release.RELEASE_KEYSTORE_PASSWORD" "$K1/password.txt"
check "uploaded key password equals password.txt byte for byte" cmp -s "$STATE/secret.env-release.RELEASE_KEY_PASSWORD" "$K1/password.txt"
check "uploaded alias is vpnapp-release" [ "$(cat "$STATE/secret.env-release.RELEASE_KEY_ALIAS")" = vpnapp-release ]
EXPECT="$(tr -d ': ' < "$STATE/var.env-release.RELEASE_CERT_SHA256" | tr A-F a-f)"
check "pinned RELEASE_CERT_SHA256 equals the certificate, after the sign job's normalisation" [ "$EXPECT" = "$FP_PRINTED" ]

for scenario in no-environment no-reviewer admin-bypass branch-allowed repo-secret; do
  rm -rf "$STATE"; mkdir -p "$STATE"
  [ "$scenario" = repo-secret ] && printf 'x' > "$STATE/secret.repo.RELEASE_KEYSTORE_PASSWORD"
  PATH="$SHIM:$PATH" FAKE_GH_SCENARIO="$scenario" up "$K1" --repo test/repo > /dev/null 2> "$T/up-$scenario.txt"; rc=$?
  check "uploader refuses when: $scenario (and uploads nothing)" [ "$rc" -ne 0 -a "$(grep -c '^secret set' "$STATE/argv.log")" -eq 0 ]
done
sed -n 's/^ERROR: /      | /p' "$T"/up-*.txt | sort -u

# ---- 6. the original Windows failure (evidence for the root cause; never fails the job) --------------------------------------
echo "---- original generator (commit 06cbf78) on this platform:"
if git -C "$ROOT" cat-file -e 06cbf78:tools/generate-release-key.sh 2>/dev/null; then
  git -C "$ROOT" show 06cbf78:tools/generate-release-key.sh > "$T/old-generator.sh"
  bash "$T/old-generator.sh" "$T/old-keys" > "$T/old-out.txt" 2>&1; rc=$?
  echo "      exit $rc, output $(wc -c < "$T/old-out.txt" | tr -d ' ') bytes, keystore created: $([ -e "$T/old-keys/release.p12" ] && echo yes || echo no)"
  if command -v openssl >/dev/null; then
    cr="$(openssl rand -base64 33 | tr -d '/+=\n' | od -An -c | tr -s ' ' | grep -c '\\r')"
    echo "      'openssl rand -base64 33 | tr -d /+=\\n' still contains a carriage return: $([ "$cr" -gt 0 ] && echo YES || echo no)"
    pw="$(openssl rand -base64 33 | tr -d '/+=\n')"
    echo "      keytool given that value as a password says: $(MSYS_NO_PATHCONV=1 "$REAL_KEYTOOL" -genkeypair -keystore "$(native "$T/cr.p12")" \
      -storetype PKCS12 -storepass "$pw" -keypass "$pw" -alias t -keyalg RSA -keysize 2048 -dname CN=t 2>&1 | tr -d '\r' | grep -i 'error' | head -1 || true)"
  fi
else
  echo "      (history not available in this checkout)"
fi

echo
echo "$PASS passed, $FAILS failed"
[ "$FAILS" -eq 0 ]
