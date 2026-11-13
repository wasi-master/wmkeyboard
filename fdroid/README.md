# F-Droid build recipe

`com.wasimaster.wmkeyboard.yml` is the recipe F-Droid builds the app from. It is
**not** read by F-Droid from this repository: it is copied to
`metadata/com.wasimaster.wmkeyboard.yml` inside a fork of
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) and submitted as a merge
request. The copy lives here so the recipe is versioned next to the code that
has to keep satisfying it. The walk-through is in
[docs/development/releasing](../docs/src/content/docs/development/releasing.mdx).

The file itself carries no comments, and it cannot. fdroiddata's CI runs
`fdroid rewritemeta` on every changed metadata file and fails the pipeline if
the result differs by a byte; `rewritemeta` strips comments and reorders fields.
So the recipe is kept in exactly the form `rewritemeta` emits, and everything
that would have been a comment is written down here instead.

## Why each field is what it is

**`gradle: [lite]`** — the old `lite` flavour, which no longer exists. Up to
0.6.2 `:app` had a `capabilities` dimension (`full`, `lite`) and F-Droid built
`lite`. From 0.6.3 F-Droid builds the standard app with `wmkb.enableFdroid=true`
(see `gradleprops`), which leaves every proprietary dependency (ML Kit, LiteRT,
LiteRT-LM, the Google Home APIs) off the compile classpath and swaps in the
`src/nogoogle` stand-ins for the classes that call ML Kit directly. Play in-app
updates and `play-services-auth` sit behind the `enablePlayStore`/`enableGms`
flags as before.

fdroidserver turns the list into `assembleLiteRelease`, which AGP does not
register, so `app/build.gradle.kts` registers it as an alias: it builds
`assembleIntlRelease`, the build with all 48 interface languages, and mirrors
the APK to `app/build/outputs/apk/lite/release/`, the only flavoured directory
fdroidserver will look in for a recipe that says `lite`. The alias stays until
a merge request to fdroiddata switches the list to `intl` (which builds
`assembleIntlRelease` and looks in `apk/intl/release/` with no help), because
`AutoUpdateMode` copies the previous entry as it stands.

**`gradleprops`** — the first two default to `false` when `local.properties` is
absent, which it is in a clean checkout; they are stated anyway so the recipe
does not depend on that. `wmkb.enableFdroid=true` is not a default and has to be
set here. It is what tells the built app it is an F-Droid install, which decides
the channel line on bug reports and diagnostics, and which suppresses the "get it
on F-Droid" row in About that would otherwise point an F-Droid user at their own
install.

**`Binaries` and `AllowedAPKSigningKeys`** — a reproducible build, from 0.5.10.
After building a version, F-Droid downloads
`wmkeyboard-<version>-vc<code>-fdroid.apk` from that version's GitHub release,
compares it with its own unsigned build, and if the two are identical apart from
the signature, copies our signature onto its build and publishes that. So an
F-Droid install carries the same key as the GitHub APKs. A mismatch fails the
version on F-Droid instead of shipping it. `AllowedAPKSigningKeys` is the SHA-256
of the release certificate (`apksigner verify --print-certs`, "certificate
SHA-256 digest"), and the reference APK has to carry a v2 signature or better.

The reference APKs are built by the `fdroid` job in `.github/workflows/release.yml`, one matrix run per ABI,
and since 0.6.0 they are not built with Gradle there at all. The job runs
fdroidserver's own `fdroid build`, from fdroidserver master, inside F-Droid's
buildserver image (`registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie`),
from this recipe with one entry for the commit being released
(`.github/scripts/fdroid-build.sh`, which follows the `fdroid build` job in
fdroiddata's CI). It then signs the unsigned APK that leaves with
`apksigner sign --alignment-preserved` and checks the signature carries back
with `apksigcopier compare`, the tool F-Droid uses. Building it any other way
stopped being possible once the recipe compiled native code: the APK has to be
the recipe's output, and the recipe's output is now partly made by the NDK.
Running their scanner over every tag is a side benefit.

Before that, the job copied the recipe by hand (`assembleLiteRelease` from
`app/` with the three flags), and 0.5.9 was rebuilt that way and compared with
F-Droid's APK: identical apart from the signature, built on macOS with the
JetBrains Runtime 21 against F-Droid's Debian OpenJDK 21. The first try did
not match, and the one difference is worth knowing. AGP writes the commit
into `META-INF/version-control-info.textproto`, and in a git worktree (where
`.git` is a file) it cannot read it and writes `NO_VALID_GIT_FOUND`. Build a
reference APK from a real clone.

The reference APK must also carry no "Dependency metadata" signing block, the
Google-encrypted dependency list AGP adds by default. fdroiddata's `check apk`
job fails on it even when the comparison passes, since it sits in the
signature and not in the files compared. `dependenciesInfo { includeInApk =
false }` in `app/build.gradle.kts` keeps it out from 0.5.11; 0.5.10's APK was
re-signed with `apksigner sign --alignment-preserved` to remove it.

Installs from F-Droid up to 0.5.9 carry F-Droid's key, and Android refuses an
update signed with a different one, so those users reinstall once. Dual signing
(signature files under `metadata/<appid>/signatures/<vc>/`, F-Droid publishing
both) would have spared them, and was turned down: it takes an fdroiddata merge
request for every release, because the bot cannot write signature files.

`Binaries` is wrapped onto a second line by `rewritemeta`, with a trailing space
after the colon. That is its output; leave it.

**`scandelete`, `build`, `ndk` and `sudo`**: the native libraries, from 0.6.0
built from source. `scandelete` removes every prebuilt `.so` and `.a` in the
tree before the source scan. Some are ones this build cannot make from source
and goes without: the Tesseract and Whistle libraries (under
`core/intelligence/src/full/` and `core/voice/src/full/` up to 0.6.2, and from
0.6.3 under `core/intelligence/src/tesseract/` and `core/voice/src/whistle/`),
the Cactus archive
Whistle links (`native/whistle-jni/prebuilt`, which has no source to build
from), and the LLM module's ABI placeholders. Whistle can still reach an F-Droid
install: `fdroidPin` in `native/gates.json` pins the GitHub build's
`libwmwhistle.so` under a `whistle` gate of its own, which the app downloads
from the data repository's `natives` release only after the user turns on
feature library downloads (the same opt-in as everything fetched rather than
built), or takes as an import. Nothing prebuilt enters the source tree F-Droid
builds. The other directories,
`src/main/jniLibs` of `:core:intelligence`, `:core:voice` and
`:core:language`, hold what this build itself ships since 0.6.0: Harper's grammar
engine, whisper.cpp for Offline Whisper, the selective ExecuTorch build behind
Offline Asr4All, Parakeet and Moonshine, and, from the release after 0.6.0,
the text engines (Avro, Khipro, Bijoy, Hindi, Urdu and the Indic languages),
which moved from Kotlin into the Rust crate `native/wm-engine`.

From the release after 0.6.2 the ExecuTorch library lives apart, in
`core/voice/src/executorch/jniLibs` (its own delivery gate, shared with the
FUTO Swipe glide engine), and the next entry's `scandelete` needs that path
added.

Up to 0.5.14 this build carried none of them, so deleting was the whole
story. Now `build` puts them back: `../native/build-lite-libs.sh` runs after
the scan and
before Gradle, and compiles all of them from source into the directories the
scan emptied (`native/wm-engine-jni` and `native/harper-jni` with Rust and
`cargo ndk`, `native/whispercpp-jni` and `native/executorch-jni` with CMake). whisper.cpp,
ExecuTorch and the PyTorch sources ExecuTorch's code generator and optimized
kernels need are fetched at pinned commits, the Rust toolchain at a pinned
version through rustup, the crates through `Cargo.lock`. `ndk` names the NDK
the scripts compile with, and `sudo` installs CMake, Ninja, make and a host compiler
for ExecuTorch's flatbuffers tools, and the two Python modules the code
generator imports.

From the release after 0.6.2, two more come from the same script:
`native/rnnoise-jni` (RNNoise, plain C, into `core/voice/src/main/jniLibs`)
and `native/sherpa-onnx-jni` (sherpa-onnx, CMake, into
`core/voice/src/onnx/jniLibs`), and with them `native/indicxlit-jni`
(IndicXlit's search for the phonetic layouts' neural suggestions, two C++
files, plain clang, into the same directory). The next entry's `scandelete`
needs `core/voice/src/onnx/jniLibs` added. sherpa-onnx's CMake fetches its own
dependencies (kaldi-native-fbank, kaldi-decoder, simple-sentencepiece,
OpenFst and others) at the versions its pinned tag names. Its bridge links
against a stub of ONNX Runtime compiled from `ort_stub.c` and the runtime's
headers, cloned at a pinned tag; no ONNX Runtime binary is in the tree or the
build. The runtime itself is Microsoft's MIT-licensed AAR on Maven Central,
read on the phone after the download opt-in, like every other delivered
library.

From the release after 0.6.2 `native/whispercpp-jni/build.sh` also makes
`libwmwhispercpp_opencl.so` (arm64 only), whisper.cpp with ggml's OpenCL
backend for Adreno GPUs, into the same `core/voice/src/main/jniLibs`. It
clones Khronos's OpenCL-Headers and OpenCL-ICD-Loader at pinned commits
(Apache-2.0), builds the loader only to link against, and needs Python 3 to
embed the OpenCL kernels as strings. Nothing from Khronos ships; on the
phone the library uses the vendor's `libOpenCL.so`.

`native/opus-jni` (libopus at a pinned tag, CMake and Ninja, plus a small
hand-written Ogg writer) builds `libwmopus.so` into `core/voice/src/opus/jniLibs`,
the compressor for transcription server uploads. The next entry's `scandelete`
needs that directory too (`python3 native/gates.py scandelete` lists it).

The Mozc engine for Japanese adds `native/mozc-jni` (Mozc's converter, into
`core/input/src/mozc/jniLibs`), and with it the first library that builds
with Bazel rather than CMake or Cargo: Mozc supports no other build. F-Droid
does not take a prebuilt Bazel, which is what the script first fetched
(bazelisk from its GitHub release, Bazel 9.0.2 through it; [#964](https://github.com/wasi-master/wmkeyboard/issues/964)).
Debian has no usable one either: trixie's `bazel-bootstrap` is 4.2.3 and
Mozc wants 9. So on Linux the script builds Bazel 9.0.2 from source first,
the way Debian builds its own package: it fetches Bazel's distribution
archive (`bazel-9.0.2-dist.zip`, the one archive Bazel publishes for
bootstrapping, checked against the SHA-256 releases.bazel.build publishes),
and runs its `compile.sh`, which compiles a first Bazel with `javac` and the
real one with that. The archive carries every dependency of that build, so
nothing is fetched. The Bazel that comes out embeds no JDK and runs on the
machine's (`--server_javabase`), and Mozc's build is told to use that JDK
and the machine's `python3` for its code generators
(`--tool_java_runtime_version=local_jdk`,
`--extra_toolchains=@rules_python//python/runtime_env_toolchains:all`), so
no prebuilt toolchain is downloaded there either. Bazel still fetches Mozc's
module dependencies (Abseil, Protocol Buffers, the rules) from the Bazel
Central Registry as source archives, each checked against the registry's
hash, as it fetches Mozc itself. The bootstrap is the slow part, about
twenty minutes on a four-core machine, and the buildserver keeps nothing
between builds, so every entry pays it. fdroiddata's own CI is different: its
`fdroid build` job builds every new entry of a merge request one after the
other on one machine, under GitLab.com's three-hour cap on hosted runners
(a project timeout above that is ignored), and three entries at over an
hour each did not fit. So the script keeps the bootstrapped binary under
`~/.cache/wmkeyboard` and the second entry finds it there. The
0.6.3 merge request predates that and its pipeline shows the cap: entries
381 and 382 built, 383 was cut off.
The next entry's `scandelete` needs `core/input/src/mozc/jniLibs` added,
beside `core/input/src/main/jniLibs` (librime). The dictionary Mozc converts
with is not in the APK or the library: it is a CJK pack download, like the
built-in engine's.

From the release after 0.6.2 the script also builds `native/llamacpp-jni`
(llama.cpp at a pinned release tag, CMake, with its libmtmd for vision
models) into `core/intelligence/src/llama/jniLibs`, the runtime for GGUF
models; the next entry's `scandelete` needs that path added. Nothing is
fetched at configure time: no KleidiAI, no OpenSSL, no web UI. On arm64 it
adds `libwmllama_opencl.so` in `core/intelligence/src/llamagpu/jniLibs` (a
second path for `scandelete`), llama.cpp's OpenCL backend for Adreno GPUs: the
script clones Khronos's OpenCL-Headers and OpenCL-ICD-Loader at pinned tags
(Apache-2.0), builds the loader only to link against, and ships neither; the
phone's own `libOpenCL.so` is what the library opens. The OpenCL kernels are
embedded as source text by a Python script from llama.cpp, so the build
needs `python3`, which the recipe's image already has.

The NLLB translation engine adds `native/ctranslate2-jni` (OpenNMT's
CTranslate2 and Google's SentencePiece at pinned tags, CMake, CPU only with
ruy for every matrix product, no BLAS, no OpenMP) into
`core/intelligence/src/ct2/jniLibs`; the next entry's `scandelete` needs that
path added. The script clones abseil at the tag SentencePiece's own CMake
would fetch, so configuring touches no network, and makes one edit to
CTranslate2's `thread_pool.cc` (bionic has no `pthread_setaffinity_np`).
armeabi-v7a gets a stub that only refuses to load. The NLLB-200 model itself
(CC BY-NC 4.0) is not in the APK or the library: the phone fetches it from
Hugging Face after the user accepts that licence, like FUTO Swipe's weights.

The same release adds `native/zinnia-jni` (zinnia, Taku Kudo's BSD-licensed
handwriting recogniser, at a pinned commit, four C++ files and CMake) into
`core/intelligence/src/zinnia/jniLibs`. It is what reads Chinese and
Japanese handwriting in this build, which has no ML Kit; the next entry's
`scandelete` needs that path added, and the copy of the recipe here already
has it. The models it reads (the Tegaki project's, LGPL 2.1) are not in the
APK or the library: they are CJK pack downloads from the data repository.

From the release after 0.6.2 the script also builds `native/divvunspell-jni`
(Divvun's divvunspell, which checks words against GiellaLT's speller archives)
into `core/prediction/src/main/jniLibs`, beside Hunspell's library, so the
path that entry already scandeletes covers it. It is a Rust crate like Harper,
with one difference: divvunspell and its archive reader (box-format, and
box-format's fastvlq) are git dependencies, fetched by Cargo from GitHub at
the commits `Cargo.lock` pins, because crates.io's last divvunspell release is
from 2023. The spellers themselves are never in the APK or the library: the
phone downloads them from GiellaLT's own releases, or the user imports them.

From the release after 0.6.2 the script also builds `native/docscan-jni`
(OpenCV 5.0.0 at a pinned commit, only its core, imgproc, flann and geometry modules, static)
into `core/intelligence/src/docscan/jniLibs`: the built-in document scanner,
which F-Droid's build uses in place of Google's. The next entry's
`scandelete` needs that path added. OpenCV's configure step downloads nothing
with the options in `native/docscan-jni/opencv.cmake`: IPP, KleidiCV, FastCV
and the other prebuilt accelerators are off.

From the release after 0.6.2 the script also builds `native/zxingcpp-jni`
(zxing-cpp v3.1.1, readers only, static, Apache-2.0) into
`core/intelligence/src/zxing/jniLibs`: the QR and barcode reader, which
brings the QR scanner back to F-Droid's build in place of ML Kit's. The next
entry's `scandelete` needs that path added. Its configure step downloads
nothing: the writers, and with them the bundled libzint, are off.

The script also builds `native/imagegen-jni` (MNN 3.6.1 at a pinned commit,
CPU backend only, static) into `core/intelligence/src/imagegen/jniLibs`: the
sticker generator's runtime. The next entry's `scandelete` needs that path
and `core/intelligence/src/imagegennpu/jniLibs` added; the second holds the
NPU runner, which is compiled against Qualcomm's QNN headers and never part of
F-Droid's build, so it is only deleted. MNN's configure step downloads
nothing: KleidiAI, the one option that fetches sources, is off.

From the release after 0.6.2 the script also builds `native/voikko-jni`
(libvoikko 4.3.3 at a pinned commit, its VFST backend only, with a small
JNI bridge, CMake) into `native/voikko-jni/jniLibs`: Voikko, which checks
Finnish. That directory is not part of any module, so the APK never
carries the library; it is pinned like the others and fetched behind the
same opt-in. The next entry's `scandelete` needs that path added. The
Finnish dictionary (voikko-fi, GPL 2+) is not in the APK or the library
either: the phone downloads it from the data repository, where it sits
beside the source tarball it is built from.

The reproducible build is what makes this delicate. The reference APK carries
these libraries too, so F-Droid's copies must come out byte for byte the same
as the release workflow's. Each script maps every path of the build machine
out of what it compiles (`-ffile-prefix-map` for C and C++,
`--remap-path-prefix` for Rust; the committed 0.5.x libraries still carried
`/Users/...` paths in Harper's panic messages), and every tool is pinned. Do
not let a script pick up a version from the machine.

**`sdkmanager 'ndk;28.2.13676358'`** in `prebuild` (from 0.6.0, a packager's
change on the merge request): the NDK AGP 9 asks for by default, installed
beside the r29 the native scripts use. Gradle's own steps (stripping the
libraries it packages) then run with the NDK they expect. The release
workflow builds from this file, so its reference APK gets the same.

**`sdkmanager 'platforms;android-36' 'build-tools;36.0.0'`** in `prebuild`
(from 0.6.3): Mozc's Bazel build (`native/mozc-jni/build.sh`) goes through
rules_android, which refuses to start without an SDK platform and build tools
35.0.0 or newer. The buildserver image has no platform and only build tools
31.0.0 until Gradle fetches its own, which is after the `build:` step, so the
recipe installs both first. Any platform will do; 36.0.0 is the build tools
Gradle installs anyway.

**`xz-utils`** in `sudo` (from 0.6.3): librime's script unpacks Boost's
`.tar.xz`, and the image has no `xz`.

**`openjdk-21-jdk-headless` and `zip`** in `sudo` (from 0.6.3's re-cut):
what Bazel's bootstrap needs beyond the image. Bazel 9 is built with a JDK
21, and the image's `JAVA_HOME` is 17, so the script looks for Debian's
`java-21-openjdk` itself and leaves the Gradle build's JDK alone; `compile.sh`
also wants `zip` beside the `unzip` the image has.

**Two entries per version, and `VercodeOperation`** (from 0.6.2): one
APK per ABI ([#801](https://github.com/wasi-master/wmkeyboard/issues/801)).
Up to 0.6.1 the one APK carried the native libraries of every ABI to every
phone, about 16 MB more than an arm64 phone uses. Each entry sets
`wmkb.abi`, which makes Gradle package that ABI's libraries alone and stamp
its versionCode (`wmkb.versionCode * 10 + digit`: 1 armeabi-v7a, 2
arm64-v8a; `abiDigits` in `app/build.gradle.kts`), and `WMKB_ABIS` in
`build`, which makes `native/build-fdroid-libs.sh` compile that ABI alone,
so ExecuTorch builds once per entry instead of once per ABI. The F-Droid
client installs the highest code a phone can run, which is why arm64-v8a
sits above armeabi-v7a.

x86_64 is no longer built. 0.6.2 and 0.6.3 had a third entry for it (digit
3); those entries are gone from the recipe, and digit 3 is not reused.

`VercodeOperation` turns the one versionCode in `gradle.properties` into the
two. Their bot (`checkupdates.py`) copies the last *two* `Builds:` entries
and gives them the two codes in ascending order, so the entries have to
stay last and in that order: armeabi-v7a, arm64-v8a. `CurrentVersionCode`
is the higher of them, arm64-v8a's.

`Binaries:` is unchanged, and that is why the reference APKs are named by
the full code (`-vc352-fdroid.apk`) while every other release asset keeps
the plain one: `%c` is the entry's versionCode and the pattern has nothing
else to tell the ABIs apart by. The in-app update check reads F-Droid's
index in the same terms and divides by ten (`FdroidIndex.releaseCode`),
because the index's suggested build is arm64-v8a's whatever the phone is.

Store changelogs follow the codes: `changelogs/351.txt` and `352.txt` are
copies of `35.txt`, made by `scripts/changelog-abi-copies.sh`.

**`timeout`**: five hours. fdroidserver stops a build after two by default,
and in 0.6.0 ExecuTorch was three full builds, one per ABI, before Gradle
started. From 0.6.2 each entry builds one ABI and needs far less, but the
limit stays until a build log shows how much less.

**`commit: v0.6.0`** in the staged copy: a tag, which fdroiddata will not
take. The copy is written before the tag exists, so it cannot hold the hash;
the merge request that carries the entry to fdroiddata replaces it with the
tag's full hash, and the bot does the same for every later entry.

**`prebuild`** — two `sed` commands, both for the source scanner. It is a text
search over the build files, run before the build and fatal on a match, and it
does not read build logic, so it flags strings this build never resolves:

* `org.gradle.toolchains.foojay-resolver-convention` in `settings.gradle.kts`,
  which would fetch a JDK over the network. Their tooling deletes the
  `gradle-daemon-jvm.properties` the plugin maintains, and auto-provisioning is
  switched off on their builders regardless, so the plugin can do nothing there
  but fail the scan. **Consequence worth keeping in mind: with foojay gone, any
  `jvmToolchain(n)` in the tree is fatal unless `n` is a JDK their image already
  carries.** `:tools:dictc` learned this the hard way in 0.5.4; it sets a
  jvmTarget instead now, which needs no particular JDK installed.
* the `googleHomeM2` repository in `settings.gradle.kts` (from 0.6.2), a
  local Maven folder for the Google Home APIs SDK. The SDK is not in the
  repository (`feature/ime/libs/googlehome` holds only a README and a
  `.gitignore`), so the `if` around the block is always false on their
  builder, but the scanner reads `maven { url = ... }` and fails on an
  unknown repository. The `sed` deletes from the `val` line to the `if`'s
  closing brace, the first line that is eight spaces and a `}`. The same
  path came out of `scandelete`, where fdroidserver fails on a path with
  nothing to delete.
* `libs.play.app.update`, `libs.play.feature.delivery` and
  `libs.play.services.auth` in `app/build.gradle.kts`. All three sit inside
  `if (playStoreChannel)` / `if (gmsChannel)` blocks, which are false for this
  recipe, so they are never on the compile classpath. The blocks are left empty
  by the `sed`, which is valid Kotlin.
* every ML Kit and Google services dependency (from 0.6.3). Up to 0.6.2 they
  were `fullImplementation(...)`, which the scanner skips for a recipe that
  builds `lite`. With the flavour gone they are plain `implementation(...)`
  inside `if (googleSdks)`, `if (playStoreChannel)` and similar blocks, all
  false under `wmkb.enableFdroid=true`, and the scanner flags each one. One
  `sed` deletes every line naming `libs.mlkit.` or a `com.google.android.gms:`
  coordinate in `:feature:ime`, `:core:intelligence` and the three Play-only
  modules (`:feature:translate`, `:feature:handwriting`, `:feature:googlehome`,
  which this build does not include but the scanner reads anyway). The other
  deletes `libs.mlkit.dynamic.feature.support` in `app/build.gradle.kts`,
  which carries an `exclude` block, from its line to the block's closing
  brace, the first line that is eight spaces and a `}`. Comments and the
  Google Home folder path still mention both names; the scanner reads only
  dependency declarations, so they pass.

The same release replaced `scandelete`: the `src/full` paths went with the
flavour, and fdroidserver fails on a listed path that does not exist, so the
list is now `feature/llm/src/main/jniLibs`, `native/whistle-jni/prebuilt`,
`feature/llamagpu/src/main/jniLibs` (Play-only stubs, see its `stub/stub.c`) and
everything `python3 native/gates.py scandelete` prints.

Deleting the lines rather than listing the files in `scanignore` is deliberate:
`scanignore` asks a packager to take the build file on trust, and this way the
scanner reads a tree that genuinely does not mention them. `prebuild` runs before
the scanner (`prepare_source` precedes `scan_source` in fdroidserver's
`build.py`), with the working directory set to `subdir`, which is why the first
command reaches up with `../`.

The 0.5.4 entry carried two more, both since fixed in the source: one deleted the
`.takeIf` line their signing strip orphaned, and one deleted `jvmToolchain(17)`
from `:tools:dictc`. Neither is needed from 0.5.5 on. They are in this file's git
history if a build of an older tag ever has to be reproduced.

**One `Builds:` entry** — F-Droid's buildserver builds every entry it has not
seen. Shipping the back catalogue in a first submission would spend their build
time on versions nobody can install any more. Entries for 0.3.0 through 0.5.3 are
in this file's git history.

**`commit:` is a full hash, never a tag** — a packager's first review comment
(linsui, on !48225). A tag can be moved after the build is accepted; a hash
cannot. You only write it by hand for the first entry: every later one is added
by their bot, which resolves the tag it found to a hash itself (`vcs.getref` in
fdroidserver's `checkupdates.py`).

**`Categories: Keyboard & IME`** — the same review. The valid names live in
fdroiddata's `config/categories.yml`; `Writing` there means word processors and
journaling, and a keyboard has a category of its own.

**`UpdateCheckMode: Tags` with a file in `UpdateCheckData`** — also suggested in
that review, and it corrects something this file used to claim. Tags mode *on its
own* scans the Gradle build file for a version literal, which `:app` does not
have — both values come from `gradle.properties` through
`providers.gradleProperty()`. But `UpdateCheckData` switches Tags mode to reading
a file instead: `gradle.properties|wmkb.versionCode=(\d+)|.|wmkb.versionName=(.+)`
is resolved **inside the repo as checked out at each tag**, and the `.` in the
third field means "that same file again". Verified by running `fdroid
checkupdates` against the real repo, which walked `v0.5.6` down to `v0.5.2` and
found the right version at each. The earlier recipe used HTTP mode against
`main` instead; Tags is better because it reads the version a tag actually
carries rather than whatever `main` says today.

**`AutoUpdateMode: Version`** — no `v%v` pattern needed, because in Tags mode the
bot already knows which tag it matched and uses that. Every release uses the same
build configuration, so the bot adds the next `Builds:` entry by copying this
one. Verify a release still satisfies the recipe before tagging (`fdroid
checkupdates` is what the bot runs).

**No `Name`, `Summary`, `Description`, icon or screenshots** — F-Droid reads
those from `fastlane/metadata/android/en-US/` in this repo, so they only have to
be right in one place. `AutoName` is written by `fdroid checkupdates`; leaving it
out makes fdroiddata's CI add it and fail on the resulting diff.

## Checking a change before submitting it

fdroiddata's CI runs four jobs against a changed metadata file, and all four can
be run locally. Two things have to be right first.

**Use fdroidserver from git master, and pin `ruamel.yaml` to 0.18.6.** Both
matter, and each cost a failed pipeline to find. Their CI installs Debian's
`fdroidserver` package first and then overlays master's source on `PATH`, so the
*code* is master but the *YAML library* is Debian trixie's. Homebrew's 2.4.5
wrote a `\` line continuation into the `AntiFeatures` string that master does
not; and ruamel 0.19 wraps at a different column than 0.18, which rewrites every
wrapped line. Either one is enough to fail the `rewritemeta` job, whose test is a
byte-for-byte diff. With master plus 0.18.6 the local output matches CI's
artifact exactly.

**`fdroid lint` needs fdroiddata's own `config/` directory** — the valid category
and anti-feature name lists live there, and without them lint rejects both. A
plain `git clone` of fdroiddata fails on some networks; the archive does not.

```sh
python3 -m venv /tmp/fdsvenv
/tmp/fdsvenv/bin/pip install git+https://gitlab.com/fdroid/fdroidserver.git check-jsonschema
/tmp/fdsvenv/bin/pip install 'ruamel.yaml==0.18.6'   # pin AFTER, see below
mkdir -p /tmp/fd && cd /tmp/fd
curl -sS --http1.1 -o config.tar.gz \
  'https://gitlab.com/fdroid/fdroiddata/-/archive/master/fdroiddata-master.tar.gz?path=config'
tar xzf config.tar.gz && mv fdroiddata-master-config/config .
curl -sS --http1.1 -o metadata.json \
  'https://gitlab.com/fdroid/fdroiddata/-/raw/master/schemas/metadata.json'
mkdir -p metadata && cp ~/Work/WMKeyboard/fdroid/com.wasimaster.wmkeyboard.yml metadata/
export PATH=/tmp/fdsvenv/bin:$PATH
fdroid lint com.wasimaster.wmkeyboard        # must exit 0 and print nothing
fdroid rewritemeta com.wasimaster.wmkeyboard # must leave the file unchanged
fdroid checkupdates --auto com.wasimaster.wmkeyboard  # must leave the file unchanged
check-jsonschema --schemafile metadata.json metadata/com.wasimaster.wmkeyboard.yml
```

The fifth job, `fdroid build`, runs the real build in an image provisioned like
the production buildserver. The release workflow's `fdroid` job runs that same
build on every tag, in the same image, so a dry run of the workflow
(`gh workflow run release.yml -f dry_run=true`) is the local stand-in for it.

When `rewritemeta` does disagree with you, its job uploads what it wanted under
`tmp/` in the job artifacts, reachable at
`.../-/jobs/<job id>/artifacts/raw/tmp/com.wasimaster.wmkeyboard.yml`. Diffing
against that is faster than guessing.

## Why there is no `AntiFeatures` block

`NonFreeNet` was declared until 0.5.6, for the tools that reached proprietary
services. It is gone because every one of those tools now has a free,
self-hostable option the user can point at, which is the condition F-Droid's own
rule waives the anti-feature on: translate takes a LibreTranslate instance,
search a SearXNG one, photos and GIFs any MediaWiki, and the AI tool has always
taken an Ollama URL. Backups already offered WebDAV beside Dropbox.

Exchange rates needed no instance to point at, because two of their sources are
free end to end: currency-api (`fawazahmed0/exchange-api`) publishes CC0 tables
built by a public script, for fiat and coins alike, and Frankfurter is MIT and
serves European Central Bank rates. This file used to say coins had no free
source; that was wrong, currency-api always carried them. From 0.5.7 the
F-Droid build starts from those two (`CurrencyClient.Provider.fiatDefaults`,
`cryptoDefaults`), and a currency or weather chip on the suggestion strip waits
for a tap before it fetches anything (`RateSourceSettings.autoFetch` and
`WeatherSettings.autoFetch`, both off on F-Droid).
ExchangeRate-API, Coinbase and CoinGecko stay selectable, which is the same
additive shape as the other tools.

One thing still reaches a service with no free instance to swap in, inside an
optional feature that works without it: the **Keyman layout catalogue**
(`api.keyman.com`; the software is open source, the catalogue is theirs). Say
so plainly if a packager asks rather than letting them find it; if they judge
it disqualifying, the field goes back.

## Why there is no `TetheredNet` either

F-Droid applies `TetheredNet` to an app that "depends entirely on a service which
is impossible (or not easy) to replace", and waives it when there is "a simple
configuration option that allows pointing the app to a running instance of an
alternative, publicly available, self-hostable server solution". Wikipedia's own
app carries it because wikipedia.org is hardcoded, although MediaWiki is free
software.

So from 0.5.7, every address this build calls is a setting.
**Advanced › Servers** lists all of them, and each tool's own page shows the same
fields:

* every API: Wikipedia (any MediaWiki), the dictionary, Wiktionary and
  kaikki.org, Open-Meteo, the currency sources, Keyman's catalogue and downloads,
  the Noto animated emoji and the F-Droid repository the update check asks
  (IzzyOnDroid serves the same API). The keyed ones too, for a proxy or a
  compatible clone: Brave, KLIPY, GIPHY, Unsplash, Pexels, Google Translate and
  the cloud AI providers.
* every git repository it downloads from: the language data, the add-on and
  key-sound repositories, and the Espanso Hub, on GitHub, Forgejo or Gitea
  (Codeberg), GitLab, SourceHut, Bitbucket, or a plain HTTPS folder. The app
  builds each forge's raw-file address itself.

The code is `ServiceEndpoint`, `ServiceRepo` and `ServiceEndpoints` in
`:core:common`. The defaults are unchanged, nothing moves until someone edits a
field, and the other builds ignore the overrides entirely.

Google Drive, Dropbox and OneDrive backups are not in the list because this build
cannot use them at all: no Play services, and the recipe passes no client ids.
WebDAV, S3-compatible storage and a local folder were already configurable.

## A note on the wrapped strings

`rewritemeta` wraps any value too long for its line width, and the wrap leaves a
trailing space on each continued line it produces. That is its output, so it
cannot be cleaned up without failing the job, and it is harmless: YAML strips a
trailing space before a folded line break, so the string parses back with single
spaces and no newlines. Keep any long value free of real line breaks — an
earlier `AntiFeatures` description was written as a `|-` block and every wrap
point became a literal `\n` in the middle of a sentence.

## The next merge request to fdroiddata

The entries above are for released commits and stay as they are. The first
release with the 0.6.3 build changes needs one merge request before F-Droid's
bot copies the last entry forward, because these paths moved or appeared:

- `scandelete` gains `core/intelligence/src/tesseract/jniLibs`,
  `core/voice/src/whistle/jniLibs`, `core/voice/src/executorch/jniLibs`,
  `core/voice/src/onnx/jniLibs` and `core/input/src/main/jniLibs`, and loses the
  two `src/full/jniLibs` lines, which no longer exist. A prebuilt `.so` the
  list misses fails the scan.
- From that entry on, `scandelete` is generated rather than hand-kept:
  `python3 native/gates.py scandelete` prints every jniLibs directory that
  `native/gates.json` names (the text engines' and every gate's, prebuilt or
  built from source), and `feature/llm/src/main/jniLibs`, the LLM module's
  placeholders, comes on top. A new gate in the table therefore reaches the
  recipe only through the next merge request; the entries above stay pinned to
  their commits as they are.
- `build` may call `../native/build-fdroid-libs.sh`. `build-lite-libs.sh` is
  kept as a wrapper, so the copied entries keep working either way.
- `gradle` may become `[intl]`. `assembleLiteRelease` is kept as an alias, so
  `[lite]` keeps working too.
- `gradleprops` already carries `wmkb.enableFdroid=true`, which is now what
  keeps Google's SDKs out; nothing to change.

The F-Droid build fetches its feature libraries on demand (native delivery)
only after the user turns on **Download feature libraries** under Privacy,
which says it skips F-Droid's checks. The libraries it pins are the ones this
recipe compiled, so a download only matches when the published file is
byte for byte the same build.
