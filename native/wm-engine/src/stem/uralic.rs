//! Finnish and Hungarian: ports of Apache Lucene's `FinnishLightStemmer` and
//! `HungarianLightStemmer` (Apache-2.0), which follow Jacques Savoy's UniNE
//! light stemmers (Copyright (c) 2005, Jacques Savoy, BSD-3-Clause).

use super::Word;

fn vowel(c: char) -> bool {
    matches!(c, 'a' | 'e' | 'i' | 'o' | 'u' | 'y')
}

pub fn finnish(w: &mut Word) {
    if w.len < 4 {
        return;
    }
    w.map(|c| match c {
        'ä' | 'å' => 'a',
        'ö' => 'o',
        _ => c,
    });
    finnish_step1(w);
    finnish_step2(w);
    finnish_step3(w);
    finnish_norm1(w);
    finnish_norm2(w);
}

fn finnish_step1(w: &mut Word) {
    loop {
        if w.len > 8 {
            if w.ends("kin") {
                w.drop_end(3);
                continue;
            }
            if w.ends("ko") {
                w.drop_end(2);
                continue;
            }
        }
        if w.len > 11 {
            if w.ends("dellinen") {
                return w.drop_end(8);
            }
            if w.ends("dellisuus") {
                return w.drop_end(9);
            }
        }
        return;
    }
}

fn finnish_step2(w: &mut Word) {
    if w.len > 5 {
        if w.ends_any(&["lla", "tse", "sti"]) {
            return w.drop_end(3);
        }
        if w.ends("ni") {
            return w.drop_end(2);
        }
        if w.ends("aa") {
            w.drop_end(1);
        }
    }
}

fn finnish_step3(w: &mut Word) {
    let len = w.len;
    if len > 8 {
        if w.ends("nnen") {
            w.set_end(4, 's');
            return w.drop_end(3);
        }
        if w.ends("ntena") {
            w.set_end(5, 's');
            return w.drop_end(4);
        }
        if w.ends("tten") {
            return w.drop_end(4);
        }
        if w.ends("eiden") {
            return w.drop_end(5);
        }
    }
    if len > 6 {
        if w.ends_any(&["neen", "niin", "seen", "teen", "inen"]) {
            return w.drop_end(4);
        }
        if w.at_end(3) == 'h' && vowel(w.at_end(2)) && w.at_end(1) == 'n' {
            return w.drop_end(3);
        }
        if w.ends("den") {
            w.set_end(3, 's');
            return w.drop_end(2);
        }
        if w.ends("ksen") {
            w.set_end(4, 's');
            return w.drop_end(3);
        }
        if w.ends_any(&["ssa", "sta", "lla", "lta", "tta", "ksi", "lle"]) {
            return w.drop_end(3);
        }
    }
    if len > 5 {
        if w.ends("na") || w.ends("ne") {
            return w.drop_end(2);
        }
        if w.ends("nei") {
            return w.drop_end(3);
        }
    }
    if len > 4 {
        if w.ends("ja") || w.ends("ta") {
            return w.drop_end(2);
        }
        if w.at_end(1) == 'a' {
            return w.drop_end(1);
        }
        if w.at_end(1) == 'n' && vowel(w.at_end(2)) {
            return w.drop_end(2);
        }
        if w.at_end(1) == 'n' {
            w.drop_end(1);
        }
    }
}

fn finnish_norm1(w: &mut Word) {
    if w.len > 5 && w.ends("hde") {
        w.replace_end(3, "ksi");
    }
    if w.len > 4 && (w.ends("ei") || w.ends("at")) {
        return w.drop_end(2);
    }
    if w.len > 3 && matches!(w.at_end(1), 't' | 's' | 'j' | 'e' | 'a' | 'i') {
        w.drop_end(1);
    }
}

fn finnish_norm2(w: &mut Word) {
    if w.len > 8 && matches!(w.at_end(1), 'e' | 'o' | 'u') {
        w.drop_end(1);
    }
    if w.len > 4 {
        if w.at_end(1) == 'i' {
            w.drop_end(1);
        }
        if w.len > 4 {
            let mut ch = w.s[0];
            let mut i = 1;
            while i < w.len {
                if w.s[i] == ch && matches!(ch, 'k' | 'p' | 't') {
                    w.delete(i);
                } else {
                    ch = w.s[i];
                    i += 1;
                }
            }
        }
    }
}

pub fn hungarian(w: &mut Word) {
    w.map(|c| match c {
        'á' => 'a',
        'ë' | 'é' => 'e',
        'í' => 'i',
        'ó' | 'ő' | 'õ' | 'ö' => 'o',
        'ú' | 'ű' | 'ũ' | 'û' | 'ü' => 'u',
        _ => c,
    });
    hungarian_case(w);
    hungarian_possessive(w);
    if w.len > 3 && w.at_end(1) == 'k' {
        if matches!(w.at_end(2), 'a' | 'o' | 'e') && w.len > 4 {
            w.drop_end(2);
        } else {
            w.drop_end(1);
        }
    }
    if w.len > 3 && matches!(w.at_end(1), 'a' | 'e' | 'i' | 'o') {
        w.drop_end(1);
    }
}

fn hungarian_case(w: &mut Word) {
    let len = w.len;
    if len > 6 && w.ends("kent") {
        return w.drop_end(4);
    }
    if len > 5 {
        if w.ends_any(&[
            "nak", "nek", "val", "vel", "ert", "rol", "ban", "ben", "bol", "nal", "nel", "hoz", "hez", "tol",
        ]) {
            return w.drop_end(3);
        }
        if (w.ends("al") || w.ends("el")) && !vowel(w.at_end(3)) && w.at_end(3) == w.at_end(4) {
            return w.drop_end(3);
        }
    }
    if len > 4 {
        if w.ends_any(&["at", "et", "ot", "va", "ve", "ra", "re", "ba", "be", "ul", "ig"]) {
            return w.drop_end(2);
        }
        if (w.ends("on") || w.ends("en")) && !vowel(w.at_end(3)) {
            return w.drop_end(2);
        }
        match w.at_end(1) {
            't' | 'n' => w.drop_end(1),
            'a' | 'e' if w.at_end(2) == w.at_end(3) && !vowel(w.at_end(2)) => w.drop_end(2),
            _ => {}
        }
    }
}

fn hungarian_possessive(w: &mut Word) {
    let len = w.len;
    if len > 6 {
        if !vowel(w.at_end(5)) && w.ends_any(&["atok", "otok", "etek"]) {
            return w.drop_end(4);
        }
        if w.ends("itek") || w.ends("itok") {
            return w.drop_end(4);
        }
    }
    if len > 5 {
        if !vowel(w.at_end(4)) && w.ends_any(&["unk", "tok", "tek"]) {
            return w.drop_end(3);
        }
        if vowel(w.at_end(4)) && w.ends("juk") {
            return w.drop_end(3);
        }
        if w.ends("ink") {
            return w.drop_end(3);
        }
    }
    if len > 4 {
        if !vowel(w.at_end(3)) && w.ends_any(&["am", "em", "om", "ad", "ed", "od", "uk"]) {
            return w.drop_end(2);
        }
        if vowel(w.at_end(3)) && w.ends_any(&["nk", "ja", "je"]) {
            return w.drop_end(2);
        }
        if w.ends_any(&["im", "id", "ik"]) {
            return w.drop_end(2);
        }
    }
    if len > 3 {
        match w.at_end(1) {
            'a' | 'e' if !vowel(w.at_end(2)) => w.drop_end(1),
            'm' | 'd' if vowel(w.at_end(2)) => w.drop_end(1),
            'i' => w.drop_end(1),
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use crate::stem::{stem, Lang};

    fn same(lang: Lang, words: &[&str]) {
        let first = stem(lang, words[0]);
        for w in words {
            assert_eq!(stem(lang, w), first, "{lang:?} {w} vs {}", words[0]);
        }
    }

    #[test]
    fn finnish() {
        same(Lang::Finnish, &["talo", "talossa", "talosta", "taloon", "talot"]);
        assert_eq!(stem(Lang::Finnish, "kä"), "kä");
    }

    #[test]
    fn hungarian() {
        same(Lang::Hungarian, &["ház", "házban", "háznak", "házak"]);
        same(Lang::Hungarian, &["könyv", "könyvek", "könyvben"]);
    }

    #[test]
    fn finnish_reference() {
        // Sampled from Lucene's filighttestdata.zip, Savoy's own output.
        for (word, want) in [
            ("aachenin", "aachen"),
            ("ajotaitoa", "ajotaito"),
            ("amiria", "amir"),
            ("arvoiseensa", "arvoiseen"),
            ("autoilijain", "autoilij"),
            ("brittihallitus", "britihallit"),
            ("ducruet", "ducrue"),
            ("eläkeläistä", "elakela"),
            ("eritteisiin", "eriteis"),
            ("farmarien", "farmar"),
            ("haden", "had"),
            ("harmittavalla", "harmitav"),
            ("herkimmillä", "herkimm"),
            ("holmesin", "holme"),
            ("hylkii", "hylk"),
            ("ihmiskäsityksen", "ihmiskasity"),
        ] {
            assert_eq!(stem(Lang::Finnish, word), want, "{word}");
        }
    }

    #[test]
    fn hungarian_reference() {
        // Sampled from Lucene's hulighttestdata.zip, Savoy's own output.
        for (word, want) in [
            ("abazinok", "abazin"),
            ("alperesi", "alperes"),
            ("behatás", "behatas"),
            ("bizton", "bizt"),
            ("javulása", "javulas"),
            ("kedvezményekre", "kedvezmeny"),
            ("kikötõkrõl", "kik"),
            ("kiértesítés", "kiertesites"),
            ("kábeltelevíziós", "kabeltelevizios"),
            ("kövek", "kov"),
            ("lefáradtan", "lefaradt"),
            ("leütötték", "leutot"),
            ("managerbe", "manager"),
            ("megkönnyebbülve", "megkonnyebbul"),
            ("megváltoztatásakor", "megvaltoztatasakor"),
            ("másfajta", "masfajt"),
        ] {
            assert_eq!(stem(Lang::Hungarian, word), want, "{word}");
        }
    }
}
