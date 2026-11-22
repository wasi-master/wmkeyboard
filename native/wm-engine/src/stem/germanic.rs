//! German, Swedish and Norwegian: ports of Apache Lucene's
//! `GermanLightStemmer`, `SwedishLightStemmer` and `NorwegianLightStemmer`
//! (Apache-2.0), which follow Jacques Savoy's UniNE light stemmers
//! (Copyright (c) 2005, Jacques Savoy, BSD-3-Clause). Norwegian runs with
//! both the Bokmål and the Nynorsk endings, as one keyboard serves both.

use super::Word;

pub fn german(w: &mut Word) {
    w.map(|c| match c {
        'ä' | 'à' | 'á' | 'â' => 'a',
        'ö' | 'ò' | 'ó' | 'ô' => 'o',
        'ï' | 'ì' | 'í' | 'î' => 'i',
        'ü' | 'ù' | 'ú' | 'û' => 'u',
        _ => c,
    });
    german_step1(w);
    german_step2(w);
}

fn st_ending(c: char) -> bool {
    matches!(c, 'b' | 'd' | 'f' | 'g' | 'h' | 'k' | 'l' | 'm' | 'n' | 't')
}

fn german_step1(w: &mut Word) {
    let len = w.len;
    if len > 5 && w.ends("ern") {
        return w.drop_end(3);
    }
    if len > 4 && w.at_end(2) == 'e' && matches!(w.at_end(1), 'm' | 'n' | 'r' | 's') {
        return w.drop_end(2);
    }
    if len > 3 && w.at_end(1) == 'e' {
        return w.drop_end(1);
    }
    if len > 3 && w.at_end(1) == 's' && st_ending(w.at_end(2)) {
        w.drop_end(1);
    }
}

fn german_step2(w: &mut Word) {
    let len = w.len;
    if len > 5 && w.ends("est") {
        return w.drop_end(3);
    }
    if len > 4 && w.at_end(2) == 'e' && matches!(w.at_end(1), 'r' | 'n') {
        return w.drop_end(2);
    }
    if len > 4 && w.ends("st") && st_ending(w.at_end(3)) {
        w.drop_end(2);
    }
}

pub fn swedish(w: &mut Word) {
    if w.len > 4 && w.at_end(1) == 's' {
        w.drop_end(1);
    }
    let len = w.len;
    if len > 7 && (w.ends("elser") || w.ends("heten")) {
        return w.drop_end(5);
    }
    if len > 6 && w.ends_any(&["arne", "erna", "ande", "else", "aste", "orna", "aren"]) {
        return w.drop_end(4);
    }
    if len > 5 && w.ends_any(&["are", "ast", "het"]) {
        return w.drop_end(3);
    }
    if len > 4 && w.ends_any(&["ar", "er", "or", "en", "at", "te", "et"]) {
        return w.drop_end(2);
    }
    if len > 3 && matches!(w.at_end(1), 't' | 'a' | 'e' | 'n') {
        w.drop_end(1);
    }
}

pub fn norwegian(w: &mut Word) {
    if w.len > 4 && w.at_end(1) == 's' {
        w.drop_end(1);
    }
    let len = w.len;
    if len > 7 && w.ends_any(&["heter", "heten", "heita"]) {
        return w.drop_end(5);
    }
    if len > 8 && w.ends_any(&["heiter", "leiken", "leikar"]) {
        return w.drop_end(6);
    }
    if len > 5 && w.ends_any(&["dom", "het"]) {
        return w.drop_end(3);
    }
    if len > 6 && w.ends_any(&["heit", "semd", "leik"]) {
        return w.drop_end(4);
    }
    if len > 7 && w.ends_any(&["elser", "elsen"]) {
        return w.drop_end(5);
    }
    if len > 6 && w.ends_any(&["ende", "ande", "else", "este", "aste", "eren", "aren"]) {
        return w.drop_end(4);
    }
    if len > 5 && w.ends_any(&["ere", "are", "est", "ast", "ene", "ane"]) {
        return w.drop_end(3);
    }
    if len > 4 && w.ends_any(&["er", "en", "et", "ar", "st", "te"]) {
        return w.drop_end(2);
    }
    if len > 3 && matches!(w.at_end(1), 'a' | 'e' | 'n') {
        w.drop_end(1);
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
    fn german() {
        for (word, want) in [("häuser", "haus"), ("haus", "haus"), ("kinder", "kind"), ("kindern", "kind")] {
            assert_eq!(stem(Lang::German, word), want, "{word}");
        }
        same(Lang::German, &["Bücher", "Buch", "Buches"]);
        same(Lang::German, &["schnelle", "schnellen", "schneller", "schnelles"]);
        same(Lang::German, &["Tage", "Tag", "Tagen"]);
    }

    #[test]
    fn swedish() {
        same(Lang::Swedish, &["flickor", "flicka", "flickorna"]);
        same(Lang::Swedish, &["bilar", "bil", "bilen"]);
        same(Lang::Swedish, &["huset", "hus", "husen"]);
    }

    #[test]
    fn norwegian() {
        same(Lang::Norwegian, &["bilene", "bil", "bilen", "biler"]);
        same(Lang::Norwegian, &["kaker", "kake"]);
        same(Lang::Norwegian, &["hemmeligheten", "hemmelig"]);
        same(Lang::Norwegian, &["gutane", "gut", "gutar"]);
    }

    #[test]
    fn german_reference() {
        // Sampled from Lucene's delighttestdata.zip, Savoy's own output.
        for (word, want) in [
            ("aalglatten", "aalglatt"),
            ("aufseher", "aufseh"),
            ("besprechen", "besprech"),
            ("dünste", "dun"),
            ("erröte", "errot"),
            ("gebräuntes", "gebraunt"),
            ("gottesdienst", "gottesdien"),
            ("hinübergehen", "hinubergeh"),
            ("kosten", "kost"),
            ("mikrofons", "mikrofon"),
            ("pirsche", "pirsch"),
            ("schlachtheeren", "schlachthe"),
            ("staune", "staun"),
            ("ufhangen", "ufhang"),
            ("verneigte", "verneigt"),
            ("wenige", "wenig"),
        ] {
            assert_eq!(stem(Lang::German, word), want, "{word}");
        }
    }

    #[test]
    fn swedish_reference() {
        // Sampled from Lucene's svlighttestdata.zip, Savoy's own output.
        for (word, want) in [
            ("aaele", "aael"),
            ("besegrade", "besegrad"),
            ("demla", "deml"),
            ("fläcken", "fläck"),
            ("försedda", "försedd"),
            ("handlar", "handl"),
            ("inres", "inr"),
            ("kräver", "kräv"),
            ("lövmassor", "lövmass"),
            ("närheten", "när"),
            ("promenerande", "promener"),
            ("sidenkramhandlare", "sidenkramhandl"),
            ("sockersöta", "sockersöt"),
            ("sällskapandet", "sällskapand"),
            ("tänderna", "tänd"),
            ("vilat", "vil"),
        ] {
            assert_eq!(stem(Lang::Swedish, word), want, "{word}");
        }
    }
}
