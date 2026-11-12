#!/usr/bin/env bash
# Builds the F-Droid APK the way F-Droid does: `fdroid build` from fdroidserver
# master, inside F-Droid's own buildserver image, from this repo's recipe
# (fdroid/com.wasimaster.wmkeyboard.yml) with one build entry for the commit
# being released and one ABI. The recipe has one entry per ABI from 0.6.2
# (VercodeOperation there), so a release takes three runs of this script.
# The steps follow the `fdroid build` job in fdroiddata's .gitlab-ci.yml,
# which is the job that checks a reproducible build against its reference APK.
#
# Run as root inside registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie
# with this repo mounted at /src:
#
#   fdroid-build.sh <commit> <versionName> <versionCode> <abi> [en]
#
# <versionCode> is the plain number in gradle.properties; the entry gets
# `versionCode * 10 + ABI digit`, as app/build.gradle.kts gives the APK.
# Leaves the unsigned APK at /src/dist/fdroid-unsigned-<abi>.apk, its R8
# mapping at /src/dist/fdroid-mapping-<abi>.txt and the build log at
# /src/dist/fdroid-build-<abi>.log.
#
# With `en`, it then builds the English-only variant of the same F-Droid
# build in the same tree (#1290): no Google libraries and no 48 interface
# languages. Not something F-Droid builds or serves; GitHub publishes it.
# It reuses the native libraries F-Droid's build just compiled from source,
# so it pins the same bytes the intl build uploads to the natives release.
# Leaves /src/dist/fdroid-en-unsigned-<abi>.apk, its mapping at
# /src/dist/fdroid-en-mapping-<abi>.txt and its log at
# /src/dist/fdroid-en-build-<abi>.log. A failure here is reported and left
# behind: the reference APK F-Droid waits on must not be held up by it.
set -euo pipefail

commit=${1:?commit}
version=${2:?versionName}
release=${3:?versionCode}
abi=${4:?abi}
extra=${5:-}
case "$abi" in
    armeabi-v7a) code=$((release * 10 + 1)) ;;
    arm64-v8a)   code=$((release * 10 + 2)) ;;
    x86_64)      code=$((release * 10 + 3)) ;;
    *) echo "unknown ABI $abi" >&2; exit 1 ;;
esac
appid=com.wasimaster.wmkeyboard

export ANDROID_HOME=/opt/android-sdk
test -n "${fdroidserver:-}" || source /etc/profile.d/bsenv.sh
: "${home_vagrant:=/home/vagrant}"

apt-get update -q
apt-get -qy dist-upgrade
sdkmanager "platform-tools" "build-tools;31.0.0"

rm -rf "$fdroidserver"
git clone --quiet --depth 50 https://gitlab.com/fdroid/fdroidserver.git "$fdroidserver"
echo "fdroidserver $(git -C "$fdroidserver" rev-parse HEAD)"
git -C "$home_vagrant/gradlew-fdroid" pull --quiet || true

# The recipe as fdroiddata will carry it, reduced to one entry for this
# commit: the newest entry for this ABI, the one whose gradleprops name it.
# `Binaries:` comes out: the reference APK is what this build is for, and it
# is not published yet. VercodeOperation comes out too, with only one entry
# left for it to describe.
mkdir -p "$home_vagrant/metadata"
python3 - "$commit" "$version" "$code" "$abi" > "$home_vagrant/metadata/$appid.yml" <<'EOF'
import sys, yaml
commit, version, code, abi = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
with open("/src/fdroid/com.wasimaster.wmkeyboard.yml") as f:
    recipe = yaml.safe_load(f)
mine = [b for b in recipe["Builds"] if f"wmkb.abi={abi}" in b.get("gradleprops", [])]
if not mine:
    sys.exit(f"the recipe has no build entry with wmkb.abi={abi}")
build = dict(mine[-1])
build.pop("disable", None)
build.update(versionName=version, versionCode=code, commit=commit)
recipe["Builds"] = [build]
recipe.pop("Binaries", None)
recipe.pop("VercodeOperation", None)
recipe["CurrentVersion"] = version
recipe["CurrentVersionCode"] = code
yaml.safe_dump(recipe, sys.stdout, sort_keys=False, allow_unicode=True)
EOF
cat "$home_vagrant/metadata/$appid.yml"

for d in "$home_vagrant/tmp" "$home_vagrant/unsigned" "$home_vagrant/logs" \
         "$home_vagrant/build" "$home_vagrant/.android" "$home_vagrant/.gradle"; do
    mkdir -p "$d"
done
# An `--on-server` build expects the source already in build/<appid>: on
# F-Droid's buildserver the host clones it and pushes it into the VM. It
# reads SOURCE_DATE_EPOCH from that clone before its own checkout.
git clone --quiet https://github.com/wasi-master/wmkeyboard.git "$home_vagrant/build/$appid"
git -C "$home_vagrant/build/$appid" checkout --quiet "$commit"
chown -R vagrant "$home_vagrant"
apt-get install -qy sudo openjdk-21-jdk-headless
update-alternatives --set java /usr/lib/jvm/java-21-openjdk-amd64/bin/java

mkdir -p /src/dist
cd "$home_vagrant"
set +e
sudo --preserve-env --user vagrant \
    env PATH="$fdroidserver:$PATH" \
    env PYTHONPATH="$fdroidserver:$fdroidserver/examples" \
    env PYTHONUNBUFFERED=true \
    env HOME="$home_vagrant" \
    env -u CI \
    fdroid build --verbose --test --refresh-scanner --on-server --no-tarball "$appid:$code" \
    2>&1 | tee /src/dist/fdroid-build-$abi.log
status=${PIPESTATUS[0]}
set -e
apt-get install -qy sudo >/dev/null
[ "$status" -eq 0 ] || { echo "fdroid build failed ($status)"; exit "$status"; }

apk="$home_vagrant/tmp/${appid}_${code}.apk"
[ -f "$apk" ] || { echo "fdroid build left no $apk"; ls -la "$home_vagrant/tmp"; exit 1; }
cp "$apk" "/src/dist/fdroid-unsigned-$abi.apk"
# R8's mapping for it. F-Droid's own build of this commit is this build byte
# for byte (the reference APK only carries its signature over if it is), so
# this is also the mapping for a crash from an F-Droid install, which the
# retrace workflow could not read before (#963).
maps=("$home_vagrant/build/$appid"/app/build/outputs/mapping/*/mapping.txt)
if [ "${#maps[@]}" -ne 1 ] || [ ! -f "${maps[0]}" ]; then
    echo "expected one R8 mapping, found: ${maps[*]}"
    exit 1
fi
cp "${maps[0]}" "/src/dist/fdroid-mapping-$abi.txt"
# The libraries F-Droid's build compiled from source and left out of the APK
# (native delivery), for the data repository's natives release: an F-Droid
# install that opts in to library downloads fetches these exact bytes.
(cd "$home_vagrant/build/$appid" && bash /src/.github/scripts/collect-natives.sh "$apk" "/src/dist/fdroid-natives-$abi")
chmod -R a+r /src/dist

[ "$extra" = en ] || exit 0

# The English-only variant. The tree is as `fdroid build` left it: the
# recipe's prebuild seds applied (no Google dependency lines), the scanner's
# deletions made, and the native libraries its `build:` step compiled from
# source in place. So this is the recipe's own Gradle call with `en` for
# `intl`: the same gradleprops, the same Gradle (fdroidserver's `gradle`,
# gradlew-fdroid on the buildserver), the same NDK.
props=$(python3 - "$home_vagrant/metadata/$appid.yml" <<'EOF'
import sys, yaml
with open(sys.argv[1]) as f:
    build = yaml.safe_load(f)["Builds"][0]
print(" ".join("-P" + p for p in build.get("gradleprops", [])))
print(build.get("ndk", ""))
print(build.get("subdir", ""))
EOF
)
gradleprops=$(sed -n 1p <<<"$props")
ndk=$(sed -n 2p <<<"$props")
subdir=$(sed -n 3p <<<"$props")
# fdroidserver's default `gradle` is the gradlew-fdroid in its own checkout;
# older buildservers keep it in its own repo beside the home directory.
gradle=""
for g in "$fdroidserver/gradlew-fdroid" "$home_vagrant/gradlew-fdroid/gradlew-fdroid"; do
    [ -x "$g" ] && { gradle=$g; break; }
done
[ -n "$gradle" ] || gradle=$(command -v gradle || true)
if [ -z "$gradle" ]; then
    echo "::warning::No Gradle for the English-only F-Droid build; this release goes out without it"
    exit 0
fi
ndk_home=""
[ -n "$ndk" ] && [ -d "$ANDROID_HOME/ndk/$ndk" ] && ndk_home="$ANDROID_HOME/ndk/$ndk"
echo "English-only F-Droid build: $gradle $gradleprops assembleEnRelease (NDK ${ndk_home:-default})"

set +e
(
    cd "$home_vagrant/build/$appid/$subdir"
    # shellcheck disable=SC2086 # gradleprops is a list of -P flags
    sudo --preserve-env --user vagrant \
        env HOME="$home_vagrant" \
        env ANDROID_HOME="$ANDROID_HOME" ANDROID_SDK_ROOT="$ANDROID_HOME" \
        ${ndk_home:+env ANDROID_NDK_HOME="$ndk_home" ANDROID_NDK="$ndk_home"} \
        env -u CI \
        "$gradle" --no-daemon $gradleprops assembleEnRelease
) 2>&1 | tee "/src/dist/fdroid-en-build-$abi.log"
status=${PIPESTATUS[0]}
set -e
if [ "$status" -ne 0 ]; then
    echo "::warning::The English-only F-Droid build failed ($status); this release goes out without it"
    chmod -R a+r /src/dist
    exit 0
fi

out="$home_vagrant/build/$appid/${subdir:-.}/build/outputs"
en_apks=("$out"/apk/en/release/*.apk)
if [ "${#en_apks[@]}" -ne 1 ] || [ ! -f "${en_apks[0]}" ]; then
    echo "::warning::Expected one English-only APK, found: ${en_apks[*]}"
    chmod -R a+r /src/dist
    exit 0
fi
cp "${en_apks[0]}" "/src/dist/fdroid-en-unsigned-$abi.apk"
cp "$out/mapping/enRelease/mapping.txt" "/src/dist/fdroid-en-mapping-$abi.txt" \
    || echo "::warning::No R8 mapping for the English-only build"
chmod -R a+r /src/dist
