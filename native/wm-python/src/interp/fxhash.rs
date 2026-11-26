//! A fast, unkeyed hasher (rustc's FxHash) for the maps the interpreter keys
//! by identifier: scopes, class and instance attributes, host functions.
//!
//! std's SipHash is built to resist crafted collisions and costs most of a
//! name lookup on short keys. Names here come from the script itself, and a
//! script that collides its own attribute names only slows itself down, step
//! by step, under the same budget and deadline as anything else.
//!
//! Dicts and sets a script fills with data can hold keys someone else chose
//! (a web reply, a file), so they hash with [DataHasher]: keyed per process,
//! like SipHash, but a folded multiply per word, as foldhash does, at a
//! fraction of SipHash's cost on the short strings dicts mostly hold.

use std::hash::{BuildHasher, BuildHasherDefault, Hasher};
use std::rc::Rc;
use std::sync::OnceLock;

use indexmap::{IndexMap, IndexSet};

const SEED: u64 = 0x51_7c_c1_b7_27_22_0a_95;

#[derive(Default, Clone, Copy)]
pub struct FxHasher {
    hash: u64,
}

impl FxHasher {
    #[inline]
    fn add(&mut self, word: u64) {
        self.hash = (self.hash.rotate_left(5) ^ word).wrapping_mul(SEED);
    }
}

impl Hasher for FxHasher {
    #[inline]
    fn write(&mut self, bytes: &[u8]) {
        let mut rest = bytes;
        while rest.len() >= 8 {
            self.add(u64::from_le_bytes(rest[..8].try_into().unwrap()));
            rest = &rest[8..];
        }
        if rest.len() >= 4 {
            self.add(u32::from_le_bytes(rest[..4].try_into().unwrap()) as u64);
            rest = &rest[4..];
        }
        for &b in rest {
            self.add(b as u64);
        }
    }

    #[inline]
    fn write_u8(&mut self, i: u8) {
        self.add(i as u64);
    }

    #[inline]
    fn write_u32(&mut self, i: u32) {
        self.add(i as u64);
    }

    #[inline]
    fn write_u64(&mut self, i: u64) {
        self.add(i);
    }

    #[inline]
    fn write_usize(&mut self, i: usize) {
        self.add(i as u64);
    }

    /// The multiply leaves its best bits at the top, and hashbrown picks a
    /// bucket from the bottom ones: rotate them down.
    #[inline]
    fn finish(&self) -> u64 {
        self.hash.rotate_left(26)
    }
}

pub type FxBuild = BuildHasherDefault<FxHasher>;

/// Names to values, in the order they were first bound.
pub type NameMap = IndexMap<Rc<str>, super::value::Value, FxBuild>;

pub(crate) fn name_map(capacity: usize) -> NameMap {
    IndexMap::with_capacity_and_hasher(capacity, FxBuild::default())
}

// MARK: - Data dicts and sets

/// The low and high halves of `a * b`, folded together.
#[inline]
fn fold(a: u64, b: u64) -> u64 {
    let p = (a as u128).wrapping_mul(b as u128);
    (p as u64) ^ ((p >> 64) as u64)
}

/// Two words the process draws once, so nobody outside can line keys up.
fn process_keys() -> (u64, u64) {
    static KEYS: OnceLock<(u64, u64)> = OnceLock::new();
    *KEYS.get_or_init(|| {
        let state = std::collections::hash_map::RandomState::new();
        let draw = |n: u64| {
            let mut h = state.build_hasher();
            h.write_u64(n);
            // Never zero: a zero key would zero the multiply below.
            h.finish() | 1
        };
        (draw(0x243f_6a88_85a3_08d3), draw(0x1319_8a2e_0370_7344))
    })
}

/// Builds a [DataHasher] with the process's keys.
#[derive(Clone, Copy)]
pub struct DataBuild {
    k0: u64,
    k1: u64,
}

impl Default for DataBuild {
    #[inline]
    fn default() -> Self {
        let (k0, k1) = process_keys();
        DataBuild { k0, k1 }
    }
}

impl BuildHasher for DataBuild {
    type Hasher = DataHasher;

    #[inline]
    fn build_hasher(&self) -> DataHasher {
        DataHasher { acc: self.k0, k0: self.k0, k1: self.k1 }
    }
}

/// A keyed hasher for script data: each word is xored into the running
/// value and folded through a multiply by a secret, as foldhash does.
#[derive(Clone, Copy)]
pub struct DataHasher {
    acc: u64,
    k0: u64,
    k1: u64,
}

impl DataHasher {
    #[inline]
    fn add(&mut self, word: u64) {
        self.acc = fold(self.acc ^ word, self.k1);
    }
}

impl Hasher for DataHasher {
    #[inline]
    fn write(&mut self, bytes: &[u8]) {
        let mut rest = bytes;
        while rest.len() >= 16 {
            let a = u64::from_le_bytes(rest[..8].try_into().unwrap());
            let b = u64::from_le_bytes(rest[8..16].try_into().unwrap());
            self.acc = fold(self.acc ^ a ^ self.k0, b ^ self.k1);
            rest = &rest[16..];
        }
        if rest.len() >= 8 {
            self.add(u64::from_le_bytes(rest[..8].try_into().unwrap()));
            rest = &rest[8..];
        }
        if !rest.is_empty() {
            let mut tail = [0u8; 8];
            tail[..rest.len()].copy_from_slice(rest);
            // The length rides along, so "a" and "a\0" differ.
            self.add(u64::from_le_bytes(tail) ^ ((rest.len() as u64) << 59));
        }
    }

    #[inline]
    fn write_u8(&mut self, i: u8) {
        self.add(i as u64);
    }

    #[inline]
    fn write_u32(&mut self, i: u32) {
        self.add(i as u64);
    }

    #[inline]
    fn write_u64(&mut self, i: u64) {
        self.add(i);
    }

    #[inline]
    fn write_usize(&mut self, i: usize) {
        self.add(i as u64);
    }

    #[inline]
    fn finish(&self) -> u64 {
        fold(self.acc, self.k0).rotate_left(23)
    }
}

/// A script's dict: keys to values in insertion order.
pub type DictMap = IndexMap<super::value::HKey, super::value::Value, DataBuild>;

/// A script's set.
pub type KeySet = IndexSet<super::value::HKey, DataBuild>;
