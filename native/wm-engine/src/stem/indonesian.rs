//! Indonesian and Malay: a port of Apache Lucene's `IndonesianStemmer`
//! (Apache-2.0), after Fadillah Z. Tala, "A Study of Stemming Effects on
//! Information Retrieval in Bahasa Indonesia": particles, possessive
//! pronouns, then the derivational prefixes and suffixes.

use super::Word;

const KE: u8 = 1;
const PENG: u8 = 2;
const DI: u8 = 4;
const MENG: u8 = 8;
const TER: u8 = 16;
const BER: u8 = 32;
const PE: u8 = 64;

fn vowel(c: char) -> bool {
    matches!(c, 'a' | 'e' | 'i' | 'o' | 'u')
}

struct State {
    syllables: usize,
    flags: u8,
}

pub fn stem(w: &mut Word) {
    let mut st = State { syllables: w.s[..w.len].iter().filter(|&&c| vowel(c)).count(), flags: 0 };
    if st.syllables > 2 && w.ends_any(&["kah", "lah", "pun"]) {
        st.syllables -= 1;
        w.drop_end(3);
    }
    if st.syllables > 2 {
        if w.ends("ku") || w.ends("mu") {
            st.syllables -= 1;
            w.drop_end(2);
        } else if w.ends("nya") {
            st.syllables -= 1;
            w.drop_end(3);
        }
    }
    let before = w.len;
    if st.syllables > 2 {
        first_prefix(w, &mut st);
    }
    if before != w.len {
        let before = w.len;
        if st.syllables > 2 {
            suffix(w, &mut st);
        }
        if before != w.len && st.syllables > 2 {
            second_prefix(w, &mut st);
        }
    } else {
        if st.syllables > 2 {
            second_prefix(w, &mut st);
        }
        if st.syllables > 2 {
            suffix(w, &mut st);
        }
    }
}

fn cut(w: &mut Word, st: &mut State, flag: u8, n: usize) {
    st.flags |= flag;
    st.syllables -= 1;
    w.drop_start(n);
}

fn first_prefix(w: &mut Word, st: &mut State) {
    let next_vowel = |w: &Word, i: usize| w.len > i && vowel(w.s[i]);
    if w.starts("meng") {
        cut(w, st, MENG, 4)
    } else if w.starts("meny") && next_vowel(w, 4) {
        w.s[3] = 's';
        cut(w, st, MENG, 3)
    } else if w.starts("men") || w.starts("mem") {
        cut(w, st, MENG, 3)
    } else if w.starts("me") {
        cut(w, st, MENG, 2)
    } else if w.starts("peng") {
        cut(w, st, PENG, 4)
    } else if w.starts("peny") && next_vowel(w, 4) {
        w.s[3] = 's';
        cut(w, st, PENG, 3)
    } else if w.starts("peny") {
        cut(w, st, PENG, 4)
    } else if w.starts("pen") && next_vowel(w, 3) {
        w.s[2] = 't';
        cut(w, st, PENG, 2)
    } else if w.starts("pen") || w.starts("pem") {
        cut(w, st, PENG, 3)
    } else if w.starts("di") {
        cut(w, st, DI, 2)
    } else if w.starts("ter") {
        cut(w, st, TER, 3)
    } else if w.starts("ke") {
        cut(w, st, KE, 2)
    }
}

fn second_prefix(w: &mut Word, st: &mut State) {
    if w.starts("ber") || (w.len == 7 && w.starts("belajar")) {
        cut(w, st, BER, 3)
    } else if w.starts("be") && w.len > 4 && !vowel(w.s[2]) && w.s[3] == 'e' && w.s[4] == 'r' {
        cut(w, st, BER, 2)
    } else if w.starts("per") || (w.len == 7 && w.starts("pelajar")) {
        cut(w, st, 0, 3)
    } else if w.starts("pe") {
        cut(w, st, PE, 2)
    }
}

fn suffix(w: &mut Word, st: &mut State) {
    let f = st.flags;
    if w.ends("kan") && f & (KE | PENG | PE) == 0 {
        st.syllables -= 1;
        w.drop_end(3);
    } else if w.ends("an") && f & (DI | MENG | TER) == 0 {
        st.syllables -= 1;
        w.drop_end(2);
    } else if w.ends("i") && !w.ends("si") && f & (BER | KE | PENG) == 0 {
        st.syllables -= 1;
        w.drop_end(1);
    }
}

#[cfg(test)]
mod tests {
    use crate::stem::{stem, Lang};

    #[test]
    fn tala() {
        // Lucene's TestIndonesianStemmer.
        for (word, want) in [
            ("bukukah", "buku"),
            ("adalah", "ada"),
            ("bukupun", "buku"),
            ("bukuku", "buku"),
            ("bukumu", "buku"),
            ("bukunya", "buku"),
            ("mengukur", "ukur"),
            ("menyapu", "sapu"),
            ("menduga", "duga"),
            ("menuduh", "uduh"),
            ("membaca", "baca"),
            ("merusak", "rusak"),
            ("pengukur", "ukur"),
            ("penyapu", "sapu"),
            ("penduga", "duga"),
            ("pembaca", "baca"),
            ("diukur", "ukur"),
            ("tersapu", "sapu"),
            ("kekasih", "kasih"),
            ("berlari", "lari"),
            ("belajar", "ajar"),
            ("bekerja", "kerja"),
            ("perjelas", "jelas"),
            ("pelajar", "ajar"),
            ("pekerja", "kerja"),
            ("tarikkan", "tarik"),
            ("ambilkan", "ambil"),
            ("mengambilkan", "ambil"),
            ("makanan", "makan"),
            ("janjian", "janji"),
            ("perjanjian", "janji"),
            ("tandai", "tanda"),
            ("dapati", "dapat"),
            ("mendapati", "dapat"),
            ("pantai", "panta"),
            ("penyalahgunaan", "salahguna"),
            ("menyalahgunakan", "salahguna"),
            ("disalahgunakan", "salahguna"),
            ("pertanggungjawaban", "tanggungjawab"),
            ("mempertanggungjawabkan", "tanggungjawab"),
            ("dipertanggungjawabkan", "tanggungjawab"),
            ("pelaksanaan", "laksana"),
            ("pelaksana", "laksana"),
            ("melaksanakan", "laksana"),
            ("dilaksanakan", "laksana"),
            ("melibatkan", "libat"),
            ("terlibat", "libat"),
            ("penculikan", "culik"),
            ("menculik", "culik"),
            ("diculik", "culik"),
            ("penculik", "culik"),
            ("perubahan", "ubah"),
            ("peledakan", "ledak"),
            ("penanganan", "tangan"),
            ("kepolisian", "polisi"),
            ("kenaikan", "naik"),
            ("bersenjata", "senjata"),
            ("penyelewengan", "seleweng"),
            ("kecelakaan", "celaka"),
            ("gigi", "gigi"),
        ] {
            assert_eq!(stem(Lang::Indonesian, word), want, "{word}");
        }
    }
}
