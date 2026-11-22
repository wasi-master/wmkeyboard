# ONNX Runtime headers

The C and C++ API headers of ONNX Runtime 1.31.1 (MIT, `LICENSE`), the
release `libwmsherpa`, `libwmxlit` and `libwmnikud` are compiled against.
`native/sherpa-onnx-jni/build.sh`, `native/indicxlit-jni/build.sh` and
`native/nikud-jni/build.sh` read them from here unless `ORT_SRC` points at an
ONNX Runtime checkout (or, for sherpa-onnx, `ORT_HEADERS` at another copy).

They are kept in the repository because `github.com/microsoft/onnxruntime`
answered 404 from 2026-11-22, and F-Droid's build server rebuilds these
libraries from source. Headers only: the runtime itself stays Microsoft's
Maven Central build.

They are the `headers/` folder of
`com.microsoft.onnxruntime:onnxruntime-android:1.31.1`, unchanged:

    https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.31.1/onnxruntime-android-1.31.1.aar
    sha256 d4d50660300ed2c4f38ffdfd5ab5bfa3a22f995f282dda24f42ea3ea55467b49

When `onnxruntime` moves in `gradle/libs.versions.toml`, replace every file
here with the new AAR's `headers/` and bump `ORT_TAG` in the three scripts.
