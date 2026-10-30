"""What the model and the engine agree on: how text becomes tokens.

The Rust reader (`wm-engine/src/lm.rs`) applies the same rules to the words
before the caret, so a token here is a token there. Keep the two in step.

A word is lower-cased, stripped of leading and trailing punctuation, and
kept whole with its inner apostrophes and hyphens. A word with a digit in
it is the one `<num>` token. Everything the vocabulary does not hold is
`<unk>`. A sentence is `<s> words... </s>` after its conditioning prefix:
one register token and one field token (see `SPECIALS`).
"""
import re
import unicodedata

PAD, BOS, EOS, UNK, NUM = "<pad>", "<s>", "</s>", "<unk>", "<num>"
REGISTERS = ["<reg:neutral>", "<reg:casual>", "<reg:formal>"]
FIELDS = ["<field:other>", "<field:chat>", "<field:email>", "<field:search>"]
# Up to four languages in one model; the header names which is which.
LANGS = ["<lang:0>", "<lang:1>", "<lang:2>", "<lang:3>"]
SPECIALS = [PAD, BOS, EOS, UNK, NUM] + REGISTERS + FIELDS + LANGS
assert len(SPECIALS) == 16

# Characters trimmed off either end of a word: punctuation and symbols, not
# letters, digits, apostrophes or hyphens.
_EDGE = re.compile(r"^[^\w'’\-]+|[^\w'’\-]+$", re.UNICODE)
_DIGIT = re.compile(r"\d")
_INNER_JUNK = re.compile(r"[^\w'’\-]", re.UNICODE)


def word_key(token: str) -> str | None:
    """One whitespace-separated token as the engine files it, or None."""
    t = unicodedata.normalize("NFC", token).lower().replace("’", "'")
    t = _EDGE.sub("", t)
    if not t or _INNER_JUNK.search(t):
        return None
    if _DIGIT.search(t):
        return NUM
    if t.strip("'-") == "":
        return None
    return t


def tokenize(sentence: str) -> list[str]:
    out = []
    for raw in sentence.split():
        w = word_key(raw)
        if w is not None:
            out.append(w)
    return out


# Subword pieces for words the vocabulary lacks. A word the model does not
# hold as a token is spelled as one start piece ("\u2581ab", the word's first
# letters) followed by continuation pieces ("##cd"), greedy longest match from
# the left, so a name or a rare word still gets a probability and still
# carries context, where it used to be `<unk>`. The reader in lm.rs applies
# the same two tables; the markers never collide with a word token, since
# word_key strips them.
START, CONT = "\u2581", "##"
MAX_PIECE = 5


class Pieces:
    def __init__(self, starts: set[str], conts: set[str]):
        self.starts, self.conts = starts, conts

    @staticmethod
    def build(oov_counts: dict[str, int], n: int, alphabet=()) -> "Pieces":
        """Pieces from the words outside the vocabulary: every letter both
        ways, then the commonest substrings as start and continuation pieces
        until `n` pieces are chosen. `alphabet` adds letters the outside words
        may lack, so that any word of the corpus can be spelled."""
        import collections
        starts, conts = collections.Counter(), collections.Counter()
        letters = set(alphabet)
        for w, c in oov_counts.items():
            letters.update(w)
            for i in range(len(w)):
                for j in range(i + 1, min(len(w), i + MAX_PIECE) + 1):
                    (starts if i == 0 else conts)[w[i:j]] += c
        chosen_s, chosen_c = set(letters), set(letters)
        pool = [(c, "s", p) for p, c in starts.items() if len(p) > 1] + [(c, "c", p) for p, c in conts.items() if len(p) > 1]
        pool.sort(reverse=True)
        for c, kind, piece in pool:
            if len(chosen_s) + len(chosen_c) >= n:
                break
            (chosen_s if kind == "s" else chosen_c).add(piece)
        return Pieces(chosen_s, chosen_c)

    def tokens(self) -> list[str]:
        return [START + p for p in sorted(self.starts)] + [CONT + p for p in sorted(self.conts)]

    def encode(self, word: str) -> list[str] | None:
        """The word's pieces, or None when a letter has no piece at all."""
        out, i = [], 0
        while i < len(word):
            table, mark = (self.starts, START) if i == 0 else (self.conts, CONT)
            for j in range(min(len(word), i + MAX_PIECE), i, -1):
                if word[i:j] in table:
                    out.append(mark + word[i:j])
                    i = j
                    break
            else:
                return None
        return out
