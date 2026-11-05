#!/usr/bin/env bash
# Real-OpenVPN check of the relay-identity policy (finding B2).
#
# Builds a throw-away PKI, starts real OpenVPN servers presenting different certificates, produces the client profile
# with the app's REAL ConfigSanitizer, and runs a real OpenVPN client against each server. Asserts that:
#   - a server with a certificate for opengw.net from the trusted root CONNECTS,
#   - a certificate for another name, a certificate without the server role, and a certificate from an untrusted CA
#     are REJECTED,
#   - and (control) a plain VPN Gate-style profile WITHOUT the policy would have accepted the wrong-name server,
#     which is the weakness the policy closes.
# Needs: openvpn, openssl, java, gradle, root (tun devices). Nothing is routed: --route-noexec, loopback only.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W="${WORK:-$(mktemp -d)}"; mkdir -p "$W"; cd "$W"
PASS=0; FAIL=0
ok()   { echo "  PASS: $*"; PASS=$((PASS+1)); }
bad()  { echo "  FAIL: $*"; FAIL=$((FAIL+1)); }

echo "== PKI"
ec() { openssl ecparam -name prime256v1 -genkey -noout -out "$1.key" 2>/dev/null; }
mkroot() { ec "$1"; openssl req -x509 -new -key "$1.key" -subj "/CN=$2" -days 30 -sha256 -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" -out "$1.pem" 2>/dev/null; }
mkleaf() { # name cn eku signer
  ec "$1"; openssl req -new -key "$1.key" -subj "/CN=$2" -out "$1.csr" 2>/dev/null
  printf "basicConstraints=CA:FALSE\nkeyUsage=digitalSignature\nextendedKeyUsage=%s\nsubjectAltName=DNS:%s\n" "$3" "$2" > "$1.ext"
  openssl x509 -req -in "$1.csr" -CA "$4.pem" -CAkey "$4.key" -CAcreateserial -days 30 -sha256 -extfile "$1.ext" -out "$1.pem" 2>/dev/null
}
mkroot root "Trusted Test Root"; mkroot rogue "Rogue Root"
mkleaf good   opengw.net    serverAuth root
mkleaf badcn  evil.example  serverAuth root
mkleaf badeku opengw.net    clientAuth root
mkleaf badca  opengw.net    serverAuth rogue
FP=$(openssl x509 -in root.pem -noout -fingerprint -sha256 | cut -d= -f2 | tr -d : | tr A-Z a-z)
echo "root fingerprint $FP"

echo "== profiles through the app's real sanitizer"
mkdir -p prof
for c in good badcn badeku badca; do
  { printf 'client\ndev tun\nproto udp\nremote 203.0.114.7 1194\ncipher AES-128-CBC\nauth SHA1\nresolv-retry infinite\nnobind\nauth-user-pass\n<ca>\n'; cat root.pem; printf '</ca>\n'; } > "prof/$c.profile"
done
( cd "$ROOT/engine" && gradle -q --console=plain sanitizeCli --args="$W/prof 203.0.114.7 $FP" ) >/dev/null 2>&1 || { echo "sanitizer CLI failed"; exit 2; }
ls prof | tr '\n' ' '; echo
grep -q "^remote-cert-tls server$" prof/good.sanitized && grep -q "^verify-x509-name opengw.net name$" prof/good.sanitized \
  && ok "sanitized profile carries remote-cert-tls server + verify-x509-name opengw.net name" || bad "policy lines missing from sanitized profile"
grep -qi "comp" prof/good.sanitized && bad "compression directive present" || ok "no compression directive"

echo "== real OpenVPN servers and clients"
printf 'vpn\nvpn\n' > creds; printf '#!/bin/sh\nexit 0\n' > auth.sh; chmod +x auth.sh
n=0
run_case() { # name expect(connect|reject) profile
  local name="$1" expect="$2" prof="$3"; n=$((n+1)); local port=$((21000+n)) subnet="10.97.$n.0"
  openvpn --daemon --writepid "srv$n.pid" --log "srv$n.log" --mode server --tls-server --proto udp --local 127.0.0.1 --port $port \
    --dev tun --dev-type tun --server $subnet 255.255.255.0 --topology subnet \
    --ca root.pem --cert "$name.pem" --key "$name.key" --dh none --verify-client-cert none \
    --cipher AES-128-CBC --data-ciphers AES-128-CBC --data-ciphers-fallback AES-128-CBC --auth SHA1 --script-security 2 --auth-user-pass-verify $W/auth.sh via-env --username-as-common-name \
    --keepalive 2 8 --verb 3 || { bad "$name: server did not start"; return; }
  sleep 1
  # Same profile the app would run, pointed at loopback for the test. Nothing is routed.
  sed "s/^remote 203.0.114.7 1194/remote 127.0.0.1 $port/; s/^dev tun/dev tun/" "$prof" > "cli$n.conf"
  # The same extra options TunnelManager.buildProfile adds for a relay that declares AES-128-CBC.
  printf 'auth-user-pass %s/creds\ntls-version-min 1.2\nallow-compression no\ndata-ciphers AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305:AES-128-CBC\ndata-ciphers-fallback AES-128-CBC\nconnect-retry-max 1\nconnect-timeout 8\nverb 4\n' "$W" >> "cli$n.conf"
  timeout 25 openvpn --config "cli$n.conf" --dev tun --dev-type tun --route-noexec --ifconfig-noexec --log "cli$n.log" --writepid "cli$n.pid" --daemon
  for _ in $(seq 1 24); do sleep 1; grep -q "Initialization Sequence Completed\|VERIFY .*ERROR\|Options error\|TLS Error\|AUTH_FAILED" "cli$n.log" 2>/dev/null && break; done
  if [ ! -s "cli$n.log" ] || grep -q "Options error" "cli$n.log" || ! grep -q "TLS: Initial packet\|VERIFY\|TLS Error\|Initialization Sequence" "cli$n.log"; then
    bad "$name: client did not run a TLS handshake (harness problem, not a result)"; tail -5 "cli$n.log" 2>/dev/null; kill "$(cat srv$n.pid 2>/dev/null)" 2>/dev/null; return
  fi
  local connected=no; grep -q "Initialization Sequence Completed" "cli$n.log" && connected=yes
  kill "$(cat cli$n.pid 2>/dev/null)" 2>/dev/null; kill "$(cat srv$n.pid 2>/dev/null)" 2>/dev/null; sleep 0.5
  # A rejection must be a certificate/identity rejection, not some unrelated failure.
  local why; why=$(grep -m1 -o 'VERIFY X509NAME ERROR[^,]*\|VERIFY EKU ERROR[^,]*\|VERIFY KU ERROR[^,]*\|VERIFY ERROR: depth=0, error=[^:]*' "cli$n.log" | cut -c1-110)
  if [ "$expect" = connect ]; then [ $connected = yes ] && ok "$name: connected as expected" || { bad "$name: expected a connection"; tail -12 "cli$n.log"; }
  else
    if [ $connected = no ] && [ -n "$why" ]; then ok "$name: rejected by certificate verification ($why)"
    else bad "$name: must be rejected by certificate verification (connected=$connected why='$why')"; tail -12 "cli$n.log"; fi
  fi
}
run_case good   connect prof/good.sanitized
run_case badcn  reject  prof/badcn.sanitized
run_case badeku reject  prof/badeku.sanitized
run_case badca  reject  prof/badca.sanitized
# Control: a stock VPN Gate-style profile (no verification options) accepts the wrong-name server. This is what the policy fixes.
{ printf 'client\ndev tun\nproto udp\nremote 127.0.0.1 1194\ncipher AES-128-CBC\nauth SHA1\nnobind\nauth-user-pass\n<ca>\n'; cat root.pem; printf '</ca>\n'; } > prof/control.sanitized
sed -i 's/^remote 127.0.0.1 1194/remote 203.0.114.7 1194/' prof/control.sanitized
run_case badcn  connect prof/control.sanitized && echo "  (control: without the policy the wrong-name server is accepted)"

echo "== result: $PASS passed, $FAIL failed"
pkill -f "openvpn --daemon" 2>/dev/null || true
[ $FAIL -eq 0 ]
