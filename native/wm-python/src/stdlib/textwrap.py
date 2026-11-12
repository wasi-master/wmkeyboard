"""textwrap: wrap, fill, shorten, dedent, indent."""


def wrap(text, width=70, initial_indent="", subsequent_indent="", break_long_words=True):
    words = text.split()
    lines = []
    line = initial_indent
    empty = True
    for word in words:
        indent = initial_indent if not lines else subsequent_indent
        candidate = word if empty else line + " " + word
        if empty:
            candidate = indent + word
        if len(candidate) <= width:
            line = candidate
            empty = False
            continue
        if not empty:
            lines.append(line)
            indent = subsequent_indent
            line = indent
            empty = True
        piece = word
        while break_long_words and len(indent + piece) > width and width > len(indent):
            room = width - len(indent)
            lines.append(indent + piece[:room])
            piece = piece[room:]
            indent = subsequent_indent
        line = indent + piece
        empty = False
    if not empty:
        lines.append(line)
    return lines


def fill(text, width=70, **kwargs):
    return "\n".join(wrap(text, width, **kwargs))


def shorten(text, width, placeholder=" [...]"):
    words = text.split()
    joined = " ".join(words)
    if len(joined) <= width:
        return joined
    out = ""
    for word in words:
        candidate = word if not out else out + " " + word
        if len(candidate) + len(placeholder) > width:
            break
        out = candidate
    return (out + placeholder).strip() if out else placeholder.strip()


def dedent(text):
    lines = text.split("\n")
    margin = None
    for line in lines:
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip())
        if margin is None or indent < margin:
            margin = indent
    if not margin:
        return "\n".join(l if l.strip() else "" for l in lines)
    return "\n".join(l[margin:] if l.strip() else "" for l in lines)


def indent(text, prefix, predicate=None):
    out = []
    for line in text.split("\n"):
        keep = predicate(line) if predicate is not None else bool(line.strip())
        out.append(prefix + line if keep else line)
    return "\n".join(out)
