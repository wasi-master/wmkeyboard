#!/usr/bin/env python3
"""Tell every issue a release mentions that it has shipped.

Reads the body of a published GitHub release, finds each issue it links, and
comments on that issue: which version it is in, a mention of whoever opened
it and of everyone who reacted to it (any reaction but 👎 and 😕), any settings the change added (as ``wmkeyboard.pages.dev/open/`` links that
open the row in the app), and a note that F-Droid and Google Play lag the
GitHub release by a few days.

How the settings are found, since nothing records them per issue:

1. **The commits behind the issue.** Every commit between the previous tag and
   this one whose message names the issue (``Closes #285``, ``Refs #285``),
   plus the commits GitHub itself tied to it (the issue's timeline: a
   ``referenced`` or ``closed`` event, or a merged pull request that
   cross-references it), plus any commit a maintainer's comment names by hash
   ("fixed in 9dc0e42"). Only commits inside the release's own range count,
   so a hash that happens to resolve to something older changes nothing.
2. **The rows those commits added.** A ``<string name="…">`` added under
   ``src/main/res/values/`` that no ``values/`` file had at the previous tag,
   and that ``docs/src/data/settings-links.json`` knows as a settings screen or
   row. That file is the app's own settings search index, dumped by
   ``SettingsLinksDump``, and a row's link name *is* the resource name of its
   title, so the match is exact rather than a guess from wording.

Safe to run again, and meant to be: each comment carries a hidden marker for
its tag, so a second run skips an issue already told (or updates the comment
if the text has changed since), and a release published twice, once by the
workflow and once by hand, still tells each issue once.

Usage::

    release_comments.py --repo owner/name --tag v0.5.12 [--dry-run]
    release_comments.py --tag v0.5.11 --dry-run --only 285,302 --out /tmp/c

The token comes from ``GH_TOKEN`` or ``GITHUB_TOKEN``, or ``gh auth token``
when neither is set. Needs a checkout with full history and tags.

Exit status: 0 when every issue was told or deliberately skipped, 1 when a
comment failed to post (the rest are still tried first), 2 for a run that
could not start (no such release, a tag missing from the checkout), and 3 for
a release that is not one to announce: a draft, a pre-release, or one with no
APKs attached.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

NOT_ANNOUNCEABLE = 3

API = "https://api.github.com"
SITE = "https://wmkeyboard.pages.dev"
SETTINGS_LINKS = "docs/src/data/settings-links.json"
STRINGS_GLOB = ":(glob)**/src/main/res/values/*.xml"

# The first release that answers wmkeyboard:// links at all. A link carries
# `since=` only past it, the way `withSince` in docs/src/lib/settings-since.ts
# writes them, so an app new enough to open a link is never told it is too old.
SINCE_FLOOR = "0.5.3"
# Rows on a keyboard mode's editor, which exists once per mode.
MODE_EDITOR = "mode_edit/{modeId}"
MODE_LIST_ROUTE = "modes"
# The file whose presence at a tag means that release opens settings links.
DEEP_LINK_SOURCE = "SettingsDeepLink.kt"

# More issues than any release has linked (0.5.9 has 31). Past it the body has
# almost certainly been misread, and 200 comments are not something to post on
# a hunch.
MAX_ISSUES = 80
# Settings listed in one comment before the rest are summed up.
MAX_SETTINGS = 8
# Commit hashes listed in one comment.
MAX_COMMITS = 6
# GitHub asks for at least a second between content-creating requests.
WRITE_PAUSE_SECONDS = 1.5

# Reactions that mean "not for me". Every other one (👍 😄 🎉 ❤️ 🚀 👀) counts.
NEGATIVE_REACTIONS = {"-1", "confused"}
# Reactors mentioned by name in one comment; the rest are counted, not pinged.
MAX_REACTORS = 25

MAINTAINER_ASSOCIATIONS = {"OWNER", "MEMBER", "COLLABORATOR"}
DEFAULT_STORES = "F-Droid,Google Play"


# --- GitHub ------------------------------------------------------------------


class GitHubError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(f"HTTP {status}: {message}")
        self.status = status


def token() -> str:
    for name in ("GH_TOKEN", "GITHUB_TOKEN"):
        if os.environ.get(name):
            return os.environ[name]
    try:
        return subprocess.run(
            ["gh", "auth", "token"], check=True, capture_output=True, text=True
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        sys.exit("No GH_TOKEN or GITHUB_TOKEN, and `gh auth token` gave nothing.")


class GitHub:
    def __init__(self, repo: str, auth: str):
        self.repo = repo
        self.auth = auth

    def request(self, method: str, path: str, body: dict | None = None,
                accept: str = "application/vnd.github+json"):
        """One call, with retries for rate limits and server hiccups.

        Returns ``(json, headers)``. Raises GitHubError for any other failure.
        """
        url = path if path.startswith("https://") else f"{API}{path}"
        # A POST that timed out or drew a 5xx may still have landed, and a
        # second one would be a duplicate comment. Only a refusal the server
        # states (a rate limit) is safe to repeat for it.
        replayable = method != "POST"
        data = json.dumps(body).encode() if body is not None else None
        for attempt in range(5):
            req = urllib.request.Request(url, data=data, method=method, headers={
                "Accept": accept,
                "Authorization": f"Bearer {self.auth}",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "wmkeyboard-release-comments",
                **({"Content-Type": "application/json"} if data else {}),
            })
            try:
                with urllib.request.urlopen(req, timeout=30) as resp:
                    raw = resp.read()
                    return (json.loads(raw) if raw else None), resp.headers
            except urllib.error.HTTPError as e:
                text = e.read().decode(errors="replace")
                try:
                    message = json.loads(text).get("message", text)
                except ValueError:
                    message = text
                limited = e.code == 429 or (
                    e.code == 403 and ("rate limit" in message.lower()
                                       or e.headers.get("Retry-After")))
                if (limited or (e.code >= 500 and replayable)) and attempt < 4:
                    wait = int(e.headers.get("Retry-After") or 0)
                    if not wait and e.headers.get("X-RateLimit-Remaining") == "0":
                        reset = int(e.headers.get("X-RateLimit-Reset") or 0)
                        wait = max(reset - int(time.time()), 1)
                    wait = min(max(wait, 5 * (attempt + 1)), 120)
                    log(f"  {method} {path}: {e.code}, retrying in {wait}s")
                    time.sleep(wait)
                    continue
                raise GitHubError(e.code, message) from None
            except (urllib.error.URLError, TimeoutError) as e:
                if replayable and attempt < 4:
                    time.sleep(5 * (attempt + 1))
                    continue
                raise GitHubError(0, str(e)) from None
        raise GitHubError(0, "retries exhausted")

    def get(self, path: str, **kw):
        return self.request("GET", path, **kw)[0]

    def paginate(self, path: str, **kw) -> list:
        sep = "&" if "?" in path else "?"
        url, out = f"{path}{sep}per_page=100", []
        while url:
            page, headers = self.request("GET", url, **kw)
            out.extend(page or [])
            url = None
            for part in (headers.get("Link") or "").split(","):
                m = re.search(r'<([^>]+)>;\s*rel="next"', part)
                if m:
                    url = m.group(1)
        return out


# --- git ---------------------------------------------------------------------


def git(*args: str, check: bool = True) -> str:
    result = subprocess.run(["git", *args], capture_output=True, text=True)
    if check and result.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)}: {result.stderr.strip()}")
    return result.stdout if result.returncode == 0 else ""


def previous_tag(tag: str) -> str | None:
    """The release before this one, by version, whether or not this one contains it.

    Ancestry is not enough: a re-cut tag sits on its own commit off main (0.6.2
    did), so 0.6.3 contained 0.6.1 but not 0.6.2 and was measured from 0.6.1.
    ``prev..tag`` is still the right range for a tag that is not an ancestor.
    """
    mine = version_key(tag)
    # A stable release counts from the stable one before it, past its own
    # pre-releases, which never comment: their issues hear from this one.
    stable = not is_prerelease(tag)
    candidates = [
        t for t in git("tag", "--list", "v*").split()
        if t != tag and version_key(t) < mine and not (stable and is_prerelease(t))
    ]
    return max(candidates, key=version_key) if candidates else None


def is_prerelease(tag: str) -> bool:
    """v0.6.8rc1 and v0.7.0-beta.2 are; v0.6.8 is not."""
    return re.fullmatch(r"\d+(\.\d+)*", tag.lstrip("v")) is None


def version_key(v: str) -> tuple:
    """Orders 0.6.7 < 0.6.8rc1 < 0.6.8rc2 < 0.6.8 < 0.6.9."""
    m = re.match(r"(\d+(?:\.\d+)*)[-.]?(.*)", v.lstrip("v"))
    if not m:
        return ((0,), 0, ())
    release = tuple(int(n) for n in m.group(1).split("."))
    suffix = m.group(2)
    if not suffix:
        return (release, 1, ())
    pre = tuple(int(n) if n.isdigit() else 0 for n in re.findall(r"\d+", suffix))
    return (release, 0, pre)


def string_names_at(ref: str | None) -> set[str]:
    if not ref:
        return set()
    out = git("grep", "-hoE", r'<string[^>]*name="[^"]+"', ref, "--", STRINGS_GLOB, check=False)
    return set(re.findall(r'name="([^"]+)"', out))


def added_string_names(sha: str) -> set[str]:
    diff = git("diff-tree", "--root", "-r", "-p", "-U0", "--no-color", "--no-renames",
               "--no-commit-id", sha, "--", STRINGS_GLOB)
    return set(re.findall(r'^\+[^+].*?<string\b[^>]*\bname="([^"]+)"', diff, re.MULTILINE))


def has_file(ref: str, basename: str) -> bool:
    return any(p.endswith("/" + basename) for p in git("ls-tree", "-r", "--name-only", ref).split("\n"))


# --- reading the release -----------------------------------------------------


def strip_code(markdown: str) -> str:
    """Drops fenced blocks, inline code and HTML comments, where a `#12` is not a link."""
    text = re.sub(r"<!--.*?-->", " ", markdown, flags=re.DOTALL)
    text = re.sub(r"^(```|~~~).*?^\1", " ", text, flags=re.DOTALL | re.MULTILINE)
    return re.sub(r"`[^`\n]*`", " ", text)


def issue_refs(text: str, repo: str) -> list[int]:
    """Issue numbers a piece of text links, in order of first appearance.

    ``#12`` counts only when it stands alone: not ``other/repo#12``, not the
    ``&#12;`` of an HTML entity, not ``#12abc``. A full URL counts only for
    this repository.
    """
    found: dict[int, int] = {}
    url = re.compile(rf"https?://github\.com/{re.escape(repo)}/issues/(\d+)\b", re.IGNORECASE)
    bare = re.compile(r"(?<![\w/&#;])#(\d+)\b")
    for pattern in (url, bare):
        for m in pattern.finditer(text):
            n = int(m.group(1))
            if n > 0:
                found.setdefault(n, m.start())
    return sorted(found, key=found.get)


def commit_refs(message: str, repo: str) -> set[int]:
    return set(issue_refs(message, repo))


# --- the settings index ------------------------------------------------------


@dataclass
class Row:
    title: str
    name: str
    route: str
    screens: list[str]
    screen: bool
    pattern: str = ""


def load_index(tag: str) -> dict[str, list[Row]]:
    """name → entries, from the tag's copy of the index and the checkout's.

    The tag's copy describes the app as it shipped, so its entries go first.
    The checkout's (main, normally) fills in what the tag's lacks: the index is
    refreshed by a workflow the tag push itself starts, so rows added just
    before the tag are often only in main's copy.
    """
    sources = [git("show", f"{tag}:{SETTINGS_LINKS}", check=False)]
    if Path(SETTINGS_LINKS).is_file():
        sources.append(Path(SETTINGS_LINKS).read_text())
    index: dict[str, list[Row]] = {}
    for i, raw in enumerate(sources):
        if not raw.strip():
            continue
        try:
            entries = json.loads(raw)
        except ValueError as e:
            log(f"::warning::Could not read {SETTINGS_LINKS} ({'tag' if i == 0 else 'checkout'}): {e}")
            continue
        fresh: dict[str, list[Row]] = {}
        for e in entries:
            if not isinstance(e, dict) or not e.get("name") or not e.get("title"):
                continue
            fresh.setdefault(e["name"], []).append(Row(
                title=str(e["title"]), name=str(e["name"]), route=str(e.get("route") or ""),
                screens=[str(s) for s in e.get("screens") or []], screen=bool(e.get("screen")),
                pattern=str(e.get("pattern") or ""),
            ))
        for name, rows in fresh.items():
            index.setdefault(name, rows)
    return index


@dataclass
class NewSetting:
    title: str
    crumbs: list[str]
    url: str
    screen: bool
    route: str


def open_url(route: str | None, name: str | None, version: str) -> str:
    params = []
    if route is not None:
        params.append(("route", route))
    if name:
        params.append(("setting", name))
    if version_key(version) > version_key(SINCE_FLOOR):
        params.append(("since", version))
    return f"{SITE}/open/?" + urllib.parse.urlencode(params, safe="/")


def describe(name: str, rows: list[Row], version: str) -> NewSetting:
    screens = [r for r in rows if r.screen]
    if screens:
        s = screens[0]
        return NewSetting(s.title, s.screens, open_url(s.route, None, version), True, s.route)
    if any(r.pattern == MODE_EDITOR for r in rows):
        # A row on every keyboard mode's own editor. There is no one mode to
        # send it to, so the link opens the list of modes.
        first = rows[0]
        return NewSetting(first.title, ["Keyboard modes", "any mode"],
                          open_url(MODE_LIST_ROUTE, None, version), False, MODE_LIST_ROUTE)
    first = rows[0]
    # One screen holds it: name the row alone and let the app find it, as the
    # docs chips do. Several (a GIF and a sticker tool both have "Results for
    # each search"): pin the first screen so the link lands somewhere definite.
    route = first.route if len({r.route for r in rows}) > 1 else None
    return NewSetting(first.title, first.screens, open_url(route, name, version), False, first.route)


def under(row: NewSetting, screen: NewSetting) -> bool:
    """Whether a row lives on (or below) a screen, by route or by breadcrumb.

    Both, because a screen's sub-pages do not always nest their routes:
    KDE Connect is ``tool/KDE_CONNECT`` and its device list ``kdeconnect/devices``.
    """
    if row.route == screen.route or row.route.startswith(screen.route.rstrip("/") + "/"):
        return True
    path = screen.crumbs + [screen.title]
    return row.crumbs[:len(path)] == path


# --- one issue ---------------------------------------------------------------


@dataclass
class Plan:
    number: int
    title: str = ""
    author: str | None = None
    reactors: list[str] = field(default_factory=list)
    state: str = ""
    commits: list[str] = field(default_factory=list)
    settings: list[NewSetting] = field(default_factory=list)
    existing: dict | None = None
    skip: str | None = None
    body: str = ""


def find_reactors(gh: GitHub, number: int, exclude: set[str]) -> list[str]:
    """People who reacted to the issue itself with anything but 👎 or 😕, oldest first.

    Bots, the ghost account and anyone in ``exclude`` (the author, the owner)
    are left out, and each person is listed once however many reactions they gave.
    """
    try:
        reactions = gh.paginate(f"/repos/{gh.repo}/issues/{number}/reactions")
    except GitHubError as e:
        log(f"  #{number}: no reactions ({e})")
        return []
    seen = {e.lower() for e in exclude}
    out = []
    for r in reactions:
        user = r.get("user") or {}
        login = user.get("login")
        if (r.get("content") in NEGATIVE_REACTIONS or user.get("type") != "User"
                or not login or login == "ghost" or login.lower() in seen):
            continue
        seen.add(login.lower())
        out.append(login)
    return out


def find_commits(gh: GitHub, number: int, comments: list[dict], by_message: dict[int, list[str]],
                 in_range: set[str], order: dict[str, int]) -> list[str]:
    found = set(by_message.get(number, []))

    def keep(sha: str | None):
        if not sha:
            return
        full = sha if len(sha) == 40 else git("rev-parse", "--verify", "--quiet",
                                               f"{sha}^{{commit}}", check=False).strip()
        if full in in_range:
            found.add(full)

    try:
        timeline = gh.paginate(f"/repos/{gh.repo}/issues/{number}/timeline")
    except GitHubError as e:
        log(f"  #{number}: no timeline ({e}); commit messages only")
        timeline = []
    for ev in timeline:
        kind = ev.get("event")
        if kind in ("referenced", "closed"):
            keep(ev.get("commit_id"))
        elif kind == "cross-referenced":
            src = (ev.get("source") or {}).get("issue") or {}
            pr = src.get("pull_request") or {}
            if not pr.get("merged_at") or (src.get("repository") or {}).get("full_name") != gh.repo:
                continue
            try:
                for c in gh.paginate(f"/repos/{gh.repo}/pulls/{src['number']}/commits"):
                    keep(c.get("sha"))
                keep(gh.get(f"/repos/{gh.repo}/pulls/{src['number']}").get("merge_commit_sha"))
            except GitHubError as e:
                log(f"  #{number}: could not read PR #{src.get('number')} ({e})")

    # "Fixed in 9dc0e42", from someone who would know. At least one letter in
    # the hash, so a number in a sentence is never taken for a commit.
    for c in comments:
        if c.get("author_association") not in MAINTAINER_ASSOCIATIONS:
            continue
        for token in re.findall(r"(?<![\w/])([0-9a-f]{7,40})\b", c.get("body") or ""):
            if re.search(r"[a-f]", token):
                keep(token)
        for token in re.findall(rf"github\.com/{re.escape(gh.repo)}/commit/([0-9a-f]{{7,40}})",
                                c.get("body") or "", re.IGNORECASE):
            keep(token)

    return sorted(found, key=lambda s: order.get(s, 0))


def find_settings(commits: list[str], before: set[str], after: set[str],
                  index: dict[str, list[Row]], version: str, merges: set[str]) -> list[NewSetting]:
    names: set[str] = set()
    for sha in commits:
        if sha in merges:
            # A merge's own diff repeats its branch's commits, which are
            # already on the list whenever the PR was found.
            continue
        names |= added_string_names(sha)
    # New in this release, still there at its tag, and a settings screen or row.
    names = {n for n in names if n not in before and n in after and n in index}
    found = sorted((describe(n, index[n], version) for n in names),
                   key=lambda s: (not s.screen, s.crumbs, s.title))
    # A new screen stands for the rows on it: a tool that arrives with twenty
    # options is one link, not twenty-one.
    screens = [s for s in found if s.screen]
    return [s for s in found
            if s.screen or not any(under(s, parent) for parent in screens)]


# --- the comment -------------------------------------------------------------


def marker(tag: str) -> str:
    return f"<!-- wmkb-release-comment tag={tag} -->"


def join_words(items: list[str]) -> str:
    return items[0] if len(items) == 1 else ", ".join(items[:-1]) + " and " + items[-1]


def compose(plan: Plan, *, tag: str, version: str, release_url: str, repo: str,
            owner: str, stores: list[str]) -> str:
    lines = [marker(tag)]
    shipped = ("The change for this issue is out in" if plan.state == "closed"
               else "Work on this issue is out in")
    greeting = f"@{plan.author}, " if plan.author and plan.author.lower() != owner.lower() else ""
    if greeting:
        shipped = shipped[0].lower() + shipped[1:]
    lines.append(f"🚀 {greeting}{shipped} **[WM Keyboard {version}]({release_url})**.")

    if plan.reactors:
        named = [f"@{r}" for r in plan.reactors[:MAX_REACTORS]]
        more = len(plan.reactors) - len(named)
        lines.append("")
        lines.append("Also letting " + join_words(named + ([f"{more} others"] if more > 0 else []))
                     + " know, since you reacted to this issue.")

    if plan.commits:
        shown = [f"[`{s[:9]}`](https://github.com/{repo}/commit/{s})" for s in plan.commits[:MAX_COMMITS]]
        more = len(plan.commits) - len(shown)
        lines.append("")
        lines.append("Changed in " + join_words(shown + ([f"{more} more"] if more > 0 else [])) + ".")

    if plan.settings:
        lines.append("")
        lines.append("**New in the settings** with this change. Each link opens it in the app:")
        lines.append("")
        for s in plan.settings[:MAX_SETTINGS]:
            where = " › ".join(s.crumbs)
            what = "screen" if s.screen else "setting"
            lines.append(f"- [**{s.title}**]({s.url}) ({what}{', under ' + where if where else ''})")
        rest = len(plan.settings) - MAX_SETTINGS
        if rest > 0:
            lines.append(f"- and {rest} more")
        lines.append("")
        lines.append(f"> [!NOTE]\n> These open only once {version} or newer is installed. "
                     "An older version does not have them yet.")

    lines.append("")
    get = f"The APKs are on the [release page]({release_url}) now."
    if stores:
        verb = "takes" if len(stores) == 1 else "take"
        get += (f" {join_words(stores)} usually {verb} 2 to 3 days to publish a new version, "
                "so if the store still shows the older one, it is on its way.")
    lines.append(get)
    lines.append("")
    lines.append("<sub>Posted automatically when the release was published.</sub>")
    return "\n".join(lines) + "\n"


# --- main --------------------------------------------------------------------


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


def summary(md: str) -> None:
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write(md + "\n")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", "wasi-master/wmkeyboard"))
    ap.add_argument("--tag", required=True)
    ap.add_argument("--dry-run", action="store_true", help="Print the comments; post nothing.")
    ap.add_argument("--only", default="", help="Comma-separated issue numbers to limit the run to.")
    ap.add_argument("--out", help="Also write each comment to <dir>/<issue>.md.")
    ap.add_argument("--stores", default=DEFAULT_STORES,
                    help="Comma-separated stores that lag the GitHub release. Empty for none.")
    args = ap.parse_args()

    tag = args.tag.strip()
    if not re.fullmatch(r"v\d+(\.\d+)*([-.][\w.]+)?", tag):
        log(f"::error::{tag!r} does not look like a release tag")
        return 2
    only = {int(n) for n in re.findall(r"\d+", args.only)}
    stores = [s.strip() for s in args.stores.split(",") if s.strip()]
    gh = GitHub(args.repo, token())
    owner = args.repo.split("/")[0]

    # --- the release must really be out, with something to download.
    try:
        release = gh.get(f"/repos/{args.repo}/releases/tags/{urllib.parse.quote(tag)}")
    except GitHubError as e:
        log(f"::error::No published release for {tag} ({e})")
        return 2
    if release.get("draft"):
        log(f"::notice::{tag} is still a draft")
        return NOT_ANNOUNCEABLE
    if release.get("prerelease"):
        log(f"::notice::{tag} is a pre-release; issues are told about full releases only")
        return NOT_ANNOUNCEABLE
    apks = [a for a in release.get("assets") or []
            if a.get("name", "").endswith(".apk") and a.get("state") == "uploaded"]
    if not apks:
        log(f"::notice::{tag} has no APKs attached, so there is nothing to tell anyone to install")
        return NOT_ANNOUNCEABLE
    version = tag.lstrip("v")
    release_url = release["html_url"]

    if git("rev-parse", "--verify", "--quiet", f"{tag}^{{commit}}", check=False).strip() == "":
        log(f"::error::{tag} is not in this checkout. Fetch with full history and tags.")
        return 2

    refs = issue_refs(strip_code(release.get("body") or ""), args.repo)
    if only:
        refs = [n for n in refs if n in only]
    log(f"{tag}: {len(apks)} APKs, {len(refs)} issue references: {', '.join(map(str, refs)) or '-'}")
    if len(refs) > MAX_ISSUES:
        log(f"::error::{len(refs)} issues is more than any release links; refusing to comment on all of them")
        return 2
    if not refs:
        summary(f"### Release comments for {tag}\n\nThe release notes link no issues.")
        return 0

    # --- what the release range holds.
    prev = previous_tag(tag)
    span = f"{prev}..{tag}" if prev else tag
    log(f"range: {span}")
    in_range = set(git("rev-list", span).split())
    merges = set(git("rev-list", "--merges", span).split())
    order = {sha: i for i, sha in enumerate(reversed(git("rev-list", "--topo-order", span).split()))}
    by_message: dict[int, list[str]] = {}
    for record in git("log", "--format=%H%x1f%B%x1e", span).split("\x1e"):
        if "\x1f" not in record:
            continue
        sha, message = record.strip().split("\x1f", 1)
        for n in commit_refs(message, args.repo):
            by_message.setdefault(n, []).append(sha)

    links_work = has_file(tag, DEEP_LINK_SOURCE)
    index = load_index(tag) if links_work else {}
    before, after = (string_names_at(prev), string_names_at(tag)) if links_work else (set(), set())

    # --- plan every issue before touching any of them.
    plans: list[Plan] = []
    for n in refs:
        plan = Plan(n)
        plans.append(plan)
        try:
            issue = gh.get(f"/repos/{args.repo}/issues/{n}")
        except GitHubError as e:
            plan.skip = "deleted" if e.status in (404, 410) else f"unreadable ({e})"
            continue
        if issue.get("pull_request"):
            plan.skip = "a pull request"
            continue
        if not (issue.get("repository_url") or "").lower().endswith(f"/repos/{args.repo}".lower()):
            plan.skip = "transferred to another repository"
            continue
        plan.title = issue.get("title") or ""
        plan.state = issue.get("state") or ""
        user = issue.get("user") or {}
        if user.get("type") == "User" and user.get("login") and user["login"] != "ghost":
            plan.author = user["login"]
        plan.reactors = find_reactors(gh, n, {owner} | ({plan.author} if plan.author else set()))
        try:
            comments = gh.paginate(f"/repos/{args.repo}/issues/{n}/comments")
        except GitHubError as e:
            plan.skip = f"comments unreadable ({e})"
            continue
        plan.existing = next((c for c in comments if marker(tag) in (c.get("body") or "")), None)
        plan.commits = find_commits(gh, n, comments, by_message, in_range, order)
        if links_work:
            plan.settings = find_settings(plan.commits, before, after, index, version, merges)
        plan.body = compose(plan, tag=tag, version=version, release_url=release_url,
                            repo=args.repo, owner=owner, stores=stores)
        if plan.existing and plan.existing.get("body", "").strip() == plan.body.strip():
            plan.skip = "already told"

    # --- post.
    failed = 0
    rows = []
    for plan in plans:
        head = f"#{plan.number} {plan.title}".strip()
        if plan.skip:
            log(f"skip {head}: {plan.skip}")
            rows.append((plan, f"skipped: {plan.skip}"))
            continue
        action = "update" if plan.existing else "post"
        if args.dry_run:
            print(f"\n===== {head}  [{action}, {len(plan.commits)} commits, "
                  f"{len(plan.settings)} settings, {len(plan.reactors)} reactors] =====\n{plan.body}")
            rows.append((plan, f"would {action}"))
        else:
            try:
                if plan.existing:
                    gh.request("PATCH", f"/repos/{args.repo}/issues/comments/{plan.existing['id']}",
                               {"body": plan.body})
                else:
                    gh.request("POST", f"/repos/{args.repo}/issues/{plan.number}/comments",
                               {"body": plan.body})
                done = {"post": "posted", "update": "updated"}[action]
                log(f"{done} {head}")
                rows.append((plan, done))
            except GitHubError as e:
                failed += 1
                log(f"::warning::Could not {action} on #{plan.number}: {e}")
                rows.append((plan, f"FAILED: {e}"))
            time.sleep(WRITE_PAUSE_SECONDS)
        if args.out:
            Path(args.out).mkdir(parents=True, exist_ok=True)
            Path(args.out, f"{plan.number}.md").write_text(plan.body)

    table = [f"### Release comments for {tag}{' (dry run)' if args.dry_run else ''}", "",
             f"Range `{span}`. Settings links {'checked' if links_work else 'not available in this release'}.",
             "", "| Issue | Result | Commits | New settings | Reactors |", "|---|---|--:|--:|--:|"]
    for plan, result in rows:
        table.append(f"| #{plan.number} | {result} | {len(plan.commits)} | {len(plan.settings)} "
                     f"| {len(plan.reactors)} |")
    summary("\n".join(table))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
