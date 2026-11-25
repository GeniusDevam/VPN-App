#!/usr/bin/env bash
# Uploads a key made by tools/generate-release-key.sh to the PROTECTED GitHub environment "release", and nowhere else.
#
#   bash tools/upload-release-key.sh [key-dir] [--repo OWNER/REPO]     defaults: ~/.vpnapp-signing, GeniusDevam/VPN-App
#
# Before uploading it checks that the keystore opens with password.txt, that it is not the compromised old key, that the
# environment exists and is protected (required reviewer, no administrator bypass, deployments limited to tags), and that no
# repository-wide secret with a release name exists. Values reach gh on standard input, never on a command line, and are never
# printed. Afterwards it checks that the four secrets and the certificate pin are in the environment and not repository-wide.
set -euo pipefail
umask 077
. "$(cd "$(dirname "$0")" && pwd)/release-key-lib.sh"

DIR="$HOME/.vpnapp-signing"; REPO="GeniusDevam/VPN-App"
while [ $# -gt 0 ]; do
  case "$1" in
    --repo) REPO="${2:?--repo needs OWNER/REPO}"; shift 2 ;;
    -h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) DIR="$1"; shift ;;
  esac
done
KS="$DIR/release.p12"; PWF="$DIR/password.txt"; ENVIRONMENT="release"
SECRETS="RELEASE_KEYSTORE_B64 RELEASE_KEYSTORE_PASSWORD RELEASE_KEY_PASSWORD RELEASE_KEY_ALIAS"
WORK=""

cleanup() {
  local rc=$?
  set +e
  if [ "$rc" -ne 0 ]; then report_failure "$rc"; say "Nothing else was uploaded after the failing step."; fi
  [ -z "$WORK" ] || rm -rf "$WORK"
  [ -z "$TOOL_LOG" ] || rm -f "$TOOL_LOG"
  unset RK_SECRET B64
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP
TOOL_LOG="$(mktemp "${TMPDIR:-/tmp}/vpnapp-upload-log.XXXXXX")"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/vpnapp-upload.XXXXXX")"
gh_() { MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' gh "$@"; }
lines() { tr -d '\r' | sed '/^$/d'; }

step "1/6 Checking the key files in $DIR" "run tools/generate-release-key.sh first, or pass the folder that holds release.p12"
[ -f "$KS" ] && [ -f "$PWF" ] || fail "expected $KS and $PWF"
valid_password_file "$PWF" || fail "password.txt must be one line of plain ASCII (no line break, at least 16 characters)"
RK_SECRET="$(cat "$PWF")"; case "$RK_SECRET" in *[!0-9A-Za-z]*) RK_SECRET="" ;; esac   # only used to scrub tool output
find_keytool
run "$KEYTOOL" -J-Duser.language=en -list -keystore "$(native "$KS")" -storetype PKCS12 -storepass:file "$(native "$PWF")" \
  -alias "$RELEASE_KEY_ALIAS_NAME"
certificate_sha256 "$KS" "$PWF" "$WORK"
say "    certificate SHA-256: $CERT_SHA256"

step "2/6 Checking GitHub CLI access to $REPO" "run 'gh auth login' and make sure your account can administer $REPO"
command -v gh >/dev/null || fail "the GitHub CLI (gh) is not installed"
run gh auth status
ENV_STATE="$(gh_ api "repos/$REPO/environments/$ENVIRONMENT" --jq \
  '[(.can_admins_bypass|tostring), (([.protection_rules[]?.type] | index("required_reviewers")) != null | tostring), (.deployment_branch_policy != null | tostring)] | join(" ")' \
  2>>"$TOOL_LOG" | lines)" || fail "the environment '$ENVIRONMENT' was not found in $REPO; create it first (docs/RELEASE_SIGNING.md)"

step "3/6 Checking that the environment is protected" "fix the environment settings (docs/RELEASE_SIGNING.md, steps 1-3), then run this again"
set -- $ENV_STATE
[ "${1:-}" = false ] || fail "administrators can bypass the '$ENVIRONMENT' protection rules; turn off 'Allow administrators to bypass'"
[ "${2:-}" = true ] || fail "the '$ENVIRONMENT' environment has no required reviewer"
[ "${3:-}" = true ] || fail "the '$ENVIRONMENT' environment allows deployments from any branch or tag; limit it to tags matching v*"
POLICIES="$(gh_ api "repos/$REPO/environments/$ENVIRONMENT/deployment-branch-policies" \
  --jq '.branch_policies[] | "\(.type // "branch") \(.name)"' 2>>"$TOOL_LOG" | lines)"
printf '%s\n' "$POLICIES" | grep -qx 'tag v\*' || fail "the '$ENVIRONMENT' environment has no deployment rule for tags matching v*"
if printf '%s\n' "$POLICIES" | grep -q '^branch '; then
  fail "the '$ENVIRONMENT' environment also allows branches ($(printf '%s' "$POLICIES" | grep '^branch ' | tr '\n' ' ')); allow only tags matching v*"
fi
REPO_SECRETS="$(gh_ secret list --repo "$REPO" --json name --jq '.[].name' 2>>"$TOOL_LOG" | lines)"
for s in $SECRETS; do
  if printf '%s\n' "$REPO_SECRETS" | grep -qx "$s"; then
    fail "a REPOSITORY-wide secret $s exists, visible to every workflow; delete it first: gh secret delete $s --repo $REPO"
  fi
done

step "4/6 Preparing the upload" "base64 or the keystore file is unreadable"
B64="$(base64 < "$KS" | tr -d '\r\n')"
printf '%s' "$B64" | base64 --decode > "$WORK/roundtrip.p12" 2>>"$TOOL_LOG"
[ "$(sha256_of "$WORK/roundtrip.p12")" = "$(sha256_of "$KS")" ] || fail "the base64 copy of the keystore does not decode to the same file"
rm -f "$WORK/roundtrip.p12"

step "5/6 Uploading to the '$ENVIRONMENT' environment only" "gh could not store a secret; its own message is shown above"
printf '%s' "$B64" | gh_ secret set RELEASE_KEYSTORE_B64 --env "$ENVIRONMENT" --repo "$REPO" >>"$TOOL_LOG" 2>&1
gh_ secret set RELEASE_KEYSTORE_PASSWORD --env "$ENVIRONMENT" --repo "$REPO" < "$PWF" >>"$TOOL_LOG" 2>&1
gh_ secret set RELEASE_KEY_PASSWORD --env "$ENVIRONMENT" --repo "$REPO" < "$PWF" >>"$TOOL_LOG" 2>&1
printf '%s' "$RELEASE_KEY_ALIAS_NAME" | gh_ secret set RELEASE_KEY_ALIAS --env "$ENVIRONMENT" --repo "$REPO" >>"$TOOL_LOG" 2>&1
gh_ variable set RELEASE_CERT_SHA256 --env "$ENVIRONMENT" --repo "$REPO" --body "$CERT_SHA256" >>"$TOOL_LOG" 2>&1
unset B64

step "6/6 Checking where everything ended up" "compare the repository and environment secrets on GitHub (Settings > Secrets and variables, and Environments > release)"
ENV_SECRETS="$(gh_ secret list --env "$ENVIRONMENT" --repo "$REPO" --json name --jq '.[].name' 2>>"$TOOL_LOG" | lines)"
REPO_SECRETS="$(gh_ secret list --repo "$REPO" --json name --jq '.[].name' 2>>"$TOOL_LOG" | lines)"
for s in $SECRETS; do
  printf '%s\n' "$ENV_SECRETS" | grep -qx "$s" || fail "$s is not in the '$ENVIRONMENT' environment"
  ! printf '%s\n' "$REPO_SECRETS" | grep -qx "$s" || fail "$s exists as a repository-wide secret; delete it: gh secret delete $s --repo $REPO"
done
PINNED="$(gh_ variable get RELEASE_CERT_SHA256 --env "$ENVIRONMENT" --repo "$REPO" 2>>"$TOOL_LOG" | lines)"
[ "$PINNED" = "$CERT_SHA256" ] || fail "RELEASE_CERT_SHA256 in the environment does not match this key"

cat <<MSG

Done. The '$ENVIRONMENT' environment of $REPO now holds:
  secrets    $SECRETS
  variable   RELEASE_CERT_SHA256 = $CERT_SHA256
No repository-wide secret with these names exists. Only the sign job, after your approval and only for a v* tag, can read them.

Publish the certificate fingerprint where users can compare it (README "Signing key" section, release notes):
  $(colon_form "$CERT_SHA256")
MSG
