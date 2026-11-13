#!/usr/bin/env bash
# Rewrites the body of an already-published GitHub release from
# release-notes/<version>.md, using the same generator the release workflow
# uses, and creates the release when the tag has none.
#
#   backfill-release-notes.sh [--dry-run] <tag> [<tag> ...]
#
# The download grid needs the size of every attached file. Nothing is
# downloaded for that: the asset list from the API carries the byte counts, and
# this writes sparse stand-ins of exactly that size for the generator to
# measure.
#
# Needs `gh` authenticated against the repo, and a checkout with full history.
set -euo pipefail

repo=${REPO:-wasi-master/wmkeyboard}
dry=false
if [ "${1:-}" = '--dry-run' ]; then dry=true; shift; fi
[ $# -gt 0 ] || { echo "usage: $0 [--dry-run] <tag>..." >&2; exit 2; }

here=$(cd "$(dirname "$0")" && pwd)
generate="$here/release-notes.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

for tag in "$@"; do
  version=${tag#v}

  # The version code the release's own filenames were built with. Tags older
  # than gradle.properties keep it in app/build.gradle.kts; tags older than
  # both have no artifacts to name, so any value does.
  code=$(git show "${tag}:gradle.properties" 2>/dev/null |
           sed -n 's/^wmkb\.versionCode=//p' | head -n1)
  if [ -z "$code" ]; then
    code=$(git show "${tag}:app/build.gradle.kts" 2>/dev/null |
             sed -n 's/.*versionCode *= *\([0-9]\{1,\}\).*/\1/p' | head -n1)
  fi
  code=${code:-0}

  dist="$work/$tag"
  mkdir -p "$dist"
  exists=true
  gh release view "$tag" --repo "$repo" \
     --json assets --jq '.assets[] | "\(.size)\t\(.name)"' > "$work/assets" 2>/dev/null ||
    exists=false

  if [ "$exists" = true ]; then
    while IFS=$'\t' read -r size name; do
      [ -n "$name" ] || continue
      # Sparse file of the real length: the generator only ever measures these.
      : > "$dist/$name"
      [ "$size" -gt 0 ] && dd if=/dev/zero of="$dist/$name" bs=1 count=0 \
                              seek="$size" status=none
      # 0.3.0 shipped before the filenames were versioned. The generator looks
      # for the modern name, so give it one of the same length to measure; the
      # links are pointed back at the real name once the body is written.
      case "$name" in
        app-*-release.apk)
          modern=${name#app-}
          modern=wmkeyboard-${version}-vc${code}-${modern%-release.apk}.apk
          ln "$dist/$name" "$dist/$modern" 2>/dev/null ||
            cp "$dist/$name" "$dist/$modern"
          ;;
      esac
    done < "$work/assets"
  fi

  body="$work/$tag.md"
  "$generate" "$version" "$code" "$tag" "$dist" "$repo" > "$body"

  # Point the grid back at the names actually attached, rather than
  # re-uploading anything under the modern ones.
  if [ -f "$dist/app-full-universal-release.apk" ]; then
    for flavor in full lite; do
      for abi in arm64-v8a armeabi-v7a universal; do
        sed -i.bak "s|wmkeyboard-${version}-vc${code}-${flavor}-${abi}\.apk|app-${flavor}-${abi}-release.apk|g" "$body"
      done
    done
    rm -f "$body.bak"
  fi

  if [ "$dry" = true ]; then
    if [ "$exists" = true ]; then state='existing release'; else state='no release yet'; fi
    echo "===== $tag ($state, versionCode $code) ====="
    cat "$body"
    continue
  fi

  if [ "$exists" = true ]; then
    gh release edit "$tag" --repo "$repo" \
       --title "WM Keyboard $version" --notes-file "$body"
    echo "updated  $tag"
  else
    # --latest=false: these are tags cut after the fact. Letting GitHub decide
    # would be a coin toss on whether an old tag takes the Latest badge, which
    # the in-app updater and everyone's bookmarks read.
    gh release create "$tag" --repo "$repo" --latest=false \
       --title "WM Keyboard $version" --notes-file "$body"
    echo "created  $tag"
  fi
done
