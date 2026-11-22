//! English: step 1 of Porter's algorithm (M. F. Porter, "An algorithm for
//! suffix stripping", 1980), written from the published description.
//! Plurals, `-ed` and `-ing` with the doubled consonant and the silent `e`
//! put back, and a final `y` after a vowel-bearing stem: "running" and
//! "run", "hoping" and "hope", "studies" and "study" meet. The later steps
//! (`-ation`, `-ness`, `-ful`) join words search should keep apart.

use super::Word;

fn is_cons(w: &Word, i: usize) -> bool {
    match w.s[i] {
        'a' | 'e' | 'i' | 'o' | 'u' => false,
        'y' => i == 0 || !is_cons(w, i - 1),
        _ => true,
    }
}

/// Porter's m: the number of vowel-consonant runs in the first `len`.
fn measure(w: &Word, len: usize) -> usize {
    let mut m = 0;
    let mut i = 0;
    while i < len && is_cons(w, i) {
        i += 1;
    }
    loop {
        while i < len && !is_cons(w, i) {
            i += 1;
        }
        if i >= len {
            return m;
        }
        while i < len && is_cons(w, i) {
            i += 1;
        }
        m += 1;
    }
}

fn has_vowel(w: &Word, len: usize) -> bool {
    (0..len).any(|i| !is_cons(w, i))
}

fn double_cons(w: &Word, len: usize) -> bool {
    len >= 2 && w.s[len - 1] == w.s[len - 2] && is_cons(w, len - 1)
}

/// Consonant, vowel, consonant at the end, the last not w, x or y.
fn cvc(w: &Word, len: usize) -> bool {
    len >= 3
        && is_cons(w, len - 3)
        && !is_cons(w, len - 2)
        && is_cons(w, len - 1)
        && !matches!(w.s[len - 1], 'w' | 'x' | 'y')
}

pub fn stem(w: &mut Word) {
    if w.len <= 2 || !w.s[..w.len].iter().all(|c| c.is_ascii_lowercase()) {
        return;
    }
    // Step 1a.
    if w.ends("sses") || w.ends("ies") {
        w.drop_end(2);
    } else if w.ends("s") && !w.ends("ss") {
        w.drop_end(1);
    }
    // Step 1b.
    let mut cut = false;
    if w.ends("eed") {
        if measure(w, w.len - 3) > 0 {
            w.drop_end(1);
        }
    } else if w.ends("ed") && has_vowel(w, w.len - 2) {
        w.drop_end(2);
        cut = true;
    } else if w.ends("ing") && has_vowel(w, w.len - 3) {
        w.drop_end(3);
        cut = true;
    }
    if cut {
        if w.ends("at") || w.ends("bl") || w.ends("iz") {
            w.replace_end(0, "e");
        } else if double_cons(w, w.len) && !matches!(w.at_end(1), 'l' | 's' | 'z') {
            w.drop_end(1);
        } else if measure(w, w.len) == 1 && cvc(w, w.len) {
            w.replace_end(0, "e");
        }
    }
    // Step 1c.
    if w.ends("y") && has_vowel(w, w.len - 1) {
        w.set_end(1, 'i');
    }
}

#[cfg(test)]
mod tests {
    use crate::stem::{stem, Lang};

    #[test]
    fn porter_step_one() {
        // Porter's own examples for step 1.
        for (word, want) in [
            ("caresses", "caress"),
            ("ponies", "poni"),
            ("ties", "ti"),
            ("caress", "caress"),
            ("cats", "cat"),
            ("feed", "feed"),
            ("agreed", "agree"),
            ("plastered", "plaster"),
            ("bled", "bled"),
            ("motoring", "motor"),
            ("sing", "sing"),
            ("conflated", "conflate"),
            ("troubled", "trouble"),
            ("sized", "size"),
            ("hopping", "hop"),
            ("tanned", "tan"),
            ("falling", "fall"),
            ("hissing", "hiss"),
            ("fizzed", "fizz"),
            ("failing", "fail"),
            ("filing", "file"),
            ("happy", "happi"),
            ("sky", "sky"),
        ] {
            assert_eq!(stem(Lang::English, word), want, "{word}");
        }
    }

    #[test]
    fn forms_meet() {
        for group in [
            &["run", "runs", "running"][..],
            &["chat", "chats", "chatting", "chatted"],
            &["hope", "hoped", "hoping", "hopes"],
            &["study", "studies", "studied"],
            &["copy", "copies", "copied"],
            &["email", "emails", "emailed"],
        ] {
            let first = stem(Lang::English, group[0]);
            for w in group {
                assert_eq!(stem(Lang::English, w), first, "{w}");
            }
        }
    }

    #[test]
    fn short_and_non_ascii_words_are_kept() {
        assert_eq!(stem(Lang::English, "is"), "is");
        assert_eq!(stem(Lang::English, "cafés"), "cafés");
    }
}
