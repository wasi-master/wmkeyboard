//! Russian, Bulgarian and Czech: ports of Apache Lucene's
//! `RussianLightStemmer` (after Jacques Savoy's UniNE stemmer, Copyright (c)
//! 2005, Jacques Savoy, BSD-3-Clause), `BulgarianStemmer` (Preslav Nakov,
//! "Searching Strategies for the Bulgarian Language") and `CzechStemmer`
//! (Ljiljana Dolamic and Jacques Savoy, "Indexing and stemming approaches
//! for the Czech language"), all Apache-2.0.

use super::Word;

pub fn russian(w: &mut Word) {
    // Lucene leaves ё to a filter before it; search folds it here.
    w.map(|c| if c == 'ё' { 'е' } else { c });
    russian_case(w);
    if w.len > 3 {
        match w.at_end(1) {
            'ь' | 'и' => w.drop_end(1),
            'н' if w.at_end(2) == 'н' => w.drop_end(1),
            _ => {}
        }
    }
}

fn russian_case(w: &mut Word) {
    let len = w.len;
    if len > 6 && w.ends_any(&["иями", "оями"]) {
        return w.drop_end(4);
    }
    if len > 5
        && w.ends_any(&[
            "иям", "иях", "оях", "ями", "оям", "оьв", "ами", "его", "ему", "ери", "ими", "ого", "ому", "ыми",
            "оев",
        ])
    {
        return w.drop_end(3);
    }
    if len > 4
        && w.ends_any(&[
            "ая", "яя", "ях", "юю", "ах", "ею", "их", "ия", "ию", "ьв", "ою", "ую", "ям", "ых", "ея", "ам", "ем",
            "ей", "ём", "ев", "ий", "им", "ое", "ой", "ом", "ов", "ые", "ый", "ым", "ми",
        ])
    {
        return w.drop_end(2);
    }
    if len > 3 && matches!(w.at_end(1), 'а' | 'е' | 'и' | 'о' | 'у' | 'й' | 'ы' | 'я' | 'ь') {
        w.drop_end(1);
    }
}

pub fn bulgarian(w: &mut Word) {
    if w.len < 4 {
        return;
    }
    if w.len > 5 && w.ends("ища") {
        return w.drop_end(3);
    }
    bulgarian_article(w);
    bulgarian_plural(w);
    if w.len > 3 {
        if w.ends("я") {
            w.drop_end(1);
        }
        if w.ends("а") || w.ends("о") || w.ends("е") {
            w.drop_end(1);
        }
    }
    if w.len > 4 && w.ends("ен") {
        w.set_end(2, 'н');
        w.drop_end(1);
    }
    if w.len > 5 && w.at_end(2) == 'ъ' {
        w.set_end(2, w.at_end(1));
        w.drop_end(1);
    }
}

fn bulgarian_article(w: &mut Word) {
    let len = w.len;
    if len > 6 && w.ends("ият") {
        return w.drop_end(3);
    }
    if len > 5 && w.ends_any(&["ът", "то", "те", "та", "ия"]) {
        return w.drop_end(2);
    }
    if len > 4 && w.ends("ят") {
        w.drop_end(2);
    }
}

fn bulgarian_plural(w: &mut Word) {
    let len = w.len;
    if len > 6 {
        if w.ends("овци") || w.ends("ове") {
            return w.drop_end(3);
        }
        if w.ends("еве") {
            w.set_end(3, 'й');
            return w.drop_end(2);
        }
    }
    if len > 5 {
        if w.ends("ища") {
            return w.drop_end(3);
        }
        if w.ends("та") {
            return w.drop_end(2);
        }
        if w.ends("ци") {
            w.set_end(2, 'к');
            return w.drop_end(1);
        }
        if w.ends("зи") {
            w.set_end(2, 'г');
            return w.drop_end(1);
        }
        if w.at_end(3) == 'е' && w.at_end(1) == 'и' {
            w.set_end(3, 'я');
            return w.drop_end(1);
        }
    }
    if len > 4 {
        if w.ends("си") {
            w.set_end(2, 'х');
            return w.drop_end(1);
        }
        if w.ends("и") {
            w.drop_end(1);
        }
    }
}

pub fn czech(w: &mut Word) {
    czech_case(w);
    if w.len > 5 && w.ends_any(&["ov", "in", "ův"]) {
        w.drop_end(2);
    }
    if w.len > 0 {
        czech_norm(w);
    }
}

fn czech_case(w: &mut Word) {
    let len = w.len;
    if len > 7 && w.ends("atech") {
        return w.drop_end(5);
    }
    if len > 6 && w.ends_any(&["ětem", "etem", "atům"]) {
        return w.drop_end(4);
    }
    if len > 5
        && w.ends_any(&[
            "ech", "ich", "ích", "ého", "ěmi", "emi", "ému", "ěte", "ete", "ěti", "eti", "ího", "iho", "ími", "ímu",
            "imu", "ách", "ata", "aty", "ých", "ama", "ami", "ové", "ovi", "ými",
        ])
    {
        return w.drop_end(3);
    }
    if len > 4 && w.ends_any(&["em", "es", "ém", "ím", "ům", "at", "ám", "os", "us", "ým", "mi", "ou"]) {
        return w.drop_end(2);
    }
    if len > 3 && matches!(w.at_end(1), 'a' | 'e' | 'i' | 'o' | 'u' | 'ů' | 'y' | 'á' | 'é' | 'í' | 'ý' | 'ě') {
        w.drop_end(1);
    }
}

fn czech_norm(w: &mut Word) {
    if w.ends("čt") {
        w.set_end(2, 'c');
        return w.set_end(1, 'k');
    }
    if w.ends("št") {
        w.set_end(2, 's');
        return w.set_end(1, 'k');
    }
    match w.at_end(1) {
        'c' | 'č' => return w.set_end(1, 'k'),
        'z' | 'ž' => return w.set_end(1, 'h'),
        _ => {}
    }
    if w.len > 1 && w.at_end(2) == 'e' {
        w.set_end(2, w.at_end(1));
        return w.drop_end(1);
    }
    if w.len > 2 && w.at_end(2) == 'ů' {
        w.set_end(2, 'o');
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
    fn russian() {
        same(Lang::Russian, &["книга", "книги", "книгу", "книгой", "книгами"]);
        same(Lang::Russian, &["новый", "новая", "новое", "новые", "нового"]);
        same(Lang::Russian, &["ёлка", "елки"]);
    }

    #[test]
    fn bulgarian() {
        // Lucene's TestBulgarianStemmer.
        for group in [
            &["град", "града", "градът", "градове", "градовете"][..],
            &["народ", "народа", "народът", "народи", "народите", "народе"],
            &["път", "пътя", "пътят", "пътища", "пътищата"],
            &["градец", "градеца", "градецът"],
            &["градовце", "градовцете"],
            &["дядо", "дядото", "дядовци", "дядовците"],
            &["мъж", "мъжа", "мъже", "мъжете", "мъжо"],
        ] {
            same(Lang::Bulgarian, group);
        }
        assert_eq!(stem(Lang::Bulgarian, "градовцете"), "градовц");
    }

    #[test]
    fn czech() {
        // Lucene's TestCzechStemmer masculine animate and feminine forms.
        same(Lang::Czech, &["pán", "páni", "pánové", "pána", "pánů", "pánovi", "pánům", "pány", "páne", "pánech", "pánem"]);
        same(Lang::Czech, &["žena", "ženy", "ženě", "žen", "ženám", "ženu", "ženo", "ženách", "ženou", "ženami"]);
        same(Lang::Czech, &["město", "města", "městu", "měst", "městům", "městem", "městě", "městech", "městy"]);
    }

    #[test]
    fn russian_reference() {
        // Sampled from Lucene's rulighttestdata.zip, Savoy's own output.
        for (word, want) in [
            ("абиссинию", "абиссин"),
            ("вернусь", "вернус"),
            ("вышедшего", "вышедш"),
            ("дранье", "дран"),
            ("званием", "зван"),
            ("коровьими", "коров"),
            ("молитвой", "молитв"),
            ("непоколебимыми", "непоколебим"),
            ("ордою", "орд"),
            ("плелся", "плелс"),
            ("послушными", "послушн"),
            ("прожила", "прожил"),
            ("религиозно", "религиозн"),
            ("смеющимися", "смеющимис"),
            ("таинственного", "таинствен"),
            ("уснуть", "уснут"),
        ] {
            assert_eq!(stem(Lang::Russian, word), want, "{word}");
        }
    }
}
