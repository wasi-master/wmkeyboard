//! `_mt`: the Mersenne Twister of CPython's Modules/_randommodule.c, for the
//! `random` module written in Python (CPython's own Lib/random.py). Seeded
//! the same way, it gives the same numbers as CPython does: `random.seed(5);
//! random.random()` agrees to the bit.
//!
//! A generator's state is a native object `new` makes and the Python
//! `Random` keeps; the other functions read and change it in place.

use std::rc::Rc;

use num_bigint::{BigInt, Sign};

use super::error::R;
use super::eval::Interp;
use super::value::*;

const N: usize = 624;
const M: usize = 397;
const MATRIX_A: u32 = 0x9908_b0df;
const UPPER_MASK: u32 = 0x8000_0000;
const LOWER_MASK: u32 = 0x7fff_ffff;

pub(crate) const FUNCTIONS: &[&str] = &["new", "seed", "random", "getrandbits", "getstate", "setstate"];

/// The generator itself, read out of and written back to its list.
pub struct Mt {
    words: [u32; N],
    index: usize,
}

impl Mt {
    fn init_genrand(&mut self, s: u32) {
        self.words[0] = s;
        for i in 1..N {
            let prev = self.words[i - 1];
            self.words[i] = 1_812_433_253u32.wrapping_mul(prev ^ (prev >> 30)).wrapping_add(i as u32);
        }
        self.index = N;
    }

    fn init_by_array(&mut self, key: &[u32]) {
        self.init_genrand(19_650_218);
        let mt = &mut self.words;
        let (mut i, mut j) = (1usize, 0usize);
        for _ in 0..N.max(key.len()) {
            let prev = mt[i - 1];
            mt[i] = (mt[i] ^ (prev ^ (prev >> 30)).wrapping_mul(1_664_525)).wrapping_add(key[j]).wrapping_add(j as u32);
            i += 1;
            j += 1;
            if i >= N {
                mt[0] = mt[N - 1];
                i = 1;
            }
            if j >= key.len() {
                j = 0;
            }
        }
        for _ in 0..N - 1 {
            let prev = mt[i - 1];
            mt[i] = (mt[i] ^ (prev ^ (prev >> 30)).wrapping_mul(1_566_083_941)).wrapping_sub(i as u32);
            i += 1;
            if i >= N {
                mt[0] = mt[N - 1];
                i = 1;
            }
        }
        mt[0] = 0x8000_0000;
    }

    fn next_u32(&mut self) -> u32 {
        let mag = |y: u32| if y & 1 == 0 { 0 } else { MATRIX_A };
        if self.index >= N {
            let mt = &mut self.words;
            for kk in 0..N - M {
                let y = (mt[kk] & UPPER_MASK) | (mt[kk + 1] & LOWER_MASK);
                mt[kk] = mt[kk + M] ^ (y >> 1) ^ mag(y);
            }
            for kk in N - M..N - 1 {
                let y = (mt[kk] & UPPER_MASK) | (mt[kk + 1] & LOWER_MASK);
                mt[kk] = mt[kk + M - N] ^ (y >> 1) ^ mag(y);
            }
            let y = (mt[N - 1] & UPPER_MASK) | (mt[0] & LOWER_MASK);
            mt[N - 1] = mt[M - 1] ^ (y >> 1) ^ mag(y);
            self.index = 0;
        }
        let mut y = self.words[self.index];
        self.index += 1;
        y ^= y >> 11;
        y ^= (y << 7) & 0x9d2c_5680;
        y ^= (y << 15) & 0xefc6_0000;
        y ^ (y >> 18)
    }

    /// random(): 53 random bits as a float in [0, 1).
    fn random(&mut self) -> f64 {
        let a = self.next_u32() >> 5;
        let b = self.next_u32() >> 6;
        (f64::from(a) * 67_108_864.0 + f64::from(b)) * (1.0 / 9_007_199_254_740_992.0)
    }

    /// getrandbits(k) for k > 0: 32-bit words, least significant first.
    fn bits(&mut self, k: u64) -> BigInt {
        if k <= 32 {
            return BigInt::from(self.next_u32() >> (32 - k));
        }
        let words = (k - 1) / 32 + 1;
        let mut left = k;
        let mut out = Vec::with_capacity(words as usize);
        for _ in 0..words {
            let mut r = self.next_u32();
            if left < 32 {
                r >>= 32 - left;
            }
            out.push(r);
            left = left.saturating_sub(32);
        }
        BigInt::from_slice(Sign::Plus, &out)
    }
}

impl Interp {
    fn mt_of(&self, v: Option<&Value>) -> R<Rc<Object>> {
        match v {
            Some(Value::Object(o)) if matches!(o.as_ref(), Object::Mt(_)) => Ok(o.clone()),
            _ => Err(self.error("_mt needs a generator's state", "TypeError")),
        }
    }

    /// random_seed: None draws from the system's randomness; an int seeds
    /// by its absolute value, 32 bits at a time; anything else by its hash.
    fn mt_seed(&mut self, mt: &mut Mt, seed: Option<&Value>) -> R<()> {
        let n = match seed {
            None | Some(Value::None) => {
                let Value::Bytes(bytes) = self.native_call("urandom", &[Value::Int((N * 4) as i64)])? else {
                    return Err(self.error("urandom() gave no bytes", "RuntimeError"));
                };
                let key: Vec<u32> = bytes.as_chunks::<4>().0.iter().map(|c| u32::from_le_bytes(*c)).collect();
                mt.init_by_array(&key);
                return Ok(());
            }
            Some(Value::Bool(b)) => BigInt::from(u8::from(*b)),
            Some(v) if super::bigint::is_int(v) => num_traits::Signed::abs(&super::bigint::to_big(v).unwrap_or_default()),
            Some(v) => {
                let h = self.hash_of(v)?;
                BigInt::from(h as u64)
            }
        };
        let (_, mut key) = n.to_u32_digits();
        if key.is_empty() {
            key.push(0);
        }
        mt.init_by_array(&key);
        Ok(())
    }

    #[inline(never)]
    pub(crate) fn mt_call(&mut self, name: &str, args: &[Value]) -> R<Value> {
        if name == "new" {
            let mut mt = Mt { words: [0; N], index: N };
            self.mt_seed(&mut mt, args.first())?;
            return Ok(Value::Object(Rc::new(Object::Mt(Box::new(std::cell::RefCell::new(mt))))));
        }
        let state = self.mt_of(args.first())?;
        let Object::Mt(cell) = state.as_ref() else { unreachable!() };
        match name {
            "seed" => {
                let mut mt = Mt { words: [0; N], index: N };
                self.mt_seed(&mut mt, args.get(1))?;
                *cell.borrow_mut() = mt;
                Ok(Value::None)
            }
            "random" => Ok(Value::Float(cell.borrow_mut().random())),
            "getrandbits" => {
                let k = match args.get(1) {
                    Some(v) if super::bigint::is_int(v) => super::bigint::to_big(v).unwrap_or_default(),
                    Some(v) => return Err(self.error(format!("'{}' object cannot be interpreted as an integer", v.type_name()), "TypeError")),
                    None => return Err(self.error("getrandbits() takes exactly one argument (0 given)", "TypeError")),
                };
                if num_traits::Signed::is_negative(&k) {
                    return Err(self.error("number of bits must be non-negative", "ValueError"));
                }
                let k = num_traits::ToPrimitive::to_u64(&k).filter(|k| *k <= super::bigint::MAX_BITS).ok_or_else(|| {
                    self.error("getrandbits() asked for more bits than an int here can hold", "OverflowError")
                })?;
                if k == 0 {
                    return Ok(Value::Int(0));
                }
                let n = cell.borrow_mut().bits(k);
                Ok(super::bigint::int_value(n))
            }
            "getstate" => {
                let mt = cell.borrow();
                let mut items: Vec<Value> = mt.words.iter().map(|w| Value::Int(i64::from(*w))).collect();
                items.push(Value::Int(mt.index as i64));
                Ok(Value::tuple(items))
            }
            "setstate" => {
                let Some(Value::Tuple(t)) = args.get(1) else {
                    return Err(self.error("state vector must be a tuple", "TypeError"));
                };
                if t.len() != N + 1 {
                    return Err(self.error("state vector is the wrong size", "ValueError"));
                }
                let mut mt = Mt { words: [0; N], index: 0 };
                for (i, w) in t[..N].iter().enumerate() {
                    let Some(n) = super::bigint::to_big(w) else {
                        return Err(self.error(format!("'{}' object cannot be interpreted as an integer", w.type_name()), "TypeError"));
                    };
                    mt.words[i] = num_traits::ToPrimitive::to_u32(&n)
                        .ok_or_else(|| self.error("Python int too large to convert to C unsigned long", "OverflowError"))?;
                }
                let index = t[N].as_int().unwrap_or(-1);
                if !(0..=N as i64).contains(&index) {
                    return Err(self.error("invalid state", "ValueError"));
                }
                mt.index = index as usize;
                *cell.borrow_mut() = mt;
                Ok(Value::None)
            }
            _ => Err(self.error(format!("module '_mt' has no attribute '{name}'"), "AttributeError")),
        }
    }
}
