#!/usr/bin/env bash
# Builds the body of a GitHub release: highlights, a download grid with real
# file sizes, and a grouped changelog of every commit since the previous tag.
#
#   release-notes.sh <version> <versionCode> <tag> <dist-dir> [<owner/repo>]
#
# Writes markdown to stdout. Needs the repo checked out with full history
# (fetch-depth: 0) so the previous tag can be found.
set -euo pipefail

# A release body keeps every single newline as a line break, so the 80-column
# wrapping in release-notes/*.md and the prose below would render as a narrow
# column. Run the whole script through a filter that rejoins paragraphs.
if [ -z "${WMKB_NOTES_UNWRAPPED:-}" ]; then
  WMKB_NOTES_UNWRAPPED=1 "$0" "$@" | python3 "$(dirname "$0")/unwrap-markdown.py"
  exit 0
fi

version=${1:?version}
code=${2:?versionCode}
tag=${3:?tag}
dist=${4:?dist directory}
repo=${5:-${GITHUB_REPOSITORY:-wasi-master/wmkeyboard}}

base="https://github.com/${repo}/releases/download/${tag}"
prefix="wmkeyboard-${version}-vc${code}"

# ---------------------------------------------------------------- helpers

# Human size for a file in dist/, or "not built" when the build did not
# produce it (a release cut before native symbols existed, for one).
size_of() {
  local f="$dist/$1"
  [ -f "$f" ] || { printf 'not built'; return; }
  # stat's flags differ between BSD and GNU; wc -c is the same everywhere.
  local bytes
  bytes=$(wc -c < "$f" | tr -d ' ')
  awk -v b="$bytes" 'BEGIN {
    if (b >= 1073741824) printf "%.1f GB", b / 1073741824
    else if (b >= 1048576) printf "%.0f MB", b / 1048576
    else if (b >= 1024) printf "%.0f KB", b / 1024
    else printf "%d B", b
  }'
}

# A download cell: a linked size, or plain "not built" when the file is
# absent, so a missing artifact says so instead of offering a broken link.
cell() {
  local file="$1" emphasis="${2:-}" sz
  sz=$(size_of "$file")
  if [ "$sz" = 'not built' ]; then printf 'not built'; return; fi
  printf '%s[%s](%s/%s)%s' "$emphasis" "$sz" "$base" "$file" "$emphasis"
}

# ---------------------------------------------------------------- highlights

# Hand-written notes for this version win. Without them, fall back to the
# store changelog, which every release has to write anyway.
notes_file="release-notes/${version}.md"
store_changelog="fastlane/metadata/android/en-US/changelogs/${code}.txt"

# Open by default: a reader who wants the prose gets it without a click, and
# one who came for the download can fold it away.
echo '<details open>'
echo "<summary><h2>What's new in ${version}</h2></summary>"
echo
if [ -f "$notes_file" ]; then
  cat "$notes_file"
else
  if [ -f "$store_changelog" ]; then
    cat "$store_changelog"
  else
    echo "_No notes were written for this release._"
  fi
  echo
fi
echo
echo '</details>'
echo

# ---------------------------------------------------------------- downloads

# A release with no APKs attached, such as a tag cut before the build
# pipeline existed, gets notes and nothing else rather than an empty grid.
have_apk=false
for f in "$dist"/*.apk; do
  [ -e "$f" ] && have_apk=true && break
done

if [ "$have_apk" = true ]; then
cat <<EOF
## Download

### **⭐ [Download WM Keyboard ${version}](${base}/${prefix}-full-en-arm64-v8a.apk)** · $(size_of "${prefix}-full-en-arm64-v8a.apk")

This is the one for almost every phone from 2017 on. It installs over any earlier
version from GitHub and keeps all your settings. Also on
[Google Play](https://play.google.com/store/apps/details?id=com.wasimaster.wmkeyboard)
and [F-Droid](https://f-droid.org/packages/com.wasimaster.wmkeyboard/) (F-Droid is
often a few days behind).

**In your language:** on Android 11 or later the app switches to your phone's
language by itself a few seconds after it first opens (a small download, so it
needs the internet once). To pick a different one: **Settings › About › App
language**. On Android 7 to 10, or to have every language without a download,
get **All languages** below, which has all 48 built in.

**Keyboard languages are not affected:** every layout and dictionary is in every
download.

EOF
if [ -f "$dist/${prefix}-full-intl-arm64-v8a.apk" ]; then
cat <<EOF
<details>
<summary><b>All languages, other processors, or it won't install?</b></summary>

| Your device | Standard | All languages |
|:---|:---|:---|
| Almost every phone (arm64-v8a) | $(cell "${prefix}-full-en-arm64-v8a.apk") | $(cell "${prefix}-full-intl-arm64-v8a.apk") |
| Older 32-bit phone or watch (armeabi-v7a) | $(cell "${prefix}-full-en-armeabi-v7a.apk") | $(cell "${prefix}-full-intl-armeabi-v7a.apk") |
| Not sure, just make it work (universal) | $(cell "${prefix}-full-en-universal.apk") | $(cell "${prefix}-full-intl-universal.apk") |

**All languages** is the same app with all 48 interface languages built in
instead of fetched. Both are signed with the same key, so either installs over
the other without losing a setting, and each one's updater stays on its own kind.

</details>

EOF
else
cat <<EOF
<details>
<summary><b>It won't install, or not an ordinary phone?</b></summary>

| Your device | Download |
|:---|:---|
| Older 32-bit phone or watch | armeabi-v7a, $(cell "${prefix}-full-en-armeabi-v7a.apk") |
| Not sure, just make it work | universal, $(cell "${prefix}-full-en-universal.apk") |

</details>

EOF
fi

# The no-internet builds, from the `offline` job. Releases before it existed
# carry none of these files, and get no section rather than one saying "not
# built". Up to 0.6.3 the offline build was all languages with every library
# built in (`full-intl-nointernet`, arm64-v8a and universal); from 0.6.4 it is
# English and slim, with an all-languages universal one beside it.
if [ -f "$dist/${prefix}-full-en-nointernet-arm64-v8a.apk" ]; then
cat <<EOF
<details>
<summary><b>Offline</b> (a keyboard that cannot reach the network at all)</summary>

The standard build with \`android.permission.INTERNET\` taken out of the
manifest, so Android refuses every connection the app tries to make. It is as
small as the standard build (about 22 MB for arm64-v8a, where 0.6.3's offline
build was 275 MB) because it leaves out the same things: the
interface is English, and the libraries behind features like offline voice
typing, grammar checking, the scanner, handwriting, on-device translation and
on-device AI are not built in. It cannot download them, so you fetch the ones
you want on another device and import them: interface languages are the
\`lang-\` files below (Android 11 or later), and **Settings › Privacy › Feature
libraries** names each library file and where it is published. Every import is
checked against a checksum built into the app. Typing, prediction, gesture
typing and the clipboard need nothing. The app cannot check for its own updates,
so come back here for new versions
([how importing works](https://wmkeyboard.pages.dev/privacy/offline/)).

**All languages** is the same build with all 48 interface languages built in
(about 100 MB, universal only).
Take it on Android 7 to 10, which cannot load a language pack, or to skip
importing one. Both are signed with the same key as the standard build, so any
of them installs over another without losing a setting.

| | arm64-v8a | universal |
|:---|:---|:---|
| **Offline** | $(cell "${prefix}-full-en-nointernet-arm64-v8a.apk") | $(cell "${prefix}-full-en-nointernet-universal.apk") |
| **Offline, all languages** | | $(cell "${prefix}-full-intl-nointernet-universal.apk") |

</details>

EOF
elif [ -f "$dist/${prefix}-full-intl-nointernet-arm64-v8a.apk" ] \
  || [ -f "$dist/${prefix}-full-intl-nointernet-universal.apk" ]; then
cat <<EOF
<details>
<summary><b>Offline</b> (a keyboard that cannot reach the network at all)</summary>

The standard build with \`android.permission.INTERNET\` taken out of the
manifest, so Android refuses every connection the app tries to make, and with
everything it would otherwise download built in: all 48 interface languages and
every feature library. Anything that downloads fails as it would with the phone
offline, and the app cannot check for its own updates, so come back here for new
versions. Language packs, voice models, OCR data and offline GIF packs fetched
on another device can be imported instead
([how](https://wmkeyboard.pages.dev/privacy/offline/)).

| | arm64-v8a | universal |
|:---|:---|:---|
| **Offline** | $(cell "${prefix}-full-intl-nointernet-arm64-v8a.apk") | $(cell "${prefix}-full-intl-nointernet-universal.apk") |

</details>

EOF
fi

# The English-only F-Droid build (#1290), from the `fdroid` job's arm64 run.
# Only when this release has it: its build is allowed to fail on its own.
fdroid_en="wmkeyboard-${version}-vc$((code * 10 + 2))-fdroid-en.apk"
fdroid_all="wmkeyboard-${version}-vc$((code * 10 + 2))-fdroid.apk"
if [ -f "$dist/$fdroid_en" ]; then
cat <<EOF
<details>
<summary><b>No Google libraries</b> (the F-Droid build, in English only)</summary>

Every download above links Google's ML Kit and LiteRT for handwriting,
on-device translation, language detection and photo cut-outs. The F-Droid build
leaves them out and uses open replacements where there are any. It is built
from source with every interface language, so it is large; the English-only one
is the same build without them. Both are signed with the same key as every
other download here, so any of them installs over another without losing a
setting. Their update check asks F-Droid, so the English-only one gets its
next version from this page, not from the F-Droid app (which would replace it
with the all-languages build).

| | arm64-v8a |
|:---|:---|
| **English only** | $(cell "$fdroid_en") |
| **All languages** | $(cell "$fdroid_all") |

</details>

EOF
fi

# The interface language packs the standard build downloads, for importing on
# a phone that cannot fetch them itself. Listed, not described one by one.
packs=$(cd "$dist" && ls ${prefix}-lang-*.apk 2>/dev/null | wc -l | tr -d ' ')
if [ "$packs" -gt 0 ]; then
# size_of takes a name in $dist, so no `cd` here: with a relative $dist it
# would look in $dist/$dist and call every pack "not built".
# Named for people, not by code: nobody looks for `iw` to find Hebrew. Each
# row is the language in English and in itself, sorted by the English name.
lang_name() { # <resource qualifier>
  case "$1" in
    ar) echo 'Arabic · العربية' ;; bg) echo 'Bulgarian · Български' ;;
    bn) echo 'Bangla · বাংলা' ;; ca) echo 'Catalan · Català' ;;
    cs) echo 'Czech · Čeština' ;; da) echo 'Danish · Dansk' ;;
    de) echo 'German · Deutsch' ;; el) echo 'Greek · Ελληνικά' ;;
    es) echo 'Spanish · Español' ;; et) echo 'Estonian · Eesti' ;;
    fa) echo 'Persian · فارسی' ;; fi) echo 'Finnish · Suomi' ;;
    fr) echo 'French · Français' ;; gu) echo 'Gujarati · ગુજરાતી' ;;
    hi) echo 'Hindi · हिन्दी' ;; hr) echo 'Croatian · Hrvatski' ;;
    hu) echo 'Hungarian · Magyar' ;; in) echo 'Indonesian · Bahasa Indonesia' ;;
    is) echo 'Icelandic · Íslenska' ;; it) echo 'Italian · Italiano' ;;
    iw) echo 'Hebrew · עברית' ;; ja) echo 'Japanese · 日本語' ;;
    kn) echo 'Kannada · ಕನ್ನಡ' ;; ko) echo 'Korean · 한국어' ;;
    lt) echo 'Lithuanian · Lietuvių' ;; lv) echo 'Latvian · Latviešu' ;;
    ml) echo 'Malayalam · മലയാളം' ;; mr) echo 'Marathi · मराठी' ;;
    nl) echo 'Dutch · Nederlands' ;; no) echo 'Norwegian · Norsk' ;;
    pa) echo 'Punjabi · ਪੰਜਾਬੀ' ;; pl) echo 'Polish · Polski' ;;
    pt) echo 'Portuguese · Português' ;; ro) echo 'Romanian · Română' ;;
    ru) echo 'Russian · Русский' ;; sk) echo 'Slovak · Slovenčina' ;;
    sl) echo 'Slovenian · Slovenščina' ;; sv) echo 'Swedish · Svenska' ;;
    sw) echo 'Swahili · Kiswahili' ;; ta) echo 'Tamil · தமிழ்' ;;
    te) echo 'Telugu · తెలుగు' ;; th) echo 'Thai · ไทย' ;;
    tr) echo 'Turkish · Türkçe' ;; uk) echo 'Ukrainian · Українська' ;;
    ur) echo 'Urdu · اردو' ;; vi) echo 'Vietnamese · Tiếng Việt' ;;
    zh-rCN) echo 'Chinese, Simplified · 简体中文' ;; zu) echo 'Zulu · isiZulu' ;;
    *) echo "$1" ;;
  esac
}
pack_list=$(for f in "$dist"/${prefix}-lang-*.apk; do
  f=$(basename "$f"); q=${f#"${prefix}-lang-"}; q=${q%.apk}
  printf '%s\t| %s | [%s](%s/%s) |\n' "$(lang_name "$q")" "$(lang_name "$q")" "$(size_of "$f")" "$base" "$f"
done | LC_ALL=C sort -f | cut -f2-)
pack_list=$(printf '| Language | Download |\n|:---|:---|\n%s' "$pack_list")
cat <<EOF
<details>
<summary><b>App languages, for a phone that can't download them</b> ($packs)</summary>

You don't need these if your phone is online: the app fetches its language
itself. For a phone that can't, like one running the offline build, download
yours on another device, copy it to the phone, and open **Settings › About ›
Storage › Import downloaded files** (Android 11 or later). Each works only with
this exact version.

$pack_list

</details>

EOF
fi

# The extras block lists only what this release actually carries: mappings and
# native symbols were first attached in 0.5.8, so an older release has nothing
# under the checksums but the checksums.
extras=''
row() { # <file> <what it is>
  [ -f "$dist/$1" ] || return 0
  extras="${extras}| [\`$1\`](${base}/$1) | $(size_of "$1") | $2 |
"
}
row SHA256SUMS.txt 'Checksums for everything above'
row "${prefix}-fdroid.apk" 'The F-Droid build, **all languages, no Google libraries**. F-Droid rebuilds it from source and publishes it only if the two match, so it is the same APK F-Droid serves, byte for byte. Installs over an F-Droid install from 0.5.10 on'
# From 0.6.2 one per ABI, named by the full versionCode (release * 10 + ABI
# digit), since that is all F-Droid's `Binaries:` pattern can name them by.
fdroid_row() { # <abi> <digit> <who>
  row "wmkeyboard-${version}-vc$((code * 10 + $2))-fdroid.apk" "The F-Droid build for **$1**, **all languages, no Google libraries**. F-Droid rebuilds it from source and publishes it only if the two match, so it is the same APK F-Droid serves $3, byte for byte. Installs over an F-Droid install from 0.5.10 on"
}
fdroid_row arm64-v8a 2 'to most phones'
fdroid_row armeabi-v7a 1 'to 32-bit phones'
# The English-only variant of the arm64 F-Droid build (#1290), from 0.6.7.
row "wmkeyboard-${version}-vc$((code * 10 + 2))-fdroid-en.apk" 'The F-Droid build for **arm64-v8a** in **English only, no Google libraries**: the F-Droid build without the 48 interface languages. Built beside it from the same source, signed with the same key and carrying the same versionCode, so either installs over the other. F-Droid does not serve this one: its update check looks at F-Droid, and an update through the F-Droid client brings back the all-languages build. Come back here for the next English-only one'
row "${prefix}-full-en-mapping.txt.gz" 'R8 mapping, **standard build**. Retraces a stack trace'
row "${prefix}-full-intl-mapping.txt.gz" 'R8 mapping, **all-languages build**, which the language packs are cut from. Also fits the offline all-languages build, which it is apart from its manifest'
row "${prefix}-full-en-nointernet-mapping.txt.gz" 'R8 mapping, **offline build**. Only attached when it differs from the standard build'"'"'s, which otherwise serves both'
row "${prefix}-full-intl-nointernet-mapping.txt.gz" 'R8 mapping, **offline all-languages build**. Only attached when it differs from the all-languages one, which otherwise serves both'
row "${prefix}-play-mapping.txt.gz" 'R8 mapping, **Play build**. The Play channel flags change what R8 renames, so this is the only one that reads a crash from a Play install'
fdroid_map_row() { # <abi> <digit>
  row "wmkeyboard-${version}-vc$((code * 10 + $2))-fdroid-mapping.txt.gz" "R8 mapping, **F-Droid build for $1**. F-Droid serves that same APK, so this reads a crash from an F-Droid install"
}
fdroid_map_row arm64-v8a 2
fdroid_map_row armeabi-v7a 1
row "wmkeyboard-${version}-vc$((code * 10 + 2))-fdroid-en-mapping.txt.gz" 'R8 mapping, **English-only F-Droid build** (arm64-v8a). Its own R8 run, so the all-languages F-Droid mapping does not fit it'
row "${prefix}-full-intl-native-symbols.zip" 'Native debug symbols, **all-languages build**'
row "${prefix}-full-en-native-symbols.zip" 'Native debug symbols, **standard build**'

if [ -n "$extras" ]; then
  # Name only what is in the block. Mappings and symbols were first attached in
  # 0.5.8, so an older release's block is checksums alone.
  if [ -f "$dist/${prefix}-full-intl-mapping.txt.gz" ]; then
    summary='R8 mappings and native symbols'
    blurb='For reading a crash report from these APKs.'
  else
    summary='Checksums'
    blurb='For verifying what you downloaded.'
  fi
  echo '<details>'
  echo "<summary><b>${summary}</b></summary>"
  echo
  echo "$blurb"
  echo
  echo '| File | Size | What it is |'
  echo '|:---|:---|:---|'
  printf '%s' "$extras"
  echo
  if [ -f "$dist/SHA256SUMS.txt" ]; then
    cat <<'EOF'
Verify a download before installing it:

```sh
sha256sum -c SHA256SUMS.txt --ignore-missing
```

EOF
  fi
  if [ -f "$dist/${prefix}-full-intl-mapping.txt.gz" ]; then
    cat <<EOF
Retrace a crash with the mapping from the same build and version. The
flavour has to match on both axes, because R8 renames the two language
builds independently:

\`\`\`sh
gunzip -k ${prefix}-full-intl-mapping.txt.gz
retrace ${prefix}-full-intl-mapping.txt stacktrace.txt
\`\`\`

A crash from the Play build takes \`${prefix}-play-mapping.txt.gz\` instead:
that build is compiled with different flags, so R8 renames it differently and
none of the four above fit it. A crash from an F-Droid install takes the
\`-fdroid-mapping.txt.gz\` named like the F-Droid APK for its processor.

Or paste the crash into a bug report and let the
[retrace workflow](https://github.com/${repo}/blob/main/.github/workflows/retrace.yml) do all
of this for you — it reads the \`version:\` line, fetches the matching mapping
from this release, and comments the retraced trace on the issue.

EOF
  fi
  echo '</details>'
  echo
fi
fi

# ---------------------------------------------------------------- footer

# The grouped commit list that used to live here is gone: the prose above is
# the changelog, and the compare view is a better place to read every commit
# than a wall of subjects in the release body.
# The release before this one by version, not by ancestry: a re-cut tag sits
# on its own commit off main (0.6.2 did), and `git describe` skipped it and
# counted 0.6.3 from 0.6.1. `prev..tag` is still the right range then.
prev=$(git tag --list 'v*' --sort=-v:refname | awk -v t="$tag" 'f { print; exit } $0 == t { f = 1 }')
if [ -n "$prev" ]; then
  count=$(git log --no-merges --oneline "${prev}..${tag}" | wc -l | tr -d ' ')
  echo "${count} commits since [${prev#v}](https://github.com/${repo}/releases/tag/${prev}). [See every one](https://github.com/${repo}/compare/${prev}...${tag})."
fi
