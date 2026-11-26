"""ast: literal_eval() for Python literals, and the node class names.

literal_eval() reads strings, bytes, numbers, tuples, lists, sets, dicts,
booleans and None, as CPython's does. This interpreter does not keep
Python's own syntax tree, so parse(), dump() and unparse() raise
NotImplementedError; isinstance checks against the node classes work and
are simply never true of anything here.
"""

import _native

PyCF_ONLY_AST = 1024
PyCF_TYPE_COMMENTS = 4096
PyCF_ALLOW_TOP_LEVEL_AWAIT = 8192


class AST:
    _fields = ()
    _attributes = ("lineno", "col_offset", "end_lineno", "end_col_offset")

    def __init__(self, *args, **kwargs):
        for name, value in zip(self._fields, args):
            setattr(self, name, value)
        for name, value in kwargs.items():
            setattr(self, name, value)


def _node(name, fields=(), base=AST):
    cls = type(name, (base,), {"_fields": tuple(fields), "__module__": "ast"})
    globals()[name] = cls
    return cls


mod = _node("mod")
stmt = _node("stmt")
expr = _node("expr")
expr_context = _node("expr_context")
boolop = _node("boolop")
operator = _node("operator")
unaryop = _node("unaryop")
cmpop = _node("cmpop")
excepthandler = _node("excepthandler")
pattern = _node("pattern")
type_ignore = _node("type_ignore")
type_param = _node("type_param")
for _name, _fields in {
    "Module": ("body", "type_ignores"), "Interactive": ("body",), "Expression": ("body",), "FunctionType": ("argtypes", "returns"),
}.items():
    _node(_name, _fields, mod)
for _name, _fields in {
    "FunctionDef": ("name", "args", "body", "decorator_list", "returns", "type_comment", "type_params"),
    "AsyncFunctionDef": ("name", "args", "body", "decorator_list", "returns", "type_comment", "type_params"),
    "ClassDef": ("name", "bases", "keywords", "body", "decorator_list", "type_params"),
    "Return": ("value",), "Delete": ("targets",), "Assign": ("targets", "value", "type_comment"),
    "TypeAlias": ("name", "type_params", "value"), "AugAssign": ("target", "op", "value"),
    "AnnAssign": ("target", "annotation", "value", "simple"), "For": ("target", "iter", "body", "orelse", "type_comment"),
    "AsyncFor": ("target", "iter", "body", "orelse", "type_comment"), "While": ("test", "body", "orelse"),
    "If": ("test", "body", "orelse"), "With": ("items", "body", "type_comment"), "AsyncWith": ("items", "body", "type_comment"),
    "Match": ("subject", "cases"), "Raise": ("exc", "cause"), "Try": ("body", "handlers", "orelse", "finalbody"),
    "TryStar": ("body", "handlers", "orelse", "finalbody"), "Assert": ("test", "msg"), "Import": ("names",),
    "ImportFrom": ("module", "names", "level"), "Global": ("names",), "Nonlocal": ("names",), "Expr": ("value",),
    "Pass": (), "Break": (), "Continue": (),
}.items():
    _node(_name, _fields, stmt)
for _name, _fields in {
    "BoolOp": ("op", "values"), "NamedExpr": ("target", "value"), "BinOp": ("left", "op", "right"), "UnaryOp": ("op", "operand"),
    "Lambda": ("args", "body"), "IfExp": ("test", "body", "orelse"), "Dict": ("keys", "values"), "Set": ("elts",),
    "ListComp": ("elt", "generators"), "SetComp": ("elt", "generators"), "DictComp": ("key", "value", "generators"),
    "GeneratorExp": ("elt", "generators"), "Await": ("value",), "Yield": ("value",), "YieldFrom": ("value",),
    "Compare": ("left", "ops", "comparators"), "Call": ("func", "args", "keywords"),
    "FormattedValue": ("value", "conversion", "format_spec"), "JoinedStr": ("values",), "Constant": ("value", "kind"),
    "Attribute": ("value", "attr", "ctx"), "Subscript": ("value", "slice", "ctx"), "Starred": ("value", "ctx"),
    "Name": ("id", "ctx"), "List": ("elts", "ctx"), "Tuple": ("elts", "ctx"), "Slice": ("lower", "upper", "step"),
}.items():
    _node(_name, _fields, expr)
for _name in ("Load", "Store", "Del"):
    _node(_name, (), expr_context)
for _name in ("And", "Or"):
    _node(_name, (), boolop)
for _name in ("Add", "Sub", "Mult", "MatMult", "Div", "Mod", "Pow", "LShift", "RShift", "BitOr", "BitXor", "BitAnd", "FloorDiv"):
    _node(_name, (), operator)
for _name in ("Invert", "Not", "UAdd", "USub"):
    _node(_name, (), unaryop)
for _name in ("Eq", "NotEq", "Lt", "LtE", "Gt", "GtE", "Is", "IsNot", "In", "NotIn"):
    _node(_name, (), cmpop)
_node("ExceptHandler", ("type", "name", "body"), excepthandler)
_node("comprehension", ("target", "iter", "ifs", "is_async"))
_node("arguments", ("posonlyargs", "args", "vararg", "kwonlyargs", "kw_defaults", "kwarg", "defaults"))
_node("arg", ("arg", "annotation", "type_comment"))
_node("keyword", ("arg", "value"))
_node("alias", ("name", "asname"))
_node("withitem", ("context_expr", "optional_vars"))
_node("match_case", ("pattern", "guard", "body"))


def literal_eval(node_or_string):
    if not isinstance(node_or_string, str):
        if isinstance(node_or_string, bytes):
            node_or_string = node_or_string.decode("utf-8")
        else:
            raise ValueError(f"malformed node or string: {node_or_string!r}")
    source = node_or_string.lstrip(" \t")
    _native.literal_check(source)
    return eval(source, {})


def _unavailable(name):
    def fail(*args, **kwargs):
        raise NotImplementedError(f"ast.{name}() is not available here: this interpreter does not keep Python's syntax tree")

    fail.__name__ = name
    return fail


parse = _unavailable("parse")
dump = _unavailable("dump")
unparse = _unavailable("unparse")
fix_missing_locations = _unavailable("fix_missing_locations")
copy_location = _unavailable("copy_location")
increment_lineno = _unavailable("increment_lineno")


def iter_fields(node):
    for field in node._fields:
        try:
            yield field, getattr(node, field)
        except AttributeError:
            pass


def iter_child_nodes(node):
    for name, field in iter_fields(node):
        if isinstance(field, AST):
            yield field
        elif isinstance(field, list):
            for item in field:
                if isinstance(item, AST):
                    yield item


def walk(node):
    todo = [node]
    while todo:
        node = todo.pop(0)
        todo.extend(iter_child_nodes(node))
        yield node


def get_docstring(node, clean=True):
    if not isinstance(node, (FunctionDef, AsyncFunctionDef, ClassDef, Module)):
        raise TypeError(f"{node.__class__.__name__!r} can't have docstrings")
    body = getattr(node, "body", None)
    if not body or not isinstance(body[0], Expr):
        return None
    node = body[0].value
    if not isinstance(node, Constant) or not isinstance(node.value, str):
        return None
    text = node.value
    if clean:
        text = _cleandoc(text)
    return text


def _cleandoc(doc):
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


class NodeVisitor:
    def visit(self, node):
        method = "visit_" + node.__class__.__name__
        visitor = getattr(self, method, self.generic_visit)
        return visitor(node)

    def generic_visit(self, node):
        for field, value in iter_fields(node):
            if isinstance(value, list):
                for item in value:
                    if isinstance(item, AST):
                        self.visit(item)
            elif isinstance(value, AST):
                self.visit(value)


class NodeTransformer(NodeVisitor):
    def generic_visit(self, node):
        for field, old_value in iter_fields(node):
            if isinstance(old_value, list):
                new_values = []
                for value in old_value:
                    if isinstance(value, AST):
                        value = self.visit(value)
                        if value is None:
                            continue
                        if not isinstance(value, AST):
                            new_values.extend(value)
                            continue
                    new_values.append(value)
                old_value[:] = new_values
            elif isinstance(old_value, AST):
                new_node = self.visit(old_value)
                if new_node is None:
                    delattr(node, field)
                else:
                    setattr(node, field, new_node)
        return node
