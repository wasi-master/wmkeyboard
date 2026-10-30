plugins {
    // The library-only Play split of the `webrtc` gate: native/gates.json.
    id("wmkeyboard.native-split")
    // The WebRTC library, out of its AAR, as this split's library.
    id("wmkeyboard.webrtc")
}
