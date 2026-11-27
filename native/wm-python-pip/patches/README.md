# Patches for PyPI packages

Small changes that let a popular package run in a WM Keyboard plugin, the way
Pyodide keeps patches for the packages it ships. `wmpy pip install` and
`wmpy pip check` apply them to the package's files after downloading it, and
say which ones they applied.

One file per project, named by its PEP 503 name (`click.json`,
`python-dateutil.json`), listed in `PATCHES` in `src/patches.rs`:

```json
{
  "project": "click",
  "edits": [
    {
      "file": "click/termui.py",
      "find": "exact text in the file",
      "replace": "what goes in its place",
      "why": "one sentence: what fails without it"
    }
  ]
}
```

- `find` is replaced once, where it first appears. Keep it short but unique,
  so it survives unrelated changes in new releases.
- When `find` is not in the file, the edit is skipped and wmpy says so. Check
  the new release and update the patch.
- A fix more than one package needs (a missing module, builtin or attribute)
  belongs in the interpreter (`native/wm-python`), not in patches.
