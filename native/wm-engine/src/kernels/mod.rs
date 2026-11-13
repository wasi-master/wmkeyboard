//! Data-parallel kernels, with NEON where it pays.
//!
//! Every kernel here follows one pattern, so the next one can be added
//! without reading the others:
//!
//! - `scalar::name` is the reference: plain Rust, written for clarity, the
//!   definition of what the kernel computes. It is also what armv7
//!   and non-ARM hosts run (LLVM may vectorise it there; nothing here relies on that).
//! - `neon::name` (and `dotprod::name`, `fp16::name` for the optional
//!   extensions) is the aarch64 version, on the stable `core::arch::aarch64`
//!   intrinsics, marked `#[target_feature(enable = ...)]`, `unsafe` only for
//!   that reason.
//! - `name` is the public entry point: it asks [`active`] which features it
//!   may use and dispatches, falling back to the reference.
//! - Its tests compare every SIMD version against the reference on seeded
//!   random inputs, over lengths that hit every tail case, within a stated
//!   tolerance ([`testing`]). An exact kernel (integer arithmetic) states 0.
//!
//! Feature detection runs once ([`detected`]). NEON itself is mandatory on
//! aarch64; `dotprod` (armv8.2 `sdot`/`udot`) and `fp16` are probed at run
//! time with `is_aarch64_feature_detected!`, which reads the kernel's
//! hwcaps on Android and Linux. armv7's NEON intrinsics are still unstable
//! in Rust, so armv7 runs the reference, built with the target's `+neon`.
//!
//! [`set_force_scalar`] pins every kernel to its reference for the rest of
//! the process. The app's "force Kotlin" switch sets it too (see
//! `NativeEngine` on the Kotlin side), so one setting rules out the whole
//! native fast path when a device misbehaves.

pub mod argmax;
pub mod band;
pub mod box_average;
pub mod box_blur;
pub mod dot;
pub mod gif;
pub mod sharpen;
pub mod upscale;
pub mod yuv;

#[cfg(test)]
pub mod testing;

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::OnceLock;

/// The SIMD extensions a kernel may use.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub struct Features {
    /// Advanced SIMD: every aarch64 core.
    pub neon: bool,
    /// The armv8.2 dot-product instructions (`sdot`, `udot`).
    pub dotprod: bool,
    /// Half-precision arithmetic (armv8.2 FP16).
    pub fp16: bool,
}

impl Features {
    /// None of them: every kernel runs its scalar reference.
    pub const SCALAR: Features = Features { neon: false, dotprod: false, fp16: false };

    /// The features as one bit set, for crossing to Kotlin: bit 0 NEON,
    /// bit 1 dotprod, bit 2 fp16.
    pub fn bits(self) -> u32 {
        (self.neon as u32) | (self.dotprod as u32) << 1 | (self.fp16 as u32) << 2
    }
}

/// What this CPU offers, probed once.
pub fn detected() -> Features {
    static DETECTED: OnceLock<Features> = OnceLock::new();
    *DETECTED.get_or_init(probe)
}

#[cfg(target_arch = "aarch64")]
fn probe() -> Features {
    Features {
        neon: std::arch::is_aarch64_feature_detected!("neon"),
        dotprod: std::arch::is_aarch64_feature_detected!("dotprod"),
        fp16: std::arch::is_aarch64_feature_detected!("fp16"),
    }
}

#[cfg(not(target_arch = "aarch64"))]
fn probe() -> Features {
    Features::SCALAR
}

static FORCE_SCALAR: AtomicBool = AtomicBool::new(false);

/// Pins every kernel to its scalar reference (or releases it).
pub fn set_force_scalar(on: bool) {
    FORCE_SCALAR.store(on, Ordering::Relaxed);
}

pub fn force_scalar() -> bool {
    FORCE_SCALAR.load(Ordering::Relaxed)
}

/// The features kernels may use right now: [`detected`], or none while
/// [`set_force_scalar`] is on.
pub fn active() -> Features {
    if force_scalar() {
        Features::SCALAR
    } else {
        detected()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn forcing_scalar_hides_every_feature() {
        let _pinned = testing::FORCE.lock().unwrap_or_else(|e| e.into_inner());
        set_force_scalar(true);
        assert_eq!(Features::SCALAR, active());
        set_force_scalar(false);
        assert_eq!(detected(), active());
    }

    #[test]
    fn the_bits_name_each_feature() {
        assert_eq!(0, Features::SCALAR.bits());
        assert_eq!(0b101, Features { neon: true, dotprod: false, fp16: true }.bits());
    }

    #[cfg(target_arch = "aarch64")]
    #[test]
    fn every_aarch64_core_has_neon() {
        assert!(detected().neon);
    }
}
