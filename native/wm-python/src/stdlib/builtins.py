"""builtins: the built-in names as a module, for code that reads them as
`builtins.len` or asks `hasattr(builtins, name)`. Binding a name here does
not change the builtin itself."""

abs = abs
aiter = aiter
all = all
anext = anext
any = any
ascii = ascii
bin = bin
bool = bool
bytearray = bytearray
bytes = bytes
callable = callable
chr = chr
classmethod = classmethod
complex = complex
delattr = delattr
dict = dict
dir = dir
divmod = divmod
enumerate = enumerate
eval = eval
exec = exec
filter = filter
float = float
format = format
frozenset = frozenset
getattr = getattr
globals = globals
hasattr = hasattr
hash = hash
hex = hex
id = id
int = int
isinstance = isinstance
issubclass = issubclass
iter = iter
len = len
list = list
locals = locals
map = map
max = max
memoryview = memoryview
min = min
next = next
object = object
oct = oct
ord = ord
pow = pow
print = print
property = property
range = range
repr = repr
reversed = reversed
round = round
set = set
setattr = setattr
slice = slice
sorted = sorted
staticmethod = staticmethod
str = str
sum = sum
super = super
tuple = tuple
type = type
vars = vars
zip = zip
ArithmeticError = ArithmeticError
AssertionError = AssertionError
AttributeError = AttributeError
BaseException = BaseException
BlockingIOError = BlockingIOError
BrokenPipeError = BrokenPipeError
BufferError = BufferError
BytesWarning = BytesWarning
ChildProcessError = ChildProcessError
ConnectionAbortedError = ConnectionAbortedError
ConnectionError = ConnectionError
ConnectionRefusedError = ConnectionRefusedError
ConnectionResetError = ConnectionResetError
DeprecationWarning = DeprecationWarning
EOFError = EOFError
Ellipsis = Ellipsis
EncodingWarning = EncodingWarning
Exception = Exception
FileExistsError = FileExistsError
FileNotFoundError = FileNotFoundError
FloatingPointError = FloatingPointError
FutureWarning = FutureWarning
GeneratorExit = GeneratorExit
ImportError = ImportError
ImportWarning = ImportWarning
IndentationError = IndentationError
IndexError = IndexError
InterruptedError = InterruptedError
IsADirectoryError = IsADirectoryError
KeyError = KeyError
KeyboardInterrupt = KeyboardInterrupt
LookupError = LookupError
MemoryError = MemoryError
ModuleNotFoundError = ModuleNotFoundError
NameError = NameError
NotADirectoryError = NotADirectoryError
NotImplemented = NotImplemented
NotImplementedError = NotImplementedError
OSError = OSError
OverflowError = OverflowError
PendingDeprecationWarning = PendingDeprecationWarning
PermissionError = PermissionError
ProcessLookupError = ProcessLookupError
PythonFinalizationError = PythonFinalizationError
RecursionError = RecursionError
ReferenceError = ReferenceError
ResourceWarning = ResourceWarning
RuntimeError = RuntimeError
RuntimeWarning = RuntimeWarning
StopAsyncIteration = StopAsyncIteration
StopIteration = StopIteration
SyntaxError = SyntaxError
SyntaxWarning = SyntaxWarning
SystemError = SystemError
SystemExit = SystemExit
TabError = TabError
TimeoutError = TimeoutError
TypeError = TypeError
UnboundLocalError = UnboundLocalError
UnicodeDecodeError = UnicodeDecodeError
UnicodeEncodeError = UnicodeEncodeError
UnicodeError = UnicodeError
UnicodeTranslateError = UnicodeTranslateError
UnicodeWarning = UnicodeWarning
UserWarning = UserWarning
ValueError = ValueError
Warning = Warning
ZeroDivisionError = ZeroDivisionError


# Builtins this build may or may not have: each one it has is here too.
try:
    BaseExceptionGroup = BaseExceptionGroup
except NameError:
    pass
try:
    ExceptionGroup = ExceptionGroup
except NameError:
    pass
try:
    PythonFinalizationError = PythonFinalizationError
except NameError:
    pass
try:
    open = open
except NameError:
    pass
try:
    input = input
except NameError:
    pass
try:
    help = help
except NameError:
    pass
try:
    breakpoint = breakpoint
except NameError:
    pass
try:
    compile = compile
except NameError:
    pass
try:
    memoryview = memoryview
except NameError:
    pass
try:
    vars = vars
except NameError:
    pass
try:
    locals = locals
except NameError:
    pass
try:
    exit = exit
except NameError:
    pass
try:
    quit = quit
except NameError:
    pass
try:
    copyright = copyright
except NameError:
    pass
try:
    credits = credits
except NameError:
    pass
try:
    license = license
except NameError:
    pass
try:
    __import__ = __import__
except NameError:
    pass
try:
    __build_class__ = __build_class__
except NameError:
    pass
