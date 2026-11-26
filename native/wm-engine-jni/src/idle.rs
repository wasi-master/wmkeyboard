//! What the engine keeps for the next keystroke, given back while the
//! keyboard is hidden: `NativeEngine.trimIdle`.
//!
//! The glide decoders keep the workspace their longest stroke grew (a
//! column of samples per state, about 5 MB after a long one) and the tap
//! decoder keeps the last buffer's search (over a megabyte after a long
//! word). Both are rebuilt by the next stroke or keystroke, which reads what
//! it would have read with them kept.

use jni::objects::JClass;
use jni::JNIEnv;

use crate::guard::guarded;

/// Trims every thread's glide lane and the tap decoder's session. A lane or
/// the session in use at the time is trimmed by the call using it once it is
/// done. Returns the lanes trimmed now and whether the session was.
pub(crate) fn trim_idle() -> (usize, bool) {
    (crate::glide::trim_lanes(), crate::decoder::trim_session())
}

/// `NativeEngine.nativeTrimIdle()`: [`trim_idle`].
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_engine_NativeEngine_nativeTrimIdle(mut env: JNIEnv, _class: JClass) {
    guarded(&mut env, (), |_| {
        trim_idle();
        Some(())
    })
}
