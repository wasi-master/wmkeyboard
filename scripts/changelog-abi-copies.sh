#!/usr/bin/env bash
# Copies each store changelog to the versionCodes the stores look it up by.
#
#   scripts/changelog-abi-copies.sh           write the copies
#   scripts/changelog-abi-copies.sh --check   fail if one is missing or stale
#
# From 0.6.2 (wmkb.versionCode 35) every APK's manifest carries
# `versionCode * 10 + ABI digit` (abiDigits in app/build.gradle.kts), and both
# stores read release notes by that number: F-Droid looks for
# changelogs/351.txt and 352.txt beside the two APKs it builds, and Play for
# 350.txt beside the bundle. Neither falls back to 35.txt. So the changelog is
# still written once, as changelogs/<wmkb.versionCode>.txt, and this script
# makes the three copies; commit them with it, before the tag,
# because F-Droid reads them from the tagged commit.
#
# Play's own override tree (fastlane/play/...) only needs the bundle's copy.
set -euo pipefail

cd "$(dirname "$0")/.."
check=false
[ "${1:-}" = --check ] && check=true

# The first release with an ABI digit. Older changelogs keep their own numbers.
first=35
status=0

copy() { # <dir> <digits...>
  local dir=$1; shift
  [ -d "$dir" ] || return 0
  local f base digit target
  for f in "$dir"/*.txt; do
    base=$(basename "$f" .txt)
    [[ $base =~ ^[0-9]+$ ]] || continue
    [ "$base" -ge "$first" ] && [ "$base" -lt 100 ] || continue
    for digit in "$@"; do
      target=$dir/$((base * 10 + digit)).txt
      if $check; then
        if ! cmp -s "$f" "$target"; then
          echo "$target is missing or differs from $f; run scripts/changelog-abi-copies.sh" >&2
          status=1
        fi
      else
        cp "$f" "$target"
      fi
    done
  done
}

copy fastlane/metadata/android/en-US/changelogs 0 1 2
copy fastlane/play/metadata/android/en-US/changelogs 0
exit $status
