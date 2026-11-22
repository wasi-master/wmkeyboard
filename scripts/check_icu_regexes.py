#!/usr/bin/env python3
"""Compile every regex literal in the shipped Kotlin and Java sources with ICU.

Android's java.util.regex is ICU, not the JDK engine the unit tests run on, and
the two disagree: ICU rejects a bare `}` outside a character class (`#(\\d+)}`,
`\\{x}`) that the JDK reads as a literal. A pattern like that passes every JVM
test and then throws PatternSyntaxException on the phone, and in a `val` of an
object or companion it is an ExceptionInInitializerError on first use.

So this compiles them with the real thing: libicucore on macOS, the system
libicui18n on Linux. It reads the literals at `Regex(...)`, `"...".toRegex()`,
`Pattern.compile(...)`, `"...".toPattern()` and Kotlin's `matches("...")`,
joins `"a" + "b"` and `"a" + NAME` chains, and stands a `1` in for each `$name`
or `${...}` template and each identifier in a chain. Patterns built wholly at
run time (a user's find/replace, clipboard rules) are out of reach; those call
sites catch the exception themselves.

    python3 scripts/check_icu_regexes.py            # every src/main under the repo
    python3 scripts/check_icu_regexes.py PATH...    # only these directories or files
    python3 scripts/check_icu_regexes.py -v         # also list the non-literal call sites

Exits 1 when ICU rejects a pattern.
"""
import ctypes
import glob
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

ERROR_NAMES = {
    66305: 'U_REGEX_RULE_SYNTAX',
    66306: 'U_REGEX_INVALID_STATE',
    66307: 'U_REGEX_BAD_ESCAPE_SEQUENCE',
    66308: 'U_REGEX_PROPERTY_SYNTAX',
    66309: 'U_REGEX_UNIMPLEMENTED',
    66310: 'U_REGEX_MISMATCHED_PAREN',
    66311: 'U_REGEX_NUMBER_TOO_BIG',
    66312: 'U_REGEX_BAD_INTERVAL',
    66313: 'U_REGEX_MAX_LT_MIN',
    66314: 'U_REGEX_INVALID_BACK_REF',
    66315: 'U_REGEX_INVALID_FLAG',
    66316: 'U_REGEX_LOOK_BEHIND_LIMIT',
    66317: 'U_REGEX_SET_CONTAINS_STRING',
    66319: 'U_REGEX_MISSING_CLOSE_BRACKET',
    66320: 'U_REGEX_INVALID_RANGE',
}

UREGEX_CASE_INSENSITIVE = 2
UREGEX_COMMENTS = 4


def load_icu():
    """(uregex_open, uregex_close) from the platform's ICU."""
    if sys.platform == 'darwin':
        lib = ctypes.CDLL('/usr/lib/libicucore.dylib')
        return lib.uregex_open, lib.uregex_close
    candidates = sorted(
        glob.glob('/usr/lib/*/libicui18n.so.*') + glob.glob('/usr/lib/libicui18n.so.*') + glob.glob('/usr/lib64/libicui18n.so.*'),
        key=len,
    )
    for path in candidates:
        lib = ctypes.CDLL(path)
        version = re.search(r'\.so\.(\d+)', path).group(1)
        for suffix in ('', '_' + version):
            try:
                return getattr(lib, 'uregex_open' + suffix), getattr(lib, 'uregex_close' + suffix)
            except AttributeError:
                continue
    sys.exit('check_icu_regexes: no ICU library found (install libicu)')


class ParseError(ctypes.Structure):
    _fields_ = [('line', ctypes.c_int32), ('offset', ctypes.c_int32),
                ('pre', ctypes.c_uint16 * 16), ('post', ctypes.c_uint16 * 16)]


_open, _close = load_icu()
_open.restype = ctypes.c_void_p
_open.argtypes = [ctypes.c_void_p, ctypes.c_int32, ctypes.c_uint32, ctypes.c_void_p, ctypes.POINTER(ctypes.c_int)]
_close.argtypes = [ctypes.c_void_p]


def icu_error(pattern, flags=0):
    """ICU's complaint about [pattern], or None when it compiles."""
    data = pattern.encode('utf-16-le')
    buf = ctypes.create_string_buffer(data)
    err = ctypes.c_int(0)
    parse = ParseError()
    handle = _open(buf, len(data) // 2, flags, ctypes.byref(parse), ctypes.byref(err))
    if handle:
        _close(handle)
    if err.value > 0:
        return f'{ERROR_NAMES.get(err.value, err.value)} at offset {parse.offset}'
    return None


def skip_template(src, j):
    """Index just past the `}` closing a `${` whose body starts at [j]."""
    depth, n = 1, len(src)
    while j < n and depth:
        c = src[j]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
        elif c == '"':
            if src.startswith('"""', j):
                j = src.find('"""', j + 3) + 3
                continue
            k = j + 1
            while k < n and src[k] != '"':
                k += 2 if src[k] == '\\' else 1
            j = k + 1
            continue
        j += 1
    return j


def string_literals(src, kotlin):
    """(start, end, raw?, body) for each string literal, comments skipped."""
    i, n = 0, len(src)
    while i < n:
        if src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        c = src[i]
        if c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == '\\' else 1
            i = j + 1
            continue
        if kotlin and src.startswith('"""', i):
            j = i + 3
            while j < n:
                if src.startswith('${', j):
                    j = skip_template(src, j + 2)
                    continue
                if src.startswith('"""', j):
                    while j + 3 < n and src[j + 3] == '"':
                        j += 1
                    break
                j += 1
            yield i, j + 3, True, src[i + 3:j]
            i = j + 3
            continue
        if c == '"':
            j = i + 1
            while j < n and src[j] not in '"\n':
                if src[j] == '\\':
                    j += 2
                    continue
                if kotlin and src.startswith('${', j):
                    j = skip_template(src, j + 2)
                    continue
                j += 1
            yield i, j + 1, False, src[i + 1:j]
            i = j + 1
            continue
        i += 1


ESCAPES = {'t': '\t', 'n': '\n', 'r': '\r', 'b': '\b', 'f': '\f', '0': '\0',
           '\\': '\\', '"': '"', "'": "'", '$': '$'}


def resolve(body, raw, kotlin):
    """The pattern text a literal holds, with `1` for each template."""
    out, i, n = [], 0, len(body)
    while i < n:
        c = body[i]
        if kotlin and c == '$':
            if body.startswith("${'$'}", i):
                out.append('$')
                i += 6
                continue
            if body.startswith('${', i):
                out.append('1')
                i = skip_template(body, i + 2)
                continue
            m = re.match(r'\$[A-Za-z_]\w*', body[i:])
            if m:
                out.append('1')
                i += m.end()
                continue
        if not raw and c == '\\' and i + 1 < n:
            e = body[i + 1]
            if e == 'u':
                out.append(chr(int(body[i + 2:i + 6], 16)))
                i += 6
                continue
            out.append(ESCAPES.get(e, '\\' + e))
            i += 2
            continue
        out.append(c)
        i += 1
    return ''.join(out)


# What sits right before a literal that is a regex. Kotlin's String.split,
# replace and replaceFirst take plain text, so those count in Java only.
BEFORE_KT = re.compile(r'(?:\bRegex|\bPattern\.compile|\bmatches)\s*\(\s*(?:pattern\s*=\s*)?$')
BEFORE_JAVA = re.compile(r'(?:\bPattern\.compile|\.matches|\.replaceAll|\.replaceFirst|\.split)\s*\(\s*$')
AFTER = re.compile(r'^\s*\.\s*(?:toRegex|toPattern)\s*\(')
CALL = re.compile(r'\bRegex\s*\(|\bPattern\.compile\s*\(')
CHAIN_PLUS = re.compile(r'\s*\+\s*')
CHAIN_NAME = re.compile(r'[A-Za-z_][\w.]*(?:\([^()]*\))?')
UNSUPPORTED_FLAG = re.compile(r'\b(?:Pattern|RegexOption)\.(UNICODE_CHARACTER_CLASS|CANON_EQ)\b')


def check_file(path):
    """(rejected, non_literal_sites, literals_checked) for one source file."""
    kotlin = path.endswith('.kt')
    with open(path, encoding='utf-8', errors='replace') as f:
        src = f.read()
    lits = list(string_literals(src, kotlin))
    starts = {lit[0]: k for k, lit in enumerate(lits)}
    before_re = BEFORE_KT if kotlin else BEFORE_JAVA
    rejected, seen, checked = [], set(), 0

    for k, (s, e, raw, body) in enumerate(lits):
        if s in seen:
            continue
        if not (before_re.search(src[max(0, s - 80):s]) or AFTER.match(src[e:e + 40])):
            continue
        pattern, end = resolve(body, raw, kotlin), e
        while True:
            plus = CHAIN_PLUS.match(src, end)
            if not plus:
                break
            nxt = plus.end()
            if nxt in starts:
                k2 = starts[nxt]
                pattern += resolve(lits[k2][3], lits[k2][2], kotlin)
                seen.add(nxt)
                end = lits[k2][1]
                continue
            name = CHAIN_NAME.match(src, nxt)
            if not name:
                break
            pattern += '1'
            end = name.end()
        seen.add(s)
        checked += 1
        tail = src[end:end + 200].split('\n)')[0]
        if 'LITERAL' in tail.split(')')[0]:
            continue
        flags = (UREGEX_COMMENTS if 'COMMENTS' in tail else 0) | (UREGEX_CASE_INSENSITIVE if 'IGNORE_CASE' in tail else 0)
        error = icu_error(pattern, flags)
        if error:
            rejected.append((path, src.count('\n', 0, s) + 1, error, pattern))

    # Flags Android's Pattern refuses outright, whatever the pattern: it
    # throws IllegalArgumentException / UnsupportedOperationException, so a
    # static regex built with one fails as ExceptionInInitializerError (#1398).
    for m in UNSUPPORTED_FLAG.finditer(src):
        line_start = src.rfind('\n', 0, m.start()) + 1
        if src[line_start:m.start()].lstrip().startswith(('//', '*', '/*')):
            continue
        rejected.append((path, src.count('\n', 0, m.start()) + 1,
                         m.group(1) + ' is not supported by Android', m.group(0)))

    non_literal = []
    for m in CALL.finditer(src):
        rest = src[m.end():m.end() + 120].lstrip()
        if not rest.startswith('"') and not rest.startswith(')'):
            non_literal.append((path, src.count('\n', 0, m.start()) + 1, rest.split('\n')[0][:70]))
    return rejected, non_literal, checked


def source_files(roots):
    for root in roots:
        if os.path.isfile(root):
            yield root
            continue
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames if d not in ('build', '.gradle', '.git', 'node_modules')]
            for name in filenames:
                if name.endswith(('.kt', '.java')):
                    yield os.path.join(dirpath, name)


def default_roots():
    """Every module's src/main: the code that ships."""
    roots = []
    for dirpath, dirnames, _ in os.walk(REPO):
        dirnames[:] = [d for d in dirnames if not d.startswith('.') and d not in ('build', 'node_modules', 'docs', 'play', 'native')]
        if os.path.basename(dirpath) == 'src' and 'main' in dirnames:
            roots.append(os.path.join(dirpath, 'main'))
            dirnames[:] = []
    return sorted(roots)


# Generated data files whose cells are compiled as regexes on the phone:
# (path under the repo, the tab-separated columns that hold patterns).
DATA_TABLES = [
    # tools/phonemeta/build_phone_meta.py: intl, npp, lead, general, fixed, mobile, other.
    ('app/src/main/assets/phone/phone_meta.tsv', (2, 4, 6, 10, 11, 12, 13)),
]


def check_table(path, columns):
    """Every pattern cell of one data table, as (rejected, checked)."""
    rejected, checked = [], 0
    with open(path, encoding='utf-8') as f:
        for number, line in enumerate(f, 1):
            if line.startswith('#'):
                continue
            cells = line.rstrip('\n').split('\t')
            for index in columns:
                pattern = cells[index] if index < len(cells) else ''
                if not pattern or pattern == '=':
                    continue
                checked += 1
                error = icu_error(pattern)
                if error:
                    rejected.append((path, number, error, pattern))
    return rejected, checked


def main(argv):
    verbose = '-v' in argv
    named = [a for a in argv if a != '-v']
    roots = named or default_roots()
    rejected, non_literal, checked = [], [], 0
    for path in source_files(roots):
        r, nl, c = check_file(path)
        rejected += r
        non_literal += nl
        checked += c
    for table, columns in DATA_TABLES:
        path = os.path.join(REPO, table)
        if os.path.exists(path) and (not named or any(os.path.abspath(path).startswith(os.path.abspath(n)) for n in named)):
            r, c = check_table(path, columns)
            rejected += r
            checked += c
    rel = lambda p: os.path.relpath(p, REPO)
    for path, line, error, pattern in rejected:
        print(f'{rel(path)}:{line}: ICU rejects this regex ({error})\n    {pattern}')
    if verbose:
        for path, line, arg in non_literal:
            print(f'{rel(path)}:{line}: built at run time, not checked: {arg}')
    print(f'{checked} regex literals checked, {len(rejected)} rejected by ICU, '
          f'{len(non_literal)} call sites built at run time')
    if rejected:
        print('A bare } outside a character class is the usual cause: write \\} instead.')
    return 1 if rejected else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
