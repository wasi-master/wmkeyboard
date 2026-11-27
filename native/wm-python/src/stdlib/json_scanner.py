"""json.scanner: CPython's make_scanner, over the json module's own
decoder, which is native here."""

import re

from json import JSONDecoder

__all__ = ["make_scanner"]

NUMBER_RE = re.compile(r"(-?(?:0|[1-9][0-9]*))(\.[0-9]+)?([eE][-+]?[0-9]+)?", (re.VERBOSE | re.MULTILINE | re.DOTALL))


def make_scanner(context):
    """scan_once(string, idx) for [context], a JSONDecoder or anything with
    its options: the value at idx and the index past it, or StopIteration
    when there is no value there."""
    decoder = JSONDecoder(
        object_hook=context.object_hook,
        parse_float=context.parse_float,
        parse_int=context.parse_int,
        parse_constant=context.parse_constant,
        strict=context.strict,
        object_pairs_hook=context.object_pairs_hook,
    )
    return decoder.scan_once


py_make_scanner = c_make_scanner = make_scanner
