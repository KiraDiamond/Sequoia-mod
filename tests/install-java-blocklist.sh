#!/usr/bin/env bash
set -euo pipefail
trap 'printf "FAIL at line %s\n" "$LINENO" >&2' ERR
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
work="$(mktemp -d "${TMPDIR:-/tmp}/java-blocklist-test.XXXXXX")"
trap 'rm -rf -- "$work"' EXIT
mkdir -p "$work/jdk/lib/security" "$work/bin"
target="$work/jdk/lib/security/blocked.certs"
operator=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
# Exercise a CRLF header and a valid final entry with no newline.
printf 'Algorithm=SHA-256\r\n%s' "$operator" > "$target"
chmod 600 "$target"
bash "$root/scripts/install-java-blocklist.sh" "$work/jdk"
grep -qx "$operator" "$target"
expected="$(grep -Ec '^[0-9A-Fa-f]{64}$' "$root/security/blocked.certs")"
(( expected > 0 ))
actual="$(grep -Ec '^[0-9A-Fa-f]{64}$' "$target")"
[[ "$actual" == "$((expected + 1))" ]]
# Existing Java Properties keys and annotations must remain untouched.
printf '  %s=incident-ticket\n\t%s:operator-note\n%s # note\n' \
  "${operator/A/B}" "${operator/A/C}" "${operator/A/D}" >> "$target"
printf '%s\\\n%s=continued-note\n' "${operator:0:32}" "${operator:32}" >> "$target"
cp "$target" "$work/annotated"
bash "$root/scripts/install-java-blocklist.sh" "$work/jdk"
cmp "$target" "$work/annotated"
# A read-only file in a writable directory can still be replaced safely.
chmod 444 "$target"
bash "$root/scripts/install-java-blocklist.sh" "$work/jdk"
cmp "$target" "$work/annotated"
[[ "$(stat -c '%a' "$target" 2>/dev/null || stat -f '%Lp' "$target")" == 444 ]]
chmod 600 "$target"
cp "$target" "$work/first"
bash "$root/scripts/install-java-blocklist.sh" "$work/jdk"
cmp "$target" "$work/first"
# Metadata-copy failure must leave the active blocklist untouched.
cat > "$work/bin/cp" <<'COPY'
#!/usr/bin/env bash
exit 1
COPY
chmod +x "$work/bin/cp"
if PATH="$work/bin:$PATH" bash "$root/scripts/install-java-blocklist.sh" "$work/jdk"; then
  printf 'Metadata-copy failure unexpectedly accepted\n' >&2
  exit 1
fi
cmp "$target" "$work/first"
shopt -s nullglob
leftovers=("$target".*)
[[ "${#leftovers[@]}" == 0 ]]
rm "$work/bin/cp"
real_stat="$(command -v stat)"
cat > "$work/bin/stat" <<'STAT'
#!/usr/bin/env bash
if [[ "${@: -1}" == *.?????? ]]; then printf '99999:99999:600\n'; exit 0; fi
exec "$TEST_REAL_STAT" "$@"
STAT
chmod +x "$work/bin/stat"
if TEST_REAL_STAT="$real_stat" PATH="$work/bin:$PATH" \
  bash "$root/scripts/install-java-blocklist.sh" "$work/jdk" 2>/dev/null; then
  printf 'Silent metadata mismatch unexpectedly accepted\n' >&2
  exit 1
fi
cmp "$target" "$work/first"
leftovers=("$target".*)
[[ "${#leftovers[@]}" == 0 ]]
# A fingerprint embedded in a continued value is not a certificate-block key.
fingerprint="$(grep -m 1 -E '^[0-9A-F]{64}$' "$root/security/blocked.certs")"
printf 'Algorithm=SHA-256\noperator.reason=continued\\\n%s\n' "$fingerprint" > "$target"
bash "$root/scripts/install-java-blocklist.sh" "$work/jdk"
[[ "$(grep -Fxc "$fingerprint" "$target")" == 2 ]]
cp "$target" "$work/continued"
bash "$root/scripts/install-java-blocklist.sh" "$work/jdk"
cmp "$target" "$work/continued"
# A CRLF copy of the bundled list must still install every entry.
mkdir -p "$work/crlf/scripts" "$work/crlf/security"
cp "$root/scripts/install-java-blocklist.sh" "$work/crlf/scripts/"
awk '{ printf "%s\r\n", $0 }' "$root/security/blocked.certs" > "$work/crlf/security/blocked.certs"
printf 'Algorithm=SHA-256\n' > "$target"
bash "$work/crlf/scripts/install-java-blocklist.sh" "$work/jdk"
[[ "$(grep -Ec '^[0-9A-F]{64}$' "$target")" == "$expected" ]]
printf 'Algorithm=SHA-1\n' > "$target"
if bash "$root/scripts/install-java-blocklist.sh" "$work/jdk" 2>/dev/null; then
  printf 'Unsupported algorithm unexpectedly accepted\n' >&2
  exit 1
fi
printf 'Algorithm=SHA-1\n' > "$work/unchanged"
cmp "$target" "$work/unchanged"
rm "$target"
if bash "$root/scripts/install-java-blocklist.sh" "$work/jdk" 2>/dev/null; then
  printf 'Missing target unexpectedly accepted\n' >&2
  exit 1
fi
printf 'PASS: CRLF, unterminated entry, all bundled hashes, mode, idempotency, metadata-copy failure, cleanup, fail-closed checks\n'
