#!/usr/bin/env python3
"""Build the keyboard's phone-number validity table from libphonenumber's metadata.

Android ships libphonenumber inside the OS, and its public PhoneNumberUtils
formats numbers for free, but it does not answer "is this a real number in
region X, and is it a mobile". That needs the numbering plans, so this reads
Google's PhoneNumberMetadata.xml (Apache-2.0 data) and keeps only what the
validity check uses:

    per region: country code, international prefix, national prefix (and the
    parsing rule that replaces it in a few regions), the leading digits that
    tell regions sharing a country code apart, the possible national lengths,
    and the general, fixed-line and mobile national-number patterns.

Formatting rules, example numbers, geocoding, carrier and time-zone data, and
the non-geographic entities (`001`: +800, +870, ...) are left out.

    curl -sLo /tmp/pnm.xml \
      https://raw.githubusercontent.com/google/libphonenumber/v9.0.44/resources/PhoneNumberMetadata.xml
    python3 tools/phonemeta/build_phone_meta.py /tmp/pnm.xml --version v9.0.44

Writes app/src/main/assets/phone/phone_meta.tsv (override with -o). Every
pattern is compiled with ICU, which is what java.util.regex is on Android,
and every fixed-line and mobile example number in the XML is checked against
the table before anything is written. Exits 1 on either failure.

Asset format, one tab-separated line per region after `#` comment lines:

    region  cc  intl  np  npp  npt  lead  main  lengths  local  general  fixed  mobile  other

`np` is the national prefix, `npp`/`npt` the nationalPrefixForParsing pattern
and its transform rule, `lead` the territory's leadingDigits, `main` is 1 for
the main region of a shared country code. `lengths` and `local` are comma
lists with `a-b` ranges. `fixed` or `mobile` reads `=` when it is the same
pattern as the other one, and is empty when the region has none. `other` is
every remaining type (toll-free, premium, shared cost, VoIP, personal, pager,
UAN, voicemail) as one alternation: a number is valid only when it matches a
type, as in libphonenumber, and the general pattern alone is looser than that.
"""
import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEFAULT_OUT = os.path.join(REPO, 'app', 'src', 'main', 'assets', 'phone', 'phone_meta.tsv')

# Every number type whose lengths make up the general description's, as
# BuildMetadataFromXml computes them. noInternationalDialling is not a type.
TYPES = ('fixedLine', 'mobile', 'tollFree', 'premiumRate', 'sharedCost', 'personalNumber',
         'voip', 'pager', 'uan', 'voicemail')
OTHER_TYPES = TYPES[2:]

WHITESPACE = re.compile(r'\s+')


def pattern_of(element):
    """The element's nationalNumberPattern with the XML's layout whitespace removed."""
    if element is None:
        return None
    node = element.find('nationalNumberPattern')
    if node is None or node.text is None:
        return None
    return WHITESPACE.sub('', node.text)


def lengths_of(spec):
    """`[8-10],12` → {8, 9, 10, 12}. -1 (none at all) → empty."""
    out = set()
    if not spec:
        return out
    for part in spec.split(','):
        part = part.strip()
        if part == '-1':
            continue
        if part.startswith('['):
            low, high = part[1:-1].split('-')
            out.update(range(int(low), int(high) + 1))
        else:
            out.add(int(part))
    return out


def compact(lengths):
    """{7, 8, 9, 12} → `7-9,12`."""
    values = sorted(lengths)
    runs = []
    for value in values:
        if runs and value == runs[-1][1] + 1:
            runs[-1][1] = value
        else:
            runs.append([value, value])
    return ','.join(str(a) if a == b else f'{a}-{b}' for a, b in runs)


def load_icu_checker():
    """`icu_error(pattern)` from scripts/check_icu_regexes.py, or None off macOS/Linux ICU."""
    sys.path.insert(0, os.path.join(REPO, 'scripts'))
    try:
        import check_icu_regexes  # noqa: E402
    except SystemExit:
        return None
    return check_icu_regexes.icu_error


def full_match(pattern, value):
    return pattern is not None and re.fullmatch(pattern, value) is not None


def build(xml_path):
    root = ET.parse(xml_path).getroot()
    rows = []
    problems = []
    for territory in root.iter('territory'):
        region = territory.get('id')
        if not re.fullmatch(r'[A-Z]{2}', region or ''):
            continue  # 001: the non-geographic entities
        general = pattern_of(territory.find('generalDesc'))
        if general is None:
            problems.append(f'{region}: no general pattern')
            continue
        lengths, local = set(), set()
        for name in TYPES:
            desc = territory.find(name)
            if desc is None or pattern_of(desc) is None:
                continue
            spec = desc.find('possibleLengths')
            if spec is None:
                continue
            lengths |= lengths_of(spec.get('national'))
            local |= lengths_of(spec.get('localOnly'))
        local -= lengths
        fixed = pattern_of(territory.find('fixedLine'))
        mobile = pattern_of(territory.find('mobile'))
        for name, pattern in (('fixedLine', fixed), ('mobile', mobile)):
            desc = territory.find(name)
            example = desc.find('exampleNumber') if desc is not None else None
            if example is not None and example.text:
                number = example.text.strip()
                if not full_match(pattern, number) or not full_match(general, number):
                    problems.append(f'{region}: {name} example {number} fails its own pattern')
                if len(number) not in lengths:
                    problems.append(f'{region}: {name} example {number} has a length not in {compact(lengths)}')
        fixed_out = fixed or ''
        mobile_out = mobile or ''
        if fixed and mobile and fixed == mobile:
            mobile_out = '='
        others = []
        for name in OTHER_TYPES:
            pattern = pattern_of(territory.find(name))
            if pattern and pattern not in others:
                others.append(pattern)
        other_out = '|'.join(f'(?:{p})' for p in others) if len(others) > 1 else ''.join(others)
        npp = WHITESPACE.sub('', territory.get('nationalPrefixForParsing') or '')
        rows.append([
            region,
            territory.get('countryCode'),
            WHITESPACE.sub('', territory.get('internationalPrefix') or ''),
            territory.get('nationalPrefix') or '',
            npp,
            territory.get('nationalPrefixTransformRule') or '',
            WHITESPACE.sub('', territory.get('leadingDigits') or ''),
            '1' if territory.get('mainCountryForCode') == 'true' else '',
            compact(lengths),
            compact(local),
            general,
            fixed_out,
            mobile_out,
            other_out,
        ])
    return rows, problems


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('xml', help='PhoneNumberMetadata.xml')
    parser.add_argument('--version', required=True, help='the libphonenumber release it came from, e.g. v9.0.44')
    parser.add_argument('-o', '--out', default=DEFAULT_OUT)
    args = parser.parse_args(argv)

    rows, problems = build(args.xml)
    icu_error = load_icu_checker()
    patterns = 0
    if icu_error is None:
        print('build_phone_meta: no ICU library, patterns not checked', file=sys.stderr)
    else:
        for row in rows:
            # intl, npp, lead, general, fixed, mobile, other
            for index in (2, 4, 6, 10, 11, 12, 13):
                pattern = row[index]
                if not pattern or pattern == '=':
                    continue
                patterns += 1
                error = icu_error(pattern)
                if error:
                    problems.append(f'{row[0]}: ICU rejects {pattern!r}: {error}')
    for row in rows:
        if any('\t' in cell or '\n' in cell for cell in row):
            problems.append(f'{row[0]}: a field holds a tab or a line break')
    if problems:
        for problem in problems:
            print(problem, file=sys.stderr)
        return 1

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, 'w', encoding='utf-8', newline='\n') as out:
        out.write(f'# libphonenumber {args.version} resources/PhoneNumberMetadata.xml, trimmed to validity data.\n')
        out.write('# Copyright (C) 2009 The Libphonenumber Authors. Apache License 2.0 (licenses/apache-2.0.txt).\n')
        out.write('# https://github.com/google/libphonenumber. Regenerate with tools/phonemeta/build_phone_meta.py.\n')
        out.write('# region\tcc\tintl\tnp\tnpp\tnpt\tlead\tmain\tlengths\tlocal\tgeneral\tfixed\tmobile\tother\n')
        for row in rows:
            out.write('\t'.join(row) + '\n')
    size = os.path.getsize(args.out)
    print(f'{len(rows)} regions, {patterns} patterns ICU-checked, {size} bytes → {os.path.relpath(args.out, REPO)}')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
