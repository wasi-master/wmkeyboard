#!/usr/bin/env python3
"""Check the JVM settings shots against the manifest and the app's routes.

The docs-shots run (app/src/docShots, `-Pwmkb.docShots=true`) renders every
settings-app shot from a hand-written list in Shots.kt: an id, the route it
opens, and the row it rings. This reads that list beside manifest.json and the
deep-link allowlist (SettingsRoutes.kt) and says where they disagree, without
Gradle:

  * manifest shots of the settings app with no Shot (never rendered on the JVM),
  * Shots whose id the manifest no longer has,
  * Shots whose route no screen answers any more (a page that moved or split).

A shot whose page still exists but whose row moved to another page renders
without its ring; the run logs that, this cannot see it.

Usage:
    python3 docs/screenshots/shot_routes.py            # the report
    python3 docs/screenshots/shot_routes.py --stubs    # also Kotlin lines for the missing ids
    python3 docs/screenshots/shot_routes.py --ids      # only the ids to re-render, one a line

`--ids` prints the ids whose route no screen answers, which is
the list to hand the docs-shots run as `--tests` filters after a settings
reorganisation.
"""
import argparse
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
MANIFEST = os.path.join(HERE, "manifest.json")
SHOTS_DIR = os.path.join(ROOT, "app", "src", "docShots", "java", "com", "wasimaster", "wmkeyboard", "docshots")
ROUTES = os.path.join(ROOT, "app", "src", "main", "java", "com", "wasimaster", "wmkeyboard", "app", "SettingsRoutes.kt")

# Shot("id", "route"  — the first two string arguments, on one line or split.
SHOT = re.compile(r'\bShot\(\s*"([^"]+)"\s*,\s*"([^"]*)"', re.S)
# same("id", other) reuses another Shot's route under a second id.
SAME = re.compile(r'\bsame\(\s*"([^"]+)"\s*,\s*(\w+)\s*\)')
# private val name = Shot("id", ...
NAMED = re.compile(r'\bval\s+(\w+)\s*=\s*Shot\(\s*"([^"]+)"', re.S)
LINK = re.compile(r'wmkeyboard://settings/([A-Za-z0-9_/%.-]+)')
PATTERN = re.compile(r'^\s*"([a-z0-9_/{}A-Z.-]+)",\s*$')


def shots():
    """id -> route for every Shot in the docShots sources."""
    out, names = {}, {}
    for name in sorted(os.listdir(SHOTS_DIR)):
        if not name.endswith(".kt"):
            continue
        text = open(os.path.join(SHOTS_DIR, name), encoding="utf-8").read()
        for m in SHOT.finditer(text):
            out.setdefault(m.group(1), m.group(2))
        for m in NAMED.finditer(text):
            names[m.group(1)] = m.group(2)
        for m in SAME.finditer(text):
            src = names.get(m.group(2))
            if src in out:
                out.setdefault(m.group(1), out[src])
    return out


def route_patterns():
    """The allowlist's patterns, `{arg}` segments included, from its `all` list."""
    text = open(ROUTES, encoding="utf-8").read()
    pats = set()
    for line in text.splitlines():
        m = PATTERN.match(line)
        if m and "/" in m.group(1) or (m and m.group(1).isidentifier()):
            pats.add(m.group(1))
    return pats


def resolves(route, patterns):
    """Whether a concrete route matches a pattern segment by segment, as the NavHost does."""
    if route in patterns:
        return True
    parts = route.split("/")
    for p in patterns:
        segs = p.split("/")
        if len(segs) == len(parts) and all(s.startswith("{") or s == r for s, r in zip(segs, parts)):
            return True
    return False


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--stubs", action="store_true", help="print Kotlin Shot lines for manifest ids with none")
    ap.add_argument("--ids", action="store_true", help="print only the ids whose route moved or differs")
    args = ap.parse_args()

    manifest = [e for e in json.load(open(MANIFEST, encoding="utf-8")) if e.get("host") == "settings"]
    by_id = {e["id"]: e for e in manifest}
    have = shots()
    patterns = route_patterns()

    missing = [i for i in by_id if i not in have]
    stale = [i for i in have if i not in by_id]
    moved = sorted(i for i, r in have.items() if r and not resolves(r, patterns))
    if args.ids:
        for i in moved:
            print(i)
        return 0

    print(f"{len(manifest)} settings shots in the manifest, {len(have)} Shots on the JVM list, "
          f"{len(patterns)} route patterns.")
    print(f"\nNo Shot ({len(missing)}):")
    for i in missing:
        print(f"  {i}")
    print(f"\nShot with no manifest entry ({len(stale)}):")
    for i in stale:
        print(f"  {i}")
    print(f"\nRoute no screen answers ({len(moved)}):")
    for i in moved:
        print(f"  {i}: {have[i]}")
    if args.stubs and missing:
        print("\n// Stubs for Shots.kt; check each route and add the row to ring.")
        for i in missing:
            links = [m.group(1).rstrip("/.") for s in by_id[i].get("setup", []) for m in LINK.finditer(s)]
            route = links[0] if links else "TODO"
            print(f'private val {re.sub(r"[^A-Za-z0-9]", "_", i)} = Shot("{i}", "{route}")')
    return 1 if moved else 0


if __name__ == "__main__":
    sys.exit(main())
