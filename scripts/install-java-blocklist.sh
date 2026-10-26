#!/usr/bin/env bash
set -euo pipefail
java_home="${1:?Usage: install-java-blocklist.sh JAVA_HOME}"
source_file="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../security" && pwd)/blocked.certs"
target="$java_home/lib/security/blocked.certs"
[[ -f "$target" ]] || { printf 'Missing Java blocklist: %s\n' "$target" >&2; exit 1; }
[[ "$(head -n 1 "$target" | tr -d '\r')" == 'Algorithm=SHA-256' ]] || { printf 'Unsupported Java blocklist algorithm\n' >&2; exit 1; }
metadata() {
  stat -c '%u:%g:%a' "$1" 2>/dev/null || stat -f '%u:%g:%Lp' "$1"
}
original_metadata="$(metadata "$target")"
temporary="$(mktemp "${target}.XXXXXX")"
trap 'rm -f -- "$temporary"' EXIT
cp -p "$target" "$temporary"
# BSD cp can silently fail to preserve ownership; do not replace in that case.
[[ "$(metadata "$temporary")" == "$original_metadata" ]] || {
  printf 'Unable to preserve Java blocklist metadata\n' >&2; exit 1;
}
chmod u+w "$temporary"
# A physical fingerprint line may be part of the preceding property's value.
contains_fingerprint() {
  local line continued=false
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    if [[ "$continued" == false && "$line" == "$1" ]]; then return 0; fi
    continued=false
    while [[ "$line" == *\\ ]]; do
      if [[ "$continued" == true ]]; then continued=false; else continued=true; fi
      line="${line%?}"
    done
  done < "$target"
  return 1
}
# Keep the original Properties syntax, annotations and continuations byte-for-byte.
appended=false
while IFS= read -r fingerprint || [[ -n "$fingerprint" ]]; do
  fingerprint="${fingerprint%$'\r'}"
  [[ "$fingerprint" =~ ^[0-9A-F]{64}$ ]] || continue
  if ! contains_fingerprint "$fingerprint"; then
    if [[ "$appended" == false ]]; then printf '\n\n' >> "$temporary"; appended=true; fi
    printf '%s\n' "$fingerprint" >> "$temporary"
  fi
done < "$source_file"
chmod "${original_metadata##*:}" "$temporary"
[[ "$(metadata "$temporary")" == "$original_metadata" ]] || {
  printf 'Java blocklist metadata changed during update\n' >&2; exit 1;
}
mv -f -- "$temporary" "$target"
