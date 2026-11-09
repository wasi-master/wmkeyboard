"""What the punctuation model and the engine agree on.

The sentence before the caret becomes tokens exactly the way the context
model's does: `tools/wmlm/common.py` lower-cases a word, trims the
punctuation off its ends, keeps inner apostrophes and hyphens, turns a word
with a digit into `<num>`, and spells a word the vocabulary lacks in pieces
(`▁ab` then `##cd`, greedy longest match). The Rust reader
(`wm-engine/src/punct.rs`) applies the same rules, so a token here is a token
there. Keep the three in step.

The model reads `<s> word word ...` with nothing else in front: no register,
field or language token. One model serves every language it was trained on
and tells them apart from the words.

Its answer is one of four marks, in this order, which the file's header
repeats and the reader checks:

  0  `.`  the sentence ends as a statement
  1  `?`  as a question
  2  `!`  as an exclamation
  3  `,`  the sentence goes on after a clause break

The keyboard draws the first three on the period key and the fourth only
when asked to; a script's own marks (danda, the reversed question mark) are
the keyboard's business, not the model's.
"""
import os
import re
import sys
import unicodedata

# The context model's tokenisation, imported from beside this folder, which is
# searched last: its common.py and model.py must not shadow this folder's
# punct_common.py and punct_model.py.
sys.path.append(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "wmlm"))
from common import BOS, PAD, UNK, NUM, Pieces, START, CONT, MAX_PIECE, word_key, tokenize  # noqa: E402,F401

LABELS = [".", "?", "!", ","]
PERIOD, QUESTION, EXCLAIM, COMMA = range(4)
SPECIALS = [PAD, BOS, UNK, NUM]
assert len(SPECIALS) == 4

# Every mark a sentence may end with, mapped to the label it stands for. The
# fullwidth and Arabic forms are kept so a sentence in those scripts is a
# training example too; the model never sees the mark, only its class.
END_MARKS = {
    ".": PERIOD, "。": PERIOD, "।": PERIOD, "۔": PERIOD, "։": PERIOD, "።": PERIOD, "။": PERIOD, "។": PERIOD,
    "?": QUESTION, "？": QUESTION, "؟": QUESTION, "՞": QUESTION,
    "!": EXCLAIM, "！": EXCLAIM, "¡": EXCLAIM,
}
CLAUSE_MARKS = {",": COMMA, "،": COMMA, "、": COMMA, "，": COMMA}

# A sentence this model trains on is at least this many and at most that
# many words. Fragments of one word carry too little, and the keyboard only
# ever hands it the last `ctx` tokens of a sentence anyway.
MIN_WORDS = 2
MAX_WORDS = 40

_TRAIL = re.compile(r"[\s\"'”’»)\]}*_~]+$")
_LEAD = re.compile(r"^[\s\"'“‘«(\[{*_~\-–—¿¡]+")
_INNER_END = re.compile(r"[.!?。？！؟।۔]\s+\S")
_URLISH = re.compile(r"https?://|www\.|@\w+\.\w|\.(com|org|net|io)\b", re.IGNORECASE)
_DOTTED_ABBREV = re.compile(r"\b[A-Za-z]\.[A-Za-z]\.")


def clean(line: str) -> str:
    """One line of a corpus as a sentence: trailing quotes and brackets taken
    off so the end mark is the last character, leading dashes and quotes
    taken off the front."""
    s = unicodedata.normalize("NFC", line.strip())
    s = _TRAIL.sub("", s)
    s = _LEAD.sub("", s)
    return s


def end_label(sentence: str) -> tuple[str, int] | None:
    """The sentence without its final mark, and the mark's label, or None
    when it does not end in one mark this model learns, or holds another
    sentence end inside it (two sentences on one line), or looks like an
    address.

    `?!`, `!?`, `!!!` and `...` are read by their first character: a run of
    marks is one strong mark, and an ellipsis is a trailing-off full stop.
    """
    s = clean(sentence)
    if not s or _URLISH.search(s) or _DOTTED_ABBREV.search(s):
        return None
    end = len(s)
    while end > 0 and (s[end - 1] in END_MARKS or s[end - 1] in "…"):
        end -= 1
    if end == len(s) or end == 0:
        return None
    run = s[end:]
    first = run[0] if run[0] != "…" else "."
    label = END_MARKS.get(first)
    if label is None:
        return None
    # "..." is a trailing off, which is a statement; "?..." still asks.
    body = s[:end].rstrip()
    if not body or body[-1] in END_MARKS or body[-1] in CLAUSE_MARKS or body[-1] in ":;":
        return None
    if _INNER_END.search(body):
        return None
    n = len(body.split())
    if n < MIN_WORDS or n > MAX_WORDS:
        return None
    return body, label


def comma_prefixes(sentence: str) -> list[str]:
    """The prefixes of the sentence that end right before a comma: a clause
    the writer went on from. Only prefixes of at least MIN_WORDS words, and
    never one whose last character is itself a mark."""
    s = clean(sentence)
    out = []
    for i, ch in enumerate(s):
        if ch in CLAUSE_MARKS and i > 0:
            prefix = s[:i].rstrip()
            if not prefix or prefix[-1] in END_MARKS or prefix[-1] in CLAUSE_MARKS or prefix[-1] in ":;":
                continue
            # A comma inside a number (1,000) is not a clause break.
            if s[i - 1].isdigit() and i + 1 < len(s) and s[i + 1].isdigit():
                continue
            if _INNER_END.search(prefix):
                continue
            n = len(prefix.split())
            if MIN_WORDS <= n <= MAX_WORDS:
                out.append(prefix)
    return out


def tokens_of(text: str) -> list[str]:
    """The words the model reads for a sentence so far."""
    return tokenize(text)
