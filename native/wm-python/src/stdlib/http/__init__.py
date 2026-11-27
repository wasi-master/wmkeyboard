# HTTPStatus and HTTPMethod with CPython 3.13's members, phrases and
# descriptions (Lib/http/__init__.py; Python Software Foundation License
# Version 2, the text ships as app/src/main/assets/licenses/psf-2.0.txt).
# The phrases are attached after the classes are made rather than through
# an enum __new__.
"""http: HTTP status codes and methods."""

from enum import IntEnum, StrEnum

__all__ = ["HTTPStatus", "HTTPMethod"]


class HTTPStatus(IntEnum):
    """HTTP status codes and reason phrases."""

    CONTINUE = 100
    SWITCHING_PROTOCOLS = 101
    PROCESSING = 102
    EARLY_HINTS = 103
    OK = 200
    CREATED = 201
    ACCEPTED = 202
    NON_AUTHORITATIVE_INFORMATION = 203
    NO_CONTENT = 204
    RESET_CONTENT = 205
    PARTIAL_CONTENT = 206
    MULTI_STATUS = 207
    ALREADY_REPORTED = 208
    IM_USED = 226
    MULTIPLE_CHOICES = 300
    MOVED_PERMANENTLY = 301
    FOUND = 302
    SEE_OTHER = 303
    NOT_MODIFIED = 304
    USE_PROXY = 305
    TEMPORARY_REDIRECT = 307
    PERMANENT_REDIRECT = 308
    BAD_REQUEST = 400
    UNAUTHORIZED = 401
    PAYMENT_REQUIRED = 402
    FORBIDDEN = 403
    NOT_FOUND = 404
    METHOD_NOT_ALLOWED = 405
    NOT_ACCEPTABLE = 406
    PROXY_AUTHENTICATION_REQUIRED = 407
    REQUEST_TIMEOUT = 408
    CONFLICT = 409
    GONE = 410
    LENGTH_REQUIRED = 411
    PRECONDITION_FAILED = 412
    CONTENT_TOO_LARGE = 413
    URI_TOO_LONG = 414
    UNSUPPORTED_MEDIA_TYPE = 415
    RANGE_NOT_SATISFIABLE = 416
    EXPECTATION_FAILED = 417
    IM_A_TEAPOT = 418
    MISDIRECTED_REQUEST = 421
    UNPROCESSABLE_CONTENT = 422
    LOCKED = 423
    FAILED_DEPENDENCY = 424
    TOO_EARLY = 425
    UPGRADE_REQUIRED = 426
    PRECONDITION_REQUIRED = 428
    TOO_MANY_REQUESTS = 429
    REQUEST_HEADER_FIELDS_TOO_LARGE = 431
    UNAVAILABLE_FOR_LEGAL_REASONS = 451
    INTERNAL_SERVER_ERROR = 500
    NOT_IMPLEMENTED = 501
    BAD_GATEWAY = 502
    SERVICE_UNAVAILABLE = 503
    GATEWAY_TIMEOUT = 504
    HTTP_VERSION_NOT_SUPPORTED = 505
    VARIANT_ALSO_NEGOTIATES = 506
    INSUFFICIENT_STORAGE = 507
    LOOP_DETECTED = 508
    NOT_EXTENDED = 510
    NETWORK_AUTHENTICATION_REQUIRED = 511

    @property
    def is_informational(self):
        return 100 <= self <= 199

    @property
    def is_success(self):
        return 200 <= self <= 299

    @property
    def is_redirection(self):
        return 300 <= self <= 399

    @property
    def is_client_error(self):
        return 400 <= self <= 499

    @property
    def is_server_error(self):
        return 500 <= self <= 599


_STATUS_TEXT = {
    100: ('Continue', 'Request received, please continue'),
    101: ('Switching Protocols', 'Switching to new protocol; obey Upgrade header'),
    102: ('Processing', ''),
    103: ('Early Hints', ''),
    200: ('OK', 'Request fulfilled, document follows'),
    201: ('Created', 'Document created, URL follows'),
    202: ('Accepted', 'Request accepted, processing continues off-line'),
    203: ('Non-Authoritative Information', 'Request fulfilled from cache'),
    204: ('No Content', 'Request fulfilled, nothing follows'),
    205: ('Reset Content', 'Clear input form for further input'),
    206: ('Partial Content', 'Partial content follows'),
    207: ('Multi-Status', ''),
    208: ('Already Reported', ''),
    226: ('IM Used', ''),
    300: ('Multiple Choices', 'Object has several resources -- see URI list'),
    301: ('Moved Permanently', 'Object moved permanently -- see URI list'),
    302: ('Found', 'Object moved temporarily -- see URI list'),
    303: ('See Other', 'Object moved -- see Method and URL list'),
    304: ('Not Modified', 'Document has not changed since given time'),
    305: ('Use Proxy', 'You must use proxy specified in Location to access this resource'),
    307: ('Temporary Redirect', 'Object moved temporarily -- see URI list'),
    308: ('Permanent Redirect', 'Object moved permanently -- see URI list'),
    400: ('Bad Request', 'Bad request syntax or unsupported method'),
    401: ('Unauthorized', 'No permission -- see authorization schemes'),
    402: ('Payment Required', 'No payment -- see charging schemes'),
    403: ('Forbidden', 'Request forbidden -- authorization will not help'),
    404: ('Not Found', 'Nothing matches the given URI'),
    405: ('Method Not Allowed', 'Specified method is invalid for this resource'),
    406: ('Not Acceptable', 'URI not available in preferred format'),
    407: ('Proxy Authentication Required', 'You must authenticate with this proxy before proceeding'),
    408: ('Request Timeout', 'Request timed out; try again later'),
    409: ('Conflict', 'Request conflict'),
    410: ('Gone', 'URI no longer exists and has been permanently removed'),
    411: ('Length Required', 'Client must specify Content-Length'),
    412: ('Precondition Failed', 'Precondition in headers is false'),
    413: ('Content Too Large', 'Content is too large'),
    414: ('URI Too Long', 'URI is too long'),
    415: ('Unsupported Media Type', 'Entity body in unsupported format'),
    416: ('Range Not Satisfiable', 'Cannot satisfy request range'),
    417: ('Expectation Failed', 'Expect condition could not be satisfied'),
    418: ("I'm a Teapot", 'Server refuses to brew coffee because it is a teapot.'),
    421: ('Misdirected Request', 'Server is not able to produce a response'),
    422: ('Unprocessable Content', ''),
    423: ('Locked', ''),
    424: ('Failed Dependency', ''),
    425: ('Too Early', ''),
    426: ('Upgrade Required', ''),
    428: ('Precondition Required', 'The origin server requires the request to be conditional'),
    429: ('Too Many Requests', 'The user has sent too many requests in a given amount of time ("rate limiting")'),
    431: ('Request Header Fields Too Large', 'The server is unwilling to process the request because its header fields are too large'),
    451: ('Unavailable For Legal Reasons', 'The server is denying access to the resource as a consequence of a legal demand'),
    500: ('Internal Server Error', 'Server got itself in trouble'),
    501: ('Not Implemented', 'Server does not support this operation'),
    502: ('Bad Gateway', 'Invalid responses from another server/proxy'),
    503: ('Service Unavailable', 'The server cannot process the request due to a high load'),
    504: ('Gateway Timeout', 'The gateway server did not receive a timely response'),
    505: ('HTTP Version Not Supported', 'Cannot fulfill request'),
    506: ('Variant Also Negotiates', ''),
    507: ('Insufficient Storage', ''),
    508: ('Loop Detected', ''),
    510: ('Not Extended', ''),
    511: ('Network Authentication Required', 'The client needs to authenticate to gain network access'),
}
for _member in HTTPStatus:
    _phrase, _description = _STATUS_TEXT[int(_member)]
    object.__setattr__(_member, "phrase", _phrase)
    object.__setattr__(_member, "description", _description)


class HTTPMethod(StrEnum):
    """HTTP methods and descriptions."""

    CONNECT = 'CONNECT'
    DELETE = 'DELETE'
    GET = 'GET'
    HEAD = 'HEAD'
    OPTIONS = 'OPTIONS'
    PATCH = 'PATCH'
    POST = 'POST'
    PUT = 'PUT'
    TRACE = 'TRACE'

    def __repr__(self):
        return f"<{type(self).__name__}.{self._name_}>"


_METHOD_TEXT = {
    'CONNECT': 'Establish a connection to the server.',
    'DELETE': 'Remove the target.',
    'GET': 'Retrieve the target.',
    'HEAD': 'Same as GET, but only retrieve the status line and header section.',
    'OPTIONS': 'Describe the communication options for the target.',
    'PATCH': 'Apply partial modifications to a target.',
    'POST': 'Perform target-specific processing with the request payload.',
    'PUT': 'Replace the target with the request payload.',
    'TRACE': 'Perform a message loop-back test along the path to the target.',
}
for _member in HTTPMethod:
    object.__setattr__(_member, "description", _METHOD_TEXT[str(_member)])
del _member, _phrase, _description
