"""unittest: test cases, suites, a text runner and main().

Assertion messages read as CPython's. Tests are found by class (main()
runs every TestCase subclass defined in the module it is called for);
there are no files to discover, so TestLoader.discover() finds nothing.
"""

import difflib
import pprint
import re
import sys
import time
import traceback

__all__ = [
    "TestResult", "TestCase", "IsolatedAsyncioTestCase", "TestSuite", "TextTestRunner", "TestLoader", "FunctionTestCase",
    "main", "defaultTestLoader", "SkipTest", "skip", "skipIf", "skipUnless", "expectedFailure", "TextTestResult",
    "installHandler", "registerResult", "removeResult", "removeHandler", "addModuleCleanup", "doModuleCleanups", "enterModuleContext",
]

__unittest = True
_MAX_LENGTH = 80
_PLACEHOLDER_LEN = 12
_MIN_BEGIN_LEN = 5
_MIN_END_LEN = 5
_MIN_COMMON_LEN = 5
_MIN_DIFF_LEN = _MAX_LENGTH - (_MIN_BEGIN_LEN + _PLACEHOLDER_LEN + _MIN_COMMON_LEN + _PLACEHOLDER_LEN + _MIN_END_LEN)
DIFF_OMITTED = "\nDiff is %s characters long. Set self.maxDiff to None to see it."


def safe_repr(obj, short=False):
    try:
        result = repr(obj)
    except Exception:
        result = object.__repr__(obj)
    if not short or len(result) < _MAX_LENGTH:
        return result
    return result[:_MAX_LENGTH] + " [truncated]..."


def _shorten(s, prefixlen, suffixlen):
    skip = len(s) - prefixlen - suffixlen
    if skip > _PLACEHOLDER_LEN:
        s = "%s[%d chars]%s" % (s[:prefixlen], skip, s[len(s) - suffixlen :])
    return s


def _common_shorten_repr(*args):
    args = tuple(map(safe_repr, args))
    maxlen = max(map(len, args))
    if maxlen <= _MAX_LENGTH:
        return args
    prefix = args[0]
    for a in args[1:]:
        n = 0
        while n < len(prefix) and n < len(a) and prefix[n] == a[n]:
            n += 1
        prefix = prefix[:n]
    prefixlen = len(prefix)
    common_len = _MAX_LENGTH - (maxlen - prefixlen + _MIN_BEGIN_LEN + _PLACEHOLDER_LEN)
    if common_len > _MIN_COMMON_LEN:
        prefix = _shorten(prefix, _MIN_BEGIN_LEN, common_len)
        return tuple(prefix + s[prefixlen:] for s in args)
    prefix = _shorten(prefix, _MIN_BEGIN_LEN, _MIN_COMMON_LEN)
    return tuple(prefix + _shorten(s[prefixlen:], _MIN_DIFF_LEN, _MIN_END_LEN) for s in args)


def strclass(cls):
    return f"{cls.__module__}.{cls.__qualname__}"


class SkipTest(Exception):
    pass


class _ShouldStop(Exception):
    pass


class _UnexpectedSuccess(Exception):
    pass


def _id(obj):
    return obj


def skip(reason):
    def decorator(test_item):
        test_item.__unittest_skip__ = True
        test_item.__unittest_skip_why__ = reason
        return test_item

    if callable(reason) and not isinstance(reason, str):
        test_item = reason
        reason = ""
        return decorator(test_item)
    return decorator


def skipIf(condition, reason):
    if condition:
        return skip(reason)
    return _id


def skipUnless(condition, reason):
    if not condition:
        return skip(reason)
    return _id


def expectedFailure(test_item):
    test_item.__unittest_expecting_failure__ = True
    return test_item


_module_cleanups = []


def addModuleCleanup(function, /, *args, **kwargs):
    _module_cleanups.append((function, args, kwargs))


def doModuleCleanups():
    exceptions = []
    while _module_cleanups:
        function, args, kwargs = _module_cleanups.pop()
        try:
            function(*args, **kwargs)
        except Exception as exc:
            exceptions.append(exc)
    if exceptions:
        raise exceptions[0]


def enterModuleContext(cm):
    result = cm.__enter__()
    addModuleCleanup(cm.__exit__, None, None, None)
    return result


def installHandler():
    pass


def removeHandler(method=None):
    return method if method is not None else None


def registerResult(result):
    pass


def removeResult(result):
    return False


def _format_exc(exc):
    return "".join(traceback.format_exception(type(exc), exc, None))


class TestResult:
    _previousTestClass = None
    _testRunEntered = False
    _moduleSetUpFailed = False

    def __init__(self, stream=None, descriptions=None, verbosity=None):
        self.failfast = False
        self.failures = []
        self.errors = []
        self.testsRun = 0
        self.skipped = []
        self.expectedFailures = []
        self.unexpectedSuccesses = []
        self.collectedDurations = []
        self.shouldStop = False
        self.buffer = False
        self.tb_locals = False

    def printErrors(self):
        pass

    def startTest(self, test):
        self.testsRun += 1

    def startTestRun(self):
        pass

    def stopTest(self, test):
        pass

    def stopTestRun(self):
        pass

    def addError(self, test, err):
        self.errors.append((test, self._exc_info_to_string(err, test)))
        if self.failfast:
            self.stop()

    def addFailure(self, test, err):
        self.failures.append((test, self._exc_info_to_string(err, test)))
        if self.failfast:
            self.stop()

    def addSubTest(self, test, subtest, err):
        if err is not None:
            if issubclass(err[0], test.failureException):
                self.failures.append((subtest, self._exc_info_to_string(err, test)))
            else:
                self.errors.append((subtest, self._exc_info_to_string(err, test)))
            if self.failfast:
                self.stop()

    def addSuccess(self, test):
        pass

    def addSkip(self, test, reason):
        self.skipped.append((test, reason))

    def addExpectedFailure(self, test, err):
        self.expectedFailures.append((test, self._exc_info_to_string(err, test)))

    def addUnexpectedSuccess(self, test):
        self.unexpectedSuccesses.append(test)

    def addDuration(self, test, elapsed):
        self.collectedDurations.append((str(test), elapsed))

    def wasSuccessful(self):
        return (len(self.failures) == len(self.errors) == 0) and (not hasattr(self, "unexpectedSuccesses") or len(self.unexpectedSuccesses) == 0)

    def stop(self):
        self.shouldStop = True

    def _exc_info_to_string(self, err, test):
        exctype, value, tb = err
        return _format_exc(value)

    def __repr__(self):
        return f"<{strclass(type(self))} run={self.testsRun} errors={len(self.errors)} failures={len(self.failures)}>"


class _Outcome:
    def __init__(self, result=None):
        self.expecting_failure = False
        self.result = result
        self.result_supports_subtests = hasattr(result, "addSubTest")
        self.success = True
        self.expectedFailure = None

    def run(self, test_case, func, subTest=False):
        old_success = self.success
        self.success = True
        try:
            func()
        except KeyboardInterrupt:
            raise
        except SkipTest as e:
            self.success = False
            _addSkip(self.result, test_case, str(e))
        except _ShouldStop:
            pass
        except BaseException as e:
            exc_info = (type(e), e, None)
            if self.expecting_failure:
                self.expectedFailure = exc_info
            else:
                self.success = False
                if subTest:
                    self.result.addSubTest(test_case.test_case, test_case, exc_info)
                else:
                    _addError(self.result, test_case, exc_info)
        else:
            if subTest and self.success:
                self.result.addSubTest(test_case.test_case, test_case, None)
        finally:
            self.success = self.success and old_success


def _addSkip(result, test_case, reason):
    addSkip = getattr(result, "addSkip", None)
    if addSkip is not None:
        addSkip(test_case, reason)
    else:
        result.addSuccess(test_case)


def _addError(result, test, exc_info):
    if result is not None and exc_info is not None:
        if issubclass(exc_info[0], test.failureException):
            result.addFailure(test, exc_info)
        else:
            result.addError(test, exc_info)


class _AssertRaisesBaseContext:
    def __init__(self, expected, test_case, expected_regex=None):
        self.expected = expected
        self.test_case = test_case
        if expected_regex is not None:
            expected_regex = re.compile(expected_regex)
        self.expected_regex = expected_regex
        self.obj_name = None
        self.msg = None

    def handle(self, name, args, kwargs):
        try:
            if not args:
                self.msg = kwargs.pop("msg", None)
                if kwargs:
                    raise TypeError(f"{next(iter(kwargs))!r} is an invalid keyword argument for this function")
                return self
            callable_obj, *args = args
            try:
                self.obj_name = callable_obj.__name__
            except AttributeError:
                self.obj_name = str(callable_obj)
            with self:
                callable_obj(*args, **kwargs)
        finally:
            self = None


class _AssertRaisesContext(_AssertRaisesBaseContext):
    _base_type = BaseException
    _base_type_str = "an exception type or tuple of exception types"

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, tb):
        if exc_type is None:
            try:
                exc_name = self.expected.__name__
            except AttributeError:
                exc_name = str(self.expected)
            if self.obj_name:
                self._raiseFailure(f"{exc_name} not raised by {self.obj_name}")
            else:
                self._raiseFailure(f"{exc_name} not raised")
        if not issubclass(exc_type, self.expected):
            return False
        self.exception = exc_value
        if self.expected_regex is None:
            return True
        expected_regex = self.expected_regex
        if not expected_regex.search(str(exc_value)):
            self._raiseFailure(f'"{expected_regex.pattern}" does not match "{exc_value}"')
        return True

    def _raiseFailure(self, standardMsg):
        msg = self.test_case._formatMessage(self.msg, standardMsg)
        raise self.test_case.failureException(msg)

    __class_getitem__ = classmethod(lambda cls, item: cls)


class _AssertWarnsContext(_AssertRaisesBaseContext):
    def __enter__(self):
        import warnings

        self._catch = warnings.catch_warnings(record=True)
        self.warnings = self._catch.__enter__()
        warnings.simplefilter("always")
        return self

    def __exit__(self, exc_type, exc_value, tb):
        self._catch.__exit__(exc_type, exc_value, tb)
        if exc_type is not None:
            return False
        try:
            exc_name = self.expected.__name__
        except AttributeError:
            exc_name = str(self.expected)
        first_matching = None
        for m in self.warnings:
            w = m.message
            if not isinstance(w, self.expected):
                continue
            if first_matching is None:
                first_matching = w
            if self.expected_regex is not None and not self.expected_regex.search(str(w)):
                continue
            self.warning = w
            self.filename = getattr(m, "filename", "")
            self.lineno = getattr(m, "lineno", 0)
            return
        if first_matching is not None:
            self._raiseFailure(f'"{self.expected_regex.pattern}" does not match "{first_matching}"')
        if self.obj_name:
            self._raiseFailure(f"{exc_name} not triggered by {self.obj_name}")
        else:
            self._raiseFailure(f"{exc_name} not triggered")

    def _raiseFailure(self, standardMsg):
        msg = self.test_case._formatMessage(self.msg, standardMsg)
        raise self.test_case.failureException(msg)


_LoggingWatcher = None


class _CapturingHandler:
    def __init__(self):
        import logging

        self._handler = logging.Handler()
        self.watcher = _Watcher()
        self._handler.emit = self.emit
        self.level = 0

    def emit(self, record):
        self.watcher.records.append(record)
        self.watcher.output.append(f"{record.levelname}:{record.name}:{record.getMessage()}")


class _Watcher:
    def __init__(self):
        self.records = []
        self.output = []


class _AssertLogsContext:
    def __init__(self, test_case, logger_name, level, no_logs):
        import logging

        self.test_case = test_case
        self.logger_name = logger_name
        if level:
            self.level = logging._nameToLevel.get(level, level) if hasattr(logging, "_nameToLevel") else level
        else:
            self.level = logging.INFO
        self.msg = None
        self.no_logs = no_logs

    def __enter__(self):
        import logging

        if isinstance(self.logger_name, logging.Logger):
            logger = self.logger = self.logger_name
        else:
            logger = self.logger = logging.getLogger(self.logger_name)
        capture = _CapturingHandler()
        self._capture = capture
        self.watcher = capture.watcher
        self.old_handlers = logger.handlers[:]
        self.old_level = logger.level
        self.old_propagate = logger.propagate
        logger.handlers = [capture._handler]
        logger.setLevel(self.level)
        logger.propagate = False
        if self.no_logs:
            return None
        return capture.watcher

    def __exit__(self, exc_type, exc_value, tb):
        self.logger.handlers = self.old_handlers
        self.logger.propagate = self.old_propagate
        self.logger.setLevel(self.old_level)
        if exc_type is not None:
            return False
        if self.no_logs:
            if len(self.watcher.records) > 0:
                self._raiseFailure(f"Unexpected logs found: {self.watcher.output!r}")
        elif len(self.watcher.records) == 0:
            self._raiseFailure(f"no logs of level {self._level_name()} or higher triggered on {self.logger.name}")

    def _level_name(self):
        import logging

        return logging.getLevelName(self.level)

    def _raiseFailure(self, standardMsg):
        msg = self.test_case._formatMessage(self.msg, standardMsg)
        raise self.test_case.failureException(msg)


class _SubTest:
    def __init__(self, test_case, message, params):
        self._message = message
        self.test_case = test_case
        self.params = params
        self.failureException = test_case.failureException

    def runTest(self):
        raise NotImplementedError("subtests cannot be run directly")

    def _subDescription(self):
        parts = []
        if self._message is not _subtest_msg_sentinel:
            parts.append(f"[{self._message}]")
        if self.params:
            params_desc = ", ".join(f"{k}={v!r}" for (k, v) in self.params.items())
            parts.append(f"({params_desc})")
        return " ".join(parts) or "(<subtest>)"

    def id(self):
        return f"{self.test_case.id()} {self._subDescription()}"

    def shortDescription(self):
        return self.test_case.shortDescription()

    def __str__(self):
        return f"{self.test_case} {self._subDescription()}"


_subtest_msg_sentinel = object()


class _SubTestContext:
    def __init__(self, case, msg, params):
        self.case = case
        self.msg = msg
        self.params = params

    def __enter__(self):
        return None

    def __exit__(self, exc_type, exc_value, tb):
        case = self.case
        outcome = case._outcome
        if outcome is None or exc_type is None:
            if exc_type is None and outcome is not None and outcome.result_supports_subtests:
                pass
            return False
        if issubclass(exc_type, SkipTest):
            _addSkip(outcome.result, case, str(exc_value))
            return True
        sub = _SubTest(case, self.msg, self.params)
        if outcome.expecting_failure:
            outcome.expectedFailure = (exc_type, exc_value, None)
            return True
        outcome.success = False
        if outcome.result_supports_subtests:
            outcome.result.addSubTest(case, sub, (exc_type, exc_value, None))
        else:
            _addError(outcome.result, case, (exc_type, exc_value, None))
        return True


_test_case_classes = []


class TestCase:
    failureException = AssertionError
    longMessage = True
    maxDiff = 80 * 8
    _classSetupFailed = False
    _class_cleanups = []

    def __init_subclass__(cls, *args, **kwargs):
        _test_case_classes.append(cls)
        cls._class_cleanups = []

    def __init__(self, methodName="runTest"):
        self._testMethodName = methodName
        self._outcome = None
        self._testMethodDoc = "No test"
        try:
            testMethod = getattr(self, methodName)
        except AttributeError:
            if methodName != "runTest":
                raise ValueError(f"no such test method in {type(self)}: {methodName}") from None
        else:
            self._testMethodDoc = testMethod.__doc__
        self._cleanups = []
        self._subtest = None
        self._type_equality_funcs = {}
        self.addTypeEqualityFunc(dict, "assertDictEqual")
        self.addTypeEqualityFunc(list, "assertListEqual")
        self.addTypeEqualityFunc(tuple, "assertTupleEqual")
        self.addTypeEqualityFunc(set, "assertSetEqual")
        self.addTypeEqualityFunc(frozenset, "assertSetEqual")
        self.addTypeEqualityFunc(str, "assertMultiLineEqual")

    def addTypeEqualityFunc(self, typeobj, function):
        self._type_equality_funcs[typeobj] = function

    def addCleanup(self, function, /, *args, **kwargs):
        self._cleanups.append((function, args, kwargs))

    def enterContext(self, cm):
        result = cm.__enter__()
        self.addCleanup(cm.__exit__, None, None, None)
        return result

    @classmethod
    def addClassCleanup(cls, function, /, *args, **kwargs):
        cls._class_cleanups.append((function, args, kwargs))

    @classmethod
    def enterClassContext(cls, cm):
        result = cm.__enter__()
        cls.addClassCleanup(cm.__exit__, None, None, None)
        return result

    def setUp(self):
        pass

    def tearDown(self):
        pass

    @classmethod
    def setUpClass(cls):
        pass

    @classmethod
    def tearDownClass(cls):
        pass

    def countTestCases(self):
        return 1

    def defaultTestResult(self):
        return TestResult()

    def shortDescription(self):
        doc = self._testMethodDoc
        return doc.strip().split("\n")[0].strip() if doc else None

    def id(self):
        return f"{strclass(type(self))}.{self._testMethodName}"

    def __eq__(self, other):
        if type(self) is not type(other):
            return NotImplemented
        return self._testMethodName == other._testMethodName

    def __hash__(self):
        return hash((type(self), self._testMethodName))

    def __str__(self):
        return f"{self._testMethodName} ({strclass(type(self))}.{self._testMethodName})"

    def __repr__(self):
        return f"<{strclass(type(self))} testMethod={self._testMethodName}>"

    def subTest(self, msg=_subtest_msg_sentinel, **params):
        return _SubTestContext(self, msg, params)

    def run(self, result=None):
        if result is None:
            result = self.defaultTestResult()
            startTestRun = getattr(result, "startTestRun", None)
            stopTestRun = getattr(result, "stopTestRun", None)
            if startTestRun is not None:
                startTestRun()
        else:
            stopTestRun = None
        result.startTest(self)
        try:
            testMethod = getattr(self, self._testMethodName)
            if getattr(type(self), "__unittest_skip__", False) or getattr(testMethod, "__unittest_skip__", False):
                skip_why = getattr(type(self), "__unittest_skip_why__", "") or getattr(testMethod, "__unittest_skip_why__", "")
                _addSkip(result, self, skip_why)
                return result
            expecting_failure = getattr(testMethod, "__unittest_expecting_failure__", False) or getattr(self, "__unittest_expecting_failure__", False)
            outcome = _Outcome(result)
            start_time = time.perf_counter() if hasattr(time, "perf_counter") else time.time()
            try:
                self._outcome = outcome
                outcome.run(self, self._callSetUp)
                if outcome.success:
                    outcome.expecting_failure = expecting_failure
                    outcome.run(self, testMethod)
                    outcome.expecting_failure = False
                    outcome.run(self, self._callTearDown)
                self.doCleanups()
                stop_time = time.perf_counter() if hasattr(time, "perf_counter") else time.time()
                result.addDuration(self, stop_time - start_time) if hasattr(result, "addDuration") else None
                if outcome.success:
                    if expecting_failure:
                        if outcome.expectedFailure:
                            result.addExpectedFailure(self, outcome.expectedFailure)
                        else:
                            result.addUnexpectedSuccess(self)
                    else:
                        result.addSuccess(self)
                return result
            finally:
                outcome.expectedFailure = None
                self._outcome = None
        finally:
            result.stopTest(self)
            if stopTestRun is not None:
                stopTestRun()

    def _callSetUp(self):
        self.setUp()

    def _callTearDown(self):
        self.tearDown()

    def doCleanups(self):
        outcome = self._outcome or _Outcome()
        while self._cleanups:
            function, args, kwargs = self._cleanups.pop()
            outcome.run(self, lambda: function(*args, **kwargs))
        return outcome.success

    @classmethod
    def doClassCleanups(cls):
        cls.tearDown_exceptions = []
        while cls._class_cleanups:
            function, args, kwargs = cls._class_cleanups.pop()
            try:
                function(*args, **kwargs)
            except Exception as exc:
                cls.tearDown_exceptions.append((type(exc), exc, None))

    def __call__(self, *args, **kwds):
        return self.run(*args, **kwds)

    def debug(self):
        self.setUp()
        getattr(self, self._testMethodName)()
        self.tearDown()
        while self._cleanups:
            function, args, kwargs = self._cleanups.pop()
            function(*args, **kwargs)

    def skipTest(self, reason):
        raise SkipTest(reason)

    def fail(self, msg=None):
        raise self.failureException(msg)

    def _formatMessage(self, msg, standardMsg):
        if not self.longMessage:
            return msg or standardMsg
        if msg is None:
            return standardMsg
        try:
            return f"{standardMsg} : {msg}"
        except UnicodeDecodeError:
            return f"{safe_repr(standardMsg)} : {safe_repr(msg)}"

    def assertFalse(self, expr, msg=None):
        if expr:
            msg = self._formatMessage(msg, f"{safe_repr(expr)} is not false")
            raise self.failureException(msg)

    def assertTrue(self, expr, msg=None):
        if not expr:
            msg = self._formatMessage(msg, f"{safe_repr(expr)} is not true")
            raise self.failureException(msg)

    def assertRaises(self, expected_exception, *args, **kwargs):
        context = _AssertRaisesContext(expected_exception, self)
        try:
            return context.handle("assertRaises", args, kwargs)
        finally:
            context = None

    def assertWarns(self, expected_warning, *args, **kwargs):
        context = _AssertWarnsContext(expected_warning, self)
        return context.handle("assertWarns", args, kwargs)

    def assertLogs(self, logger=None, level=None):
        return _AssertLogsContext(self, logger, level, no_logs=False)

    def assertNoLogs(self, logger=None, level=None):
        return _AssertLogsContext(self, logger, level, no_logs=True)

    def _getAssertEqualityFunc(self, first, second):
        if type(first) == type(second):
            asserter = self._type_equality_funcs.get(type(first))
            if asserter is not None:
                if isinstance(asserter, str):
                    asserter = getattr(self, asserter)
                return asserter
        return self._baseAssertEqual

    def _baseAssertEqual(self, first, second, msg=None):
        if not first == second:
            standardMsg = "%s != %s" % _common_shorten_repr(first, second)
            msg = self._formatMessage(msg, standardMsg)
            raise self.failureException(msg)

    def assertEqual(self, first, second, msg=None):
        assertion_func = self._getAssertEqualityFunc(first, second)
        assertion_func(first, second, msg=msg)

    def assertNotEqual(self, first, second, msg=None):
        if not first != second:
            msg = self._formatMessage(msg, f"{safe_repr(first)} == {safe_repr(second)}")
            raise self.failureException(msg)

    def assertAlmostEqual(self, first, second, places=None, msg=None, delta=None):
        if first == second:
            return
        if delta is not None and places is not None:
            raise TypeError("specify delta or places not both")
        diff = abs(first - second)
        if delta is not None:
            if diff <= delta:
                return
            standardMsg = f"{safe_repr(first)} != {safe_repr(second)} within {safe_repr(delta)} delta ({safe_repr(diff)} difference)"
        else:
            if places is None:
                places = 7
            if round(diff, places) == 0:
                return
            standardMsg = f"{safe_repr(first)} != {safe_repr(second)} within {safe_repr(places)} places ({safe_repr(diff)} difference)"
        msg = self._formatMessage(msg, standardMsg)
        raise self.failureException(msg)

    def assertNotAlmostEqual(self, first, second, places=None, msg=None, delta=None):
        if delta is not None and places is not None:
            raise TypeError("specify delta or places not both")
        diff = abs(first - second)
        if delta is not None:
            if not (first == second) and diff > delta:
                return
            standardMsg = f"{safe_repr(first)} == {safe_repr(second)} within {safe_repr(delta)} delta ({safe_repr(diff)} difference)"
        else:
            if places is None:
                places = 7
            if not (first == second) and round(diff, places) != 0:
                return
            standardMsg = f"{safe_repr(first)} == {safe_repr(second)} within {safe_repr(places)} places"
        msg = self._formatMessage(msg, standardMsg)
        raise self.failureException(msg)

    def assertSequenceEqual(self, seq1, seq2, msg=None, seq_type=None):
        if seq_type is not None:
            seq_type_name = seq_type.__name__
            if not isinstance(seq1, seq_type):
                raise self.failureException(f"First sequence is not a {seq_type_name}: {safe_repr(seq1)}")
            if not isinstance(seq2, seq_type):
                raise self.failureException(f"Second sequence is not a {seq_type_name}: {safe_repr(seq2)}")
        else:
            seq_type_name = "sequence"
        differing = None
        try:
            len1 = len(seq1)
        except (TypeError, NotImplementedError):
            differing = f"First {seq_type_name} has no length.    Non-sequence?"
        if differing is None:
            try:
                len2 = len(seq2)
            except (TypeError, NotImplementedError):
                differing = f"Second {seq_type_name} has no length.    Non-sequence?"
        if differing is None:
            if seq1 == seq2:
                return
            differing = "%ss differ: %s != %s\n" % ((seq_type_name.capitalize(),) + _common_shorten_repr(seq1, seq2))
            for i in range(min(len1, len2)):
                try:
                    item1 = seq1[i]
                except (TypeError, IndexError, NotImplementedError):
                    differing += f"\nUnable to index element {i} of first {seq_type_name}\n"
                    break
                try:
                    item2 = seq2[i]
                except (TypeError, IndexError, NotImplementedError):
                    differing += f"\nUnable to index element {i} of second {seq_type_name}\n"
                    break
                if item1 != item2:
                    differing += f"\nFirst differing element {i}:\n{safe_repr(item1)}\n{safe_repr(item2)}\n"
                    break
            else:
                if len1 == len2 and seq_type is None and type(seq1) != type(seq2):
                    return
            if len1 > len2:
                differing += f"\nFirst {seq_type_name} contains {len1 - len2} additional elements.\n"
                try:
                    differing += f"First extra element {len2}:\n{safe_repr(seq1[len2])}\n"
                except (TypeError, IndexError, NotImplementedError):
                    differing += f"Unable to index element {len2} of first {seq_type_name}\n"
            elif len1 < len2:
                differing += f"\nSecond {seq_type_name} contains {len2 - len1} additional elements.\n"
                try:
                    differing += f"First extra element {len1}:\n{safe_repr(seq2[len1])}\n"
                except (TypeError, IndexError, NotImplementedError):
                    differing += f"Unable to index element {len1} of second {seq_type_name}\n"
        standardMsg = differing
        diffMsg = "\n" + "\n".join(difflib.ndiff(pprint.pformat(seq1).splitlines(), pprint.pformat(seq2).splitlines()))
        standardMsg = self._truncateMessage(standardMsg, diffMsg)
        msg = self._formatMessage(msg, standardMsg)
        self.fail(msg)

    def _truncateMessage(self, message, diff):
        max_diff = self.maxDiff
        if max_diff is None or len(diff) <= max_diff:
            return message + diff
        return message + (DIFF_OMITTED % len(diff))

    def assertListEqual(self, list1, list2, msg=None):
        self.assertSequenceEqual(list1, list2, msg, seq_type=list)

    def assertTupleEqual(self, tuple1, tuple2, msg=None):
        self.assertSequenceEqual(tuple1, tuple2, msg, seq_type=tuple)

    def assertSetEqual(self, set1, set2, msg=None):
        try:
            difference1 = set1.difference(set2)
        except TypeError as e:
            self.fail(f"invalid type when attempting set difference: {e}")
        except AttributeError as e:
            self.fail(f"first argument does not support set difference: {e}")
        try:
            difference2 = set2.difference(set1)
        except TypeError as e:
            self.fail(f"invalid type when attempting set difference: {e}")
        except AttributeError as e:
            self.fail(f"second argument does not support set difference: {e}")
        if not (difference1 or difference2):
            return
        lines = []
        if difference1:
            lines.append("Items in the first set but not the second:")
            for item in difference1:
                lines.append(repr(item))
        if difference2:
            lines.append("Items in the second set but not the first:")
            for item in difference2:
                lines.append(repr(item))
        standardMsg = "\n".join(lines)
        self.fail(self._formatMessage(msg, standardMsg))

    def assertIn(self, member, container, msg=None):
        if member not in container:
            standardMsg = f"{safe_repr(member)} not found in {safe_repr(container)}"
            self.fail(self._formatMessage(msg, standardMsg))

    def assertNotIn(self, member, container, msg=None):
        if member in container:
            standardMsg = f"{safe_repr(member)} unexpectedly found in {safe_repr(container)}"
            self.fail(self._formatMessage(msg, standardMsg))

    def assertIs(self, expr1, expr2, msg=None):
        if expr1 is not expr2:
            standardMsg = f"{safe_repr(expr1)} is not {safe_repr(expr2)}"
            self.fail(self._formatMessage(msg, standardMsg))

    def assertIsNot(self, expr1, expr2, msg=None):
        if expr1 is expr2:
            standardMsg = f"unexpectedly identical: {safe_repr(expr1)}"
            self.fail(self._formatMessage(msg, standardMsg))

    def assertDictEqual(self, d1, d2, msg=None):
        self.assertIsInstance(d1, dict, "First argument is not a dictionary")
        self.assertIsInstance(d2, dict, "Second argument is not a dictionary")
        if d1 != d2:
            standardMsg = "%s != %s" % _common_shorten_repr(d1, d2)
            diff = "\n" + "\n".join(difflib.ndiff(pprint.pformat(d1).splitlines(), pprint.pformat(d2).splitlines()))
            standardMsg = self._truncateMessage(standardMsg, diff)
            self.fail(self._formatMessage(msg, standardMsg))

    def assertCountEqual(self, first, second, msg=None):
        first_seq, second_seq = list(first), list(second)
        missing = list(second_seq)
        extra = []
        for item in first_seq:
            if item in missing:
                missing.remove(item)
            else:
                extra.append(item)
        if extra or missing:
            first_counts = []
            for item in first_seq:
                if not any(c[2] == item for c in first_counts):
                    first_counts.append((first_seq.count(item), second_seq.count(item), item))
            for item in second_seq:
                if not any(c[2] == item for c in first_counts):
                    first_counts.append((first_seq.count(item), second_seq.count(item), item))
            differences = [d for d in first_counts if d[0] != d[1]]
            standardMsg = "Element counts were not equal:\n"
            lines = ["First has %d, Second has %d:  %r" % diff for diff in differences]
            diffMsg = "\n".join(lines)
            standardMsg = self._truncateMessage(standardMsg, diffMsg)
            msg = self._formatMessage(msg, standardMsg)
            self.fail(msg)

    def assertMultiLineEqual(self, first, second, msg=None):
        self.assertIsInstance(first, str, "First argument is not a string")
        self.assertIsInstance(second, str, "Second argument is not a string")
        if first != second:
            if len(first) > self._diffThreshold or len(second) > self._diffThreshold:
                self._baseAssertEqual(first, second, msg)
            firstlines = first.splitlines(keepends=True)
            secondlines = second.splitlines(keepends=True)
            if len(firstlines) == 1 and first.strip("\r\n") == first:
                firstlines = [first + "\n"]
                secondlines = [second + "\n"]
            standardMsg = "%s != %s" % _common_shorten_repr(first, second)
            diff = "\n" + "".join(difflib.ndiff(firstlines, secondlines))
            standardMsg = self._truncateMessage(standardMsg, diff)
            self.fail(self._formatMessage(msg, standardMsg))

    _diffThreshold = 2**16

    def assertLess(self, a, b, msg=None):
        if not a < b:
            self.fail(self._formatMessage(msg, f"{safe_repr(a)} not less than {safe_repr(b)}"))

    def assertLessEqual(self, a, b, msg=None):
        if not a <= b:
            self.fail(self._formatMessage(msg, f"{safe_repr(a)} not less than or equal to {safe_repr(b)}"))

    def assertGreater(self, a, b, msg=None):
        if not a > b:
            self.fail(self._formatMessage(msg, f"{safe_repr(a)} not greater than {safe_repr(b)}"))

    def assertGreaterEqual(self, a, b, msg=None):
        if not a >= b:
            self.fail(self._formatMessage(msg, f"{safe_repr(a)} not greater than or equal to {safe_repr(b)}"))

    def assertIsNone(self, obj, msg=None):
        if obj is not None:
            self.fail(self._formatMessage(msg, f"{safe_repr(obj)} is not None"))

    def assertIsNotNone(self, obj, msg=None):
        if obj is None:
            self.fail(self._formatMessage(msg, "unexpectedly None"))

    def assertIsInstance(self, obj, cls, msg=None):
        if not isinstance(obj, cls):
            self.fail(self._formatMessage(msg, f"{safe_repr(obj)} is not an instance of {cls!r}"))

    def assertNotIsInstance(self, obj, cls, msg=None):
        if isinstance(obj, cls):
            self.fail(self._formatMessage(msg, f"{safe_repr(obj)} is an instance of {cls!r}"))

    def assertRaisesRegex(self, expected_exception, expected_regex, *args, **kwargs):
        context = _AssertRaisesContext(expected_exception, self, expected_regex)
        return context.handle("assertRaisesRegex", args, kwargs)

    def assertWarnsRegex(self, expected_warning, expected_regex, *args, **kwargs):
        context = _AssertWarnsContext(expected_warning, self, expected_regex)
        return context.handle("assertWarnsRegex", args, kwargs)

    def assertRegex(self, text, expected_regex, msg=None):
        if isinstance(expected_regex, (str, bytes)):
            expected_regex = re.compile(expected_regex)
        if not expected_regex.search(text):
            standardMsg = f"Regex didn't match: {expected_regex.pattern!r} not found in {text!r}"
            self.fail(self._formatMessage(msg, standardMsg))

    def assertNotRegex(self, text, unexpected_regex, msg=None):
        if isinstance(unexpected_regex, (str, bytes)):
            unexpected_regex = re.compile(unexpected_regex)
        match = unexpected_regex.search(text)
        if match:
            standardMsg = f"Regex matched: {text[match.start() : match.end()]!r} matches {unexpected_regex.pattern!r} in {text!r}"
            self.fail(self._formatMessage(msg, standardMsg))

    def assertStartsWith(self, s, prefix, msg=None):
        if not s.startswith(prefix):
            self.fail(self._formatMessage(msg, f"{safe_repr(s, short=True)} doesn't start with {safe_repr(prefix)}"))

    def assertEndsWith(self, s, suffix, msg=None):
        if not s.endswith(suffix):
            self.fail(self._formatMessage(msg, f"{safe_repr(s, short=True)} doesn't end with {safe_repr(suffix)}"))

    def assertHasAttr(self, obj, name, msg=None):
        if not hasattr(obj, name):
            self.fail(self._formatMessage(msg, f"{safe_repr(obj)} has no attribute {name!r}"))

    failUnlessEqual = assertEquals = assertEqual
    failIfEqual = assertNotEquals = assertNotEqual
    failUnless = assert_ = assertTrue
    failIf = assertFalse
    assertRaisesRegexp = assertRaisesRegex
    assertRegexpMatches = assertRegex
    assertNotRegexpMatches = assertNotRegex


class IsolatedAsyncioTestCase(TestCase):
    """Test methods that are coroutines run to their end, as asyncio.run does here."""

    async def asyncSetUp(self):
        pass

    async def asyncTearDown(self):
        pass

    def _callSetUp(self):
        import asyncio

        self.setUp()
        asyncio.run(self.asyncSetUp())

    def _callTearDown(self):
        import asyncio

        asyncio.run(self.asyncTearDown())
        self.tearDown()

    def run(self, result=None):
        import asyncio
        import inspect

        method = getattr(self, self._testMethodName)
        if inspect.iscoroutinefunction(method):
            original = method

            def sync_method():
                return asyncio.run(original())

            setattr(self, self._testMethodName, sync_method)
        return super().run(result)


class FunctionTestCase(TestCase):
    def __init__(self, testFunc, setUp=None, tearDown=None, description=None):
        super().__init__()
        self._setUpFunc = setUp
        self._tearDownFunc = tearDown
        self._testFunc = testFunc
        self._description = description

    def setUp(self):
        if self._setUpFunc is not None:
            self._setUpFunc()

    def tearDown(self):
        if self._tearDownFunc is not None:
            self._tearDownFunc()

    def runTest(self):
        self._testFunc()

    def id(self):
        return self._testFunc.__name__

    def __str__(self):
        return f"{strclass(type(self))} ({self._testFunc.__name__})"

    def shortDescription(self):
        if self._description is not None:
            return self._description
        doc = self._testFunc.__doc__
        return doc and doc.split("\n")[0].strip() or None


class BaseTestSuite:
    _cleanup = True

    def __init__(self, tests=()):
        self._tests = []
        self._removed_tests = 0
        self.addTests(tests)

    def __repr__(self):
        return f"<{strclass(type(self))} tests={list(self)}>"

    def __eq__(self, other):
        if not isinstance(other, type(self)):
            return NotImplemented
        return list(self) == list(other)

    def __iter__(self):
        return iter(self._tests)

    def countTestCases(self):
        cases = self._removed_tests
        for test in self:
            if test:
                cases += test.countTestCases()
        return cases

    def addTest(self, test):
        if not callable(test):
            raise TypeError(f"{test!r} is not callable")
        if isinstance(test, type) and issubclass(test, (TestCase, TestSuite)):
            raise TypeError("TestCases and TestSuites must be instantiated before passing them to addTest()")
        self._tests.append(test)

    def addTests(self, tests):
        if isinstance(tests, str):
            raise TypeError("tests must be an iterable of tests, not a string")
        for test in tests:
            self.addTest(test)

    def run(self, result):
        for index, test in enumerate(self):
            if result.shouldStop:
                break
            test(result)
        return result

    def __call__(self, *args, **kwds):
        return self.run(*args, **kwds)

    def debug(self):
        for test in self:
            test.debug()


class TestSuite(BaseTestSuite):
    def run(self, result, debug=False):
        previous_class = None
        for test in self:
            if result.shouldStop:
                break
            if isinstance(test, TestCase):
                cls = type(test)
                if cls is not previous_class:
                    if previous_class is not None:
                        _tear_down_class(previous_class, result)
                    previous_class = cls
                    if not _set_up_class(cls, result):
                        continue
                elif getattr(cls, "_classSetupFailed", False):
                    continue
            test(result)
        if previous_class is not None:
            _tear_down_class(previous_class, result)
        return result


class _ErrorHolder:
    failureException = None

    def __init__(self, description):
        self.description = description

    def id(self):
        return self.description

    def shortDescription(self):
        return None

    def __str__(self):
        return self.id()

    def countTestCases(self):
        return 0


def _set_up_class(cls, result):
    if getattr(cls, "__unittest_skip__", False):
        return True
    cls._classSetupFailed = False
    try:
        cls.setUpClass()
    except SkipTest as e:
        cls._classSetupFailed = True
        result.addSkip(_ErrorHolder(f"setUpClass ({strclass(cls)})"), str(e))
        return False
    except Exception as e:
        cls._classSetupFailed = True
        result.addError(_ErrorHolder(f"setUpClass ({strclass(cls)})"), (type(e), e, None))
        cls.doClassCleanups()
        return False
    return True


def _tear_down_class(cls, result):
    if getattr(cls, "_classSetupFailed", False) or getattr(cls, "__unittest_skip__", False):
        return
    try:
        cls.tearDownClass()
    except Exception as e:
        result.addError(_ErrorHolder(f"tearDownClass ({strclass(cls)})"), (type(e), e, None))
    cls.doClassCleanups()


class TestLoader:
    testMethodPrefix = "test"
    sortTestMethodsUsing = staticmethod(lambda x, y: (x > y) - (x < y))
    testNamePatterns = None
    suiteClass = TestSuite

    def __init__(self):
        self.errors = []

    def loadTestsFromTestCase(self, testCaseClass):
        if issubclass(testCaseClass, TestSuite):
            raise TypeError("Test cases should not be derived from TestSuite. Maybe you meant to derive from TestCase?")
        testCaseNames = self.getTestCaseNames(testCaseClass)
        if not testCaseNames and hasattr(testCaseClass, "runTest"):
            testCaseNames = ["runTest"]
        return self.suiteClass([testCaseClass(name) for name in testCaseNames])

    def loadTestsFromModule(self, module, *, pattern=None):
        tests = []
        for name in dir(module):
            obj = getattr(module, name)
            if isinstance(obj, type) and issubclass(obj, TestCase) and obj not in (TestCase, FunctionTestCase, IsolatedAsyncioTestCase):
                tests.append(self.loadTestsFromTestCase(obj))
        load_tests = getattr(module, "load_tests", None)
        tests = self.suiteClass(tests)
        if load_tests is not None:
            return load_tests(self, tests, pattern)
        return tests

    def loadTestsFromName(self, name, module=None):
        parts = name.split(".")
        if module is None:
            import importlib

            parts_copy = parts[:]
            while parts_copy:
                try:
                    module = importlib.import_module(".".join(parts_copy))
                    break
                except ImportError:
                    del parts_copy[-1]
                    if not parts_copy:
                        raise
            parts = parts[len(parts_copy) :]
        obj = module
        parent = None
        for part in parts:
            parent, obj = obj, getattr(obj, part)
        if isinstance(obj, type(sys)):
            return self.loadTestsFromModule(obj)
        if isinstance(obj, type) and issubclass(obj, TestCase):
            return self.loadTestsFromTestCase(obj)
        if callable(obj) and isinstance(parent, type) and issubclass(parent, TestCase):
            return self.suiteClass([parent(obj.__name__)])
        if isinstance(obj, TestSuite):
            return obj
        if callable(obj):
            test = obj()
            if isinstance(test, TestSuite):
                return test
            if isinstance(test, TestCase):
                return self.suiteClass([test])
            raise TypeError(f"calling {obj} returned {test}, not a test")
        raise TypeError(f"don't know how to make test from: {obj}")

    def loadTestsFromNames(self, names, module=None):
        return self.suiteClass([self.loadTestsFromName(name, module) for name in names])

    def getTestCaseNames(self, testCaseClass):
        names = []
        for name in dir(testCaseClass):
            if not name.startswith(self.testMethodPrefix):
                continue
            if not callable(getattr(testCaseClass, name)):
                continue
            if self.testNamePatterns is not None:
                import fnmatch

                full = f"{testCaseClass.__module__}.{testCaseClass.__qualname__}.{name}"
                if not any(fnmatch.fnmatchcase(full, p) for p in self.testNamePatterns):
                    continue
            names.append(name)
        names.sort()
        return names

    def discover(self, start_dir, pattern="test*.py", top_level_dir=None):
        return self.suiteClass([])


defaultTestLoader = TestLoader()


def getTestCaseNames(testCaseClass, prefix, sortUsing=None, testNamePatterns=None):
    loader = TestLoader()
    loader.testMethodPrefix = prefix
    loader.testNamePatterns = testNamePatterns
    return loader.getTestCaseNames(testCaseClass)


def makeSuite(testCaseClass, prefix="test", sortUsing=None, suiteClass=TestSuite):
    return defaultTestLoader.loadTestsFromTestCase(testCaseClass)


def findTestCases(module, prefix="test", sortUsing=None, suiteClass=TestSuite):
    return defaultTestLoader.loadTestsFromModule(module)


class _WritelnDecorator:
    def __init__(self, stream):
        self.stream = stream

    def __getattr__(self, attr):
        if attr in ("stream", "__getstate__"):
            raise AttributeError(attr)
        return getattr(self.stream, attr)

    def write(self, text):
        self.stream.write(text)

    def flush(self):
        flush = getattr(self.stream, "flush", None)
        if flush is not None:
            flush()

    def writeln(self, arg=None):
        if arg:
            self.write(arg)
        self.write("\n")


class TextTestResult(TestResult):
    separator1 = "=" * 70
    separator2 = "-" * 70

    def __init__(self, stream, descriptions, verbosity, *, durations=None):
        super().__init__(stream, descriptions, verbosity)
        self.stream = stream
        self.showAll = verbosity > 1
        self.dots = verbosity == 1
        self.descriptions = descriptions
        self._newline = True
        self.durations = durations

    def getDescription(self, test):
        doc_first_line = test.shortDescription()
        if self.descriptions and doc_first_line:
            return "\n".join((str(test), doc_first_line))
        return str(test)

    def startTest(self, test):
        super().startTest(test)
        if self.showAll:
            self.stream.write(self.getDescription(test))
            self.stream.write(" ... ")
            self.stream.flush()
            self._newline = False

    def _write_status(self, test, status):
        self.stream.writeln(status)
        self.stream.flush()
        self._newline = True

    def addSubTest(self, test, subtest, err):
        if err is not None:
            if self.showAll:
                if issubclass(err[0], subtest.failureException):
                    self._write_status(subtest, "FAIL")
                else:
                    self._write_status(subtest, "ERROR")
            elif self.dots:
                self.stream.write("F" if issubclass(err[0], subtest.failureException) else "E")
                self.stream.flush()
        super().addSubTest(test, subtest, err)

    def addSuccess(self, test):
        super().addSuccess(test)
        if self.showAll:
            self._write_status(test, "ok")
        elif self.dots:
            self.stream.write(".")
            self.stream.flush()

    def addError(self, test, err):
        super().addError(test, err)
        if self.showAll:
            self._write_status(test, "ERROR")
        elif self.dots:
            self.stream.write("E")
            self.stream.flush()

    def addFailure(self, test, err):
        super().addFailure(test, err)
        if self.showAll:
            self._write_status(test, "FAIL")
        elif self.dots:
            self.stream.write("F")
            self.stream.flush()

    def addSkip(self, test, reason):
        super().addSkip(test, reason)
        if self.showAll:
            self._write_status(test, f"skipped {reason!r}")
        elif self.dots:
            self.stream.write("s")
            self.stream.flush()

    def addExpectedFailure(self, test, err):
        super().addExpectedFailure(test, err)
        if self.showAll:
            self.stream.writeln("expected failure")
            self.stream.flush()
        elif self.dots:
            self.stream.write("x")
            self.stream.flush()

    def addUnexpectedSuccess(self, test):
        super().addUnexpectedSuccess(test)
        if self.showAll:
            self.stream.writeln("unexpected success")
            self.stream.flush()
        elif self.dots:
            self.stream.write("u")
            self.stream.flush()

    def printErrors(self):
        if self.dots or self.showAll:
            self.stream.writeln()
            self.stream.flush()
        self.printErrorList("ERROR", self.errors)
        self.printErrorList("FAIL", self.failures)
        unexpectedSuccesses = getattr(self, "unexpectedSuccesses", ())
        if unexpectedSuccesses:
            self.stream.writeln(self.separator1)
            for test in unexpectedSuccesses:
                self.stream.writeln(f"UNEXPECTED SUCCESS: {self.getDescription(test)}")
            self.stream.flush()

    def printErrorList(self, flavour, errors):
        for test, err in errors:
            self.stream.writeln(self.separator1)
            self.stream.writeln(f"{flavour}: {self.getDescription(test)}")
            self.stream.writeln(self.separator2)
            self.stream.writeln(f"{err}")
            self.stream.flush()


class TextTestRunner:
    resultclass = TextTestResult

    def __init__(self, stream=None, descriptions=True, verbosity=1, failfast=False, buffer=False, resultclass=None, warnings=None, *, tb_locals=False, durations=None):
        if stream is None:
            stream = sys.stderr
        self.stream = _WritelnDecorator(stream)
        self.descriptions = descriptions
        self.verbosity = verbosity
        self.failfast = failfast
        self.buffer = buffer
        self.tb_locals = tb_locals
        self.durations = durations
        self.warnings = warnings
        if resultclass is not None:
            self.resultclass = resultclass

    def _makeResult(self):
        try:
            return self.resultclass(self.stream, self.descriptions, self.verbosity, durations=self.durations)
        except TypeError:
            return self.resultclass(self.stream, self.descriptions, self.verbosity)

    def run(self, test):
        result = self._makeResult()
        result.failfast = self.failfast
        result.buffer = self.buffer
        result.tb_locals = self.tb_locals
        startTime = time.perf_counter() if hasattr(time, "perf_counter") else time.time()
        result.startTestRun()
        try:
            test(result)
        finally:
            result.stopTestRun()
        stopTime = time.perf_counter() if hasattr(time, "perf_counter") else time.time()
        timeTaken = stopTime - startTime
        result.printErrors()
        if hasattr(result, "separator2"):
            self.stream.writeln(result.separator2)
        run = result.testsRun
        self.stream.writeln("Ran %d test%s in %.3fs" % (run, run != 1 and "s" or "", timeTaken))
        self.stream.writeln()
        expectedFails = len(result.expectedFailures)
        unexpectedSuccesses = len(result.unexpectedSuccesses)
        skipped = len(result.skipped)
        infos = []
        if not result.wasSuccessful():
            self.stream.write("FAILED")
            failed, errored = len(result.failures), len(result.errors)
            if failed:
                infos.append(f"failures={failed}")
            if errored:
                infos.append(f"errors={errored}")
        elif run == 0 and not skipped:
            self.stream.write("NO TESTS RAN")
        else:
            self.stream.write("OK")
        if skipped:
            infos.append(f"skipped={skipped}")
        if expectedFails:
            infos.append(f"expected failures={expectedFails}")
        if unexpectedSuccesses:
            infos.append(f"unexpected successes={unexpectedSuccesses}")
        if infos:
            self.stream.writeln(" (%s)" % (", ".join(infos),))
        else:
            self.stream.write("\n")
        self.stream.flush()
        return result


class TestProgram:
    def __init__(self, module="__main__", defaultTest=None, argv=None, testRunner=None, testLoader=defaultTestLoader, exit=True, verbosity=1, failfast=None, catchbreak=None, buffer=None, warnings=None, *, tb_locals=False, durations=None):
        self.exit = exit
        self.verbosity = verbosity
        self.failfast = failfast
        self.testRunner = testRunner
        self.testLoader = testLoader
        self.durations = durations
        if isinstance(module, str):
            self.module = sys.modules.get(module)
            self._module_name = module
        else:
            self.module = module
            self._module_name = getattr(module, "__name__", None)
        if defaultTest is not None:
            names = [defaultTest] if isinstance(defaultTest, str) else list(defaultTest)
            self.test = self.testLoader.loadTestsFromNames(names, self.module)
        else:
            classes = [c for c in _test_case_classes if c.__module__ == self._module_name]
            if not classes and self.module is not None:
                self.test = self.testLoader.loadTestsFromModule(self.module)
            else:
                self.test = TestSuite([self.testLoader.loadTestsFromTestCase(c) for c in classes])
        self.runTests()

    def runTests(self):
        runner = self.testRunner
        if runner is None:
            runner = TextTestRunner(verbosity=self.verbosity, failfast=bool(self.failfast), durations=self.durations)
        elif isinstance(runner, type):
            runner = runner(verbosity=self.verbosity)
        self.result = runner.run(self.test)
        if self.exit:
            sys.exit(not self.result.wasSuccessful())


main = TestProgram


