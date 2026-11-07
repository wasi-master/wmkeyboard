#!/usr/bin/env bash
# Builds libwm_python_jni for this machine, for the JVM unit tests.
#
#   native/wm-python-jni/build-host.sh
#
# On a device PythonNative loads the .so the `plugins` gate delivered; in a
# JVM test it is the host build this script makes, found under
# native/wm-python-jni/target/release by walking up from the test's working
# directory to settings.gradle.kts. The Gradle test tasks of the modules that
# run plugins run this first.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE"
cargo build --release --locked
case "$(uname -s)" in
    Darwin) f=target/release/libwm_python_jni.dylib ;;
    Linux) f=target/release/libwm_python_jni.so ;;
    *) f=target/release/wm_python_jni.dll ;;
esac
echo "$f: $(wc -c < "$f" | tr -d ' ') bytes"
