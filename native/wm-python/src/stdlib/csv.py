"""csv: reading and writing comma-separated (and other delimited) data."""

import re

__all__ = [
    "QUOTE_MINIMAL", "QUOTE_ALL", "QUOTE_NONNUMERIC", "QUOTE_NONE", "QUOTE_STRINGS", "QUOTE_NOTNULL", "Error",
    "Dialect", "excel", "excel_tab", "unix_dialect", "field_size_limit", "reader", "writer", "register_dialect",
    "get_dialect", "list_dialects", "Sniffer", "unregister_dialect", "DictReader", "DictWriter",
]

QUOTE_MINIMAL, QUOTE_ALL, QUOTE_NONNUMERIC, QUOTE_NONE, QUOTE_STRINGS, QUOTE_NOTNULL = 0, 1, 2, 3, 4, 5
__version__ = "1.0"


class Error(Exception):
    pass


_limit = [131072]


def field_size_limit(new_limit=None):
    old = _limit[0]
    if new_limit is not None:
        if not isinstance(new_limit, int):
            raise TypeError("limit must be an integer")
        _limit[0] = new_limit
    return old


class Dialect:
    _name = ""
    _valid = False
    delimiter = None
    quotechar = None
    escapechar = None
    doublequote = None
    skipinitialspace = None
    lineterminator = None
    quoting = None
    strict = False

    def __init__(self):
        if type(self) is not Dialect:
            self._valid = True
        _check(self)


def _check(d):
    if not isinstance(d.delimiter, str) or len(d.delimiter) != 1:
        raise TypeError('"delimiter" must be a 1-character string')
    if d.quoting not in (QUOTE_MINIMAL, QUOTE_ALL, QUOTE_NONNUMERIC, QUOTE_NONE, QUOTE_STRINGS, QUOTE_NOTNULL):
        raise TypeError("bad \"quoting\" value")
    if d.quotechar is None and d.quoting != QUOTE_NONE:
        raise TypeError("quotechar must be set if quoting enabled")
    if d.quotechar is not None and (not isinstance(d.quotechar, str) or len(d.quotechar) != 1):
        raise TypeError('"quotechar" must be a 1-character string')
    if d.lineterminator is None or not isinstance(d.lineterminator, str):
        raise TypeError('"lineterminator" must be a string')


class excel(Dialect):
    delimiter = ","
    quotechar = '"'
    doublequote = True
    skipinitialspace = False
    lineterminator = "\r\n"
    quoting = QUOTE_MINIMAL


class excel_tab(excel):
    delimiter = "\t"


class unix_dialect(Dialect):
    delimiter = ","
    quotechar = '"'
    doublequote = True
    skipinitialspace = False
    lineterminator = "\n"
    quoting = QUOTE_ALL


_dialects = {"excel": excel, "excel-tab": excel_tab, "unix": unix_dialect}


class _Resolved:
    """A dialect with keyword overrides applied, as reader() and writer() use it."""

    def __init__(self, dialect, kwargs):
        if isinstance(dialect, str):
            dialect = get_dialect(dialect)
        names = ("delimiter", "quotechar", "escapechar", "doublequote", "skipinitialspace", "lineterminator", "quoting", "strict")
        for name in names:
            value = kwargs.pop(name) if name in kwargs else getattr(dialect, name, None)
            setattr(self, name, value)
        if kwargs:
            name = next(iter(kwargs))
            raise TypeError(f"'{name}' is an invalid keyword argument for this function")
        if self.doublequote is None:
            self.doublequote = True
        if self.skipinitialspace is None:
            self.skipinitialspace = False
        if self.quoting is None:
            self.quoting = QUOTE_MINIMAL
        if self.lineterminator is None:
            self.lineterminator = "\r\n"
        if self.strict is None:
            self.strict = False
        if self.quotechar == "":
            self.quotechar = None
        _check(self)


def register_dialect(name, dialect=None, **fmtparams):
    if not isinstance(name, str):
        raise TypeError("dialect name must be a string")
    base = dialect if dialect is not None else excel
    _dialects[name] = _Resolved(base, dict(fmtparams))


def unregister_dialect(name):
    if name not in _dialects:
        raise Error("unknown dialect")
    del _dialects[name]


def get_dialect(name):
    if name not in _dialects:
        raise Error("unknown dialect")
    return _dialects[name]


def list_dialects():
    return list(_dialects)


class _Reader:
    def __init__(self, lines, dialect):
        self._lines = iter(lines)
        self.dialect = dialect
        self.line_num = 0

    def __iter__(self):
        return self

    def __next__(self):
        d = self.dialect
        fields = []
        field = []
        state = "start_record"
        quoted = False
        numeric = d.quoting == QUOTE_NONNUMERIC

        def end_field():
            text = "".join(field)
            if len(text) > _limit[0]:
                raise Error(f"field larger than field limit ({_limit[0]})")
            if numeric and not quoted and text != "":
                fields.append(float(text))
            elif d.quoting in (QUOTE_STRINGS, QUOTE_NOTNULL) and not quoted and text == "":
                fields.append(None)
            else:
                fields.append(text)
            field.clear()

        while True:
            try:
                line = next(self._lines)
            except StopIteration:
                if state in ("start_record",):
                    raise
                if state == "in_quoted_field" and d.strict:
                    raise Error("unexpected end of data")
                if state in ("escaped_char", "escape_in_quoted"):
                    field.append("\n")
                end_field()
                return fields
            if not isinstance(line, str):
                raise Error(f"iterator should return strings, not {type(line).__name__} (the file should be opened in text mode)")
            self.line_num += 1
            i = 0
            n = len(line)
            while i < n:
                c = line[i]
                i += 1
                if state == "start_record":
                    if c in "\r\n":
                        state = "eat_crnl"
                        continue
                    state = "start_field"
                if state == "start_field":
                    quoted = False
                    if c in "\r\n":
                        end_field()
                        state = "eat_crnl"
                    elif c == d.quotechar and d.quoting != QUOTE_NONE:
                        quoted = True
                        state = "in_quoted_field"
                    elif c == d.escapechar:
                        state = "escaped_char"
                    elif c == " " and d.skipinitialspace:
                        pass
                    elif c == d.delimiter:
                        end_field()
                    else:
                        field.append(c)
                        state = "in_field"
                elif state == "escaped_char":
                    field.append(c)
                    state = "in_field"
                elif state == "in_field":
                    if c in "\r\n":
                        end_field()
                        state = "eat_crnl"
                    elif c == d.escapechar:
                        state = "escaped_char"
                    elif c == d.delimiter:
                        end_field()
                        state = "start_field"
                    else:
                        field.append(c)
                elif state == "in_quoted_field":
                    if c == d.escapechar:
                        state = "escape_in_quoted"
                    elif c == d.quotechar and d.quoting != QUOTE_NONE:
                        state = "quote_in_quoted" if d.doublequote else "in_field"
                    else:
                        field.append(c)
                elif state == "escape_in_quoted":
                    field.append(c)
                    state = "in_quoted_field"
                elif state == "quote_in_quoted":
                    if c == d.quotechar:
                        field.append(c)
                        state = "in_quoted_field"
                    elif c == d.delimiter:
                        end_field()
                        state = "start_field"
                    elif c in "\r\n":
                        end_field()
                        state = "eat_crnl"
                    elif not d.strict:
                        field.append(c)
                        state = "in_field"
                    else:
                        raise Error(f"'{d.delimiter}' expected after '{d.quotechar}'")
                elif state == "eat_crnl":
                    if c not in "\r\n":
                        raise Error("new-line character seen in unquoted field - do you need to open the file with newline=''?")
            # The end of this line.
            if state == "eat_crnl":
                return fields
            if state == "start_record":
                return []
            if state in ("in_quoted_field", "escape_in_quoted", "escaped_char"):
                if state != "in_quoted_field":
                    field.append("\n")
                    state = "in_quoted_field" if state == "escape_in_quoted" else "in_field"
                continue
            end_field()
            return fields


def reader(csvfile, dialect="excel", **fmtparams):
    return _Reader(csvfile, _Resolved(dialect, dict(fmtparams)))


class _Writer:
    def __init__(self, f, dialect):
        self._f = f
        self.dialect = dialect

    def _quote(self, value):
        d = self.dialect
        if value is None:
            text = ""
        elif isinstance(value, float):
            text = repr(value)
        else:
            text = str(value)
        q = d.quotechar or ""
        special = d.delimiter + q + (d.escapechar or "") + "\r\n" + d.lineterminator
        if d.quoting == QUOTE_NONE:
            out = []
            for c in text:
                if c in special:
                    if not d.escapechar:
                        raise Error("need to escape, but no escapechar set")
                    out.append(d.escapechar)
                out.append(c)
            return "".join(out)
        must = (
            d.quoting == QUOTE_ALL
            or (d.quoting == QUOTE_NONNUMERIC and (not isinstance(value, (int, float)) or isinstance(value, bool)))
            or (d.quoting == QUOTE_STRINGS and isinstance(value, str))
            or (d.quoting == QUOTE_NOTNULL and value is not None)
            or any(c in special for c in text)
        )
        out = []
        for c in text:
            if q and c == q:
                if d.doublequote:
                    out.append(q)
                elif d.escapechar:
                    out.append(d.escapechar)
                else:
                    raise Error("need to escape, but no escapechar set")
            elif d.escapechar and c == d.escapechar:
                out.append(d.escapechar)
            out.append(c)
        text = "".join(out)
        return q + text + q if must else text

    def writerow(self, row):
        try:
            values = list(row)
        except TypeError:
            raise Error(f"iterable expected, not {type(row).__name__}") from None
        if len(values) == 1 and (values[0] is None or values[0] == ""):
            d = self.dialect
            if d.quoting == QUOTE_NONE or (values[0] is None and d.quoting in (QUOTE_STRINGS, QUOTE_NOTNULL)):
                raise Error("single empty field record must be quoted")
            return self._f.write(d.quotechar * 2 + d.lineterminator)
        line = self.dialect.delimiter.join(self._quote(v) for v in values) + self.dialect.lineterminator
        return self._f.write(line)

    def writerows(self, rows):
        for row in rows:
            self.writerow(row)


def writer(csvfile, dialect="excel", **fmtparams):
    return _Writer(csvfile, _Resolved(dialect, dict(fmtparams)))


class DictReader:
    def __init__(self, f, fieldnames=None, restkey=None, restval=None, dialect="excel", *args, **kwds):
        if fieldnames is not None and iter(fieldnames) is fieldnames:
            fieldnames = list(fieldnames)
        self._fieldnames = fieldnames
        self.restkey = restkey
        self.restval = restval
        self.reader = reader(f, dialect, *args, **kwds)
        self.dialect = dialect
        self.line_num = 0

    def __iter__(self):
        return self

    @property
    def fieldnames(self):
        if self._fieldnames is None:
            try:
                self._fieldnames = next(self.reader)
            except StopIteration:
                pass
        self.line_num = self.reader.line_num
        return self._fieldnames

    @fieldnames.setter
    def fieldnames(self, value):
        self._fieldnames = value

    def __next__(self):
        if self.line_num == 0:
            self.fieldnames
        row = next(self.reader)
        self.line_num = self.reader.line_num
        while row == []:
            row = next(self.reader)
        d = dict(zip(self.fieldnames, row))
        lf = len(self.fieldnames)
        lr = len(row)
        if lf < lr:
            d[self.restkey] = row[lf:]
        elif lf > lr:
            for key in self.fieldnames[lr:]:
                d[key] = self.restval
        return d

    __class_getitem__ = classmethod(lambda cls, item: cls)


class DictWriter:
    def __init__(self, f, fieldnames, restval="", extrasaction="raise", dialect="excel", *args, **kwds):
        if fieldnames is not None and iter(fieldnames) is fieldnames:
            fieldnames = list(fieldnames)
        self.fieldnames = fieldnames
        self.restval = restval
        if extrasaction.lower() not in ("raise", "ignore"):
            raise ValueError(f"extrasaction ({extrasaction}) must be 'raise' or 'ignore'")
        self.extrasaction = extrasaction
        self.writer = writer(f, dialect, *args, **kwds)

    def writeheader(self):
        header = dict(zip(self.fieldnames, self.fieldnames))
        return self.writerow(header)

    def _dict_to_list(self, rowdict):
        if self.extrasaction == "raise":
            wrong = [k for k in rowdict if k not in self.fieldnames]
            if wrong:
                raise ValueError("dict contains fields not in fieldnames: " + ", ".join([repr(x) for x in wrong]))
        return [rowdict.get(key, self.restval) for key in self.fieldnames]

    def writerow(self, rowdict):
        return self.writer.writerow(self._dict_to_list(rowdict))

    def writerows(self, rowdicts):
        return self.writer.writerows(map(self._dict_to_list, rowdicts))

    __class_getitem__ = classmethod(lambda cls, item: cls)


class Sniffer:
    """Guesses a sample's dialect and whether its first row is a header."""

    def __init__(self):
        self.preferred = [",", "\t", ";", " ", ":"]

    def sniff(self, sample, delimiters=None):
        quotechar, doublequote, delimiter, skipinitialspace = self._guess_quote_and_delimiter(sample, delimiters)
        if not delimiter:
            delimiter, skipinitialspace = self._guess_delimiter(sample, delimiters)
        if not delimiter:
            raise Error("Could not determine delimiter")

        class dialect(Dialect):
            _name = "sniffed"
            lineterminator = "\r\n"
            quoting = QUOTE_MINIMAL

        dialect.doublequote = doublequote
        dialect.delimiter = delimiter
        dialect.quotechar = quotechar or '"'
        dialect.skipinitialspace = skipinitialspace
        return dialect

    def _guess_quote_and_delimiter(self, data, delimiters):
        matches = []
        for restr in (
            r"(?P<delim>[^\w\n\"'])(?P<space> ?)(?P<quote>[\"']).*?(?P=quote)(?P=delim)",
            r"(?:^|\n)(?P<quote>[\"']).*?(?P=quote)(?P<delim>[^\w\n\"'])(?P<space> ?)",
            r"(?P<delim>[^\w\n\"'])(?P<space> ?)(?P<quote>[\"']).*?(?P=quote)(?:$|\n)",
            r"(?:^|\n)(?P<quote>[\"']).*?(?P=quote)(?:$|\n)",
        ):
            matches = list(re.finditer(restr, data, re.DOTALL | re.MULTILINE))
            if matches:
                break
        if not matches:
            return "", False, None, 0
        quotes = {}
        delims = {}
        spaces = 0
        for m in matches:
            groups = m.groupdict()
            key = groups.get("quote")
            if key:
                quotes[key] = quotes.get(key, 0) + 1
            key = groups.get("delim")
            if key and (delimiters is None or key in delimiters):
                delims[key] = delims.get(key, 0) + 1
            if groups.get("space"):
                spaces += 1
        quotechar = max(quotes, key=quotes.get)
        if delims:
            delim = max(delims, key=delims.get)
            skipinitialspace = delims[delim] == spaces
            if delim == "\n":
                delim = ""
        else:
            delim = ""
            skipinitialspace = 0
        dq = re.compile(
            r"((%(delim)s)|^)\W*%(quote)s[^%(delim)s\n]*%(quote)s[^%(delim)s\n]*%(quote)s\W*((%(delim)s)|$)"
            % {"delim": re.escape(delim), "quote": quotechar},
            re.MULTILINE,
        )
        doublequote = bool(dq.search(data))
        return quotechar, doublequote, delim, skipinitialspace

    def _guess_delimiter(self, data, delimiters):
        lines = [line for line in data.split("\n") if line]
        if not lines:
            return "", 0
        candidates = delimiters if delimiters else "".join(chr(c) for c in range(127) if not chr(c).isalnum() and chr(c) not in "\n\"'")
        best = {}
        for c in candidates:
            counts = [line.count(c) for line in lines[:10]]
            if not any(counts):
                continue
            freq = {}
            for n in counts:
                freq[n] = freq.get(n, 0) + 1
            mode, hits = max(freq.items(), key=lambda kv: (kv[1], kv[0]))
            if mode == 0:
                continue
            best[c] = (hits / len(counts), mode)
        if not best:
            return "", 0
        top = max(v[0] for v in best.values())
        winners = [c for c, v in best.items() if v[0] == top]
        delim = None
        for p in self.preferred:
            if p in winners:
                delim = p
                break
        if delim is None:
            delim = winners[0]
        skipinitialspace = data.split("\n")[0].count(delim) == data.split("\n")[0].count(delim + " ")
        return delim, skipinitialspace

    def has_header(self, sample):
        rdr = reader(sample.splitlines(), self.sniff(sample))
        header = next(rdr)
        columns = len(header)
        column_types = {i: None for i in range(columns)}
        checked = 0
        for row in rdr:
            if checked > 20:
                break
            checked += 1
            if len(row) != columns:
                continue
            for col in list(column_types):
                thing = None
                for kind in (int, float):
                    try:
                        kind(row[col])
                        thing = kind
                        break
                    except (ValueError, OverflowError):
                        pass
                if thing is None:
                    thing = len(row[col])
                if thing != column_types[col]:
                    if column_types[col] is None:
                        column_types[col] = thing
                    else:
                        del column_types[col]
        vote = 0
        for col, col_type in column_types.items():
            if isinstance(col_type, int):
                vote += 1 if len(header[col]) != col_type else -1
            else:
                try:
                    col_type(header[col])
                except (ValueError, TypeError):
                    vote += 1
                else:
                    vote -= 1
        return vote > 0
