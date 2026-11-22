//! libchewing driven the way the keyboard asks: a whole Zhuyin reading in,
//! the converted sentence and the candidates for its start out.
//!
//! The keyboard keeps the reading itself (the bopomofo the user typed, tone
//! marks included) and asks again on every keystroke, so nothing here keeps
//! state between calls except libchewing's own learning. Each call replays
//! the reading into one [`Editor`] from empty, exactly as the keys would
//! arrive on a Dai Chien keyboard, which is the only way into libchewing's
//! composition: the conversion it shows is then libchewing's own, phrase
//! grouping, user phrases and all.
//!
//! Lengths that cross back to Kotlin are counted in reading characters.
//! Every bopomofo and tone mark is one UTF-16 unit, so that is also how
//! Kotlin indexes the buffer.

use std::path::Path;
use std::sync::Arc;

use chewing::conversion::FuzzyChewingEngine;
use chewing::conversion::Interval;
use chewing::dictionary::{
    Dictionary, DictionaryInfo, DictionaryUsage, Entries, Layered, LookupStrategy, Phrase, Trie,
    UserDictionaryManager,
};
use chewing::editor::{
    AbbrevTable, BasicEditor, ConversionEngineKind, Editor, LaxUserFreqEstimate, SymbolSelector,
};
use chewing::input::keymap::{QWERTY_MAP, map_ascii};
use chewing::input::{KeyboardEvent, keycode, keysym};
use chewing::zhuyin::{Bopomofo, Syllable};

/// The longest reading libchewing is asked to hold before it would start
/// committing the front of it by itself. Far past anything the keyboard
/// composes, so it never does.
const MAX_READING: usize = 200;

/// How many alternative whole-sentence conversions are offered after the best.
const ALT_SENTENCES: usize = 2;

/// The Dai Chien (大千) key that types each bopomofo and tone mark: the
/// layout libchewing's `Standard` syllable editor reads.
fn key_for(c: char) -> Option<u8> {
    Some(match c {
        'ㄅ' => b'1', 'ㄆ' => b'q', 'ㄇ' => b'a', 'ㄈ' => b'z',
        'ㄉ' => b'2', 'ㄊ' => b'w', 'ㄋ' => b's', 'ㄌ' => b'x',
        'ㄍ' => b'e', 'ㄎ' => b'd', 'ㄏ' => b'c',
        'ㄐ' => b'r', 'ㄑ' => b'f', 'ㄒ' => b'v',
        'ㄓ' => b'5', 'ㄔ' => b't', 'ㄕ' => b'g', 'ㄖ' => b'b',
        'ㄗ' => b'y', 'ㄘ' => b'h', 'ㄙ' => b'n',
        'ㄧ' => b'u', 'ㄨ' => b'j', 'ㄩ' => b'm',
        'ㄚ' => b'8', 'ㄛ' => b'i', 'ㄜ' => b'k', 'ㄝ' => b',',
        'ㄞ' => b'9', 'ㄟ' => b'o', 'ㄠ' => b'l', 'ㄡ' => b'.',
        'ㄢ' => b'0', 'ㄣ' => b'p', 'ㄤ' => b';', 'ㄥ' => b'/',
        'ㄦ' => b'-',
        'ˊ' => b'6', 'ˇ' => b'3', 'ˋ' => b'4', '˙' => b'7',
        // The first tone is unmarked in most writing, and a space on a
        // Chewing keyboard; its mark ends the syllable the same way.
        'ˉ' => b' ',
        _ => return None,
    })
}

fn is_tone(c: char) -> bool {
    matches!(c, 'ˊ' | 'ˇ' | 'ˋ' | '˙' | 'ˉ')
}

fn key(code: keycode::Keycode, sym: keysym::Keysym) -> KeyboardEvent {
    KeyboardEvent::builder().code(code).ksym(sym).build()
}

/// One answer for a reading.
#[derive(Debug, Default, Clone, PartialEq)]
pub struct Menu {
    /// Candidate texts, best first: the whole sentence, its alternatives,
    /// then the phrases the reading can start with, longest first.
    pub texts: Vec<String>,
    /// Reading characters each candidate consumes, from the start.
    pub consumed: Vec<usize>,
    /// For a whole-sentence candidate, which of libchewing's conversions it
    /// is (0 the best); -1 for a phrase.
    pub sentence: Vec<i32>,
    /// What the composing region shows: the syllables already complete,
    /// converted, without the one still being typed.
    pub preedit: String,
    /// Where in the reading the syllable still being typed starts; the
    /// reading's length when every syllable is complete.
    pub tail: usize,
}

/// The system dictionary, read by the editor and by [Engine::toned], which
/// needs lookups the editor does not offer.
#[derive(Debug, Clone)]
struct Shared(Arc<dyn Dictionary + Send + Sync>);

impl Dictionary for Shared {
    fn lookup(&self, syllables: &[Syllable], strategy: LookupStrategy) -> Vec<Phrase> {
        self.0.lookup(syllables, strategy)
    }
    fn entries(&self) -> Entries<'_> {
        self.0.entries()
    }
    fn about(&self) -> DictionaryInfo {
        self.0.about()
    }
    fn path(&self) -> Option<&Path> {
        self.0.path()
    }
    fn set_usage(&mut self, _usage: DictionaryUsage) {
        // Set once, before it is shared.
    }
}

/// Single characters libchewing never starts a learned phrase with on its
/// own (its `is_break_word`): particles and the commonest function words.
const BREAK_WORDS: &[&str] = &[
    "是", "的", "了", "不", "也", "而", "你", "我", "他", "與", "它", "她", "其", "就", "和", "或",
    "們", "性", "員", "子", "上", "下", "中", "內", "外", "化", "者", "家", "兒", "年", "月", "日",
    "時", "分", "秒", "街", "路", "村", "在",
];

/// The tones a syllable typed without one could have: the first (unmarked)
/// and the four marks.
const TONES: [Option<Bopomofo>; 5] = [
    None,
    Some(Bopomofo::TONE2),
    Some(Bopomofo::TONE3),
    Some(Bopomofo::TONE4),
    Some(Bopomofo::TONE5),
];

/// libchewing's editor with the keyboard's options, the system dictionary
/// again for [Engine::toned], and the version of that dictionary.
pub struct Engine {
    editor: Editor,
    system: Shared,
    version: String,
}

impl std::fmt::Debug for Engine {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Engine").field("version", &self.version).finish()
    }
}

/// Where the replayed reading's syllables end, in reading characters.
struct Replay {
    ends: Vec<usize>,
    /// Start of the syllable the keys left open at the end, if any.
    open_from: Option<usize>,
    /// Whether that open syllable was then closed and landed as the last one.
    open_landed: bool,
}

impl Engine {
    /// Opens the system dictionary [dict] (built by `chewing-dictc`) with the
    /// user's learned phrases in [user], created when missing.
    pub fn open(dict: &Path, user: &Path) -> Result<Engine, String> {
        let mut system = Trie::open(dict).map_err(|e| format!("dictionary: {e}"))?;
        system.set_usage(DictionaryUsage::BuiltIn);
        let user = UserDictionaryManager::new()
            .userphrase_path(user)
            .init()
            .map_err(|e| format!("user phrases: {e}"))?;
        Ok(Engine::with_dicts(Arc::new(system), user))
    }

    /// An engine over [system], learning into [user].
    pub fn with_dicts(system: Arc<dyn Dictionary + Send + Sync>, user: Box<dyn Dictionary>) -> Engine {
        let system = Shared(system);
        let version = system.about().version;
        let mut layered = Layered::new(vec![Box::new(system.clone()), user]);
        let estimate = LaxUserFreqEstimate::max_from(layered.user_dict_mut());
        let symbols = SymbolSelector::new(&b""[..]).expect("an empty symbol table is valid");
        let mut editor = Editor::new(
            Box::new(FuzzyChewingEngine::new()),
            layered,
            estimate,
            AbbrevTable::new(),
            symbols,
        );
        editor.set_editor_options(|o| {
            // A syllable typed without its tone still converts, as the
            // keyboard's own Zhuyin does: the next initial ends it.
            o.lookup_strategy = LookupStrategy::FuzzyPartialPrefix;
            o.conversion_engine = ConversionEngineKind::FuzzyChewingEngine;
            o.auto_commit_threshold = MAX_READING;
            o.space_is_select_key = false;
            o.easy_symbol_input = false;
            o.auto_shift_cursor = false;
            o.phrase_choice_rearward = false;
            o.disable_auto_learn_phrase = false;
        });
        Engine { editor, system, version }
    }

    pub fn version(&self) -> &str {
        &self.version
    }

    /// Types [reading] into an empty editor. With [close], a syllable left
    /// open at the end is closed too (a space: libchewing's first tone, which
    /// under the fuzzy lookup matches any tone), so it converts.
    fn replay(&mut self, reading: &[char], close: bool) -> Replay {
        let editor = &mut self.editor;
        editor.clear();
        let mut ends = Vec::with_capacity(reading.len());
        let mut open_from = None;
        for (i, &c) in reading.iter().take(MAX_READING).enumerate() {
            let Some(ascii) = key_for(c) else { continue };
            let was_open = editor.entering_syllable();
            // A tone with no syllable before it marks nothing; libchewing
            // would start a toneless syllable of it.
            if is_tone(c) && !was_open {
                continue;
            }
            let syllable_before = editor.syllable_buffer();
            let before = editor.len();
            editor.process_keyevent(map_ascii(&QWERTY_MAP, ascii));
            let grew = editor.len() > before;
            let open = editor.entering_syllable();
            if grew {
                // A tone closes the syllable it follows; any other key that
                // lands a syllable began the next one (the fuzzy close), so
                // the landed one ends before it.
                let end = if open { i } else { i + 1 };
                for _ in before..editor.len() {
                    ends.push(end);
                }
            }
            open_from = if !open {
                // Closed: landed, or dropped for want of a word.
                None
            } else if grew || !was_open || !editor.syllable_buffer().starts_with(syllable_before) {
                // This key began a syllable, whether or not the one before
                // it landed.
                Some(i)
            } else {
                open_from
            };
        }
        let mut open_landed = false;
        if close && editor.entering_syllable() {
            let before = editor.len();
            editor.process_keyevent(key(keycode::KEY_SPACE, keysym::SYM_SPACE));
            for _ in before..editor.len() {
                ends.push(reading.len());
            }
            open_landed = editor.len() > before;
        }
        Replay { ends, open_from, open_landed }
    }

    /// The conversion of [reading] and up to [limit] candidates.
    pub fn convert(&mut self, reading: &str, limit: usize) -> Menu {
        let chars: Vec<char> = reading.chars().collect();
        if chars.is_empty() || limit == 0 {
            return Menu { tail: chars.len(), ..Menu::default() };
        }
        let full = self.replay(&chars, true);
        let tail = full.open_from.unwrap_or(chars.len());
        let sentence = self.editor.display();
        // What shows while typing: the sentence without the syllable still
        // being typed, which shows as its keys. One character per syllable.
        let complete = full.ends.len() - usize::from(full.open_landed);
        let preedit: String = sentence.chars().take(complete).collect();
        let mut menu = Menu { preedit, tail, ..Menu::default() };
        if full.ends.is_empty() {
            return menu;
        }
        let whole = chars.len();
        let add = |menu: &mut Menu, text: String, consumed: usize, sentence: i32| {
            if menu.texts.len() >= limit || text.is_empty() {
                return;
            }
            if menu.texts.iter().zip(&menu.consumed).any(|(t, &c)| *t == text && c == consumed) {
                return;
            }
            menu.texts.push(text);
            menu.consumed.push(consumed);
            menu.sentence.push(sentence);
        };
        add(&mut menu, sentence, whole, 0);

        // What the reading can start with, as libchewing's own candidate
        // window lists it with the cursor at the front: the longest phrase
        // first, then each shorter one down to the first character's
        // readings. Gathered before the alternative sentences, which go
        // ahead of them in the list.
        let mut phrases = Menu::default();
        self.editor.process_keyevent(key(keycode::KEY_HOME, keysym::SYM_HOME));
        if self.editor.start_selecting().is_ok() {
            loop {
                if let Ok(candidates) = self.editor.all_candidates() {
                    for text in candidates {
                        let syllables = text.chars().count().clamp(1, full.ends.len());
                        phrases.texts.push(text);
                        phrases.consumed.push(full.ends[syllables - 1]);
                    }
                }
                if phrases.texts.len() >= limit || !self.editor.has_next_selection_point() {
                    break;
                }
                if self.editor.jump_to_next_selection_point().is_err() {
                    break;
                }
            }
            let _ = self.editor.cancel_selecting();
        }

        // The next-best sentences: Tab with the cursor at the end, in libchewing.
        self.editor.process_keyevent(key(keycode::KEY_END, keysym::SYM_END));
        for alt in 1..=ALT_SENTENCES {
            self.editor.process_keyevent(key(keycode::KEY_TAB, keysym::SYM_TAB));
            let text = self.editor.display();
            if menu.texts.first() == Some(&text) {
                break;
            }
            add(&mut menu, text, whole, alt as i32);
        }
        for (text, consumed) in phrases.texts.into_iter().zip(phrases.consumed) {
            add(&mut menu, text, consumed, -1);
        }
        self.editor.clear();
        menu
    }

    /// The syllables of the first [consumed] reading characters, after a replay.
    fn leading_syllables(&self, ends: &[usize], consumed: usize) -> Vec<Syllable> {
        let count = ends.iter().take_while(|&&end| end <= consumed).count();
        let symbols = self.editor.symbols();
        symbols[..count.min(symbols.len())].iter().filter_map(|s| s.to_syllable()).collect()
    }

    /// [syllables] with the tones [phrase] is read with, where the user left
    /// them off.
    ///
    /// The keyboard lets a syllable go without its tone and libchewing finds
    /// it by prefix, but a phrase is learned under exact syllables: learned
    /// toneless, it would be a new entry beside the dictionary's own, at a
    /// new phrase's low frequency, and never found again by a reading that
    /// has the tones. So each toneless syllable takes a tone its character
    /// has in the system dictionary, preferring a combination the dictionary
    /// knows the whole phrase under.
    fn toned(&self, syllables: &[Syllable], phrase: &str) -> Vec<Syllable> {
        let chars: Vec<char> = phrase.chars().collect();
        if chars.len() != syllables.len() {
            return syllables.to_vec();
        }
        let options: Vec<Vec<Syllable>> = syllables
            .iter()
            .zip(&chars)
            .map(|(&syllable, &c)| {
                if syllable.has_tone() {
                    return vec![syllable];
                }
                let text = c.to_string();
                let found: Vec<Syllable> = TONES
                    .iter()
                    .map(|&tone| {
                        let mut toned = syllable;
                        if let Some(tone) = tone {
                            toned.update(tone);
                        }
                        toned
                    })
                    .filter(|toned| {
                        self.system
                            .lookup(&[*toned], LookupStrategy::Standard)
                            .iter()
                            .any(|p| p.as_str() == text)
                    })
                    .collect();
                if found.is_empty() { vec![syllable] } else { found }
            })
            .collect();
        let first: Vec<Syllable> = options.iter().map(|o| o[0]).collect();
        if chars.len() == 1 {
            return first;
        }
        // Few characters have more than two readings, so this stays small;
        // past the cap the first reading of each is taken.
        let mut picks = vec![0usize; options.len()];
        for _ in 0..64 {
            let candidate: Vec<Syllable> = options.iter().zip(&picks).map(|(o, &i)| o[i]).collect();
            if self
                .system
                .lookup(&candidate, LookupStrategy::Standard)
                .iter()
                .any(|p| p.as_str() == phrase)
            {
                return candidate;
            }
            let mut at = 0;
            loop {
                if at == picks.len() {
                    return first;
                }
                picks[at] += 1;
                if picks[at] < options[at].len() {
                    break;
                }
                picks[at] = 0;
                at += 1;
            }
        }
        first
    }

    fn learn(&mut self, syllables: &[Syllable], phrase: &str) -> bool {
        if syllables.is_empty() || syllables.len() != phrase.chars().count() {
            return false;
        }
        let toned = self.toned(syllables, phrase);
        self.editor.learn_phrase(&toned, phrase).is_ok()
    }

    /// Learns that the start of [reading], [consumed] characters of it, means
    /// [phrase]: libchewing raises it for those syllables, or adds it.
    pub fn learn_phrase(&mut self, reading: &str, consumed: usize, phrase: &str) -> bool {
        let chars: Vec<char> = reading.chars().collect();
        let replay = self.replay(&chars, true);
        let syllables = self.leading_syllables(&replay.ends, consumed);
        let ok = self.learn(&syllables, phrase);
        self.editor.clear();
        ok
    }

    /// Learns the whole sentence [reading] converted to, conversion
    /// [sentence] of it, as libchewing does when the user presses Enter:
    /// every phrase in it, and each run of single characters (past the
    /// particles it never starts a phrase with) as a new phrase.
    pub fn learn_sentence(&mut self, reading: &str, sentence: usize) -> bool {
        let chars: Vec<char> = reading.chars().collect();
        let replay = self.replay(&chars, true);
        if replay.ends.is_empty() {
            return false;
        }
        for _ in 0..sentence {
            self.editor.process_keyevent(key(keycode::KEY_TAB, keysym::SYM_TAB));
        }
        let intervals: Vec<Interval> = self.editor.intervals().collect();
        let syllables: Vec<Option<Syllable>> =
            self.editor.symbols().iter().map(|s| s.to_syllable()).collect();
        let span = |interval: &Interval| -> Option<Vec<Syllable>> {
            syllables.get(interval.start..interval.end)?.iter().copied().collect()
        };
        let mut learned = Vec::<(Vec<Syllable>, String)>::new();
        let mut keep = |s: Vec<Syllable>, text: String| {
            if !learned.iter().any(|(_, t)| *t == text) {
                learned.push((s, text));
            }
        };
        for interval in intervals.iter().filter(|it| it.is_phrase && it.len() > 1) {
            if let Some(s) = span(interval) {
                keep(s, interval.text.to_string());
            }
        }
        let mut run = (Vec::new(), String::new());
        for interval in &intervals {
            let single = interval.is_phrase && interval.len() == 1 && !BREAK_WORDS.contains(&&*interval.text);
            match (single, span(interval)) {
                (true, Some(s)) => {
                    run.0.extend(s);
                    run.1.push_str(&interval.text);
                }
                _ => {
                    if run.1.chars().count() > 1 {
                        keep(std::mem::take(&mut run.0), std::mem::take(&mut run.1));
                    }
                    run = (Vec::new(), String::new());
                }
            }
        }
        if run.1.chars().count() > 1 {
            keep(run.0, run.1);
        }
        self.editor.clear();
        let mut ok = false;
        for (s, text) in learned {
            ok |= self.learn(&s, &text);
        }
        ok
    }

    /// Writes the learned phrases out.
    pub fn sync(&mut self) -> bool {
        self.editor.user_dict().flush().is_ok()
    }

    /// Forgets every learned phrase, on disk too.
    pub fn clear_learned(&mut self) -> bool {
        let user = self.editor.user_dict();
        let entries: Vec<_> = user.entries().collect();
        for (syllables, phrase) in entries {
            let _ = user.remove_phrase(&syllables, phrase.as_str());
        }
        user.flush().is_ok()
    }

    /// How many phrases the user dictionary holds.
    pub fn learned_count(&mut self) -> usize {
        self.editor.user_dict().entries().count()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chewing::dictionary::TrieBuf;

    /// ㄋㄧˇ ㄏㄠˇ (你好), ㄓㄨㄥ ㄨㄣˊ (中文) and a few readings of each
    /// character, as a real trie: the fuzzy lookup reads only tries, not a
    /// TrieBuf's unflushed entries.
    fn engine() -> Engine {
        use chewing::dictionary::{DictionaryBuilder, TrieBuilder};
        let s = |r: &str| r.parse::<Syllable>().unwrap();
        let mut builder = TrieBuilder::new();
        for (reading, text, freq) in [
            ("ㄋㄧˇ", "你", 100), ("ㄋㄧˇ", "妳", 50), ("ㄋㄧˇ", "擬", 10),
            ("ㄏㄠˇ", "好", 100), ("ㄏㄠˋ", "號", 80),
            ("ㄓㄨㄥ", "中", 100), ("ㄓㄨㄥ", "終", 40),
            ("ㄨㄣˊ", "文", 100), ("ㄨㄣˊ", "聞", 30),
        ] {
            builder.insert(&[s(reading)], (text, freq).into()).unwrap();
        }
        builder.insert(&[s("ㄋㄧˇ"), s("ㄏㄠˇ")], ("你好", 500).into()).unwrap();
        builder.insert(&[s("ㄓㄨㄥ"), s("ㄨㄣˊ")], ("中文", 500).into()).unwrap();
        let mut bytes = Vec::new();
        builder.write(&mut bytes).unwrap();
        let mut system = Trie::new(&bytes[..]).unwrap();
        system.set_usage(DictionaryUsage::BuiltIn);
        let mut user = TrieBuf::new_in_memory();
        user.set_usage(DictionaryUsage::User);
        Engine::with_dicts(Arc::new(system), Box::new(user))
    }

    #[test]
    fn the_whole_reading_converts_as_a_sentence_first() {
        let mut e = engine();
        let menu = e.convert("ㄋㄧˇㄏㄠˇㄓㄨㄥㄨㄣˊ", 20);
        assert_eq!(menu.texts[0], "你好中文");
        assert_eq!(menu.consumed[0], 12);
        assert_eq!(menu.sentence[0], 0);
        assert_eq!(menu.tail, 12);
        assert_eq!(menu.preedit, "你好中文");
    }

    #[test]
    fn the_phrases_at_the_front_follow_with_what_they_consume() {
        let mut e = engine();
        let menu = e.convert("ㄋㄧˇㄏㄠˇㄓㄨㄥㄨㄣˊ", 20);
        let at = menu.texts.iter().position(|t| t == "你好").unwrap();
        assert_eq!(menu.consumed[at], 6);
        assert_eq!(menu.sentence[at], -1);
        let ni = menu.texts.iter().position(|t| t == "你").unwrap();
        assert_eq!(menu.consumed[ni], 3);
        assert!(at < ni, "the longer phrase is offered first");
        assert!(menu.texts.contains(&"妳".to_string()));
    }

    #[test]
    fn a_syllable_without_its_tone_still_converts() {
        let mut e = engine();
        // No tone marks at all: the next initial closes each syllable.
        let menu = e.convert("ㄋㄧㄏㄠ", 20);
        assert_eq!(menu.texts[0], "你好");
        assert_eq!(menu.consumed[0], 4);
        // While typing, the last syllable is still open and shows as keys.
        assert_eq!(menu.tail, 2);
        assert_eq!(menu.preedit.chars().count(), 1);
    }

    #[test]
    fn learning_a_phrase_moves_it_up() {
        let mut e = engine();
        assert!(e.learn_phrase("ㄋㄧˇ", 3, "擬"));
        let menu = e.convert("ㄋㄧˇ", 20);
        assert_eq!(menu.texts[0], "擬");
        assert_eq!(e.learned_count(), 1);
        assert!(e.clear_learned());
        assert_eq!(e.learned_count(), 0);
    }

    #[test]
    fn learning_a_sentence_keeps_its_new_phrase() {
        let mut e = engine();
        // 好 and 文 are each single characters here: together a new phrase.
        assert!(e.learn_sentence("ㄏㄠˇㄨㄣˊ", 0));
        assert_eq!(e.learned_count(), 1);
        let menu = e.convert("ㄏㄠˇㄨㄣˊ", 20);
        assert_eq!(menu.texts[0], "好文");
        // 你 is one of the particles a learned run never takes.
        assert!(!e.learn_sentence("ㄋㄧˇ", 0));
    }

    #[test]
    fn a_phrase_picked_without_tones_is_learned_under_its_own() {
        let mut e = engine();
        assert!(e.learn_phrase("ㄋㄧ", 2, "擬"));
        // Found again with the tone typed, so it went in under ㄋㄧˇ.
        let menu = e.convert("ㄋㄧˇ", 20);
        assert_eq!(menu.texts[0], "擬");
    }

    #[test]
    fn nothing_to_convert_gives_nothing() {
        let mut e = engine();
        assert!(e.convert("", 10).texts.is_empty());
        assert!(e.convert("ˊˊ", 10).texts.is_empty());
    }
}

/// Against the real dictionary: `CHEWING_DICT=<chewing-….dat> cargo test -- --ignored`.
#[cfg(test)]
mod real {
    use super::*;
    use chewing::dictionary::TrieBuf;

    fn engine() -> Option<Engine> {
        let path = std::env::var("CHEWING_DICT").ok()?;
        let mut system = Trie::open(path).unwrap();
        system.set_usage(DictionaryUsage::BuiltIn);
        let mut user = TrieBuf::new_in_memory();
        user.set_usage(DictionaryUsage::User);
        Some(Engine::with_dicts(Arc::new(system), Box::new(user)))
    }

    #[test]
    #[ignore]
    fn sentences_convert_as_taiwan_writes_them() {
        let Some(mut e) = engine() else { return };
        for (reading, expected) in [
            ("ㄋㄧˇㄏㄠˇ", "你好"),
            ("ㄨㄛˇㄕˋㄊㄞˊㄨㄢㄖㄣˊ", "我是台灣人"),
            ("ㄐㄧㄣㄊㄧㄢㄊㄧㄢㄑㄧˋㄏㄣˇㄏㄠˇ", "今天天氣很好"),
            // No tones at all.
            ("ㄐㄧㄣㄊㄧㄢㄊㄧㄢㄑㄧㄏㄣㄏㄠ", "今天天氣很好"),
        ] {
            let menu = e.convert(reading, 30);
            eprintln!("{reading}: {:?}", menu.texts.iter().zip(&menu.consumed).take(12).collect::<Vec<_>>());
            assert_eq!(menu.texts[0], expected, "{reading}");
        }
        let started = std::time::Instant::now();
        for _ in 0..200 {
            e.convert("ㄐㄧㄣㄊㄧㄢㄊㄧㄢㄑㄧˋㄏㄣˇㄏㄠˇㄨㄛˇㄕˋㄊㄞˊㄨㄢㄖㄣˊ", 100);
        }
        eprintln!("convert, 11 syllables: {:?} each", started.elapsed() / 200);
    }
}
