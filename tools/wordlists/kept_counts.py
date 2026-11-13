#!/usr/bin/env python3
"""Regenerates DictionaryKeptCounts.kt: how many words each downloadable word
list actually yields, which is what the size dialog has to promise.

The catalogue's totalWordCount is the file's line count. The downloader keeps
fewer: it stops at the first word counted under twice (the noise tail of a
counted list), skips lines that are not one word, and takes a list with no
counts at all whole, whatever size was asked for. Hindi's 242,911 lines yield
81,289 words; Hinglish's 1,080,362 yield 179,459.

This replays WordlistDownloadManager.parse over every list the catalogue names,
streaming each file only as far as the app would read it, and writes the table.
Run it after the data repo rebuilds a list:

    python3 tools/wordlists/kept_counts.py
"""
import concurrent.futures
import gzip
import io
import os
import re
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PKG = "core/prediction/src/main/java/com/wasimaster/wmkeyboard/core/dictionaries"
CATALOG = os.path.join(ROOT, PKG, "DictionaryCatalog.kt")
OUT = os.path.join(ROOT, PKG, "DictionaryKeptCounts.kt")
RAW = "https://raw.githubusercontent.com/wasi-master/wmkeyboard-data/master/data/{repo}/{file}"

MIN_FREQUENCY = 2  # WordlistDownloadManager.MIN_FREQUENCY
MAX_WORD_LENGTH = 48  # WordlistDownloadManager.MAX_WORD_LENGTH, in UTF-16 units
INT = re.compile(r"^[+-]?\d+$")


def calls(source):
    """Every entry(...) / aosp(...) call in the entries list, as (kind, args)."""
    start = source.index("val entries: List<DictionaryEntry> = listOf(")
    for m in re.finditer(r"\b(entry|aosp)\(", source[start:]):
        i = start + m.end()
        depth, j = 1, i
        while depth:
            c = source[j]
            depth += c == "("
            depth -= c == ")"
            j += 1
        yield m.group(1), source[i : j - 1]


def entries():
    source = open(CATALOG, encoding="utf-8").read()
    constants = dict(re.findall(r'private const val (\w+) = "([^"]+)"', source))
    out = []
    for kind, args in calls(source):
        strings = re.findall(r'"([^"]*)"', args)
        if len(strings) < 3:
            continue
        repo = strings[2]
        stem = re.search(r'fileStem = (?:"([^"]+)"|(\w+))', args)
        suffix = re.search(r'suffix = "([^"]+)"', args)
        if stem:
            name = stem.group(1) or constants[stem.group(2)]
        else:
            name = f"{repo}_{suffix.group(1) if suffix else ('aosp' if kind == 'aosp' else 'full')}"
        out.append((repo, name + ".txt.gz", kind == "aosp"))
    return out


class Counting(io.RawIOBase):
    def __init__(self, raw):
        self.raw, self.read_bytes = raw, 0

    def readable(self):
        return True

    def readinto(self, b):
        n = self.raw.readinto(b)
        self.read_bytes += n or 0
        return n


def kept(repo, file, aosp):
    """(words kept with no cap, compressed bytes read for them, flat)."""
    request = urllib.request.Request(RAW.format(repo=repo, file=file), headers={"User-Agent": "wordlist-counts"})
    with urllib.request.urlopen(request, timeout=60) as response:
        counting = Counting(response)
        stream = gzip.GzipFile(fileobj=io.BufferedReader(counting, 1 << 16))
        text = io.TextIOWrapper(stream, encoding="utf-8", errors="replace", newline=None)
        ranked = False if aosp else None
        count = 0
        for line in text:
            t = line.strip()
            if not t or t.startswith("#"):
                continue
            sep = t.rfind(" ")
            if sep <= 0:
                continue
            word = t[:sep].strip()
            raw = t[sep + 1 :]
            if not INT.match(raw) or not -(2**31) <= int(raw) < 2**31:
                continue
            frequency = int(raw)
            if ranked is None:
                ranked = frequency >= MIN_FREQUENCY
            if ranked and frequency < MIN_FREQUENCY:
                break
            if len(word.encode("utf-16-le")) // 2 > MAX_WORD_LENGTH or " " in word:
                continue
            count += 1
        return count, counting.read_bytes, ranked is False and not aosp


def main():
    files = sorted(set(entries()))
    results = {}
    with concurrent.futures.ThreadPoolExecutor(8) as pool:
        jobs = {pool.submit(kept, *f): f for f in files}
        for job in concurrent.futures.as_completed(jobs):
            repo, file, _ = jobs[job]
            try:
                results[file] = job.result()
            except Exception as e:  # noqa: BLE001 - reported, the table keeps going
                print(f"{repo}/{file}: {e}", file=sys.stderr)
            print(f"{len(results)}/{len(files)} {file} {results.get(file)}", file=sys.stderr)
    rows = "\n".join(
        f'        "{file}" to Kept({n}, {b}L{", flat = true" if flat else ""}),'
        for file, (n, b, flat) in sorted(results.items())
    )
    with open(OUT, "w", encoding="utf-8") as out:
        out.write(
            f"""package com.wasimaster.wmkeyboard.core.dictionaries

// Generated by tools/wordlists/kept_counts.py. Do not edit by hand: rerun it.

/**
 * What each downloadable list yields once [WordlistDownloadManager] has read
 * it: the words it keeps with no size cap, and the compressed bytes it reads
 * to get them. Keyed by [DictionaryEntry.fileName].
 *
 * A counted list stops at its first word seen once, so it keeps far fewer
 * words than it has lines (Hindi 242,911 lines, 81,289 words), and a size
 * above that number fetches the same list as the size below it. [Kept.flat]
 * marks a list with no counts, which is taken whole at any size.
 */
internal object DictionaryKeptCounts {{
    class Kept(val words: Int, val bytes: Long, val flat: Boolean = false)

    fun of(entry: DictionaryEntry): Kept? = table[entry.fileName]

    private val table: Map<String, Kept> = mapOf(
{rows}
    )
}}
"""
        )
    print(f"wrote {len(results)} of {len(files)} lists to {OUT}", file=sys.stderr)


if __name__ == "__main__":
    main()
