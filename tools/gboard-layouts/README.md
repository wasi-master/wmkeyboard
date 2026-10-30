# Gboard layouts

Reads the keyboards out of a Gboard APK and turns what the app lacks into layouts. Run it by hand; nothing in the build depends on it. The APK and the JSON it produces stay out of the repository.

```bash
adb pull "$(adb shell pm path com.google.android.inputmethod.latin | head -1 | sed 's/package://' | tr -d '\r')" base.apk
aapt2 dump resources base.apk > resources.txt
python3 -I extract.py base.apk resources.txt gboard.json
python3 -I generate.py <repo> gboard.json          # prints the plan, writes plan.json
python3 -I generate.py <repo> gboard.json --write
```

## How Gboard stores a keyboard

Resource names are obfuscated, so everything is reached through its id. An `ime` names a language and includes `keyboard_group`s, one per variant. A variant's `keyboard` has a language definition (`lang_def`, the keys) merged with a grid definition (`main_def`, the layout and which key goes in which view). A `softkey` carries `press_data`, `long_press_data` and `slide*_data`, and `key_mapping` entries with a `state` give the other pages: `SHIFT` and `SUB_CATEGORY_n`.

## The rules

- Same keys as one of our own layouts: append its long-press letters and marks to ours, after what the key already has. Digits and symbols are left alone, because our keys order those on purpose.
- Nearly the same keys (90% of the letters): the same, and Gboard's board is added too, since moving our keys would change a layout people have learned.
- Different keys: add Gboard's as `gb_*`.
- A language we do not have: add it to `GboardLanguages.kt` with its layout.
- `qwertz`, `azerty`, `dvorak`, `colemak` and `pcqwerty` are the same keys in every language, so they are not copied per language. The desktop-shaped board ships once, under English.

Our Kotlin built-ins are compared but never edited, and neither are converted Keyman layouts or anything with a composer, T9 keys or Latin keys spelling another script.

Not converted, because each needs an engine rather than keys: transliteration (`abc → हिन्दी`), Chinese, Japanese, handwriting, morse and Korean 10-key.
