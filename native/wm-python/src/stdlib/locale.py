"""locale: the C locale, which is the one plugins have.

Numbers and money read and print the way C does (a dot for decimals, no
grouping), encodings are UTF-8, and setlocale() accepts only "C", "POSIX"
and "" (meaning the same). normalize() turns a locale name into the full
form gettext looks for, as CPython's does for the common cases. For the
user's language, read plugin.locale from the wm package.
"""

__all__ = [
    "getlocale", "getdefaultlocale", "getpreferredencoding", "Error", "setlocale", "localeconv", "strcoll", "strxfrm",
    "str", "atof", "atoi", "format_string", "currency", "normalize", "LC_CTYPE", "LC_COLLATE", "LC_TIME",
    "LC_MONETARY", "LC_NUMERIC", "LC_ALL", "LC_MESSAGES", "CHAR_MAX", "getencoding",
]

_str = str

LC_CTYPE = 0
LC_NUMERIC = 1
LC_TIME = 2
LC_COLLATE = 3
LC_MONETARY = 4
LC_MESSAGES = 5
LC_ALL = 6
CHAR_MAX = 127


class Error(Exception):
    pass


def setlocale(category, locale=None):
    if locale is None or locale in ("", "C", "POSIX"):
        return "C"
    if isinstance(locale, tuple) and locale in ((None, None), ("C", None)):
        return "C"
    raise Error("unsupported locale setting")


def localeconv():
    return {
        "int_curr_symbol": "", "currency_symbol": "", "mon_decimal_point": "", "mon_thousands_sep": "",
        "mon_grouping": [], "positive_sign": "", "negative_sign": "", "int_frac_digits": CHAR_MAX,
        "frac_digits": CHAR_MAX, "p_cs_precedes": CHAR_MAX, "p_sep_by_space": CHAR_MAX, "n_cs_precedes": CHAR_MAX,
        "n_sep_by_space": CHAR_MAX, "p_sign_posn": CHAR_MAX, "n_sign_posn": CHAR_MAX, "decimal_point": ".",
        "thousands_sep": "", "grouping": [],
    }


def getlocale(category=LC_CTYPE):
    return (None, None)


def getdefaultlocale(envvars=("LC_ALL", "LC_CTYPE", "LANG", "LANGUAGE")):
    return (None, "UTF-8")


def getpreferredencoding(do_setlocale=True):
    return "UTF-8"


def getencoding():
    return "UTF-8"


def strcoll(a, b):
    return (a > b) - (a < b)


def strxfrm(s):
    return s


def format_string(f, val, grouping=False, monetary=False):
    return f % val


def format(percent, value, grouping=False, monetary=False, *additional):
    return percent % value


def currency(val, symbol=True, grouping=False, international=False):
    raise ValueError("Currency formatting is not possible using the 'C' locale.")


def str(val):
    return "%.12g" % val


def delocalize(string):
    return string


def localize(string, grouping=False, monetary=False):
    return string


def atof(string, func=float):
    return func(string)


def atoi(string):
    return int(string)


_aliases = {"c": "C", "posix": "C", "en": "en_US.ISO8859-1", "english": "en_EN.ISO8859-1"}


def normalize(localename):
    """A locale name in the full form gettext and setlocale use: `de_DE` gives `de_DE.ISO8859-1`."""
    code = localename.lower()
    if ":" in code:
        code = code.replace(":", ".")
    if "@" in code:
        code, modifier = code.split("@", 1)
    else:
        modifier = ""
    if "." in code:
        langname, encoding = code.split(".")[:2]
    else:
        langname, encoding = code, ""
    if code in _aliases and not modifier:
        return _aliases[code]
    if not encoding:
        if "_" not in langname:
            return localename
        lang, territory = langname.split("_", 1)
        result = f"{lang}_{territory.upper()}.ISO8859-1"
    else:
        lang, _, territory = langname.partition("_")
        enc = "UTF-8" if encoding.replace("-", "") == "utf8" else encoding.upper()
        result = f"{lang}_{territory.upper()}.{enc}" if territory else f"{lang}.{enc}"
    if modifier:
        result += "@" + modifier
    return result


def resetlocale(category=LC_ALL):
    pass
