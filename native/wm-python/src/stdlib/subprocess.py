"""subprocess, for a plugin, which cannot start other programs.

The names a package reads when it loads are here: the constants, the
exceptions and the result classes, and list2cmdline. Starting a program
(Popen, run, call, check_output, getoutput, ...) raises PermissionError.
"""

__all__ = [
    "Popen", "PIPE", "STDOUT", "call", "check_call", "getstatusoutput", "getoutput", "check_output", "run",
    "CalledProcessError", "DEVNULL", "SubprocessError", "TimeoutExpired", "CompletedProcess",
]

PIPE = -1
STDOUT = -2
DEVNULL = -3

_CANNOT = "plugins cannot start other programs"


class SubprocessError(Exception):
    pass


class CalledProcessError(SubprocessError):
    def __init__(self, returncode, cmd, output=None, stderr=None):
        self.returncode = returncode
        self.cmd = cmd
        self.output = output
        self.stderr = stderr

    def __str__(self):
        return f"Command '{self.cmd}' returned non-zero exit status {self.returncode}."

    @property
    def stdout(self):
        return self.output

    @stdout.setter
    def stdout(self, value):
        self.output = value


class TimeoutExpired(SubprocessError):
    def __init__(self, cmd, timeout, output=None, stderr=None):
        self.cmd = cmd
        self.timeout = timeout
        self.output = output
        self.stderr = stderr

    def __str__(self):
        return f"Command '{self.cmd}' timed out after {self.timeout} seconds"

    @property
    def stdout(self):
        return self.output


class CompletedProcess:
    def __init__(self, args, returncode, stdout=None, stderr=None):
        self.args = args
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr

    def __repr__(self):
        parts = [f"args={self.args!r}", f"returncode={self.returncode!r}"]
        if self.stdout is not None:
            parts.append(f"stdout={self.stdout!r}")
        if self.stderr is not None:
            parts.append(f"stderr={self.stderr!r}")
        return f"{type(self).__name__}({', '.join(parts)})"

    def check_returncode(self):
        if self.returncode:
            raise CalledProcessError(self.returncode, self.args, self.stdout, self.stderr)


class Popen:
    def __init__(self, args, *pargs, **kwargs):
        raise PermissionError(_CANNOT)


def run(*popenargs, **kwargs):
    raise PermissionError(_CANNOT)


def call(*popenargs, **kwargs):
    raise PermissionError(_CANNOT)


def check_call(*popenargs, **kwargs):
    raise PermissionError(_CANNOT)


def check_output(*popenargs, **kwargs):
    raise PermissionError(_CANNOT)


def getstatusoutput(cmd, *, encoding=None, errors=None):
    raise PermissionError(_CANNOT)


def getoutput(cmd, *, encoding=None, errors=None):
    raise PermissionError(_CANNOT)


def list2cmdline(seq):
    """Arguments joined the way Windows' C runtime reads them back (CPython's rules)."""
    result = []
    for arg in map(str, seq):
        bs_buf = []
        if result:
            result.append(" ")
        needquote = (" " in arg) or ("\t" in arg) or not arg
        if needquote:
            result.append('"')
        for c in arg:
            if c == "\\":
                bs_buf.append(c)
            elif c == '"':
                result.append("\\" * len(bs_buf) * 2)
                bs_buf = []
                result.append('\\"')
            else:
                if bs_buf:
                    result.extend(bs_buf)
                    bs_buf = []
                result.append(c)
        if bs_buf:
            result.extend(bs_buf)
        if needquote:
            result.extend(bs_buf)
            result.append('"')
    return "".join(result)
