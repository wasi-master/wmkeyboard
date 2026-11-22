# numbat-jni: sums with units

Rust cdylib wrapping [Numbat](https://numbat.dev) (`numbat` 1.24, MIT OR
Apache-2.0) behind a small JNI surface for the calculator tool and the
calculator chip. Kotlin entry points: `com.wasimaster.wmkeyboard.core.tools.Numbat`
(the facade) over `NumbatNative`, with `CalcRouting` deciding what reaches it.

The library is the `numbat` gate in `native/gates.json`: about 1.9 MB on
arm64 and 1.3 MB on armeabi-v7a, too big for `wm_engine`, which every keyboard
start loads. The prebuilt `libwmnumbat.so` files are committed under
`core/tools/src/numbat/jniLibs/<abi>/`, so the app builds without a Rust
toolchain. Rebuild them after you change this crate or bump `numbat`:

```sh
ANDROID_NDK=/opt/homebrew/share/android-ndk native/numbat-jni/build.sh
```

`build.sh` installs the pinned Rust toolchain and Android targets through
rustup and the pinned `cargo-ndk`, builds arm64-v8a and armeabi-v7a (16 KB
pages on arm64, the NDK's default), and remaps the registry, standard library
and repository paths out of the library, so two machines with the same
toolchain build the same bytes. F-Droid deletes the committed copies and
builds them with it, through `native/build-fdroid-libs.sh`.

`numbat` is built with `default-features = false`: no plotting, and none of
Numbat's own exchange-rate fetching.

## Contract with Kotlin

- `nativeEvaluate(expression, digits) -> String` returns one JSON object:
  `{"ok":true,"text":"1.824 m","kind":"quantity","value":1.824,"unit":"m"}`
  (`kind` is also `datetime`, `string`, `boolean` or `other`, without
  `value` and `unit`), or `{"ok":false,"code":...,"detail":...,"message":...}`
  with `code` one of `refused`, `temperature`, `empty`, `parse`, `unknown`
  (`detail` is the identifier), `dimensions`, `type`, `runtime`.
- `nativeSetRates(json) -> Boolean` takes the keyboard's rate table, a flat
  object of units per US dollar (`CurrencyClient.Rates`), and rebuilds the
  currency units from it.
- `nativeWarmUp()` loads the prelude (about 80 ms on an M-series Mac).

## What it refuses

The context lives as long as the keyboard, and Numbat runs inside the
keyboard's process with no way to stop it, so `refusal()` turns away
definitions (`let`, `fn`, `unit`, `dimension`, `use`, `struct`), lists, the
prelude's recursive functions (`range`, `map`, `fibonacci`…), private helpers
(`_…`), and anything over 200 characters or more than one line. Temperatures
(`°C`, `°F`) are refused as well: Numbat has no such units and reads `°C` as
degrees times coulombs. The context is rebuilt from the loaded prelude every
256 expressions, since Numbat keeps the source of each one.

## Exchange rates

Numbat's rate cache can be filled once per process, so it is not used. A
module importer serves `units::currencies` with every `exchange_rate("XYZ")`
written out from our table (units per euro, as the module wants), drops the
units the table lacks, and adds the table's other codes (`BDT`, coins) after
it, each under its code. A new table rebuilds the context from a copy of the
prelude (about 4 ms).

## Tests

`cargo test` runs the host tests: units in expressions, the refusals, error
classes, our rates, and the examples the docs pages quote. `cargo test --release
-- --ignored --nocapture timing` prints what the prelude, a rate change and a
warm expression cost; `... samples` prints how a list of inputs read.
