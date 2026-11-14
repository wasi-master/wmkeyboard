#!/usr/bin/env bash
# Collects the project's own native libraries that an APK leaves out and pins
# instead (native delivery), named the way the data repository's `natives`
# release publishes them: `<sha256>-<name>`.
#
#   collect-natives.sh <apk> <out-dir>
#
# Reads the APK's assets/native/pins.tsv, finds every `data` library in the
# jniLibs the build packaged from, and copies each one whose bytes match its
# pin. A pinned library with no matching file fails the run: the release
# would otherwise point every phone at a file that does not exist.
#
# NATIVES_FALLBACK_ROOT names a second checkout to look in after the current
# directory. F-Droid's scanner deletes prebuilt libraries it cannot build
# (libwmwhistle.so), yet its build still pins them from native/gates.json's
# fdroidPin; the committed bytes are in the untouched checkout.
set -euo pipefail

apk=$1
out=$2
mkdir -p "$out"

pins=$(unzip -p "$apk" assets/native/pins.tsv)
# Every jniLibs directory a library of native/gates.json lives in.
dirs=()
while IFS= read -r dir; do dirs+=("$dir"); done < <(python3 "$(dirname "$0")/../../native/gates.py" dirs)
[ ${#dirs[@]} -gt 0 ] || { echo "::error::native/gates.py listed no jniLibs directories"; exit 1; }

echo "$pins" | awk -F'\t' '!/^#/ && $6 == "data" {print $2 "\t" $3 "\t" $4}' | while IFS=$'\t' read -r abi name sha; do
    found=
    for dir in "${dirs[@]}" ${NATIVES_FALLBACK_ROOT:+"${dirs[@]/#/$NATIVES_FALLBACK_ROOT/}"}; do
        file=$dir/$abi/$name
        [ -f "$file" ] || continue
        if [ "$(sha256sum "$file" | cut -d' ' -f1)" = "$sha" ]; then
            cp "$file" "$out/$sha-$name"
            found=1
            break
        fi
    done
    [ -n "$found" ] || { echo "::error::no $abi $name with SHA-256 $sha in the jniLibs"; exit 1; }
    echo "$abi $name $sha"
done
