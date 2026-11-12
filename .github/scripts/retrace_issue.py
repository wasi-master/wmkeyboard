#!/usr/bin/env python3
"""Retrace an obfuscated stack trace pasted into an issue.

Reads the issue (or comment) body, works out which release build it came from,
fetches that build's R8 mapping off the GitHub release, runs R8's own retrace
over the whole body, and prints a comment to post back.

Two things this deliberately does not do:

* **Guess.** The retracer is R8's, not ours — it is the only thing that
  reconstructs the frames R8 inlined away, and a hand-rolled parser would get
  them subtly wrong. Picking the mapping is a table lookup off the ``version:``
  line the app writes, not a heuristic.
* **Trust the body.** Everything here is text a stranger typed. It arrives
  through the environment, never through a shell argument, is capped before it
  is parsed, and is fenced and stripped of control characters before it goes
  back out.

Usage::

    BODY="$(cat trace.txt)" retrace_issue.py --issue 42 --repo owner/name
    BODY="..." retrace_issue.py --dry-run --local-mapping mapping.txt

Exit status is 0 whenever the run reached a decision, including "nothing here
to retrace" — a bug report without a stack trace is the normal case, not a
failure.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

# --- limits ------------------------------------------------------------------
# A body is at most this many characters before it is cut. GitHub caps an issue
# at 65,536, but an issue can be edited and an API body can be longer, and
# retrace's cost is linear in the lines it walks.
MAX_BODY_CHARS = 200_000

# Attached logs. The app's own **Share diagnostics** hands the report to the
# share sheet as a *file*, so the most complete reports arrive as an
# attachment and not as pasted text — issue #75 is exactly that. Only
# GitHub's own attachment host is fetched, never a link a reporter chose.
ATTACHMENT_RE = re.compile(r"https://github\.com/user-attachments/files/\d+/[\w.\-]+")
MAX_ATTACHMENTS = 3
MAX_ATTACHMENT_BYTES = 5_000_000

# What goes back in the comment. GitHub's own limit is 65,536 characters.
MAX_COMMENT_LINES = 400
MAX_COMMENT_CHARS = 50_000

# R8 to fall back on when the mapping does not name its compiler. The retrace
# format is versioned inside the mapping (`version: 2.2` on line 5), and any
# recent R8 reads any older map, so this only has to be recent.
FALLBACK_R8_VERSION = "9.3.16"
R8_JAR_URL = "https://dl.google.com/dl/android/maven2/com/android/tools/r8/{v}/r8-{v}.jar"

# --- what an obfuscated frame looks like --------------------------------------
# A frame that is worth retracing. Two independent tells, either is enough:
#
#  * `(SourceFile:123)` — `-renamesourcefileattribute SourceFile` in
#    app/proguard-rules.pro flattens every file name in a release build to
#    exactly this, so it is close to a signature of our own R8 output.
#  * short identifiers — `at q3.a(...)`, what R8's renaming produces.
OBFUSCATED_RE = re.compile(
    r"^\s*at\s+(?:[\w$]+\.)*[\w$]+\((?:SourceFile|Unknown Source)[:)]"
    r"|^\s*at\s+(?:[a-zA-Z0-9$_]{1,3}\.)+[a-zA-Z0-9$_]{1,4}\(",
    re.MULTILINE,
)

# The header DebugLog.writeCrash writes:
#   version: 0.5.9 (24) fullEn (Play Store)
VERSION_RE = re.compile(
    r"^\s*version:\s*(?P<name>[0-9][\w.\-+]*)\s*\((?P<code>\d+)\)"
    r"(?:\s+(?P<flavor>[A-Za-z]+))?"
    r"(?:\s*\((?P<channel>Play Store|F-Droid)\))?",
    re.MULTILINE,
)

# The bug report form's "App version" field, for a trace pasted without the
# app's own header.
# The form renders the field as a heading with the answer on its own line
# below, so the gap has to be allowed to cross newlines — bounded, so a
# heading cannot reach a version number paragraphs away.
LOOSE_VERSION_RE = re.compile(
    r"^[^\S\n]*(?:#+\s*)?(?:app\s+)?version\b[^0-9]{0,40}?(\d+\.\d+\.\d+)",
    re.MULTILINE | re.IGNORECASE,
)

# Mapping asset suffixes, in the order they are tried when the record does not
# say which build it is. Play first: it is the only one most installs can be.
# `full` and `lite` are the names releases up to 0.5.9 used, before
# `languages` became a flavour dimension and split each of them in two.
# `fdroid` last: a record without its channel can still be from F-Droid.
ALL_SUFFIXES = ("play", "full-intl", "full-en", "lite-intl", "lite-en", "full", "lite", "fdroid")

# The first release that carries the F-Droid build's own mappings, one per ABI
# (`-vc<full code>-fdroid-mapping.txt.gz`). F-Droid's build has been ours byte
# for byte since it went reproducible, so its R8 run is the release workflow's,
# but the mapping was thrown away until #963.
FDROID_MAPPINGS_FROM = "0.6.4"

# What the record calls the build -> the asset suffixes that could carry its
# mapping, best first. Every entry ends in the legacy name, because a record
# from a new build can still be reporting against an old release whose assets
# were never split by language.
FLAVOR_TO_SUFFIX = {
    # From 0.6.3 the app has only the `languages` flavour, so a record says
    # `intl` or `en`; the mapping assets keep their `full-` names.
    "intl": ("full-intl", "full"),
    "en": ("full-en", "full"),
    "fullintl": ("full-intl", "full"),
    "fullen": ("full-en", "full"),
    "liteintl": ("lite-intl", "lite"),
    "liteen": ("lite-en", "lite"),
    # Builds before the flavour tag landed said `full`/`lite` and could not
    # name their language build. Both are tried and the better one wins.
    "full": ("full-intl", "full-en", "full"),
    "lite": ("lite-intl", "lite-en", "lite"),
}

# The flavours that name the build outright, so the mapping is a lookup and
# not a competition between candidates.
EXACT_FLAVORS = frozenset({"intl", "en", "fullintl", "fullen", "liteintl", "liteen"})

# One obfuscated frame, split into the parts a mapping is checked against.
OBF_FRAME_RE = re.compile(
    r"^[^\S\n]*at\s+([\w$.]+)\.([\w$<>]+)\((?:SourceFile|Unknown Source):(\d+)\)",
    re.MULTILINE,
)

# How much of a trace a mapping has to account for before its output is worth
# posting. **Measured, not guessed** (issues #252, #253, #237, #271): the right
# mapping accounts for 100% of the frames, and every wrong one for 0-20%. There
# is nothing in between, because a mapping either was or was not written by the
# R8 run that produced the code in the trace.
#
# Without this gate a wrong mapping still renames every frame, into names that
# read perfectly and mean nothing — issue #271 was retraced into WmSlider and
# SoundPack because a 57-frame count beat a 56-frame one. Counting renamed
# frames measures nothing; this measures whether the mapping fits.
MIN_FIT = 0.6

# Below this, say which mapping was used and that the lines are a lead.
SURE_FIT = 0.9

# A reporter saying where they got the app, when the record's own header is
# missing. Only ever used to explain a failure, never to pick a mapping.
FDROID_PROSE_RE = re.compile(r"\bf[\s-]?droid\b", re.IGNORECASE)

MARKER_PREFIX = "<!-- retrace-bot:"


@dataclass
class Build:
    """What the body says about the build the trace came from."""

    version: str
    code: str | None
    flavor: str | None
    channel: str | None
    exact: bool  # the flavour was named outright, so the mapping is not a guess


# --- reading the body ---------------------------------------------------------


def find_build(body: str) -> Build | None:
    m = VERSION_RE.search(body)
    if m:
        flavor = (m.group("flavor") or "").lower() or None
        return Build(
            version=m.group("name"),
            code=m.group("code"),
            flavor=flavor,
            channel=m.group("channel"),
            exact=flavor in EXACT_FLAVORS or m.group("channel") == "Play Store",
        )
    loose = LOOSE_VERSION_RE.search(body)
    if loose:
        return Build(version=loose.group(1), code=None, flavor=None, channel=None, exact=False)
    return None


def with_attachments(body: str) -> str:
    """The body plus any log files attached to it.

    Text only, size-capped, and from `github.com/user-attachments/files/`
    alone — a URL a reporter typed is not fetched, so this cannot be pointed at
    an internal address. What comes back is exactly as untrusted as the body
    and goes through the same fence on the way out.
    """
    seen: list[str] = []
    for url in ATTACHMENT_RE.findall(body):
        if url not in seen:
            seen.append(url)
    if not seen:
        return body
    parts = [body]
    for url in seen[:MAX_ATTACHMENTS]:
        name = url.rsplit("/", 1)[-1]
        if not name.endswith((".txt", ".log", ".md")):
            continue
        try:
            with urllib.request.urlopen(url, timeout=60) as response:
                raw = response.read(MAX_ATTACHMENT_BYTES + 1)
        except (urllib.error.URLError, OSError) as err:
            print(f"attachment {name} not fetched: {err}", file=sys.stderr)
            continue
        if len(raw) > MAX_ATTACHMENT_BYTES:
            print(f"attachment {name} is too big", file=sys.stderr)
            continue
        text = raw.decode("utf-8", errors="replace")
        if "\x00" in text:
            continue
        parts.append(f"\n\n=== attached: {name} ===\n{text}")
        print(f"read attachment {name} ({len(raw)} bytes)", file=sys.stderr)
    return "".join(parts)


def candidate_suffixes(build: Build) -> list[str]:
    """Mapping suffixes to try, best first."""
    if build.channel == "Play Store":
        # Only the bundle's own mapping fits a Play install, and a release
        # without one cannot be retraced rather than retraced wrongly.
        return ["play"]
    if build.channel == "F-Droid":
        # F-Droid serves the release workflow's own F-Droid build, so its
        # mapping is one of that build's, one per ABI. A record that says
        # `en` is the English-only variant the release publishes beside it
        # (#1290), a separate R8 run with its own mapping.
        if build.flavor == "en":
            return ["fdroid-en", "fdroid"]
        return ["fdroid"]
    mapped = FLAVOR_TO_SUFFIX.get(build.flavor or "")
    if mapped:
        return list(mapped)
    return list(ALL_SUFFIXES)


# --- the release ---------------------------------------------------------------


def gh_json(args: list[str]) -> object:
    out = subprocess.run(["gh", *args], capture_output=True, text=True, check=True)
    return json.loads(out.stdout)


def release_assets(repo: str, tag: str) -> list[str]:
    try:
        data = gh_json(["release", "view", tag, "--repo", repo, "--json", "assets"])
    except subprocess.CalledProcessError:
        return []
    return [a["name"] for a in data.get("assets", [])]  # type: ignore[union-attr]


def download_asset(repo: str, tag: str, name: str, into: Path) -> Path | None:
    dest = into / name
    if dest.exists():
        return dest
    result = subprocess.run(
        ["gh", "release", "download", tag, "--repo", repo, "--pattern", name, "--dir", str(into)],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0 or not dest.exists():
        print(f"could not download {name}: {result.stderr.strip()}", file=sys.stderr)
        return None
    return dest


def gunzip(path: Path) -> Path:
    out = path.with_suffix("")  # .txt.gz -> .txt
    if out.exists():
        return out
    with gzip.open(path, "rb") as src, open(out, "wb") as dst:
        shutil.copyfileobj(src, dst, length=1 << 22)
    return out


# --- retrace -------------------------------------------------------------------


def compiler_version(mapping: Path) -> str:
    """The R8 that wrote this mapping, off its own header."""
    with open(mapping, encoding="utf-8", errors="replace") as f:
        for _ in range(20):
            line = f.readline()
            if not line or not line.startswith("#"):
                break
            if line.startswith("# compiler_version:"):
                v = line.split(":", 1)[1].strip()
                if re.fullmatch(r"[\w.\-]+", v):
                    return v
    return FALLBACK_R8_VERSION


def r8_jar(version: str, cache: Path) -> Path:
    cache.mkdir(parents=True, exist_ok=True)
    jar = cache / f"r8-{version}.jar"
    if jar.exists() and jar.stat().st_size > 1_000_000:
        return jar
    for candidate in (version, FALLBACK_R8_VERSION):
        url = R8_JAR_URL.format(v=candidate)
        target = cache / f"r8-{candidate}.jar"
        if target.exists() and target.stat().st_size > 1_000_000:
            return target
        try:
            with urllib.request.urlopen(url, timeout=120) as response, open(target, "wb") as out:
                shutil.copyfileobj(response, out)
            return target
        except (urllib.error.URLError, OSError) as err:
            print(f"r8 {candidate} not fetched: {err}", file=sys.stderr)
    raise SystemExit("no R8 jar available to retrace with")


def mapping_fit(mapping: Path, frames: list[tuple[str, str, int]]) -> float:
    """How much of the trace this mapping actually accounts for, 0 to 1.

    For each frame `a.b(SourceFile:12)` the mapping has to hold class `a`, a
    member renamed to `b` inside it, and a line range around 12. The class
    alone proves nothing — every mapping of this app holds thousands of
    two-letter class names, so a wrong one matches on class name constantly.
    The method-and-line pair is what a different R8 run cannot fake.
    """
    if not frames:
        return 0.0
    wanted = {cls for cls, _, _ in frames}
    members: dict[str, dict[str, list[tuple[int, int] | None]]] = {}
    current: str | None = None
    with open(mapping, encoding="utf-8", errors="replace") as f:
        for line in f:
            if line[:1] not in (" ", "#"):
                parts = line.rstrip("\n").rsplit(" -> ", 1)
                obf = parts[1][:-1] if len(parts) == 2 and parts[1].endswith(":") else None
                current = obf if obf in wanted else None
                if current is not None:
                    members.setdefault(current, {})
                continue
            if current is None:
                continue
            stripped = line.strip()
            if stripped.startswith("#") or " -> " not in stripped:
                continue
            body, name = stripped.rsplit(" -> ", 1)
            span = re.match(r"^(\d+):(\d+):", body)
            members[current].setdefault(name, []).append(
                (int(span.group(1)), int(span.group(2))) if span else None
            )
    hits = 0
    for cls, method, line_no in frames:
        ranges = members.get(cls, {}).get(method)
        if ranges and any(r is None or r[0] <= line_no <= r[1] for r in ranges):
            hits += 1
    return hits / len(frames)


def retrace(jar: Path, mapping: Path, body: str, workdir: Path) -> str:
    """Run R8's retrace over the whole body.

    The body goes through a file, never a shell argument. Retrace rewrites the
    lines it recognises and passes everything else through, so prose around the
    trace survives untouched.
    """
    trace_file = workdir / "trace.txt"
    trace_file.write_text(body, encoding="utf-8")
    result = subprocess.run(
        [
            "java",
            "-Xmx5g",
            "-cp",
            str(jar),
            "com.android.tools.r8.retrace.Retrace",
            "--quiet",
            str(mapping),
            str(trace_file),
        ],
        capture_output=True,
        text=True,
        timeout=600,
    )
    if result.returncode != 0:
        print(f"retrace failed: {result.stderr[-2000:]}", file=sys.stderr)
        return ""
    return result.stdout


# --- the comment ---------------------------------------------------------------


def sanitize(text: str) -> str:
    """Make arbitrary retraced output safe to paste into a comment."""
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    # Control characters other than newline and tab. A trace can carry them
    # when a reporter pastes from a terminal.
    text = re.sub(r"[\x00-\x08\x0b-\x1f\x7f]", "", text)
    lines = text.split("\n")
    if len(lines) > MAX_COMMENT_LINES:
        lines = lines[:MAX_COMMENT_LINES] + [f"… {len(lines) - MAX_COMMENT_LINES} more lines cut"]
    text = "\n".join(lines)
    if len(text) > MAX_COMMENT_CHARS:
        text = text[:MAX_COMMENT_CHARS] + "\n… cut"
    return text.strip("\n")


def fence(text: str) -> str:
    """A fence longer than any backtick run inside, so nothing escapes it.

    Inside a fence GitHub renders no mentions and no links, which is what keeps
    an `@name` in someone's stack trace from paging a stranger.
    """
    longest = max((len(m) for m in re.findall(r"`+", text)), default=0)
    ticks = "`" * max(3, longest + 1)
    return f"{ticks}text\n{text}\n{ticks}"


def marker(body: str) -> str:
    """Identifies this exact body, so an edit re-runs and a re-run does not."""
    return f"{MARKER_PREFIX}{hashlib.sha256(body.encode('utf-8')).hexdigest()[:16]} -->"


def already_commented(repo: str, issue: int, mark: str) -> bool:
    try:
        data = gh_json(["issue", "view", str(issue), "--repo", repo, "--json", "comments"])
    except subprocess.CalledProcessError:
        return False
    return any(mark in (c.get("body") or "") for c in data.get("comments", []))  # type: ignore[union-attr]


def post(repo: str, issue: int, text: str, dry_run: bool) -> None:
    if dry_run:
        print("--- comment ---")
        print(text)
        return
    with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False, encoding="utf-8") as f:
        f.write(text)
        path = f.name
    subprocess.run(
        ["gh", "issue", "comment", str(issue), "--repo", repo, "--body-file", path],
        check=True,
    )
    os.unlink(path)


# --- main ----------------------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    parser.add_argument("--issue", type=int, default=int(os.environ.get("ISSUE_NUMBER", "0") or 0))
    parser.add_argument("--dry-run", action="store_true", help="print the comment instead of posting it")
    parser.add_argument("--local-mapping", help="retrace against this mapping instead of a release asset")
    args = parser.parse_args()

    body = os.environ.get("BODY", "")
    if not body.strip():
        print("empty body, nothing to do")
        return 0
    # Attachments first: a report shared from the app is a file, so the body
    # alone is often just "crash log attached" with the trace and the version
    # header both inside it.
    body = with_attachments(body)
    if len(body) > MAX_BODY_CHARS:
        body = body[:MAX_BODY_CHARS]

    if not OBFUSCATED_RE.search(body):
        print("no obfuscated frames, nothing to do")
        return 0

    mark = marker(body)
    if args.issue and args.repo and not args.dry_run and already_commented(args.repo, args.issue, mark):
        print("this exact body was already retraced")
        return 0

    workdir = Path(os.environ.get("RUNNER_TEMP", tempfile.gettempdir())) / "retrace"
    workdir.mkdir(parents=True, exist_ok=True)
    cache = workdir / "cache"

    # --- a local mapping: the offline path, for testing the pipeline ---------
    if args.local_mapping:
        mapping = Path(args.local_mapping)
        out = retrace(r8_jar(compiler_version(mapping), cache), mapping, body, workdir)
        if not out:
            return 0
        header = f"Retraced against `{mapping.name}`."
        post(args.repo, args.issue, f"{mark}\n{header}\n\n{fence(sanitize(out))}", args.dry_run)
        return 0

    build = find_build(body)
    if build is None:
        post(
            args.repo,
            args.issue,
            f"{mark}\nThis looks like a stack trace from a release build, but it carries no "
            "`version:` line, and which build it came from is what decides which mapping can "
            "read it. In the app, **Settings › About › Diagnostics** → hold the crash text "
            "copies it with the `version:` and `android:` lines in front, or **Share "
            "diagnostics** exports the whole report.",
            args.dry_run,
        )
        return 0

    tag = f"v{build.version}"
    assets = release_assets(args.repo, tag)
    if not assets:
        print(f"no release {tag}")
        return 0

    # Asset names carry the version code, which an old record may not have, so
    # match on the suffix and read the code back off the name. The F-Droid
    # suffix matches one mapping per ABI, and the record does not say which
    # ABI it is: all of them go in, and the fit check picks.
    names: list[str] = []
    for suffix in candidate_suffixes(build):
        wanted = f"-{suffix}-mapping.txt.gz"
        names += sorted(n for n in assets if n.endswith(wanted) and n not in names)
    if not names and build.channel == "F-Droid":
        post(
            args.repo,
            args.issue,
            f"{mark}\nNo mapping of the F-Droid build was published for {tag}, so this trace "
            "cannot be retraced. F-Droid serves the same APK this project's release workflow "
            "builds, byte for byte, and that build's R8 mapping is attached to every release "
            f"from {FDROID_MAPPINGS_FROM} on. On an older version, a crash from a build on the "
            "[releases page](https://github.com/wasi-master/wmkeyboard/releases/latest) can be "
            "retraced.",
            args.dry_run,
        )
        return 0
    if not names:
        post(
            args.repo,
            args.issue,
            f"{mark}\nNo R8 mapping was published for {tag}, so this trace cannot be retraced. "
            "Mappings are attached to every release from 0.5.8 on.",
            args.dry_run,
        )
        return 0

    frames = [(cls, method, int(line)) for cls, method, line in OBF_FRAME_RE.findall(body)]
    if not frames:
        print("frames carry no line numbers, so no mapping can be checked against them")
        return 0

    # Check every candidate against the trace before retracing anything. The
    # check is cheap next to being wrong, and a mapping that does not fit is
    # not a worse answer than another one — it is not an answer.
    best: tuple[float, str, Path] | None = None
    for name in names:
        archive = download_asset(args.repo, tag, name, workdir)
        if archive is None:
            continue
        mapping = gunzip(archive)
        fit = mapping_fit(mapping, frames)
        print(f"{name}: fits {fit:.0%} of {len(frames)} frames")
        if best is None or fit > best[0]:
            best = (fit, name, mapping)
        if fit >= 0.95:
            break

    if best is None or best[0] < MIN_FIT:
        best_line = f" The closest, `{best[1]}`, accounts for {best[0]:.0%} of it." if best else ""
        if build.channel != "F-Droid" and FDROID_PROSE_RE.search(body):
            fdroid = (
                " The report says this came from F-Droid, which would explain it if this release "
                f"is older than {FDROID_MAPPINGS_FROM}: the F-Droid build's mapping was only "
                "published from then on."
            )
        else:
            fdroid = (
                " That usually means the build did not come from this release — a different "
                f"version, an F-Droid build from before {FDROID_MAPPINGS_FROM}, or a build made "
                "locally."
            )
        post(
            args.repo,
            args.issue,
            f"{mark}\nNo mapping published for {tag} fits this trace, so retracing it would "
            f"produce names that read well and mean nothing.{best_line}{fdroid}\n\n"
            "If the app is from this release, **Settings › About › Diagnostics** → hold the "
            "crash text copies it with the `version:` and `android:` lines, which say exactly "
            "which build it is.",
            args.dry_run,
        )
        return 0

    fit, name, mapping = best
    out = retrace(r8_jar(compiler_version(mapping), cache), mapping, body, workdir)
    if not out:
        print("retrace produced nothing")
        return 0

    lines = [
        mark,
        f"**Retraced** — [`{name}`]"
        f"(https://github.com/{args.repo}/releases/download/{tag}/{name}) accounts for "
        f"{fit:.0%} of the {len(frames)} obfuscated frames.",
    ]
    if fit < SURE_FIT:
        lines.append(
            "\n> Not every frame is covered by this mapping, so treat the line numbers as a lead "
            "rather than a fact.",
        )
    lines.append("")
    lines.append("<details><summary>Retraced trace</summary>\n")
    lines.append(fence(sanitize(out)))
    lines.append("\n</details>")
    post(args.repo, args.issue, "\n".join(lines), args.dry_run)
    return 0


if __name__ == "__main__":
    sys.exit(main())
