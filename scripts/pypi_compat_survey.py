#!/usr/bin/env python3
"""Which PyPI packages can run in WM Keyboard plugins, and what stops the rest.

Runs `wmpy pip check NAME --json` on the most downloaded PyPI projects
(hugovk/top-pypi-packages) and on a list of packages plugin authors reach
for, then writes docs/src/data/pypi-compat.json: each package's verdict, what
blocks it, and every blocker ranked by how many packages it stops.

    python3 scripts/pypi_compat_survey.py --wmpy native/wmpy/target/release/wmpy
    python3 scripts/pypi_compat_survey.py --wmpy ./wmpy --top 100 --names emoji humanize

The verdicts come from the interpreter wmpy was built with, so build wmpy
from the commit you want measured and say which in --interpreter. Standard
library only; Python 3.9 or newer.
"""

import argparse
import concurrent.futures
import datetime
import json
import os
import re
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

TOP_URL = "https://hugovk.github.io/top-pypi-packages/top-pypi-packages.min.json"
PYPI_JSON = "https://pypi.org/pypi/{}/json"
USER_AGENT = "wmkeyboard-pypi-survey (+https://github.com/wasi-master/WMKeyboard)"

# Packages plugin authors ask for, whether or not they are in the top list:
# text, dates, numbers, formats, parsing, codes, small utilities.
PICKED = [
    "emoji", "humanize", "python-dateutil", "unidecode", "python-slugify", "inflect",
    "num2words", "word2number", "titlecase", "langdetect", "pyspellchecker", "rapidfuzz",
    "thefuzz", "jellyfish", "textblob", "pyyaml", "tomli", "tomlkit", "toml", "markdown",
    "mistune", "xmltodict", "feedparser", "html2text", "beautifulsoup4", "soupsieve",
    "pyotp", "simpleeval", "tabulate", "qrcode", "segno", "email-validator", "validators",
    "phonenumbers", "arrow", "pendulum", "pytz", "babel", "isodate", "croniter",
    "dateparser", "parse", "jmespath", "roman", "pint", "mpmath", "pyfiglet", "art",
    "cowsay", "pyjokes", "webcolors", "colour", "shortuuid", "hashids", "base58",
    "more-itertools", "toolz", "cachetools", "attrs", "marshmallow", "semver",
    "requests", "httpx", "chardet", "charset-normalizer", "idna", "six", "typing-extensions",
]

# A package with only compiled wheels is still handed to wmpy, which takes
# the .py files of a small one (it may fall back to Python, as PyYAML does)
# and refuses a big one without downloading it.

KIND_ORDER = ["language", "stdlib", "builtin", "sandbox", "compiled", "dependency", "package", "limits", "other"]


def fetch_json(url, retries=3):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if attempt == retries - 1:
                raise
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            if attempt == retries - 1:
                raise
        time.sleep(2 * (attempt + 1))
    return None


class LookupFailed:
    """A PyPI lookup that never got an answer, as opposed to a 404."""

    def __init__(self, why):
        self.why = str(why)


def normalize(name):
    return re.sub(r"[-_.]+", "-", name).lower()


def classify_files(files):
    """What the latest release ships: 'pure', 'sdist', 'compiled' or 'none'."""
    files = [f for f in files if not f.get("yanked")]
    wheels = [f for f in files if f.get("packagetype") == "bdist_wheel"]
    sdists = [f for f in files if f.get("packagetype") == "sdist"]
    # name-1.0-py3-none-any.whl: the Python tag is third from the end.
    pure = [w for w in wheels if w["filename"].endswith("-none-any.whl") and "py3" in w["filename"].rsplit("-", 3)[-3]]
    if pure:
        return "pure", None
    if wheels:
        return "compiled", None
    if sdists:
        return "sdist", None
    return "none", None


# ---- turning wmpy's problems into blocker keys -------------------------------

# A quoted name, not the apostrophe of "keyboard's".
QUOTED = re.compile(r"(?<![A-Za-z])'([^']+)'")


def blocker_key(p):
    """(kind, code, subject) for one problem: the same missing feature gets the
    same key in every package, whatever file or line it was found at."""
    code = p.get("code") or "other"
    msg = p.get("message") or ""
    q = QUOTED.findall(msg)
    if code == "syntax":
        # "the keyboard's Python cannot run this file: Kind: what"
        text = msg.split(": ", 1)[1] if ": " in msg else msg
        text = re.sub(r"^\w+(Error|Exception): ", "", text)
        text = text.replace(" by PyMini", "")
        text = re.sub(r"\d+", "N", text)
        return "language", code, text[:120]
    if code in ("metaclass", "multiple-inheritance", "big-int"):
        return "language", code, code
    if code in ("c-extension", "c-source", "needs-build"):
        return "compiled", code, q[0] if q and code == "c-extension" else code
    if code in ("file-too-big", "too-deep", "module-name"):
        return "limits", code, code
    if code in ("sandbox-import", "sandbox-builtin"):
        return "sandbox", code, q[0] if q else code
    if code == "missing-builtin":
        return "builtin", code, q[0] if q else code
    if code == "missing-attribute":
        return ("stdlib", code, ".".join(q[:2])) if len(q) >= 2 else ("stdlib", code, msg)
    if code == "missing-name":
        if msg.startswith("the keyboard's") and len(q) >= 2:
            return "stdlib", code, "from {} import {}".format(q[0], q[1])
        return "package", code, "own module"
    if code == "missing-import":
        if "is not installed in this plugin" in msg:
            return "dependency", code, q[0] if q else msg
        if msg.startswith("the package has no module") or "climbs above" in msg:
            return "package", code, "own module"
        if msg.startswith("the wm package"):
            return "package", code, q[0] if q else msg
        return "stdlib", code, q[0] if q else msg
    return "other", code, q[0] if q else msg[:120]


def key_id(key):
    return "{}:{}:{}".format(*key)


# ---- one package -------------------------------------------------------------

def check(wmpy, name, cache, timeout):
    env = dict(os.environ, WMPY_CACHE_DIR=cache, NO_COLOR="1")
    try:
        r = subprocess.run([wmpy, "pip", "check", name, "--json"], capture_output=True, text=True, env=env, timeout=timeout)
    except subprocess.TimeoutExpired:
        return None, "wmpy took over {} s".format(timeout)
    if r.returncode in (0, 1) and r.stdout.strip():
        try:
            return json.loads(r.stdout), None
        except json.JSONDecodeError:
            pass
    err = (r.stderr or r.stdout).strip().splitlines()
    first = next((l for l in err if l.strip()), "wmpy exited with {}".format(r.returncode))
    return None, re.sub(r"^error(\[[\w-]+\])?:\s*", "", first)[:200]


def survey_one(wmpy, cache, timeout, item, meta):
    name = item["name"]
    row = {"name": name, "rank": item.get("rank"), "downloads": item.get("downloads"), "picked": item.get("picked", False)}
    if isinstance(meta, LookupFailed):
        row.update(verdict="failed", reason="PyPI lookup failed: {}".format(meta.why)[:200])
        return row, []
    if meta is None:
        row.update(verdict="failed", reason="not on PyPI")
        return row, []
    info = meta.get("info") or {}
    row["version"] = info.get("version")
    row["summary"] = (info.get("summary") or "").strip()[:110]
    shape, size = classify_files(meta.get("urls") or [])
    if shape == "none":
        row.update(verdict="failed", reason="no files in the latest release")
        return row, []
    out, err = check(wmpy, name, cache, timeout)
    if out is None:
        row.update(verdict="failed", reason=err)
        return row, []
    me = normalize(name)
    pkgs = out.get("packages") or []
    built_in = next((n for n in out.get("notes") or [] if "built into" in n), None)
    if not pkgs and built_in:
        row.update(verdict="built-in", reason="comes with the keyboard: import it without installing")
        return row, []
    own = next((p for p in pkgs if normalize(p.get("name", "")) == me), pkgs[0] if pkgs else None)
    if own is None:
        row.update(verdict="failed", reason="wmpy returned no package")
        return row, []
    row["version"] = own.get("version") or row.get("version")
    row["bytes"] = own.get("bytes")
    row["own"] = own.get("verdict")
    if own.get("standIn"):
        # Compiled only: the keyboard's pure-Python stand-in goes in its place.
        row["standIn"] = True
    deps = [{"name": p.get("name"), "verdict": p.get("verdict")} for p in pkgs if p is not own]
    if deps:
        row["deps"] = deps
    problems = own.get("problems") or []
    errors = [p for p in problems if p.get("severity") == "error"]
    row["errors"] = len(errors)
    row["warnings"] = sum(1 for p in problems if p.get("severity") == "warning")
    counts, first = {}, {}
    for p in errors:
        k = blocker_key(p)
        counts[k] = counts.get(k, 0) + 1
        first.setdefault(k, p)
    ordered = sorted(counts, key=lambda k: (KIND_ORDER.index(k[0]), -counts[k], k[2]))
    row["blockers"] = [{"kind": k[0], "code": k[1], "subject": k[2], "count": counts[k]} for k in ordered]
    worst = own.get("verdict")
    if any(d["verdict"] == "incompatible" for d in deps):
        worst = "incompatible"
    elif worst == "ok" and any(d["verdict"] == "warnings" for d in deps):
        worst = "warnings"
    # Incompatible only for its compiled code: no Python fallback for it.
    if worst == "incompatible" and shape == "compiled" and ordered and all(k[0] == "compiled" for k in ordered):
        worst = "compiled"
        row["reason"] = "compiled code it cannot do without"
    row["verdict"] = worst
    found = []
    for k in ordered:
        p = first[k]
        found.append((k, {"package": name, "message": p.get("message"), "file": p.get("file"), "line": p.get("line"), "help": p.get("help")}))
    return row, found


# ---- writing it down ---------------------------------------------------------

PER_PACKAGE = 5
MAX_BLOCKERS = 300


def trim(data):
    """Keeps the file small enough to refresh every week: each package's
    first blockers, and the blockers seen in more than one package (or that
    stop one alone)."""
    for r in data["packages"]:
        b = r.get("blockers") or []
        if len(b) > PER_PACKAGE:
            r["blockers"] = b[:PER_PACKAGE]
            r["moreBlockers"] = len(b) - PER_PACKAGE
    data["blockers"] = [b for b in data["blockers"] if b["packages"] > 1 or b["sole"] > 0][:MAX_BLOCKERS]
    return data


def dump(data):
    """JSON with one package and one blocker per line, so a week's changes
    read as a diff."""
    line = lambda v: json.dumps(v, ensure_ascii=False, separators=(",", ":"))
    keys = list(data)
    out = ["{"]
    for i, k in enumerate(keys):
        end = "," if i < len(keys) - 1 else ""
        v = data[k]
        if isinstance(v, list):
            out.append(" {}: [".format(json.dumps(k)))
            out.extend("  " + line(x) + ("," if j < len(v) - 1 else "") for j, x in enumerate(v))
            out.append(" ]" + end)
        else:
            out.append(" {}: {}{}".format(json.dumps(k), json.dumps(v, ensure_ascii=False), end))
    out.append("}")
    return "\n".join(out) + "\n"


# ---- the whole survey --------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--wmpy", default="wmpy", help="the wmpy binary to ask (default: wmpy on PATH)")
    ap.add_argument("--top", type=int, default=500, help="how many of the most downloaded projects (default 500)")
    ap.add_argument("--names", nargs="*", default=None, help="check only these packages")
    ap.add_argument("--no-picked", action="store_true", help="leave out the list of packages plugin authors ask for")
    ap.add_argument("--jobs", type=int, default=2, help="wmpy runs at once (default 2)")
    ap.add_argument("--timeout", type=int, default=300, help="seconds per package (default 300)")
    ap.add_argument("--cache", default=None, help="download cache (default: a temporary folder)")
    ap.add_argument("--interpreter", default="", help="which interpreter wmpy was built from, for the record, e.g. 'main@1a2b3c4'")
    ap.add_argument("--max-failed", type=float, default=0.05,
                    help="write nothing when more than this share of packages could not be checked (default 0.05)")
    ap.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "src", "data", "pypi-compat.json"))
    args = ap.parse_args()

    version = subprocess.run([args.wmpy, "--version"], capture_output=True, text=True).stdout.strip()
    if not version:
        sys.exit("cannot run {} --version".format(args.wmpy))
    cache = args.cache or tempfile.mkdtemp(prefix="wmpy-survey-")

    items, seen = [], set()
    top_date = None
    if args.names is not None and args.names:
        for n in args.names:
            items.append({"name": n})
            seen.add(normalize(n))
    else:
        top = fetch_json(TOP_URL)
        top_date = (top.get("last_update") or "")[:10]
        for i, r in enumerate(top["rows"][: args.top]):
            items.append({"name": r["project"], "rank": i + 1, "downloads": r["download_count"]})
            seen.add(normalize(r["project"]))
        ranks = {normalize(r["project"]): (i + 1, r["download_count"]) for i, r in enumerate(top["rows"])}
        for it in items:
            if normalize(it["name"]) in {normalize(p) for p in PICKED}:
                it["picked"] = True
        if not args.no_picked:
            for n in PICKED:
                if normalize(n) not in seen:
                    rank, dl = ranks.get(normalize(n), (None, None))
                    items.append({"name": n, "rank": rank, "downloads": dl, "picked": True})
                    seen.add(normalize(n))

    print("{} packages, {}, cache {}".format(len(items), version, cache), file=sys.stderr)
    metas = {}
    with concurrent.futures.ThreadPoolExecutor(8) as ex:
        futs = {ex.submit(fetch_json, PYPI_JSON.format(it["name"])): it["name"] for it in items}
        for f in concurrent.futures.as_completed(futs):
            try:
                metas[futs[f]] = f.result()
            except Exception as e:  # noqa: BLE001 - one bad lookup must not stop the run
                print("  {}: PyPI lookup failed: {}".format(futs[f], e), file=sys.stderr)
                metas[futs[f]] = LookupFailed(e)

    rows, found = [], {}
    done = [0]
    lock = threading.Lock()

    def run(it):
        row, f = survey_one(args.wmpy, cache, args.timeout, it, metas.get(it["name"]))
        with lock:
            done[0] += 1
            print("  [{}/{}] {}: {}".format(done[0], len(items), it["name"], row["verdict"]), file=sys.stderr)
        return row, f

    with concurrent.futures.ThreadPoolExecutor(max(1, args.jobs)) as ex:
        for row, f in ex.map(run, items):
            rows.append(row)
            for k, example in f:
                found.setdefault(k, []).append((row, example))

    # Rank the blockers: how many packages each one stops, how many it stops
    # on its own (fixing it alone would let the package load), and the
    # downloads behind them.
    blockers = []
    for k, hits in found.items():
        names = sorted({r["name"] for r, _ in hits}, key=lambda n: next((r.get("rank") or 10**9 for r, _ in hits if r["name"] == n), 10**9))
        sole = sum(1 for r, _ in hits if len(r.get("blockers") or []) == 1 and (r.get("own") in (None, "incompatible")))
        example = next((e for _, e in hits if e), None)
        b = {"kind": k[0], "code": k[1], "subject": k[2], "packages": len(names), "sole": sole,
             "downloads": sum(r.get("downloads") or 0 for r, _ in hits), "examples": names[:8]}
        if example:
            b["message"] = example["message"]
            if example.get("help"):
                b["help"] = example["help"]
            b["at"] = "{} {}:{}".format(example["package"], example.get("file"), example.get("line") or "")
        blockers.append(b)
    blockers.sort(key=lambda b: (-b["packages"], -b["sole"], -b["downloads"], b["subject"]))

    rows.sort(key=lambda r: (r.get("rank") or 10**9, r["name"].lower()))
    totals = {}
    for r in rows:
        totals[r["verdict"]] = totals.get(r["verdict"], 0) + 1
    data = {
        "generated": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d"),
        "wmpy": version,
        "interpreter": args.interpreter,
        "list": {"source": "hugovk/top-pypi-packages", "date": top_date, "top": None if args.names else args.top,
                 "picked": 0 if args.no_picked or args.names else len(PICKED)},
        "totals": totals,
        "blockers": blockers,
        "packages": rows,
    }
    # A dropped connection turns most of the list into "failed"; such a run
    # must not replace good data.
    failed = totals.get("failed", 0)
    if failed > args.max_failed * max(len(rows), 1):
        sys.exit("{} of {} packages could not be checked (over {:.0%}); not writing {}".format(failed, len(rows), args.max_failed, args.out))
    out = os.path.abspath(args.out)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    tmp = out + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        fh.write(dump(trim(data)))
    os.replace(tmp, out)
    print("wrote {}: {}".format(out, ", ".join("{} {}".format(v, k) for k, v in sorted(totals.items()))), file=sys.stderr)
    for b in blockers[:25]:
        print("  {:4} {:4}  {:10} {}".format(b["packages"], b["sole"], b["kind"], b["subject"]), file=sys.stderr)


if __name__ == "__main__":
    main()
