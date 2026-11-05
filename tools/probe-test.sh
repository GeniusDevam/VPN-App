#!/usr/bin/env bash
# Real-OpenVPN check of the protocol-aware latency probe for tls-auth relays (finding C3).
# Starts real OpenVPN servers on loopback and runs the app's REAL NetworkProber against them:
#   - tls-auth with key-direction (client 1 / server 0): must be answered (HMAC-signed reset, reply HMAC verified)
#   - tls-auth bidirectional (no key-direction): must be answered
#   - same server, probe holding the WRONG key: must get NO answer (a relay never answers an unauthenticated/forged reset)
#   - tls-crypt profile: not parsed as tls-auth, so the prober must use the weak TCP fallback, labelled as such
# Needs: openvpn, openssl, java, gradle. No tun device is needed (--dev null) and nothing is routed.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W="${WORK:-$(mktemp -d)}"; mkdir -p "$W"; cd "$W"
PASS=0; FAIL=0
ok()  { echo "  PASS: $*"; PASS=$((PASS+1)); }
bad() { echo "  FAIL: $*"; FAIL=$((FAIL+1)); }

openssl ecparam -name prime256v1 -genkey -noout -out ca.key 2>/dev/null
openssl req -x509 -new -key ca.key -subj "/CN=probe-ca" -days 3 -sha256 -out ca.pem 2>/dev/null
openssl ecparam -name prime256v1 -genkey -noout -out srv.key 2>/dev/null
openssl req -new -key srv.key -subj "/CN=srv" -out srv.csr 2>/dev/null
openssl x509 -req -in srv.csr -CA ca.pem -CAkey ca.key -CAcreateserial -days 3 -sha256 -out srv.pem 2>/dev/null
openvpn --genkey secret ta.key; openvpn --genkey secret wrong.key; openvpn --genkey secret tc.key

start_server() { # port tlsopts...
  local port=$1; shift
  openvpn --daemon --writepid "srv$port.pid" --log "srv$port.log" --tls-server --proto udp --local 127.0.0.1 --lport $port \
    --dev null --ca ca.pem --cert srv.pem --key srv.key --dh none --verb 3 "$@" || echo "server $port failed to start"
}
profile() { # file keyfile direction(or -) tag(tls-auth|tls-crypt)
  { printf 'client\ndev tun\nproto udp\nremote 127.0.0.1 1194\ncipher AES-128-CBC\nauth SHA1\n'
    [ "$3" != "-" ] && printf 'key-direction %s\n' "$3"
    printf '<%s>\n' "$4"; cat "$2"; printf '</%s>\n' "$4"; } > "$1"
}
probe() {
  local out; out=$( cd "$ROOT/engine" && gradle -q --console=plain probeCli --args="$1 127.0.0.1 $2 udp" 2>"$W/probe.err" )
  echo "$out" | grep '^method=' || { echo "(no result; gradle said:)" >&2; tail -5 "$W/probe.err" >&2; }
}

start_server 21941 --tls-auth ta.key 0
start_server 21942 --tls-auth ta.key
start_server 21943 --tls-crypt tc.key
sleep 1.5
profile dir.conf ta.key 1 tls-auth
profile bidi.conf ta.key - tls-auth
profile wrong.conf wrong.key 1 tls-auth
profile crypt.conf tc.key - tls-crypt

r=$(probe "$W/dir.conf" 21941);   echo "  key-direction: $r"
case "$r" in method=OPENVPN_UDP_RESET\ rtt=[0-9]*) ok "tls-auth (key-direction 1/0) answered by a real server" ;; *) bad "tls-auth key-direction probe: '$r'";; esac
r=$(probe "$W/bidi.conf" 21942);  echo "  bidirectional: $r"
case "$r" in method=OPENVPN_UDP_RESET\ rtt=[0-9]*) ok "tls-auth (bidirectional) answered by a real server" ;; *) bad "tls-auth bidirectional probe: '$r'";; esac
r=$(probe "$W/wrong.conf" 21941); echo "  wrong key:     $r"
case "$r" in method=OPENVPN_UDP_RESET\ rtt=none) ok "a probe with the wrong key gets no answer" ;; *) bad "wrong-key probe should be unanswered: '$r'";; esac
r=$(probe "$W/crypt.conf" 21943); echo "  tls-crypt:     $r"
case "$r" in method=TCP_FALLBACK*) ok "tls-crypt falls back to the labelled weak TCP check" ;; *) bad "tls-crypt should use TCP_FALLBACK: '$r'";; esac

echo "== result: $PASS passed, $FAIL failed"
for p in srv*.pid; do kill "$(cat "$p")" 2>/dev/null; done
[ $FAIL -eq 0 ]
