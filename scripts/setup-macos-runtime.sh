#!/bin/bash
# Pinned runtime with the IME lifecycle fix, from the official JetBrains release.
# Downloads to a build cache only; never changes system Java or installed apps.
set -euo pipefail
[[ "$(uname -s)" == Darwin && "$(uname -m)" == arm64 ]] || {
  echo 'This runtime is for macOS arm64 only.' >&2; exit 1;
}
root="$(cd "$(dirname "$0")/.." && pwd)"
name=jbrsdk-21.0.11-osx-aarch64-b1163.125
cache="$root/.gradle/yunx-runtime"
runtime="$cache/$name/Contents/Home"
checksum=4b2769d097547b70df3cd8ff49d137579acb7fea874419052ec3450565665dc3262caa9a7a9f7e9ca611fd02650eb002845b3cd029cebc300eb00dd00ef45c43
if [[ ! -x "$runtime/bin/java" ]]; then
  mkdir -p "$cache"
  staging="$(mktemp -d "$cache/download.XXXXXX")"
  trap 'rm -rf "$staging"' EXIT
  curl --fail --location --retry 2 \
    "https://cache-redirector.jetbrains.com/intellij-jbr/$name.tar.gz" \
    --output "$staging/runtime.tar.gz"
  actual="$(shasum -a 512 "$staging/runtime.tar.gz" | awk '{print $1}')"
  [[ "$actual" == "$checksum" ]] || { echo 'Runtime checksum mismatch' >&2; exit 1; }
  tar -xzf "$staging/runtime.tar.gz" -C "$staging"
  mv "$staging/$name" "$cache/$name"
fi
"$runtime/bin/java" --add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED \
  "$root/scripts/diagnostics/InactiveInputMethodProbe.java" >&2
printf '%s\n' "$runtime"
