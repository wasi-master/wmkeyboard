//! A fast, unkeyed hasher (rustc's FxHash) for the maps the interpreter keys
//! by identifier: scopes, class and instance attributes, host functions.
//!
//! std's SipHash is built to resist crafted collisions and costs most of a
//! name lookup on short keys. Names here come from the script itself, and a
//! script that collides its own attribute names only slows itself down, step
//! by step, under the same budget and deadline as anything else. Dicts and
//! sets a script fills with data keep SipHash.

use std::hash::{BuildHasherDefault, Hasher};
use std::rc::Rc;

use indexmap::IndexMap;

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
