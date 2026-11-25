# Shared helpers for tools/generate-release-key.sh and tools/upload-release-key.sh. Sourced, not run.
# Works in bash 3.2+ on Linux, macOS and Windows (Git Bash / MSYS2). Never prints a password or key material.

LC_ALL=C; export LC_ALL
RELEASE_KEY_ALIAS_NAME="vpnapp-release"
# The certificate of the key that was published in this repository's history. It must never be used again.
LEAKED_CERT_SHA256="e73acd75265ca67ef3fb295538c49a8a81be0b122bacf72006f46691fe0efae4"

case "$(uname -s 2>/dev/null)" in MINGW*|MSYS*|CYGWIN*) RK_WINDOWS=1 ;; *) RK_WINDOWS=0 ;; esac

STEP="starting"; STEP_HINT=""; TOOL_LOG=""; RK_SECRET=""

say() { printf '%s\n' "$*" >&2; }
step() { STEP="$1"; STEP_HINT="${2:-}"; say "[$1]"; [ -z "$TOOL_LOG" ] || : > "$TOOL_LOG"; }   # a failure shows only its own step's output
fail() { say "ERROR: $*"; exit 1; }

# Removes the current secret and anything shaped like a key-sized hex value from tool output before it is shown.
sanitize() {
  if [ -n "$RK_SECRET" ]; then sed -e "s/$RK_SECRET/[password removed]/g" -e 's/[0-9A-Fa-f]\{64\}/[hex value removed]/g'
  else sed -e 's/[0-9A-Fa-f]\{64\}/[hex value removed]/g'; fi
}

# Shows why the failing step failed: the step name, the tool's own error (sanitized), and a hint.
report_failure() {
  local rc="$1"
  say ""
  say "FAILED at step: $STEP (exit code $rc)"
  if [ -n "$TOOL_LOG" ] && [ -s "$TOOL_LOG" ]; then
    say "Output of the failing tool (passwords and key-sized values removed):"
    tail -n 25 "$TOOL_LOG" | tr -d '\r' | sanitize | sed 's/^/    /' >&2
  fi
  [ -z "$STEP_HINT" ] || say "Hint: $STEP_HINT"
}

# Runs a tool with its output captured in the private log, not on screen. Git Bash otherwise rewrites arguments that start
# with "/" (icacls switches, for example) into Windows paths; native-path arguments are converted explicitly with native().
run() { MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$@" >>"$TOOL_LOG" 2>&1; }

# A path as the native tool on this platform expects it (C:\Users\... on Windows).
native() { if [ "$RK_WINDOWS" = 1 ]; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

find_keytool() {
  local c jh=""
  KEYTOOL="$(command -v keytool 2>/dev/null || true)"
  if [ -z "$KEYTOOL" ] && [ -n "${JAVA_HOME:-}" ]; then
    jh="$JAVA_HOME"; [ "$RK_WINDOWS" = 1 ] && jh="$(cygpath -u "$JAVA_HOME")"
    for c in "$jh/bin/keytool" "$jh/bin/keytool.exe"; do [ -x "$c" ] && KEYTOOL="$c" && break; done
  fi
  [ -n "$KEYTOOL" ] || fail "keytool was not found. Install a JDK (for example Eclipse Temurin 21) and make sure 'keytool' runs in this terminal or JAVA_HOME is set."
}

# 64 lowercase hex characters (256 bits) from the system CSPRNG, with nothing else in them. On Windows, OpenSSL writes its
# output with CR LF line endings; the CR used to end up inside the password, which Java then rejected ("Password is not ASCII").
# Keeping only [0-9a-f] makes the result independent of line endings and of which tool produced it.
random_password() {
  local out=""
  if command -v openssl >/dev/null 2>&1; then out="$( (openssl rand -hex 32 2>/dev/null || true) | tr -dc '0-9a-f')"; fi
  if [ "${#out}" -ne 64 ] && [ -r /dev/urandom ]; then out="$(od -An -tx1 -N32 /dev/urandom | tr -dc '0-9a-f')"; fi
  printf '%s' "$out"
}

valid_password_file() {  # printable ASCII only, no line breaks, at least 16 characters (Java rejects anything else)
  local f="$1" n
  n="$(wc -c < "$f" | tr -d ' ')"
  [ "$n" -ge 16 ] && ! LC_ALL=C grep -q '[^ -~]' "$f" && [ "$(wc -l < "$f" | tr -d ' ')" -eq 0 ]
}

sha256_of() {
  local h
  if command -v sha256sum >/dev/null 2>&1; then h="$(sha256sum < "$1")"
  elif command -v shasum >/dev/null 2>&1; then h="$(shasum -a 256 < "$1")"
  else h="$(openssl dgst -sha256 -r < "$1")"; fi
  printf '%s' "$h" | tr -dc '0-9a-f' | cut -c1-64
}

# Certificate SHA-256 computed twice: by hashing the exported certificate, and as reported by keytool. Both must agree.
# Sets CERT_SHA256 (lowercase hex, no colons: the format the release job compares).
certificate_sha256() {  # keystore, password-file, scratch-dir
  local ks="$1" pwf="$2" tmp="$3" listed
  run "$KEYTOOL" -J-Duser.language=en -exportcert -keystore "$(native "$ks")" -storetype PKCS12 \
    -storepass:file "$(native "$pwf")" -alias "$RELEASE_KEY_ALIAS_NAME" -file "$(native "$tmp/cert.der")"
  CERT_SHA256="$(sha256_of "$tmp/cert.der")"
  rm -f "$tmp/cert.der"
  listed="$(MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$KEYTOOL" -J-Duser.language=en -list -v -keystore "$(native "$ks")" \
    -storetype PKCS12 -storepass:file "$(native "$pwf")" -alias "$RELEASE_KEY_ALIAS_NAME" 2>>"$TOOL_LOG" \
    | tr -d '\r' | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | awk 'NR == 1' | tr -d ': ' | tr 'A-F' 'a-f')"
  [ "${#CERT_SHA256}" -eq 64 ] || fail "could not compute the certificate fingerprint"
  [ "$listed" = "$CERT_SHA256" ] || fail "the certificate fingerprint reported by keytool does not match the exported certificate"
  [ "$CERT_SHA256" != "$LEAKED_CERT_SHA256" ] || fail "this is the compromised key that was published in the repository's history; it must never be used"
}

colon_form() { printf '%s' "$1" | tr 'a-f' 'A-F' | sed 's/../&:/g; s/:$//'; }

# ---- file protection ----------------------------------------------------------------------------------------------------
# Linux/macOS: owner-only modes. Windows: chmod is a no-op in Git Bash (NTFS has no Unix modes), so the real control is the
# NTFS access list: inheritance removed and a single entry giving full control to the current user's SID.
windows_sid() {
  local who
  who="$(cygpath -u "${SYSTEMROOT:-C:\\Windows}")/System32/whoami.exe"
  RK_SID="$(MSYS_NO_PATHCONV=1 "$who" /user /fo csv /nh 2>>"$TOOL_LOG" | tr -d '\r"' | awk -F, '{print $NF}')"
  RK_USER="$(MSYS_NO_PATHCONV=1 "$who" 2>>"$TOOL_LOG" | tr -d '\r')"
  case "$RK_SID" in S-1-*) ;; *) fail "could not determine your Windows account (whoami /user)" ;; esac
}

protect() {  # path, dir|file
  local p="$1" kind="$2" grant
  if [ "$RK_WINDOWS" = 1 ]; then
    [ -n "${RK_SID:-}" ] || windows_sid
    if [ "$kind" = dir ]; then grant="*${RK_SID}:(OI)(CI)F"; else grant="*${RK_SID}:F"; fi
    run icacls "$(native "$p")" /inheritance:r /grant:r "$grant"
  else
    if [ "$kind" = dir ]; then chmod 700 "$p"; else chmod 600 "$p"; fi
  fi
  verify_protected "$p" "$kind"
}

verify_protected() {  # path, dir|file
  local p="$1" kind="$2" acl n m
  if [ "$RK_WINDOWS" = 1 ]; then
    acl="$(MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' icacls "$(native "$p")" 2>>"$TOOL_LOG" | tr -d '\r')"
    n="$(printf '%s\n' "$acl" | grep -o ':(' | wc -l | tr -d ' ')"
    [ "$n" -eq 1 ] && printf '%s\n' "$acl" | grep -qiF "${RK_USER}:(" \
      || { printf '%s\n' "$acl" >>"$TOOL_LOG"; fail "$p is not restricted to your Windows account alone (found $n access entries)"; }
  else
    m="$(stat -c '%a' "$p" 2>/dev/null || stat -f '%Lp' "$p")"
    if [ "$kind" = dir ]; then [ "$m" = 700 ] || fail "$p has mode $m, expected 700"; else [ "$m" = 600 ] || fail "$p has mode $m, expected 600"; fi
  fi
}
