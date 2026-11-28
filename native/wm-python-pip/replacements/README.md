# Stand-ins for compiled packages

A few small, popular PyPI packages ship only as compiled code (Rust or C),
which plugins cannot load. Each folder here is a pure-Python stand-in for
one of them: the same import names and the same API, written for the
keyboard's interpreter, the way Pyodide builds such packages for the
browser. `wmpy pip install`, `wmpy pip check` and the web editor's package
dialog put the stand-in in when the release they chose has no pure-Python
wheel, and say so. A later release that ships a pure-Python wheel is
installed as it is.

| Project | Imports as | Written against | Leaves out |
|---|---|---|---|
| `fastuuid` | `fastuuid` | 0.14.0 | |
| `jiter` | `jiter` | 0.19.0 | the string cache (`cache_usage()` is 0) |
| `orjson` | `orjson` | 3.13.0 | numpy arrays |
| `rpds-py` | `rpds` | 2026.9.1 | |
| `uuid-utils` | `uuid_utils` | 1.3.0 | |

Each folder holds the files as they go into a plugin (`orjson/__init__.py`),
listed in `REPLACEMENTS` in `src/replacements.rs` with the release they
follow. They are this repository's own code, under its MIT licence, which
installs as `licenses/<project>/LICENSE`.

## Adding one

- Only for a package that is small, compiled, and needed by packages that
  otherwise run: a big compiled package (numpy, pydantic-core) is not a
  stand-in's job.
- Write it against the latest release's API, and run the project's own test
  suite against it, under CPython and under `wmpy test` (copy the tests into
  a plugin folder; a small `pytest.py` with `raises` and `mark.parametrize`
  covers most suites). Note what fails and why.
- Keep it to what the keyboard's interpreter runs: no regular expressions on
  text that may be long (they are capped at 40,000 characters), no reliance
  on CPython internals.
- Add it to `REPLACEMENTS` and to the table above, and to the docs page
  `plugins/guides/python-packages.mdx`.
