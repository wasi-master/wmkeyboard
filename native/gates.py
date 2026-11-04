#!/usr/bin/env python3
"""Reads native/gates.json, the one list of on-demand native gates, for the
shell side of the build. Gradle reads the same file itself (build-logic's
NativeGates and settings.gradle.kts).

    gates.py dirs                every jniLibs directory a library lives in
                                 (collect-natives.sh)
    gates.py libs                every gated library file, <dir>/<abi>/<name>,
                                 whether or not it is checked out (natives.yml)
    gates.py fdroid-scripts      the build scripts F-Droid's build runs, in
                                 order, each once (build-fdroid-libs.sh)
    gates.py fdroid-libs <abi>   the gated libraries F-Droid's build makes from
                                 source for <abi> (release.yml's check)
    gates.py scandelete          every jniLibs directory of the table, for a
                                 new fdroiddata entry's `scandelete`

Paths are relative to the repository root. Standard library only: F-Droid's
build server and the GitHub runners both have Python 3.
"""
import json
import os
import sys

TABLE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "gates.json")


def load():
    with open(TABLE, encoding="utf-8") as f:
        return json.load(f)


def gated(table):
    for gate in table["gates"]:
        for library in gate.get("libraries", []):
            yield gate, library


def unique(items):
    seen = []
    for item in items:
        if item not in seen:
            seen.append(item)
    return seen


def main(argv):
    table = load()
    command = argv[1] if len(argv) > 1 else ""
    if command == "dirs":
        out = unique(library["dir"] for _, library in gated(table))
    elif command == "libs":
        out = unique(
            f"{library['dir']}/{abi}/{library['name']}"
            for _, library in gated(table)
            for abi in library.get("abis", table["abis"])
        )
    elif command == "fdroid-scripts":
        libraries = list(table.get("base", [])) + [library for _, library in gated(table)]
        out = unique(library["fdroidBuild"] for library in libraries if library.get("fdroidBuild"))
    elif command == "fdroid-libs" and len(argv) == 3:
        abi = argv[2]
        out = [
            library["name"]
            for _, library in gated(table)
            if library.get("fdroidBuild") and abi in library.get("abis", table["abis"])
        ]
    elif command == "scandelete":
        out = unique([library["dir"] for library in table.get("base", [])] + [library["dir"] for _, library in gated(table)])
    else:
        sys.stderr.write(__doc__)
        return 2
    for line in out:
        print(line)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
