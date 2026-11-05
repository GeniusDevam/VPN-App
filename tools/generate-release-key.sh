#!/usr/bin/env bash
# Creates a NEW release-signing key for this app, outside the repository, and tells you how to install it as CI secrets.
# Run it on your own machine (not in CI, not in a shared session). Nothing here is ever committed or printed to logs.
#
#   tools/generate-release-key.sh [output-dir]      default: ~/.vpnapp-signing
#
# Prints the PUBLIC certificate fingerprint (safe to publish). The password stays in a 0600 file.
set -euo pipefail
DIR="${1:-$HOME/.vpnapp-signing}"
[ -e "$DIR/release.p12" ] && { echo "refusing to overwrite $DIR/release.p12"; exit 1; }
mkdir -p "$DIR"; chmod 700 "$DIR"
PW="$(openssl rand -base64 33 | tr -d '/+=\n')"
ALIAS="vpnapp-release"
keytool -genkeypair -keystore "$DIR/release.p12" -storetype PKCS12 -storepass "$PW" -keypass "$PW" -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 9125 -dname "CN=VPN App release, O=GeniusDevam" >/dev/null 2>&1
printf '%s' "$PW" > "$DIR/password.txt"; chmod 600 "$DIR/release.p12" "$DIR/password.txt"
FP="$(keytool -list -v -keystore "$DIR/release.p12" -storepass "$PW" -alias "$ALIAS" 2>/dev/null | awk -F'SHA256: ' '/SHA256:/{print $2; exit}')"
cat <<MSG

Key created in $DIR  (release.p12 and password.txt, both mode 0600).
Back them up somewhere private and offline: if you lose the key you cannot ship updates that Android will accept.

Signing certificate SHA-256 (public, publish it in the README and release notes):
  $FP

Install as secrets of the PROTECTED ENVIRONMENT "release" (not repository-wide secrets; docs/RELEASE_SIGNING.md explains the one-time
GitHub setup of that environment first: required reviewer, tag-only deployments). Values are read from files, never typed on a command line:
  base64 -w0 "$DIR/release.p12" | gh secret set RELEASE_KEYSTORE_B64 --env release --repo GeniusDevam/VPN-App
  gh secret set RELEASE_KEYSTORE_PASSWORD --env release --repo GeniusDevam/VPN-App < "$DIR/password.txt"
  gh secret set RELEASE_KEY_PASSWORD      --env release --repo GeniusDevam/VPN-App < "$DIR/password.txt"
  printf '%s' "$ALIAS" | gh secret set RELEASE_KEY_ALIAS --env release --repo GeniusDevam/VPN-App
Pin the expected certificate (an environment VARIABLE, public; the release job refuses to run without it and refuses any other key):
  gh variable set RELEASE_CERT_SHA256 --env release --repo GeniusDevam/VPN-App --body "$FP"
Do NOT create repository-level secrets with these names: CI will not read them, and they would only widen who can see them.
MSG
