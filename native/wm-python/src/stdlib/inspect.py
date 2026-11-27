"""inspect: what kind of object something is, and the signatures of
functions, methods and classes.

There is no source code or call stack to look at here: getsource() and
friends raise OSError, and stack() and currentframe() have nothing to give.
"""

import builtins
import functools
import sys
import types
from collections import namedtuple

CO_OPTIMIZED = 0x1
CO_NEWLOCALS = 0x2
CO_VARARGS = 0x4
CO_VARKEYWORDS = 0x8
CO_NESTED = 0x10
CO_GENERATOR = 0x20
CO_NOFREE = 0x40
CO_COROUTINE = 0x80
CO_ITERABLE_COROUTINE = 0x100
CO_ASYNC_GENERATOR = 0x200

TPFLAGS_IS_ABSTRACT = 1 << 20


# MARK: - What kind of object


def ismodule(object):
    return isinstance(object, types.ModuleType)


def isclass(object):
    return isinstance(object, type)


def ismethod(object):
    return isinstance(object, types.MethodType)


def isfunction(object):
    return isinstance(object, types.FunctionType)


def isbuiltin(object):
    return isinstance(object, types.BuiltinFunctionType)


def ismethoddescriptor(object):
    if isclass(object) or ismethod(object) or isfunction(object):
        return False
    tp = type(object)
    return hasattr(tp, "__get__") and not hasattr(tp, "__set__")


def isdatadescriptor(object):
    if isclass(object) or ismethod(object) or isfunction(object):
        return False
    if isinstance(object, property):
        return True
    tp = type(object)
    return hasattr(tp, "__set__") or hasattr(tp, "__delete__")


def isroutine(object):
    return isbuiltin(object) or isfunction(object) or ismethod(object) or ismethoddescriptor(object)


def _has_code_flag(f, flag):
    while ismethod(f):
        f = f.__func__
    while isinstance(f, functools.partial):
        f = f.func
    if not isfunction(f):
        return False
    return bool(f.__code__.co_flags & flag)


def isgeneratorfunction(obj):
    return _has_code_flag(obj, CO_GENERATOR)


_is_coroutine_mark = object()


def markcoroutinefunction(func):
    if hasattr(func, "__func__"):
        func = func.__func__
    func._is_coroutine_marker = _is_coroutine_mark
    return func


def iscoroutinefunction(obj):
    if getattr(obj, "_is_coroutine_marker", None) is _is_coroutine_mark:
        return True
    return _has_code_flag(obj, CO_COROUTINE)


def isasyncgenfunction(obj):
    return _has_code_flag(obj, CO_ASYNC_GENERATOR)


def isasyncgen(object):
    return isinstance(object, types.AsyncGeneratorType)


def isgenerator(object):
    return isinstance(object, types.GeneratorType)


def iscoroutine(object):
    return isinstance(object, types.CoroutineType)


def isawaitable(object):
    return isinstance(object, types.CoroutineType) or hasattr(type(object), "__await__")


def istraceback(object):
    return isinstance(object, types.TracebackType)


def isframe(object):
    return isinstance(object, types.FrameType)


def iscode(object):
    return isinstance(object, types.CodeType)


def isabstract(object):
    return bool(getattr(object, "__abstractmethods__", False))


def isgetsetdescriptor(object):
    return False


def ismemberdescriptor(object):
    return False


def ismethodwrapper(object):
    return False


# MARK: - Members, docs, modules


def getmembers(object, predicate=None):
    results = []
    for key in dir(object):
        try:
            value = getattr(object, key)
        except AttributeError:
            continue
        if not predicate or predicate(value):
            results.append((key, value))
    results.sort(key=lambda pair: pair[0])
    return results


def getmembers_static(object, predicate=None):
    return getmembers(object, predicate)


def getmro(cls):
    return cls.__mro__


def cleandoc(doc):
    lines = doc.expandtabs().split("\n")
    margin = None
    for line in lines[1:]:
        content = len(line.lstrip())
        if content:
            indent = len(line) - content
            margin = indent if margin is None else min(margin, indent)
    if lines:
        lines[0] = lines[0].lstrip()
    if margin is not None:
        for i in range(1, len(lines)):
            lines[i] = lines[i][margin:]
    while lines and not lines[-1]:
        lines.pop()
    while lines and not lines[0]:
        lines.pop(0)
    return "\n".join(lines)


def getdoc(object):
    try:
        doc = object.__doc__
    except AttributeError:
        return None
    if doc is None and isclass(object):
        for base in object.__mro__[1:]:
            doc = getattr(base, "__doc__", None)
            if doc is not None and base is not object:
                break
            doc = None
    if not isinstance(doc, str):
        return None
    return cleandoc(doc)


def getcomments(object):
    return None


def getmodule(object, _filename=None):
    if ismodule(object):
        return object
    name = getattr(object, "__module__", None)
    if name is None:
        return None
    return sys.modules.get(name)


def getmodulename(path):
    name = path.rsplit("/", 1)[-1]
    if name.endswith(".py"):
        return name[:-3]
    return None


def _no_source(object):
    raise OSError("could not get source code: plugins run without their source files")


def getfile(object):
    if ismodule(object) and getattr(object, "__file__", None):
        return object.__file__
    raise TypeError(f"{object!r} is a built-in object")


def getsourcefile(object):
    return None


def getsource(object):
    _no_source(object)


def getsourcelines(object):
    _no_source(object)


def findsource(object):
    _no_source(object)


def getblock(lines):
    return lines


def indentsize(line):
    expline = line.expandtabs()
    return len(expline) - len(expline.lstrip())


def currentframe():
    return None


def stack(context=1):
    return []


def trace(context=1):
    return []


def getouterframes(frame, context=1):
    return []


def getinnerframes(tb, context=1):
    return []


def getframeinfo(frame, context=1):
    raise TypeError(f"{frame!r} is not a frame or traceback object")


def unwrap(func, *, stop=None):
    f = func
    seen = {id(f)}
    while hasattr(f, "__wrapped__") and not (stop is not None and stop(f)):
        f = f.__wrapped__
        if id(f) in seen:
            raise ValueError(f"wrapper loop when unwrapping {func!r}")
        seen.add(id(f))
    return f


def get_annotations(obj, *, globals=None, locals=None, eval_str=False):
    ann = getattr(obj, "__annotations__", None)
    return dict(ann) if ann else {}


def formatannotation(annotation, base_module=None):
    if getattr(annotation, "__module__", None) == "typing":
        return repr(annotation).replace("typing.", "")
    if isinstance(annotation, type):
        if annotation.__module__ in ("builtins", base_module):
            return annotation.__qualname__
        return annotation.__module__ + "." + annotation.__qualname__
    return repr(annotation)


_sentinel = object()


def getattr_static(obj, attr, default=_sentinel):
    try:
        return vars(obj)[attr]
    except (TypeError, KeyError):
        pass
    for klass in (obj.__mro__ if isclass(obj) else type(obj).__mro__):
        try:
            return vars(klass)[attr]
        except (TypeError, KeyError):
            pass
    if default is not _sentinel:
        return default
    raise AttributeError(attr)


# MARK: - Signatures


class _empty:
    """Marker for a parameter with no default or annotation."""


class _void:
    """Marker for "no value given"."""


class _ParameterKind:
    def __init__(self, value, name, description):
        self.value = value
        self.name = name
        self.description = description

    def __repr__(self):
        return f"<_ParameterKind.{self.name}: {self.value}>"

    def __str__(self):
        return self.name

    def __int__(self):
        return self.value

    def __index__(self):
        return self.value

    def __eq__(self, other):
        if isinstance(other, _ParameterKind):
            return self.value == other.value
        if isinstance(other, int):
            return self.value == other
        return NotImplemented

    def __lt__(self, other):
        return self.value < int(other)

    def __le__(self, other):
        return self.value <= int(other)

    def __gt__(self, other):
        return self.value > int(other)

    def __ge__(self, other):
        return self.value >= int(other)

    def __hash__(self):
        return hash(self.value)


_POSITIONAL_ONLY = _ParameterKind(0, "POSITIONAL_ONLY", "positional-only")
_POSITIONAL_OR_KEYWORD = _ParameterKind(1, "POSITIONAL_OR_KEYWORD", "positional or keyword")
_VAR_POSITIONAL = _ParameterKind(2, "VAR_POSITIONAL", "variadic positional")
_KEYWORD_ONLY = _ParameterKind(3, "KEYWORD_ONLY", "keyword-only")
_VAR_KEYWORD = _ParameterKind(4, "VAR_KEYWORD", "variadic keyword")


class Parameter:
    POSITIONAL_ONLY = _POSITIONAL_ONLY
    POSITIONAL_OR_KEYWORD = _POSITIONAL_OR_KEYWORD
    VAR_POSITIONAL = _VAR_POSITIONAL
    KEYWORD_ONLY = _KEYWORD_ONLY
    VAR_KEYWORD = _VAR_KEYWORD
    empty = _empty

    def __init__(self, name, kind, *, default=_empty, annotation=_empty):
        if not isinstance(kind, _ParameterKind):
            kind = [_POSITIONAL_ONLY, _POSITIONAL_OR_KEYWORD, _VAR_POSITIONAL, _KEYWORD_ONLY, _VAR_KEYWORD][int(kind)]
        if default is not _empty and kind in (_VAR_POSITIONAL, _VAR_KEYWORD):
            raise ValueError(f"{kind.description} parameters cannot have default values")
        if not isinstance(name, str):
            raise TypeError(f"name must be a str, not a {type(name).__name__}")
        if not name.isidentifier():
            raise ValueError(f"{name!r} is not a valid parameter name")
        self._name = name
        self._kind = kind
        self._default = default
        self._annotation = annotation

    @property
    def name(self):
        return self._name

    @property
    def default(self):
        return self._default

    @property
    def annotation(self):
        return self._annotation

    @property
    def kind(self):
        return self._kind

    def replace(self, *, name=_void, kind=_void, annotation=_void, default=_void):
        return type(self)(
            self._name if name is _void else name,
            self._kind if kind is _void else kind,
            default=self._default if default is _void else default,
            annotation=self._annotation if annotation is _void else annotation,
        )

    def __str__(self):
        kind = self.kind
        formatted = self._name
        if self._annotation is not _empty:
            formatted = f"{formatted}: {formatannotation(self._annotation)}"
        if self._default is not _empty:
            if self._annotation is not _empty:
                formatted = f"{formatted} = {self._default!r}"
            else:
                formatted = f"{formatted}={self._default!r}"
        if kind == _VAR_POSITIONAL:
            formatted = "*" + formatted
        elif kind == _VAR_KEYWORD:
            formatted = "**" + formatted
        return formatted

    def __repr__(self):
        return f'<{type(self).__name__} "{self}">'

    def __hash__(self):
        return hash((self._name, self._kind, self._annotation, self._default))

    def __eq__(self, other):
        if self is other:
            return True
        if not isinstance(other, Parameter):
            return NotImplemented
        return self._name == other._name and self._kind == other._kind and self._default == other._default and self._annotation == other._annotation


class BoundArguments:
    def __init__(self, signature, arguments):
        self.arguments = arguments
        self._signature = signature

    @property
    def signature(self):
        return self._signature

    @property
    def args(self):
        args = []
        for param_name, param in self._signature.parameters.items():
            if param.kind in (_VAR_KEYWORD, _KEYWORD_ONLY):
                break
            try:
                arg = self.arguments[param_name]
            except KeyError:
                break
            if param.kind == _VAR_POSITIONAL:
                args.extend(arg)
            else:
                args.append(arg)
        return tuple(args)

    @property
    def kwargs(self):
        kwargs = {}
        kwargs_started = False
        for param_name, param in self._signature.parameters.items():
            if not kwargs_started:
                if param.kind in (_VAR_KEYWORD, _KEYWORD_ONLY):
                    kwargs_started = True
                elif param_name not in self.arguments:
                    kwargs_started = True
                    continue
            if not kwargs_started:
                continue
            try:
                arg = self.arguments[param_name]
            except KeyError:
                pass
            else:
                if param.kind == _VAR_KEYWORD:
                    kwargs.update(arg)
                else:
                    kwargs[param_name] = arg
        return kwargs

    def apply_defaults(self):
        arguments = self.arguments
        new_arguments = []
        for name, param in self._signature.parameters.items():
            try:
                new_arguments.append((name, arguments[name]))
            except KeyError:
                if param.default is not _empty:
                    val = param.default
                elif param.kind == _VAR_POSITIONAL:
                    val = ()
                elif param.kind == _VAR_KEYWORD:
                    val = {}
                else:
                    continue
                new_arguments.append((name, val))
        self.arguments = dict(new_arguments)

    def __eq__(self, other):
        if not isinstance(other, BoundArguments):
            return NotImplemented
        return self.signature == other.signature and self.arguments == other.arguments

    def __repr__(self):
        args = ", ".join(f"{arg}={value!r}" for arg, value in self.arguments.items())
        return f"<{type(self).__name__} ({args})>"


class Signature:
    empty = _empty
    _parameter_cls = Parameter
    _bound_arguments_cls = BoundArguments

    def __init__(self, parameters=None, *, return_annotation=_empty, __validate_parameters__=True):
        params = {}
        if parameters is not None:
            for param in parameters:
                if param.name in params:
                    raise ValueError(f"duplicate parameter name: {param.name!r}")
                params[param.name] = param
        self._parameters = params
        self._return_annotation = return_annotation

    @classmethod
    def from_callable(cls, obj, *, follow_wrapped=True, globals=None, locals=None, eval_str=False):
        return _signature_from_callable(obj, follow_wrapped)

    @property
    def parameters(self):
        return types.MappingProxyType(self._parameters)

    @property
    def return_annotation(self):
        return self._return_annotation

    def replace(self, *, parameters=_void, return_annotation=_void):
        if parameters is _void:
            parameters = self._parameters.values()
        if return_annotation is _void:
            return_annotation = self._return_annotation
        return type(self)(parameters, return_annotation=return_annotation)

    def __eq__(self, other):
        if self is other:
            return True
        if not isinstance(other, Signature):
            return NotImplemented
        return self._return_annotation == other._return_annotation and list(self._parameters.values()) == list(other._parameters.values())

    def __hash__(self):
        return hash((tuple(self._parameters.values()), self._return_annotation))

    def _bind(self, args, kwargs, *, partial=False):
        arguments = {}
        parameters = iter(self._parameters.values())
        parameters_ex = ()
        arg_vals = iter(args)
        while True:
            try:
                arg_val = next(arg_vals)
            except StopIteration:
                try:
                    param = next(parameters)
                except StopIteration:
                    break
                else:
                    if param.kind == _VAR_POSITIONAL:
                        break
                    elif param.name in kwargs:
                        if param.kind == _POSITIONAL_ONLY:
                            raise TypeError(f"{param.name!r} parameter is positional only, but was passed as a keyword")
                        parameters_ex = (param,)
                        break
                    elif param.kind == _VAR_KEYWORD or param.default is not _empty:
                        parameters_ex = (param,)
                        break
                    else:
                        if partial:
                            parameters_ex = (param,)
                            break
                        raise TypeError(f"missing a required argument: {param.name!r}") from None
            else:
                try:
                    param = next(parameters)
                except StopIteration:
                    raise TypeError("too many positional arguments") from None
                else:
                    if param.kind in (_VAR_KEYWORD, _KEYWORD_ONLY):
                        raise TypeError("too many positional arguments") from None
                    if param.kind == _VAR_POSITIONAL:
                        values = [arg_val]
                        values.extend(arg_vals)
                        arguments[param.name] = tuple(values)
                        break
                    if param.name in kwargs and param.kind != _POSITIONAL_ONLY:
                        raise TypeError(f"multiple values for argument {param.name!r}") from None
                    arguments[param.name] = arg_val
        kwargs_param = None
        for param in list(parameters_ex) + list(parameters):
            if param.kind == _VAR_KEYWORD:
                kwargs_param = param
                continue
            if param.kind == _VAR_POSITIONAL:
                continue
            param_name = param.name
            try:
                arg_val = kwargs.pop(param_name)
            except KeyError:
                if not partial and param.kind != _VAR_POSITIONAL and param.default is _empty:
                    raise TypeError(f"missing a required argument: {param_name!r}") from None
            else:
                if param.kind == _POSITIONAL_ONLY:
                    raise TypeError(f"{param.name!r} parameter is positional only, but was passed as a keyword")
                arguments[param_name] = arg_val
        if kwargs:
            if kwargs_param is not None:
                arguments[kwargs_param.name] = kwargs
            else:
                raise TypeError(f"got an unexpected keyword argument {next(iter(kwargs))!r}")
        return self._bound_arguments_cls(self, arguments)

    def bind(self, /, *args, **kwargs):
        return self._bind(args, dict(kwargs))

    def bind_partial(self, /, *args, **kwargs):
        return self._bind(args, dict(kwargs), partial=True)

    def __repr__(self):
        return f"<{type(self).__name__} {self}>"

    def __str__(self):
        result = []
        render_pos_only_separator = False
        render_kw_only_separator = True
        for param in self._parameters.values():
            formatted = str(param)
            kind = param.kind
            if kind == _POSITIONAL_ONLY:
                render_pos_only_separator = True
            elif render_pos_only_separator:
                result.append("/")
                render_pos_only_separator = False
            if kind == _VAR_POSITIONAL:
                render_kw_only_separator = False
            elif kind == _KEYWORD_ONLY and render_kw_only_separator:
                result.append("*")
                render_kw_only_separator = False
            result.append(formatted)
        if render_pos_only_separator:
            result.append("/")
        rendered = "({})".format(", ".join(result))
        if self._return_annotation is not _empty:
            rendered += f" -> {formatannotation(self._return_annotation)}"
        return rendered


def _function_signature(func, skip_first=False):
    code = func.__code__
    argcount = code.co_argcount
    posonlycount = code.co_posonlyargcount
    kwonlycount = code.co_kwonlyargcount
    names = code.co_varnames
    flags = code.co_flags
    defaults = func.__defaults__ or ()
    kwdefaults = func.__kwdefaults__ or {}
    annotations = getattr(func, "__annotations__", None) or {}
    params = []
    positional = names[:argcount]
    first_default = argcount - len(defaults)
    for i, name in enumerate(positional):
        default = defaults[i - first_default] if i >= first_default else _empty
        kind = _POSITIONAL_ONLY if i < posonlycount else _POSITIONAL_OR_KEYWORD
        params.append(Parameter(name, kind, default=default, annotation=annotations.get(name, _empty)))
    rest = names[argcount:]
    kwonly = rest[:kwonlycount]
    rest = rest[kwonlycount:]
    if flags & CO_VARARGS:
        params.append(Parameter(rest[0], _VAR_POSITIONAL, annotation=annotations.get(rest[0], _empty)))
        rest = rest[1:]
    for name in kwonly:
        params.append(Parameter(name, _KEYWORD_ONLY, default=kwdefaults.get(name, _empty), annotation=annotations.get(name, _empty)))
    if flags & CO_VARKEYWORDS:
        params.append(Parameter(rest[0], _VAR_KEYWORD, annotation=annotations.get(rest[0], _empty)))
    if skip_first:
        if params and params[0].kind in (_POSITIONAL_ONLY, _POSITIONAL_OR_KEYWORD):
            params = params[1:]
        elif not params or params[0].kind != _VAR_POSITIONAL:
            raise ValueError("invalid method signature")
    return Signature(params, return_annotation=annotations.get("return", _empty))


def _signature_from_callable(obj, follow_wrapped=True, skip_first=False):
    if not callable(obj):
        raise TypeError(f"{obj!r} is not a callable object")
    if ismethod(obj):
        sig = _signature_from_callable(obj.__func__, follow_wrapped)
        params = list(sig.parameters.values())
        if params and params[0].kind in (_POSITIONAL_ONLY, _POSITIONAL_OR_KEYWORD):
            params = params[1:]
        return sig.replace(parameters=params)
    if follow_wrapped:
        obj = unwrap(obj, stop=lambda f: hasattr(f, "__signature__"))
    sig = getattr(obj, "__signature__", None)
    if sig is not None and isinstance(sig, Signature):
        return sig
    if isinstance(obj, functools.partial):
        wrapped_sig = _signature_from_callable(obj.func, follow_wrapped)
        params = list(wrapped_sig.parameters.values())
        params = params[len(obj.args) :]
        keywords = obj.keywords or {}
        new_params = []
        for p in params:
            if p.name in keywords:
                new_params.append(p.replace(default=keywords[p.name], kind=_KEYWORD_ONLY))
            else:
                new_params.append(p)
        return wrapped_sig.replace(parameters=new_params)
    if isfunction(obj):
        return _function_signature(obj, skip_first)
    if isclass(obj):
        init = getattr(obj, "__init__", None)
        if isfunction(init):
            return _function_signature(init, skip_first=True)
        new = vars(obj).get("__new__") if hasattr(obj, "__dict__") else None
        if isfunction(new):
            return _function_signature(new, skip_first=True)
        # Neither written in Python: a built-in type's, as CPython gives it
        # where it has one, or object's own, which takes nothing.
        if getattr(obj, "__module__", None) == "builtins" and obj is not object:
            text = _BUILTIN_SIGNATURES.get(obj.__name__) if obj is getattr(builtins, obj.__name__, None) else None
            if text is None:
                raise ValueError(f"no signature found for builtin type {obj!r}")
            return _signature_from_text(text)
        return Signature([])
    if isbuiltin(obj) and getattr(obj, "__module__", None) == "builtins":
        text = _BUILTIN_SIGNATURES.get(obj.__name__) if obj is getattr(builtins, obj.__name__, None) else None
        if text is not None:
            return _signature_from_text(text)
    call = getattr(type(obj), "__call__", None)
    if call is not None and isfunction(call):
        return _function_signature(call, skip_first=True)
    raise ValueError(f"no signature found for builtin {obj!r}")


# The builtins' signatures, as CPython's __text_signature__ gives them; those
# CPython has none for (max, getattr, int, str, ...) are missing here too.
_BUILTIN_SIGNATURES = {
    "abs": "(x, /)", "aiter": "(async_iterable, /)", "all": "(iterable, /)", "any": "(iterable, /)",
    "ascii": "(obj, /)", "bin": "(number, /)", "bool": "(object=False, /)", "callable": "(obj, /)",
    "chr": "(i, /)", "classmethod": "(function, /)",
    "compile": "(source, filename, mode, flags=0, dont_inherit=False, optimize=-1, *, _feature_version=-1)",
    "complex": "(real=0, imag=0)", "delattr": "(obj, name, /)", "divmod": "(x, y, /)",
    "enumerate": "(iterable, start=0)", "eval": "(source, /, globals=None, locals=None)",
    "exec": "(source, /, globals=None, locals=None, *, closure=None)", "filter": "(function, iterable, /)",
    "float": "(x=0, /)", "format": "(value, format_spec='', /)", "frozenset": "(iterable=(), /)",
    "globals": "()", "hasattr": "(obj, name, /)", "hash": "(obj, /)", "hex": "(number, /)", "id": "(obj, /)",
    "input": "(prompt='', /)", "isinstance": "(obj, class_or_tuple, /)", "issubclass": "(cls, class_or_tuple, /)",
    "len": "(obj, /)", "list": "(iterable=(), /)", "locals": "()", "map": "(function, iterable, /, *iterables)",
    "object": "()", "oct": "(number, /)",
    "open": "(file, mode='r', buffering=-1, encoding=None, errors=None, newline=None, closefd=True, opener=None)",
    "ord": "(c, /)", "pow": "(base, exp, mod=None)", "print": "(*args, sep=' ', end='\\n', file=None, flush=False)",
    "property": "(fget=None, fset=None, fdel=None, doc=None)", "repr": "(obj, /)", "reversed": "(sequence, /)",
    "round": "(number, ndigits=None)", "set": "(iterable=(), /)", "setattr": "(obj, name, value, /)",
    "sorted": "(iterable, /, *, key=None, reverse=False)", "staticmethod": "(function, /)",
    "sum": "(iterable, /, start=0)", "tuple": "(iterable=(), /)", "zip": "(*iterables, strict=False)",
}


def _signature_from_text(text):
    """A Signature from text like "(obj, /, *, key=None)": CPython's
    __text_signature__ form, whose defaults are literals."""
    import ast
    params = []
    kind = _POSITIONAL_OR_KEYWORD
    for part in (p.strip() for p in text[1:-1].split(", ")):
        if not part:
            continue
        if part == "/":
            params = [p.replace(kind=_POSITIONAL_ONLY) for p in params]
            continue
        if part == "*":
            kind = _KEYWORD_ONLY
            continue
        if part.startswith("**"):
            params.append(Parameter(part[2:], _VAR_KEYWORD))
            continue
        if part.startswith("*"):
            params.append(Parameter(part[1:], _VAR_POSITIONAL))
            kind = _KEYWORD_ONLY
            continue
        name, eq, default = part.partition("=")
        params.append(Parameter(name, kind, default=ast.literal_eval(default) if eq else _empty))
    return Signature(params)


def signature(obj, *, follow_wrapped=True, globals=None, locals=None, eval_str=False):
    return Signature.from_callable(obj, follow_wrapped=follow_wrapped)


FullArgSpec = namedtuple("FullArgSpec", "args, varargs, varkw, defaults, kwonlyargs, kwonlydefaults, annotations")
ArgInfo = namedtuple("ArgInfo", "args varargs keywords locals")
Attribute = namedtuple("Attribute", "name kind defining_class object")
ClosureVars = namedtuple("ClosureVars", "nonlocals globals builtins unbound")
FrameInfo = namedtuple("FrameInfo", "frame filename lineno function code_context index")
Traceback = namedtuple("Traceback", "filename lineno function code_context index")


def getfullargspec(func):
    try:
        sig = _signature_from_callable(func, follow_wrapped=False)
    except Exception as ex:
        raise TypeError("unsupported callable") from ex
    args = []
    varargs = None
    varkw = None
    kwonlyargs = []
    annotations = {}
    defaults = ()
    kwdefaults = {}
    if sig.return_annotation is not _empty:
        annotations["return"] = sig.return_annotation
    for param in sig.parameters.values():
        kind = param.kind
        name = param.name
        if kind in (_POSITIONAL_ONLY, _POSITIONAL_OR_KEYWORD):
            args.append(name)
            if param.default is not _empty:
                defaults += (param.default,)
        elif kind == _VAR_POSITIONAL:
            varargs = name
        elif kind == _KEYWORD_ONLY:
            kwonlyargs.append(name)
            if param.default is not _empty:
                kwdefaults[name] = param.default
        elif kind == _VAR_KEYWORD:
            varkw = name
        if param.annotation is not _empty:
            annotations[name] = param.annotation
    return FullArgSpec(args, varargs, varkw, defaults or None, kwonlyargs, kwdefaults or None, annotations)


def getcallargs(func, /, *positional, **named):
    bound = signature(func).bind(*positional, **named)
    bound.apply_defaults()
    return dict(bound.arguments)


def getclosurevars(func):
    return ClosureVars({}, {}, {}, set())


def getgeneratorstate(generator):
    return "GEN_CREATED"


def getcoroutinestate(coroutine):
    return "CORO_CREATED"


class ClassFoundException(Exception):
    pass


class BlockFinder:
    pass


class EndOfBlock(Exception):
    pass
