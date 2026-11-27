"""ssl, for a plugin, which cannot open connections itself.

Its constants, enums and exceptions are here, and an SSLContext that keeps
the settings code gives it, so packages that prepare TLS when they load
still load. Wrapping a socket raises PermissionError: wm.http, and the
requests and httpx that come with the keyboard, speak TLS for plugins.
"""

import base64 as _base64
import os as _os
from collections import namedtuple as _namedtuple
from enum import IntEnum as _IntEnum, IntFlag as _IntFlag

_CANNOT = "plugins cannot open connections themselves; use wm.http (from wm import http), or requests or httpx"

OPENSSL_VERSION = "OpenSSL 3.0.0 (not used by plugins)"
OPENSSL_VERSION_INFO = (3, 0, 0, 0, 0)
OPENSSL_VERSION_NUMBER = 0x30000000
_OPENSSL_API_VERSION = (3, 0, 0, 0, 0)

HAS_SNI = True
HAS_ECDH = True
HAS_NPN = False
HAS_ALPN = True
HAS_NEVER_CHECK_COMMON_NAME = True
HAS_SSLv2 = False
HAS_SSLv3 = False
HAS_TLSv1 = True
HAS_TLSv1_1 = True
HAS_TLSv1_2 = True
HAS_TLSv1_3 = True
HAS_PSK = False
HAS_PHA = True
CHANNEL_BINDING_TYPES = ["tls-unique"]


class _SSLMethod(_IntEnum):
    PROTOCOL_SSLv23 = 2
    PROTOCOL_TLS_CLIENT = 16
    PROTOCOL_TLS_SERVER = 17
    PROTOCOL_TLSv1 = 3
    PROTOCOL_TLSv1_1 = 4
    PROTOCOL_TLSv1_2 = 5


PROTOCOL_SSLv23 = PROTOCOL_TLS = _SSLMethod.PROTOCOL_SSLv23
PROTOCOL_TLS_CLIENT = _SSLMethod.PROTOCOL_TLS_CLIENT
PROTOCOL_TLS_SERVER = _SSLMethod.PROTOCOL_TLS_SERVER
PROTOCOL_TLSv1 = _SSLMethod.PROTOCOL_TLSv1
PROTOCOL_TLSv1_1 = _SSLMethod.PROTOCOL_TLSv1_1
PROTOCOL_TLSv1_2 = _SSLMethod.PROTOCOL_TLSv1_2


class Options(_IntFlag):
    OP_ALL = 0x80000050
    OP_NO_SSLv2 = 0
    OP_NO_SSLv3 = 0x02000000
    OP_NO_TLSv1 = 0x04000000
    OP_NO_TLSv1_1 = 0x10000000
    OP_NO_TLSv1_2 = 0x08000000
    OP_NO_TLSv1_3 = 0x20000000
    OP_CIPHER_SERVER_PREFERENCE = 0x00400000
    OP_SINGLE_DH_USE = 0
    OP_SINGLE_ECDH_USE = 0
    OP_NO_COMPRESSION = 0x00020000
    OP_NO_TICKET = 0x00004000
    OP_NO_RENEGOTIATION = 0x40000000
    OP_ENABLE_MIDDLEBOX_COMPAT = 0x00100000
    OP_IGNORE_UNEXPECTED_EOF = 0x00000080
    OP_LEGACY_SERVER_CONNECT = 0x00000004
    OP_ENABLE_KTLS = 0x00000008


class VerifyMode(_IntEnum):
    CERT_NONE = 0
    CERT_OPTIONAL = 1
    CERT_REQUIRED = 2


class VerifyFlags(_IntFlag):
    VERIFY_DEFAULT = 0
    VERIFY_CRL_CHECK_LEAF = 4
    VERIFY_CRL_CHECK_CHAIN = 12
    VERIFY_X509_STRICT = 32
    VERIFY_ALLOW_PROXY_CERTS = 64
    VERIFY_X509_TRUSTED_FIRST = 32768
    VERIFY_X509_PARTIAL_CHAIN = 0x80000


class AlertDescription(_IntEnum):
    ALERT_DESCRIPTION_CLOSE_NOTIFY = 0
    ALERT_DESCRIPTION_HANDSHAKE_FAILURE = 40
    ALERT_DESCRIPTION_BAD_CERTIFICATE = 42
    ALERT_DESCRIPTION_CERTIFICATE_EXPIRED = 45
    ALERT_DESCRIPTION_UNKNOWN_CA = 48
    ALERT_DESCRIPTION_INTERNAL_ERROR = 80


class SSLErrorNumber(_IntEnum):
    SSL_ERROR_SSL = 1
    SSL_ERROR_WANT_READ = 2
    SSL_ERROR_WANT_WRITE = 3
    SSL_ERROR_WANT_X509_LOOKUP = 4
    SSL_ERROR_SYSCALL = 5
    SSL_ERROR_ZERO_RETURN = 6
    SSL_ERROR_WANT_CONNECT = 7
    SSL_ERROR_EOF = 8
    SSL_ERROR_INVALID_ERROR_CODE = 10


for _enum in (Options, VerifyMode, VerifyFlags, AlertDescription, SSLErrorNumber):
    for _name, _member in _enum.__members__.items():
        globals()[_name] = _member
del _enum, _name, _member


class TLSVersion(_IntEnum):
    MINIMUM_SUPPORTED = -2
    MAXIMUM_SUPPORTED = -1
    SSLv3 = 768
    TLSv1 = 769
    TLSv1_1 = 770
    TLSv1_2 = 771
    TLSv1_3 = 772


class _ASN1Object(_namedtuple("_ASN1Object", "nid shortname longname oid")):
    pass


class Purpose(_ASN1Object):
    pass


Purpose.SERVER_AUTH = Purpose(129, "serverAuth", "TLS Web Server Authentication", "1.3.6.1.5.5.7.3.1")
Purpose.CLIENT_AUTH = Purpose(130, "clientAuth", "TLS Web Client Authentication", "1.3.6.1.5.5.7.3.2")


class SSLError(OSError):
    library = None
    reason = None

    def __str__(self):
        return self.args[1] if len(self.args) > 1 and isinstance(self.args[1], str) else super().__str__()


class SSLZeroReturnError(SSLError):
    pass


class SSLWantReadError(SSLError):
    pass


class SSLWantWriteError(SSLError):
    pass


class SSLSyscallError(SSLError):
    pass


class SSLEOFError(SSLError):
    pass


class SSLCertVerificationError(SSLError, ValueError):
    verify_code = 0
    verify_message = ""


CertificateError = SSLCertVerificationError

DefaultVerifyPaths = _namedtuple("DefaultVerifyPaths", "cafile capath openssl_cafile_env openssl_cafile openssl_capath_env openssl_capath")


def get_default_verify_paths():
    return DefaultVerifyPaths(None, None, "SSL_CERT_FILE", "", "SSL_CERT_DIR", "")


class SSLContext:
    """Keeps the settings it is given; wrapping a socket raises."""

    sslsocket_class = None
    sslobject_class = None

    def __init__(self, protocol=None, *args, **kwargs):
        self.protocol = PROTOCOL_TLS if protocol is None else protocol
        client = self.protocol == PROTOCOL_TLS_CLIENT
        self.verify_mode = CERT_REQUIRED if client else CERT_NONE
        self.check_hostname = client
        self.options = OP_ALL | OP_NO_SSLv3
        self.verify_flags = VERIFY_X509_TRUSTED_FIRST
        self.minimum_version = TLSVersion.MINIMUM_SUPPORTED
        self.maximum_version = TLSVersion.MAXIMUM_SUPPORTED
        self.post_handshake_auth = False
        self.keylog_filename = None
        self.hostname_checks_common_name = False
        self.security_level = 2
        self.sni_callback = None
        self.num_tickets = 2
        self._ciphers = "DEFAULT"
        self._alpn = []

    def load_default_certs(self, purpose=Purpose.SERVER_AUTH):
        pass

    def load_verify_locations(self, cafile=None, capath=None, cadata=None):
        if cafile is None and capath is None and cadata is None:
            raise TypeError("cafile, capath and cadata cannot be all omitted")

    def load_cert_chain(self, certfile, keyfile=None, password=None):
        raise PermissionError("plugins have no file system to read a certificate from")

    def set_default_verify_paths(self):
        pass

    def set_ciphers(self, ciphers):
        self._ciphers = ciphers

    def get_ciphers(self):
        return []

    def set_alpn_protocols(self, alpn_protocols):
        self._alpn = list(alpn_protocols)

    def set_npn_protocols(self, npn_protocols):
        pass

    def set_servername_callback(self, server_name_callback):
        self.sni_callback = server_name_callback

    def set_ecdh_curve(self, curve_name):
        pass

    def load_dh_params(self, path):
        raise PermissionError("plugins have no file system")

    def cert_store_stats(self):
        return {"x509": 0, "crl": 0, "x509_ca": 0}

    def get_ca_certs(self, binary_form=False):
        return []

    def session_stats(self):
        return {}

    def wrap_socket(self, sock, server_side=False, do_handshake_on_connect=True, suppress_ragged_eofs=True, server_hostname=None, session=None):
        raise PermissionError(_CANNOT)

    def wrap_bio(self, incoming, outgoing, server_side=False, server_hostname=None, session=None):
        raise PermissionError(_CANNOT)


class SSLSocket:
    def __init__(self, *args, **kwargs):
        raise PermissionError(_CANNOT)


class SSLObject:
    def __init__(self, *args, **kwargs):
        raise PermissionError(_CANNOT)


class MemoryBIO:
    def __init__(self):
        self._data = b""
        self.eof = False

    @property
    def pending(self):
        return len(self._data)

    def read(self, n=-1):
        data = self._data if n < 0 else self._data[:n]
        self._data = self._data[len(data):]
        return data

    def write(self, data):
        self._data += bytes(data)
        return len(data)

    def write_eof(self):
        self.eof = True


class SSLSession:
    pass


def create_default_context(purpose=Purpose.SERVER_AUTH, *, cafile=None, capath=None, cadata=None):
    context = SSLContext(PROTOCOL_TLS_CLIENT if purpose == Purpose.SERVER_AUTH else PROTOCOL_TLS_SERVER)
    if purpose == Purpose.CLIENT_AUTH:
        context.verify_mode = CERT_NONE
        context.check_hostname = False
    return context


def _create_unverified_context(protocol=None, *, cert_reqs=CERT_NONE, check_hostname=False, purpose=Purpose.SERVER_AUTH, certfile=None, keyfile=None, cafile=None, capath=None, cadata=None):
    context = SSLContext(PROTOCOL_TLS_CLIENT if protocol is None else protocol)
    context.check_hostname = check_hostname
    context.verify_mode = cert_reqs
    return context


_create_default_https_context = create_default_context
_create_stdlib_context = _create_unverified_context


def wrap_socket(*args, **kwargs):
    raise PermissionError(_CANNOT)


def get_server_certificate(addr, ssl_version=PROTOCOL_TLS_CLIENT, ca_certs=None, timeout=None):
    raise PermissionError(_CANNOT)


PEM_HEADER = "-----BEGIN CERTIFICATE-----"
PEM_FOOTER = "-----END CERTIFICATE-----"


def DER_cert_to_PEM_cert(der_cert_bytes):
    f = _base64.standard_b64encode(der_cert_bytes).decode("ascii")
    ss = [PEM_HEADER]
    ss += [f[i : i + 64] for i in range(0, len(f), 64)]
    ss.append(PEM_FOOTER + "\n")
    return "\n".join(ss)


def PEM_cert_to_DER_cert(pem_cert_string):
    if not pem_cert_string.startswith(PEM_HEADER):
        raise ValueError(f"Invalid PEM encoding; must start with {PEM_HEADER}")
    if not pem_cert_string.strip().endswith(PEM_FOOTER):
        raise ValueError(f"Invalid PEM encoding; must end with {PEM_FOOTER}")
    d = pem_cert_string.strip()[len(PEM_HEADER) : -len(PEM_FOOTER)]
    return _base64.decodebytes(d.encode("ASCII", "strict"))


def cert_time_to_seconds(cert_time):
    import calendar
    import time

    months = ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    time_format = " %d %H:%M:%S %Y GMT"
    try:
        month_number = months.index(cert_time[:3].title()) + 1
    except ValueError:
        raise ValueError(f'time data {cert_time!r} does not match format "%b{time_format}"') from None
    tt = time.strptime(cert_time[3:], time_format)
    return calendar.timegm((tt[0], month_number) + tt[2:6])


def RAND_bytes(n):
    return _os.urandom(n)


def RAND_status():
    return True


def RAND_add(string, entropy):
    pass
