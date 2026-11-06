plugins {
    // The library-only Play split of the `vosk` gate: native/gates.json.
    id("wmkeyboard.native-split")
    // Alpha Cephei's libvosk.so, out of its AAR, as this split's library.
    id("wmkeyboard.vosk")
}
