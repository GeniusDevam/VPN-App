#!/usr/bin/env bash
# Re-signs an already-built APK with the key given in the environment, WITHOUT rebuilding it, so what gets released is
# byte-for-byte the code that was built and tested (only the signature changes).
#
#   tools/resign-apk.sh <in.apk> <out.apk>
#   env: KS_FILE (PKCS12 path)  KS_PASS  KEY_ALIAS  KEY_PASS      (passwords are read from the environment, never argv)
#        BUILD_TOOLS (dir of apksigner/zipalign/aapt2; default: newest in $ANDROID_HOME/build-tools)
#
# It then proves: exactly one signer, whose certificate is printed; v1, v2 and v3 signatures all verify for minSdk 23 (Android 6.0
# checks ONLY the v1/JAR signature; without it the APK does not install there); no signature material from the previous signer
# survives; every non-META-INF entry is identical to the input; 16 KB alignment is intact.
# Prints "CERT_SHA256=<hex>" as the last line on success.
set -euo pipefail
IN="$1"; OUT="$2"
: "${KS_FILE:?}" "${KS_PASS:?}" "${KEY_ALIAS:?}" "${KEY_PASS:?}"
BT="${BUILD_TOOLS:-$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)}"
export KS_PASS KEY_PASS

"$BT/apksigner" sign --ks "$KS_FILE" --ks-key-alias "$KEY_ALIAS" --ks-pass env:KS_PASS --key-pass env:KEY_PASS \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --out "$OUT" "$IN"

"$BT/apksigner" verify --verbose --min-sdk-version 23 --print-certs "$OUT" > "$OUT.verify.txt"
grep -q "Verified using v1 scheme (JAR signing): true" "$OUT.verify.txt" || { echo "v1 signature did not verify (Android 6.0 would refuse the APK)"; exit 1; }
grep -q "Verified using v2 scheme (APK Signature Scheme v2): true" "$OUT.verify.txt" || { echo "v2 signature did not verify"; exit 1; }
grep -q "Verified using v3 scheme (APK Signature Scheme v3): true" "$OUT.verify.txt" || { echo "v3 signature did not verify"; exit 1; }
[ "$(grep -c 'Number of signers: ' "$OUT.verify.txt")" = 1 ] && grep -q "Number of signers: 1" "$OUT.verify.txt" || { echo "expected exactly one signer"; exit 1; }
CERT="$(grep -m1 'certificate SHA-256 digest' "$OUT.verify.txt" | awk '{print $NF}')"
[ -n "$CERT" ] || { echo "no certificate digest"; exit 1; }
rm -f "$OUT.verify.txt"

# Nothing from the previous signer may remain, and the code must be unchanged.
python3 -I - "$IN" "$OUT" <<'PY'
import sys, zipfile
a, b = zipfile.ZipFile(sys.argv[1]), zipfile.ZipFile(sys.argv[2])
sig = lambda n: n.startswith("META-INF/") and n.rsplit(".", 1)[-1].upper() in ("RSA", "DSA", "EC", "SF")
# The new v1 signature adds its own .SF and signature block; none of the previous signer's files may survive unchanged.
old = {n: a.read(n) for n in a.namelist() if sig(n)}
left = [n for n in b.namelist() if sig(n) and n in old and b.read(n) == old[n]]
if left:
    sys.exit(f"signature files from the previous signer survived: {left}")
sf = [n for n in b.namelist() if sig(n) and n.upper().endswith(".SF")]
if len(sf) != 1:
    sys.exit(f"expected exactly one v1 signer (.SF file), found {sf}")
ia = {i.filename: (i.CRC, i.file_size) for i in a.infolist() if not i.filename.startswith("META-INF/")}
ib = {i.filename: (i.CRC, i.file_size) for i in b.infolist() if not i.filename.startswith("META-INF/")}
if ia != ib:
    diff = sorted(set(ia) ^ set(k for k in ib if ib[k] != ia.get(k)))[:5]
    sys.exit(f"re-signing changed APK contents: {diff}")
print(f"contents identical: {len(ia)} entries")
PY
"$BT/zipalign" -c -P 16 -v 4 "$OUT" | tail -1
echo "CERT_SHA256=$(echo "$CERT" | tr -d ': ' | tr A-F a-f)"
