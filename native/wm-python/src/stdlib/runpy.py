"""runpy: running a module or a file as a program. A plugin is already the
program, and has no files to run: both raise, as CPython does for a module
it cannot find."""

__all__ = ["run_module", "run_path"]


def run_module(mod_name, init_globals=None, run_name=None, alter_sys=False):
    raise ImportError(f"plugins cannot run a module as a program: {mod_name}")


def run_path(path_name, init_globals=None, run_name=None):
    raise PermissionError(13, "plugins have no file system: keep data with wm.storage, and open the user's files with wm.files")
