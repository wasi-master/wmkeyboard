"""logging: loggers, handlers and formatters, writing to sys.stderr (or a
stream given) the way Python's do.

With nothing configured, a warning or worse prints its bare message, as
Python's last-resort handler does; basicConfig() sets up the root logger
with the usual "LEVEL:name:message" format.
"""

import sys as _sys
import time as _time

CRITICAL = 50
FATAL = CRITICAL
ERROR = 40
WARNING = 30
WARN = WARNING
INFO = 20
DEBUG = 10
NOTSET = 0

_level_names = {CRITICAL: "CRITICAL", ERROR: "ERROR", WARNING: "WARNING", INFO: "INFO", DEBUG: "DEBUG", NOTSET: "NOTSET"}
_name_levels = {"CRITICAL": CRITICAL, "FATAL": FATAL, "ERROR": ERROR, "WARN": WARNING, "WARNING": WARNING,
                "INFO": INFO, "DEBUG": DEBUG, "NOTSET": NOTSET}


def getLevelName(level):
    if level in _level_names:
        return _level_names[level]
    if level in _name_levels:
        return _name_levels[level]
    return f"Level {level}"


def addLevelName(level, name):
    _level_names[level] = name
    _name_levels[name] = level


def _check_level(level):
    if isinstance(level, int):
        return level
    if str(level) in _name_levels:
        return _name_levels[str(level)]
    raise ValueError(f"Unknown level: {level!r}")


class LogRecord:
    def __init__(self, name, level, pathname, lineno, msg, args, exc_info, func=None, sinfo=None):
        self.name = name
        self.msg = msg
        self.args = args
        self.levelno = level
        self.levelname = getLevelName(level)
        self.pathname = pathname
        self.filename = pathname
        self.module = pathname
        self.lineno = lineno
        self.funcName = func
        self.exc_info = exc_info
        self.exc_text = None
        self.stack_info = sinfo
        self.created = _time.time()
        self.msecs = (self.created - int(self.created)) * 1000
        self.relativeCreated = 0
        self.process = 0
        self.thread = 0
        self.threadName = "MainThread"

    def getMessage(self):
        msg = str(self.msg)
        if self.args:
            msg = msg % self.args
        return msg

    def __repr__(self):
        return f'<LogRecord: {self.name}, {self.levelno}, {self.pathname}, {self.lineno}, "{self.msg}">'


class Formatter:
    default_time_format = "%Y-%m-%d %H:%M:%S"
    default_msec_format = "%s,%03d"

    def __init__(self, fmt=None, datefmt=None, style="%", validate=True, *, defaults=None):
        self._style = style
        self._fmt = fmt or {"%": "%(message)s", "{": "{message}", "$": "${message}"}[style]
        self.datefmt = datefmt
        self._defaults = defaults or {}

    def usesTime(self):
        return "asctime" in self._fmt

    def formatTime(self, record, datefmt=None):
        from datetime import datetime
        dt = datetime.fromtimestamp(record.created)
        if datefmt:
            return dt.strftime(datefmt)
        return self.default_msec_format % (dt.strftime(self.default_time_format), record.msecs)

    def formatException(self, ei):
        if isinstance(ei, tuple):
            ei = ei[1]
        return f"{type(ei).__name__}: {ei}" if ei is not None else ""

    def formatMessage(self, record):
        values = dict(self._defaults)
        values.update(vars(record))
        if self._style == "{":
            return self._fmt.format(**values)
        if self._style == "$":
            out = self._fmt
            for k, v in values.items():
                out = out.replace("${" + k + "}", str(v)).replace("$" + k, str(v))
            return out
        return self._fmt % values

    def format(self, record):
        record.message = record.getMessage()
        if self.usesTime():
            record.asctime = self.formatTime(record, self.datefmt)
        s = self.formatMessage(record)
        if record.exc_info:
            if not record.exc_text:
                record.exc_text = self.formatException(record.exc_info)
        if record.exc_text:
            s = s + "\n" + record.exc_text
        return s


_default_formatter = Formatter()


class Filter:
    def __init__(self, name=""):
        self.name = name

    def filter(self, record):
        if not self.name or record.name == self.name:
            return True
        return record.name.startswith(self.name + ".")


class Filterer:
    def __init__(self):
        self.filters = []

    def addFilter(self, filter):
        if filter not in self.filters:
            self.filters.append(filter)

    def removeFilter(self, filter):
        if filter in self.filters:
            self.filters.remove(filter)

    def filter(self, record):
        for f in self.filters:
            ok = f.filter(record) if hasattr(f, "filter") else f(record)
            if not ok:
                return False
        return True


class Handler(Filterer):
    def __init__(self, level=NOTSET):
        Filterer.__init__(self)
        self.level = _check_level(level)
        self.formatter = None
        self.name = None

    def setLevel(self, level):
        self.level = _check_level(level)

    def setFormatter(self, fmt):
        self.formatter = fmt

    def format(self, record):
        return (self.formatter or _default_formatter).format(record)

    def handle(self, record):
        if self.filter(record):
            self.emit(record)
            return True
        return False

    def emit(self, record):
        raise NotImplementedError("emit must be implemented by Handler subclasses")

    def flush(self):
        pass

    def close(self):
        pass

    def handleError(self, record):
        pass


class StreamHandler(Handler):
    terminator = "\n"

    def __init__(self, stream=None):
        Handler.__init__(self)
        self.stream = stream

    def emit(self, record):
        stream = self.stream if self.stream is not None else _sys.stderr
        stream.write(self.format(record) + self.terminator)

    def setStream(self, stream):
        old, self.stream = self.stream, stream
        return old


class NullHandler(Handler):
    def handle(self, record):
        return False

    def emit(self, record):
        pass


class _LastResort(Handler):
    def __init__(self):
        Handler.__init__(self, WARNING)

    def emit(self, record):
        _sys.stderr.write(record.getMessage() + "\n")


lastResort = _LastResort()


class Logger(Filterer):
    def __init__(self, name, level=NOTSET):
        Filterer.__init__(self)
        self.name = name
        self.level = _check_level(level)
        self.parent = None
        self.propagate = True
        self.handlers = []
        self.disabled = False

    def setLevel(self, level):
        self.level = _check_level(level)

    def getEffectiveLevel(self):
        logger = self
        while logger:
            if logger.level:
                return logger.level
            logger = logger.parent
        return NOTSET

    def isEnabledFor(self, level):
        return not self.disabled and level >= _disable_below() and level >= self.getEffectiveLevel()

    def addHandler(self, hdlr):
        if hdlr not in self.handlers:
            self.handlers.append(hdlr)

    def removeHandler(self, hdlr):
        if hdlr in self.handlers:
            self.handlers.remove(hdlr)

    def hasHandlers(self):
        logger = self
        while logger:
            if logger.handlers:
                return True
            if not logger.propagate:
                return False
            logger = logger.parent
        return False

    def getChild(self, suffix):
        return getLogger(self.name + "." + suffix if self.name != "root" else suffix)

    def _log(self, level, msg, args, exc_info=None, extra=None, stack_info=False, stacklevel=1):
        if exc_info is True:
            exc_info = _sys.exc_info()
        record = LogRecord(self.name, level, "<script>", 0, msg, args, exc_info)
        if extra:
            for k, v in extra.items():
                setattr(record, k, v)
        self.handle(record)

    def handle(self, record):
        if self.disabled or not self.filter(record):
            return
        found = 0
        logger = self
        while logger:
            for h in logger.handlers:
                found += 1
                if record.levelno >= h.level:
                    h.handle(record)
            if not logger.propagate:
                break
            logger = logger.parent
        if found == 0 and record.levelno >= lastResort.level:
            lastResort.handle(record)

    def log(self, level, msg, *args, **kwargs):
        if self.isEnabledFor(level):
            self._log(level, msg, args, **kwargs)

    def debug(self, msg, *args, **kwargs):
        if self.isEnabledFor(DEBUG):
            self._log(DEBUG, msg, args, **kwargs)

    def info(self, msg, *args, **kwargs):
        if self.isEnabledFor(INFO):
            self._log(INFO, msg, args, **kwargs)

    def warning(self, msg, *args, **kwargs):
        if self.isEnabledFor(WARNING):
            self._log(WARNING, msg, args, **kwargs)

    warn = warning

    def error(self, msg, *args, **kwargs):
        if self.isEnabledFor(ERROR):
            self._log(ERROR, msg, args, **kwargs)

    def exception(self, msg, *args, exc_info=True, **kwargs):
        self.error(msg, *args, exc_info=exc_info, **kwargs)

    def critical(self, msg, *args, **kwargs):
        if self.isEnabledFor(CRITICAL):
            self._log(CRITICAL, msg, args, **kwargs)

    fatal = critical

    def __repr__(self):
        return f"<{type(self).__name__} {self.name} ({getLevelName(self.getEffectiveLevel())})>"


class RootLogger(Logger):
    def __init__(self, level):
        Logger.__init__(self, "root", level)


class LoggerAdapter:
    def __init__(self, logger, extra=None):
        self.logger = logger
        self.extra = extra

    def process(self, msg, kwargs):
        kwargs["extra"] = self.extra
        return msg, kwargs

    def log(self, level, msg, *args, **kwargs):
        msg, kwargs = self.process(msg, kwargs)
        self.logger.log(level, msg, *args, **kwargs)

    def debug(self, msg, *args, **kwargs):
        self.log(DEBUG, msg, *args, **kwargs)

    def info(self, msg, *args, **kwargs):
        self.log(INFO, msg, *args, **kwargs)

    def warning(self, msg, *args, **kwargs):
        self.log(WARNING, msg, *args, **kwargs)

    def error(self, msg, *args, **kwargs):
        self.log(ERROR, msg, *args, **kwargs)

    def exception(self, msg, *args, exc_info=True, **kwargs):
        self.log(ERROR, msg, *args, exc_info=exc_info, **kwargs)

    def critical(self, msg, *args, **kwargs):
        self.log(CRITICAL, msg, *args, **kwargs)

    def isEnabledFor(self, level):
        return self.logger.isEnabledFor(level)


root = RootLogger(WARNING)
_loggers = {}
_disabled = [NOTSET]


def _disable_below():
    return _disabled[0] + 1 if _disabled[0] else NOTSET


def disable(level=CRITICAL):
    _disabled[0] = level


def getLogger(name=None):
    if not name or name == "root":
        return root
    if name in _loggers:
        return _loggers[name]
    logger = Logger(name)
    _loggers[name] = logger
    # The nearest existing ancestor is its parent; existing descendants move under it.
    parent = root
    parts = name.split(".")
    for i in range(len(parts) - 1, 0, -1):
        above = ".".join(parts[:i])
        if above in _loggers:
            parent = _loggers[above]
            break
    logger.parent = parent
    for other_name, other in _loggers.items():
        if other_name.startswith(name + ".") and other.parent is parent:
            other.parent = logger
    return logger


def basicConfig(**kwargs):
    force = kwargs.pop("force", False)
    if force:
        root.handlers.clear()
    if root.handlers:
        return
    handlers = kwargs.pop("handlers", None)
    if handlers is None:
        stream = kwargs.pop("stream", None)
        handlers = [StreamHandler(stream)]
    fmt = kwargs.pop("format", "%(levelname)s:%(name)s:%(message)s")
    datefmt = kwargs.pop("datefmt", None)
    style = kwargs.pop("style", "%")
    formatter = Formatter(fmt, datefmt, style)
    for h in handlers:
        if h.formatter is None:
            h.setFormatter(formatter)
        root.addHandler(h)
    level = kwargs.pop("level", None)
    if level is not None:
        root.setLevel(level)


def getLoggerClass():
    return Logger


def critical(msg, *args, **kwargs):
    root.critical(msg, *args, **kwargs)


fatal = critical


def error(msg, *args, **kwargs):
    root.error(msg, *args, **kwargs)


def exception(msg, *args, exc_info=True, **kwargs):
    root.error(msg, *args, exc_info=exc_info, **kwargs)


def warning(msg, *args, **kwargs):
    root.warning(msg, *args, **kwargs)


warn = warning


def info(msg, *args, **kwargs):
    root.info(msg, *args, **kwargs)


def debug(msg, *args, **kwargs):
    root.debug(msg, *args, **kwargs)


def log(level, msg, *args, **kwargs):
    root.log(level, msg, *args, **kwargs)


def captureWarnings(capture):
    pass


def shutdown():
    pass
