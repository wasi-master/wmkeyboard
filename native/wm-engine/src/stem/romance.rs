//! French, Spanish, Portuguese and Italian: ports of Apache Lucene's
//! `FrenchLightStemmer`, `SpanishLightStemmer`, `PortugueseLightStemmer` and
//! `ItalianLightStemmer` (Apache-2.0), which follow Jacques Savoy's UniNE
//! light stemmers (Copyright (c) 2005, Jacques Savoy, BSD-3-Clause).
//! Galician is our own plural step, the kind Lucene's minimal stemmer reads
//! from its RSLP table.

use super::Word;

fn fold_vowels(c: char) -> char {
    match c {
        'à' | 'á' | 'â' | 'ä' => 'a',
        'ò' | 'ó' | 'ô' | 'ö' => 'o',
        'è' | 'é' | 'ê' | 'ë' => 'e',
        'ù' | 'ú' | 'û' | 'ü' => 'u',
        'ì' | 'í' | 'î' | 'ï' => 'i',
        _ => c,
    }
}

pub fn french(w: &mut Word) {
    if w.len > 5 && w.at_end(1) == 'x' {
        if w.at_end(3) == 'a' && w.at_end(2) == 'u' && w.at_end(4) != 'e' {
            w.set_end(2, 'l');
        }
        w.drop_end(1);
    }
    if w.len > 3 && w.at_end(1) == 'x' {
        w.drop_end(1);
    }
    if w.len > 3 && w.at_end(1) == 's' {
        w.drop_end(1);
    }
    let len = w.len;
    if len > 9 && w.ends("issement") {
        w.replace_end(6, "");
        w.set_end(1, 'r');
        return french_norm(w);
    }
    if len > 8 && w.ends("issant") {
        w.drop_end(4);
        w.set_end(1, 'r');
        return french_norm(w);
    }
    if len > 6 && w.ends("ement") {
        w.drop_end(4);
        if w.len > 3 && w.ends("ive") {
            w.drop_end(1);
            w.set_end(1, 'f');
        }
        return french_norm(w);
    }
    if len > 11 && w.ends("ficatrice") {
        w.drop_end(5);
        w.set_end(2, 'e');
        w.set_end(1, 'r');
        return french_norm(w);
    }
    if len > 10 && w.ends("ficateur") {
        w.drop_end(4);
        w.set_end(2, 'e');
        w.set_end(1, 'r');
        return french_norm(w);
    }
    if len > 9 && w.ends("catrice") {
        w.drop_end(3);
        w.set_end(4, 'q');
        w.set_end(3, 'u');
        w.set_end(2, 'e');
        return french_norm(w);
    }
    if len > 8 && w.ends("cateur") {
        w.drop_end(2);
        w.set_end(4, 'q');
        w.set_end(3, 'u');
        w.set_end(2, 'e');
        w.set_end(1, 'r');
        return french_norm(w);
    }
    if len > 8 && w.ends("atrice") {
        w.drop_end(4);
        w.set_end(2, 'e');
        w.set_end(1, 'r');
        return french_norm(w);
    }
    if len > 7 && w.ends("ateur") {
        w.drop_end(3);
        w.set_end(2, 'e');
        w.set_end(1, 'r');
        return french_norm(w);
    }
    if len > 6 && w.ends("trice") {
        w.drop_end(1);
        w.set_end(3, 'e');
        w.set_end(2, 'u');
        w.set_end(1, 'r');
    }
    let len = w.len;
    if len > 5 && w.ends("ième") {
        w.drop_end(4);
        return french_norm(w);
    }
    if len > 7 && w.ends("teuse") {
        w.drop_end(2);
        w.set_end(1, 'r');
        return french_norm(w);
    }
    if len > 6 && w.ends("teur") {
        w.drop_end(1);
        w.set_end(1, 'r');
        return french_norm(w);
    }
    if len > 5 && w.ends("euse") {
        w.drop_end(2);
        return french_norm(w);
    }
    if len > 8 && w.ends("ère") {
        w.drop_end(1);
        w.set_end(2, 'e');
        return french_norm(w);
    }
    if len > 7 && w.ends("ive") {
        w.drop_end(1);
        w.set_end(1, 'f');
        return french_norm(w);
    }
    if len > 4 && (w.ends("folle") || w.ends("molle")) {
        w.drop_end(2);
        w.set_end(1, 'u');
        return french_norm(w);
    }
    if len > 9 && w.ends("nnelle") {
        w.drop_end(5);
        return french_norm(w);
    }
    if len > 9 && w.ends("nnel") {
        w.drop_end(3);
        return french_norm(w);
    }
    if len > 4 && w.ends("ète") {
        w.drop_end(1);
        w.set_end(2, 'e');
    }
    if w.len > 8 && w.ends("ique") {
        w.drop_end(4);
    }
    let len = w.len;
    if len > 8 && w.ends("esse") {
        w.drop_end(3);
        return french_norm(w);
    }
    if len > 7 && w.ends("inage") {
        w.drop_end(3);
        return french_norm(w);
    }
    if len > 9 && w.ends("isation") {
        w.drop_end(7);
        if w.len > 5 && w.ends("ual") {
            w.set_end(2, 'e');
        }
        return french_norm(w);
    }
    if len > 9 && w.ends("isateur") {
        w.drop_end(7);
        return french_norm(w);
    }
    if len > 8 && (w.ends("ation") || w.ends("ition")) {
        w.drop_end(5);
        return french_norm(w);
    }
    french_norm(w)
}

fn french_norm(w: &mut Word) {
    if w.len > 4 {
        w.map(|c| match c {
            'à' | 'á' | 'â' => 'a',
            'ô' => 'o',
            'è' | 'é' | 'ê' => 'e',
            'ù' | 'û' => 'u',
            'î' => 'i',
            'ç' => 'c',
            _ => c,
        });
        let mut ch = w.s[0];
        let mut i = 1;
        while i < w.len {
            if w.s[i] == ch && ch.is_alphabetic() {
                w.delete(i);
            } else {
                ch = w.s[i];
                i += 1;
            }
        }
    }
    if w.len > 4 && w.ends("ie") {
        w.drop_end(2);
    }
    if w.len > 4 {
        if w.at_end(1) == 'r' {
            w.drop_end(1);
        }
        if w.at_end(1) == 'e' {
            w.drop_end(1);
        }
        if w.at_end(1) == 'e' {
            w.drop_end(1);
        }
        if w.at_end(1) == w.at_end(2) && w.at_end(1).is_alphabetic() {
            w.drop_end(1);
        }
    }
}

pub fn spanish(w: &mut Word) {
    if w.len < 5 {
        return;
    }
    w.map(fold_vowels);
    match w.at_end(1) {
        'o' | 'a' | 'e' => w.drop_end(1),
        's' => {
            if w.at_end(2) == 'e' && w.at_end(3) == 's' && w.at_end(4) == 'e' {
                w.drop_end(2);
            } else if w.at_end(2) == 'e' && w.at_end(3) == 'c' {
                w.set_end(3, 'z');
                w.drop_end(2);
            } else if matches!(w.at_end(2), 'o' | 'a' | 'e') {
                w.drop_end(2);
            }
        }
        _ => {}
    }
}

pub fn portuguese(w: &mut Word) {
    if w.len < 4 {
        return;
    }
    portuguese_suffix(w);
    if w.len > 3 && w.at_end(1) == 'a' {
        portuguese_feminine(w);
    }
    if w.len > 4 && matches!(w.at_end(1), 'e' | 'a' | 'o') {
        w.drop_end(1);
    }
    w.map(|c| match c {
        'ã' => 'a',
        'õ' => 'o',
        'ç' => 'c',
        _ => fold_vowels(c),
    });
}

fn portuguese_suffix(w: &mut Word) {
    let len = w.len;
    if len > 4 && w.ends("es") && matches!(w.at_end(3), 'r' | 's' | 'l' | 'z') {
        return w.drop_end(2);
    }
    if len > 3 && w.ends("ns") {
        w.set_end(2, 'm');
        return w.drop_end(1);
    }
    if len > 4 && (w.ends("eis") || w.ends("éis")) {
        w.set_end(3, 'e');
        w.set_end(2, 'l');
        return w.drop_end(1);
    }
    if len > 4 && w.ends("ais") {
        w.set_end(2, 'l');
        return w.drop_end(1);
    }
    if len > 4 && w.ends("óis") {
        w.set_end(3, 'o');
        w.set_end(2, 'l');
        return w.drop_end(1);
    }
    if len > 4 && w.ends("is") {
        return w.set_end(1, 'l');
    }
    if len > 3 && (w.ends("ões") || w.ends("ães")) {
        w.drop_end(1);
        w.set_end(2, 'ã');
        return w.set_end(1, 'o');
    }
    if len > 6 && w.ends("mente") {
        return w.drop_end(5);
    }
    if len > 3 && w.at_end(1) == 's' {
        w.drop_end(1);
    }
}

fn portuguese_feminine(w: &mut Word) {
    let len = w.len;
    if len > 7 && (w.ends("inha") || w.ends("iaca") || w.ends("eira")) {
        return w.set_end(1, 'o');
    }
    if len > 6 {
        if w.ends_any(&["osa", "ica", "ida", "ada", "iva", "ama"]) {
            return w.set_end(1, 'o');
        }
        if w.ends("ona") {
            w.set_end(3, 'ã');
            w.set_end(2, 'o');
            return w.drop_end(1);
        }
        if w.ends("ora") {
            return w.drop_end(1);
        }
        if w.ends("esa") {
            w.set_end(3, 'ê');
            return w.drop_end(1);
        }
        if w.ends("na") {
            w.set_end(1, 'o');
        }
    }
}

pub fn italian(w: &mut Word) {
    if w.len < 6 {
        return;
    }
    w.map(fold_vowels);
    let before = w.at_end(2);
    match w.at_end(1) {
        'e' | 'i' if before == 'i' || before == 'h' => w.drop_end(2),
        'a' | 'o' if before == 'i' => w.drop_end(2),
        'e' | 'i' | 'a' | 'o' => w.drop_end(1),
        _ => {}
    }
}

/// Galician plurals onto the singular: "cans" and "can", "animais" and
/// "animal", "mulleres" and "muller", "luces" and "luz".
pub fn galician(w: &mut Word) {
    w.map(fold_vowels);
    if w.len < 4 || w.at_end(1) != 's' {
        return;
    }
    if w.ends("ns") {
        w.drop_end(1);
    } else if w.len > 4 && w.ends_any(&["ais", "eis", "ois", "uis"]) {
        // "-Vis" onto "-Vl": the vowel stays, the i becomes the l.
        w.drop_end(1);
        w.set_end(1, 'l');
    } else if w.len > 4 && w.ends("ces") {
        w.drop_end(2);
        w.set_end(1, 'z');
    } else if w.len > 4 && w.ends_any(&["res", "les", "ses", "zes"]) {
        w.drop_end(2);
    } else {
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
    fn french() {
        // Lucene's TestFrenchLightStemmer cases.
        for (word, want) in [
            ("chevaux", "cheval"),
            ("cheval", "cheval"),
            ("hiboux", "hibou"),
            ("hibou", "hibou"),
            ("chantés", "chant"),
            ("chanter", "chant"),
            ("chante", "chant"),
            ("baronnes", "baron"),
            ("barons", "baron"),
            ("baron", "baron"),
            ("peaux", "peau"),
            ("anneaux", "aneau"),
            ("neveux", "neveu"),
            ("affreux", "afreu"),
            ("affreuse", "afreu"),
            ("investissement", "investi"),
            ("investir", "investi"),
            ("assourdissant", "asourdi"),
            ("assourdir", "asourdi"),
            ("pratiquement", "pratiqu"),
            ("pratique", "pratiqu"),
            ("administrativement", "administratif"),
            ("administratif", "administratif"),
            ("justificatrice", "justifi"),
            ("justificateur", "justifi"),
            ("justifier", "justifi"),
            ("educatrice", "eduqu"),
            ("eduquer", "eduqu"),
            ("communicateur", "comuniqu"),
            ("communiquer", "comuniqu"),
            ("accompagnatrice", "acompagn"),
            ("accompagnateur", "acompagn"),
            ("administrateur", "administr"),
            ("administrer", "administr"),
            ("productrice", "product"),
            ("producteur", "product"),
            ("acheteuse", "achet"),
            ("acheteur", "achet"),
            ("planteur", "plant"),
            ("plante", "plant"),
            ("poreuse", "poreu"),
            ("poreux", "poreu"),
            ("plieuse", "plieu"),
            ("bijoutière", "bijouti"),
            ("bijoutier", "bijouti"),
            ("caissière", "caisi"),
            ("caissier", "caisi"),
            ("abrasive", "abrasif"),
            ("abrasif", "abrasif"),
            ("folle", "fou"),
            ("fou", "fou"),
            ("personnelle", "person"),
            ("personne", "person"),
            ("complète", "complet"),
            ("complet", "complet"),
            ("aromatique", "aromat"),
            ("faiblesse", "faibl"),
            ("faible", "faibl"),
            ("patinage", "patin"),
            ("patin", "patin"),
            ("sonorisation", "sono"),
            ("ritualisation", "rituel"),
            ("rituel", "rituel"),
            ("nomination", "nomin"),
            ("disposition", "dispos"),
            ("dispose", "dispos"),
        ] {
            assert_eq!(stem(Lang::French, word), want, "{word}");
        }
    }

    #[test]
    fn spanish() {
        for (word, want) in [
            ("chicas", "chic"),
            ("chica", "chic"),
            ("chicos", "chic"),
            ("lápices", "lapiz"),
            ("lápiz", "lapiz"),
            ("intereses", "interes"),
            ("casa", "casa"),
        ] {
            assert_eq!(stem(Lang::Spanish, word), want, "{word}");
        }
        same(Lang::Spanish, &["perros", "perro", "perra"]);
    }

    #[test]
    fn portuguese() {
        same(Lang::Portuguese, &["canções", "canção"]);
        same(Lang::Portuguese, &["animais", "animal"]);
        same(Lang::Portuguese, &["homens", "homem"]);
        same(Lang::Portuguese, &["bonitos", "bonito", "bonitas", "bonita"]);
        same(Lang::Portuguese, &["famosa", "famoso", "famosos"]);
        same(Lang::Portuguese, &["papéis", "papel"]);
        assert_eq!(stem(Lang::Portuguese, "sol"), "sol");
    }

    #[test]
    fn italian() {
        same(Lang::Italian, &["ragazzo", "ragazzi", "ragazza", "ragazze"]);
        // Under six letters a word is kept: "amica", "amiche" stay apart.
        assert_eq!(stem(Lang::Italian, "amica"), "amica");
        same(Lang::Italian, &["camicia", "camicie"]);
        assert_eq!(stem(Lang::Italian, "città"), "città");
    }

    #[test]
    fn galician() {
        same(Lang::Galician, &["cans", "can"]);
        same(Lang::Galician, &["animais", "animal"]);
        same(Lang::Galician, &["papeis", "papel"]);
        same(Lang::Galician, &["mulleres", "muller"]);
        same(Lang::Galician, &["luces", "luz"]);
        same(Lang::Galician, &["casas", "casa"]);
        same(Lang::Galician, &["camións", "camión"]);
    }

    #[test]
    fn french_reference() {
        // Sampled from Lucene's frlighttestdata.zip, Savoy's own output.
        for (word, want) in [
            ("abaissait", "abaisait"),
            ("apprenant", "aprenant"),
            ("bonaventure", "bonaventur"),
            ("coeurs", "coeu"),
            ("curés", "curé"),
            ("disparussent", "disparusent"),
            ("ennius", "eniu"),
            ("femmelette", "femelet"),
            ("héroïques", "heroïqu"),
            ("jérôme", "jerom"),
            ("meublées", "meubl"),
            ("pardonnées", "pardon"),
            ("préparé", "prepar"),
            ("régla", "regla"),
            ("sauveurs", "sauveu"),
            ("tellement", "tele"),
        ] {
            assert_eq!(stem(Lang::French, word), want, "{word}");
        }
    }

    #[test]
    fn spanish_reference() {
        // Sampled from Lucene's eslighttestdata.zip, Savoy's own output.
        for (word, want) in [
            ("aarón", "aaron"),
            ("anotó", "anot"),
            ("bernabe", "bernab"),
            ("circo", "circ"),
            ("cordillera", "cordiller"),
            ("desplegarán", "desplegaran"),
            ("entenderá", "entender"),
            ("filtros", "filtr"),
            ("ibargüengoitia", "ibarguengoiti"),
            ("jurisdicción", "jurisdiccion"),
            ("mexicas", "mexic"),
            ("oscuras", "oscur"),
            ("preferible", "preferibl"),
            ("reflexión", "reflexion"),
            ("sentado", "sentad"),
            ("toledo", "toled"),
        ] {
            assert_eq!(stem(Lang::Spanish, word), want, "{word}");
        }
    }

    #[test]
    fn portuguese_reference() {
        // Sampled from Lucene's ptlighttestdata.zip, Savoy's own output.
        for (word, want) in [
            ("aacho", "aach"),
            ("aparências", "aparenci"),
            ("bilíngues", "bilingu"),
            ("chuteiras", "chuteir"),
            ("cópula", "copul"),
            ("destruição", "destruica"),
            ("entregando", "entregand"),
            ("fazermos", "fazerm"),
            ("havíamos", "haviam"),
            ("ironicamente", "ironic"),
            ("masculinas", "masculin"),
            ("oceano", "ocean"),
            ("pochê", "poche"),
            ("recebe", "receb"),
            ("seleto", "selet"),
            ("título", "titul"),
        ] {
            assert_eq!(stem(Lang::Portuguese, word), want, "{word}");
        }
    }

    #[test]
    fn italian_reference() {
        // Sampled from Lucene's itlighttestdata.zip, Savoy's own output.
        for (word, want) in [
            ("abakoumova", "abakoumov"),
            ("anziano", "anzian"),
            ("bevette", "bevett"),
            ("codignoni", "codignon"),
            ("costitutivi", "costitutiv"),
            ("dragaggio", "dragagg"),
            ("franche", "franc"),
            ("incolti", "incolt"),
            ("lazzarini", "lazzarin"),
            ("movimenti", "moviment"),
            ("perlomeno", "perlomen"),
            ("prudenza", "prudenz"),
            ("rimbuca", "rimbuc"),
            ("sciocco", "sciocc"),
            ("spaventosi", "spaventos"),
            ("togliendoli", "togliendol"),
        ] {
            assert_eq!(stem(Lang::Italian, word), want, "{word}");
        }
    }
}
