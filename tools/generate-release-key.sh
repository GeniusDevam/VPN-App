#!/usr/bin/env bash
# Creates a NEW release-signing key for this app, outside the repository. Run it on your own computer: Linux, macOS, or Windows
# in Git Bash. It never uploads anything and never prints the password or the key.
#
#   bash tools/generate-release-key.sh [output-dir]      default: ~/.vpnapp-signing
#
# Result: <output-dir>/release.p12 (PKCS#12, RSA 4096, alias vpnapp-release) and <output-dir>/password.txt (its password), both
# readable only by your account (mode 600 on Linux/macOS; on Windows an access list with your account alone). It prints the
# PUBLIC certificate SHA-256. Upload with: bash tools/upload-release-key.sh [output-dir]
#
# Safety: it refuses to run if a key or password file already exists there. Everything is built in a private staging folder
# and moved into place only after the keystore has been opened with the saved password, so a failed or interrupted run leaves
# either both files or neither. If a step fails it says which one and shows that tool's error, with passwords removed.
set -euo pipefail
umask 077
. "$(cd "$(dirname "$0")" && pwd)/release-key-lib.sh"

case "${1:-}" in -h|--help) sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;; esac
DIR="${1:-$HOME/.vpnapp-signing}"
STAGING=""; PLACED_PASSWORD=0

cleanup() {
  local rc=$?
  set +e
  if [ "$rc" -ne 0 ]; then
    report_failure "$rc"
    # Never leave a password without its key (a key is only moved after its password, and only if the password move worked).
    if [ "$PLACED_PASSWORD" = 1 ] && [ ! -e "$DIR/release.p12" ]; then rm -f "$DIR/password.txt"; fi
    say "No key was created: nothing was left in $DIR."
  fi
  [ -z "$STAGING" ] || rm -rf "$STAGING"
  [ -z "$TOOL_LOG" ] || rm -f "$TOOL_LOG"
  unset KS_PW RK_SECRET
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP
TOOL_LOG="$(mktemp "${TMPDIR:-/tmp}/vpnapp-keygen-log.XXXXXX")"

step "1/7 Checking tools" "install the missing tool, or run this in Git Bash on Windows"
find_keytool
command -v od >/dev/null || command -v openssl >/dev/null || fail "neither openssl nor od is available to read random bytes"
if [ "$RK_WINDOWS" = 1 ]; then
  for t in cygpath icacls; do command -v "$t" >/dev/null || fail "$t is missing; run this script in Git Bash"; done
fi
say "    platform: $(uname -s)   keytool: $KEYTOOL"

step "2/7 Checking that no key exists yet in $DIR" "move the existing files somewhere safe first; this script never replaces a key"
if [ -e "$DIR/release.p12" ] || [ -L "$DIR/release.p12" ]; then
  fail "refusing to overwrite: $DIR/release.p12 already exists. If it is your release key, keep it (and its password.txt) backed up and upload it with tools/upload-release-key.sh."
fi
if [ -e "$DIR/password.txt" ] || [ -L "$DIR/password.txt" ]; then
  fail "refusing to continue: $DIR/password.txt exists without release.p12. If you know it belongs to no key, delete it and run this again."
fi
EXISTING="$DIR"; while [ ! -d "$EXISTING" ]; do EXISTING="$(dirname "$EXISTING")"; done
if command -v git >/dev/null 2>&1 && git -C "$EXISTING" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  fail "$DIR is inside a git repository; choose a folder outside any repository so the key can never be committed"
fi

step "3/7 Creating the folder, readable only by you" "check that you own $DIR and can change its permissions"
mkdir -p "$DIR"
protect "$DIR" dir
STAGING="$(mktemp -d "$DIR/.keygen-incomplete.XXXXXX")"
protect "$STAGING" dir

step "4/7 Generating a random password" "the system random source did not return usable bytes"
RK_SECRET="$(random_password)"
[ "${#RK_SECRET}" -eq 64 ] || fail "expected 64 random hex characters, got ${#RK_SECRET}"
printf '%s' "$RK_SECRET" > "$STAGING/password.txt"
valid_password_file "$STAGING/password.txt" || fail "the saved password is not plain ASCII"
export KS_PW="$RK_SECRET"     # handed to keytool through its environment, never on its command line

step "5/7 Generating the RSA-4096 key pair (can take a minute)" "keytool could not create the keystore; its own message is shown above"
run "$KEYTOOL" -J-Duser.language=en -genkeypair -keystore "$(native "$STAGING/release.p12")" -storetype PKCS12 \
  -storepass:env KS_PW -keypass:env KS_PW -alias "$RELEASE_KEY_ALIAS_NAME" -keyalg RSA -keysize 4096 -validity 9125 \
  -dname "CN=VPN App release, O=GeniusDevam" -noprompt
unset KS_PW

step "6/7 Verifying the keystore with the saved password" "the keystore does not open with password.txt, so neither file is kept"
run "$KEYTOOL" -J-Duser.language=en -list -keystore "$(native "$STAGING/release.p12")" -storetype PKCS12 \
  -storepass:file "$(native "$STAGING/password.txt")" -alias "$RELEASE_KEY_ALIAS_NAME"
grep -q "4096-bit RSA" <(MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$KEYTOOL" -J-Duser.language=en -list -v \
  -keystore "$(native "$STAGING/release.p12")" -storetype PKCS12 -storepass:file "$(native "$STAGING/password.txt")" \
  -alias "$RELEASE_KEY_ALIAS_NAME" 2>>"$TOOL_LOG" | tr -d '\r') || fail "the new key is not an RSA 4096 key"
certificate_sha256 "$STAGING/release.p12" "$STAGING/password.txt" "$STAGING"
protect "$STAGING/password.txt" file
protect "$STAGING/release.p12" file

step "7/7 Moving the verified key into place" "another program may have created files in $DIR meanwhile"
[ ! -e "$DIR/release.p12" ] && [ ! -e "$DIR/password.txt" ] || fail "a key appeared in $DIR while this script ran; nothing was replaced"
mv "$STAGING/password.txt" "$DIR/password.txt"; PLACED_PASSWORD=1
mv "$STAGING/release.p12" "$DIR/release.p12"
verify_protected "$DIR/password.txt" file
verify_protected "$DIR/release.p12" file

if [ "$RK_WINDOWS" = 1 ]; then SHOWN="$DIR  ($(native "$DIR"))"; ACCESS="an access list with only your Windows account ($RK_USER)"
else SHOWN="$DIR"; ACCESS="mode 600, folder 700"; fi
cat <<MSG

Done. Your new release key is in:
  $SHOWN
    release.p12    the keystore (RSA 4096, alias $RELEASE_KEY_ALIAS_NAME)
    password.txt   its password
  Both are readable only by you: $ACCESS.

Back BOTH files up now, somewhere private and offline (for example an encrypted USB drive, or a password manager that stores
files). If you lose them you can never ship an update that Android will accept for installs signed with this key.

Signing certificate SHA-256 (public; publish it in the README and in release notes):
  $(colon_form "$CERT_SHA256")
  $CERT_SHA256

Next: upload it to the protected GitHub environment "release" (and nowhere else):
  bash tools/upload-release-key.sh "$DIR"
MSG
