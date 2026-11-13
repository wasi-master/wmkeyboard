"""Your plugin in the user's language.

    from wm import _, ngettext

    ui.button(_("Look up"), on_click=look_up)
    ui.label(ngettext("{n} day", "{n} days", len(days)))

Write English and wrap it. The archive's locales/<tag>.json maps that English
to the text in one language: {"Look up": "খুঁজুন"}. The keyboard picks the
file for the user's language (pt-BR, then pt), and English stands for
anything a file leaves out. Named values fill {braces} after translation, so
a translation may put them in another order.
"""

import _wm

_strings = _wm.strings if isinstance(_wm.strings, dict) else {}

# The tag of the file in use ("bn", "pt-BR"), or None for English.
language = _strings.get("@lang")

# The user's first language as Android reports it, translated or not.
locale = _wm.context.get("locale", "en") if isinstance(_wm.context, dict) else "en"


def _fill(text, english, values):
    if not values:
        return text
    try:
        return text.format(**values)
    except (KeyError, IndexError, ValueError):
        # A translation that names a value the code does not pass: English is
        # better than an error in the panel.
        return english.format(**values)


def gettext(text, **values):
    """[text] in the user's language, with {name} filled from [values]."""
    found = _strings.get(text)
    return _fill(found if isinstance(found, str) and found else text, text, values)


_ = gettext


def _category(n):
    """The CLDR plural category of the whole number [n] in the file's language."""
    lang = (language or "en").split("-")[0]
    n = abs(int(n))
    m10, m100 = n % 10, n % 100
    if lang in ("zh", "ja", "ko", "th", "vi", "id", "ms", "my", "km", "lo"):
        return "other"
    if lang in ("fr", "hi", "bn", "gu", "fa", "am", "kn", "mr", "zu") or (lang == "pt" and language != "pt-PT"):
        return "one" if n in (0, 1) else "other"
    if lang in ("ru", "uk", "be", "sr", "hr", "bs"):
        if m10 == 1 and m100 != 11:
            return "one"
        if 2 <= m10 <= 4 and not 12 <= m100 <= 14:
            return "few"
        return "many"
    if lang == "pl":
        if n == 1:
            return "one"
        if 2 <= m10 <= 4 and not 12 <= m100 <= 14:
            return "few"
        return "many"
    if lang in ("cs", "sk"):
        return "one" if n == 1 else "few" if 2 <= n <= 4 else "other"
    if lang == "ar":
        if n == 0:
            return "zero"
        if n in (1, 2):
            return "one" if n == 1 else "two"
        if 3 <= m100 <= 10:
            return "few"
        if 11 <= m100 <= 99:
            return "many"
        return "other"
    if lang == "he":
        return "one" if n == 1 else "two" if n == 2 else "other"
    if lang == "ro":
        return "one" if n == 1 else "few" if n == 0 or 2 <= m100 <= 19 else "other"
    if lang == "lv":
        if m10 == 0 or 11 <= m100 <= 19:
            return "zero"
        return "one" if m10 == 1 else "other"
    if lang == "lt":
        if 11 <= m100 <= 19:
            return "other"
        return "one" if m10 == 1 else "few" if m10 >= 2 else "other"
    if lang == "sl":
        return "one" if m100 == 1 else "two" if m100 == 2 else "few" if m100 in (3, 4) else "other"
    return "one" if n == 1 else "other"


def ngettext(singular, plural, n, **values):
    """[singular] or [plural] for the count [n], in the user's language.
    {n} is filled with the count, and any other {name} from [values]."""
    values = dict(values)
    values.setdefault("n", n)
    english = singular if n == 1 else plural
    found = _strings.get(singular)
    if isinstance(found, dict):
        text = found.get(_category(n)) or found.get("other") or found.get("one")
        if text:
            return _fill(text, english, values)
    elif isinstance(found, str) and found and n == 1:
        return _fill(found, english, values)
    return _fill(english, english, values)
