"""socket, for a plugin, which cannot open connections itself.

What packages read when they load is here: the address families, socket
types, protocol and option constants, the exceptions, the socket class to
subclass or test against, and the functions that only turn addresses into
other forms (inet_aton, inet_pton, htons, ...). Opening a socket, or asking
the network anything (getaddrinfo, gethostbyname, create_connection),
raises PermissionError. Web requests go through wm.http, or the requests
and httpx that come with the keyboard.
"""

import ipaddress as _ipaddress
from enum import IntEnum as _IntEnum, IntFlag as _IntFlag

_CANNOT = "plugins cannot open connections themselves; use wm.http (from wm import http), or requests or httpx"

error = OSError


class herror(OSError):
    pass


class gaierror(OSError):
    pass


timeout = TimeoutError


class AddressFamily(_IntEnum):
    AF_UNSPEC = 0
    AF_UNIX = 1
    AF_INET = 2
    AF_INET6 = 10
    AF_NETLINK = 16
    AF_PACKET = 17
    AF_BLUETOOTH = 31


class SocketKind(_IntEnum):
    SOCK_STREAM = 1
    SOCK_DGRAM = 2
    SOCK_RAW = 3
    SOCK_RDM = 4
    SOCK_SEQPACKET = 5
    SOCK_NONBLOCK = 2048
    SOCK_CLOEXEC = 524288


class MsgFlag(_IntFlag):
    MSG_OOB = 1
    MSG_PEEK = 2
    MSG_DONTROUTE = 4
    MSG_CTRUNC = 8
    MSG_TRUNC = 32
    MSG_DONTWAIT = 64
    MSG_EOR = 128
    MSG_WAITALL = 256
    MSG_NOSIGNAL = 16384
    MSG_CMSG_CLOEXEC = 1073741824


class AddressInfo(_IntFlag):
    AI_PASSIVE = 1
    AI_CANONNAME = 2
    AI_NUMERICHOST = 4
    AI_V4MAPPED = 8
    AI_ALL = 16
    AI_ADDRCONFIG = 32
    AI_MASK = 1063
    AI_NUMERICSERV = 1024


for _enum in (AddressFamily, SocketKind, MsgFlag, AddressInfo):
    for _name, _member in _enum.__members__.items():
        globals()[_name] = _member
del _enum, _name, _member

has_ipv6 = True
SOMAXCONN = 4096
_GLOBAL_DEFAULT_TIMEOUT = object()

SOL_SOCKET = 1
SOL_IP = 0
SOL_TCP = 6
SOL_UDP = 17
SO_DEBUG = 1
SO_REUSEADDR = 2
SO_TYPE = 3
SO_ERROR = 4
SO_DONTROUTE = 5
SO_BROADCAST = 6
SO_SNDBUF = 7
SO_RCVBUF = 8
SO_KEEPALIVE = 9
SO_OOBINLINE = 10
SO_LINGER = 13
SO_REUSEPORT = 15
SO_RCVLOWAT = 18
SO_SNDLOWAT = 19
SO_RCVTIMEO = 20
SO_SNDTIMEO = 21
SO_ACCEPTCONN = 30
IPPROTO_IP = 0
IPPROTO_ICMP = 1
IPPROTO_TCP = 6
IPPROTO_UDP = 17
IPPROTO_IPV6 = 41
IPPROTO_ICMPV6 = 58
IPPROTO_RAW = 255
IPV6_V6ONLY = 26
IP_TOS = 1
IP_TTL = 2
IP_MULTICAST_TTL = 33
IP_MULTICAST_LOOP = 34
IP_ADD_MEMBERSHIP = 35
TCP_NODELAY = 1
TCP_MAXSEG = 2
TCP_KEEPIDLE = 4
TCP_KEEPINTVL = 5
TCP_KEEPCNT = 6
TCP_FASTOPEN = 23
TCP_USER_TIMEOUT = 18
SHUT_RD = 0
SHUT_WR = 1
SHUT_RDWR = 2
INADDR_ANY = 0
INADDR_BROADCAST = 0xFFFFFFFF
INADDR_LOOPBACK = 0x7F000001
INADDR_NONE = 0xFFFFFFFF
NI_NUMERICHOST = 1
NI_NUMERICSERV = 2
NI_NOFQDN = 4
NI_NAMEREQD = 8
NI_DGRAM = 16
NI_MAXHOST = 1025
NI_MAXSERV = 32
EAI_AGAIN = -3
EAI_BADFLAGS = -1
EAI_FAIL = -4
EAI_FAMILY = -6
EAI_MEMORY = -10
EAI_NODATA = -5
EAI_NONAME = -2
EAI_SERVICE = -8
EAI_SOCKTYPE = -7

_default_timeout = None


def getdefaulttimeout():
    return _default_timeout


def setdefaulttimeout(timeout):
    global _default_timeout
    _default_timeout = timeout


class socket:
    """A socket cannot be made in a plugin: constructing one raises."""

    def __init__(self, family=-1, type=-1, proto=-1, fileno=None):
        raise PermissionError(_CANNOT)

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()

    def close(self):
        pass


SocketType = socket


def create_connection(address, timeout=_GLOBAL_DEFAULT_TIMEOUT, source_address=None, *, all_errors=False):
    raise PermissionError(_CANNOT)


def create_server(address, *, family=AF_INET, backlog=None, reuse_port=False, dualstack_ipv6=False):
    raise PermissionError(_CANNOT)


def socketpair(family=None, type=SOCK_STREAM, proto=0):
    raise PermissionError(_CANNOT)


def fromfd(fd, family, type, proto=0):
    raise PermissionError(_CANNOT)


def getaddrinfo(host, port, family=0, type=0, proto=0, flags=0):
    raise PermissionError(_CANNOT)


def gethostbyname(hostname):
    raise PermissionError(_CANNOT)


def gethostbyname_ex(hostname):
    raise PermissionError(_CANNOT)


def gethostbyaddr(ip_address):
    raise PermissionError(_CANNOT)


def getnameinfo(sockaddr, flags):
    raise PermissionError(_CANNOT)


def gethostname():
    """The phone's name is not shared with plugins."""
    return "localhost"


def getfqdn(name=""):
    return name or "localhost"


def has_dualstack_ipv6():
    return False


_SERVICES = {"http": 80, "https": 443, "ftp": 21, "ssh": 22, "telnet": 23, "smtp": 25, "domain": 53, "pop3": 110, "imap": 143, "imaps": 993, "pop3s": 995, "submission": 587, "ntp": 123}


def getservbyname(servicename, protocolname=None):
    try:
        return _SERVICES[servicename]
    except KeyError:
        raise OSError("service/proto not found") from None


def getservbyport(port, protocolname=None):
    for name, number in _SERVICES.items():
        if number == port:
            return name
    raise OSError("port/proto not found")


def getprotobyname(protocolname):
    try:
        return {"ip": 0, "icmp": 1, "tcp": 6, "udp": 17, "ipv6": 41, "icmpv6": 58}[protocolname]
    except KeyError:
        raise OSError("protocol not found") from None


def htons(x):
    return ((x & 0xFF) << 8) | ((x >> 8) & 0xFF)


ntohs = htons


def htonl(x):
    return int.from_bytes((x & 0xFFFFFFFF).to_bytes(4, "little"), "big")


ntohl = htonl


def inet_aton(ip_string):
    parts = ip_string.split(".")
    if not 1 <= len(parts) <= 4:
        raise OSError("illegal IP address string passed to inet_aton")
    try:
        nums = [int(p, 0) for p in parts]
    except ValueError:
        raise OSError("illegal IP address string passed to inet_aton") from None
    last = 4 - len(nums) + 1
    if any(n < 0 or n > 255 for n in nums[:-1]) or nums[-1] < 0 or nums[-1] >= 256**last:
        raise OSError("illegal IP address string passed to inet_aton")
    value = 0
    for n in nums[:-1]:
        value = value * 256 + n
    value = value * (256**last) + nums[-1]
    return value.to_bytes(4, "big")


def inet_ntoa(packed_ip):
    if len(packed_ip) != 4:
        raise OSError("packed IP wrong length for inet_ntoa")
    return ".".join(str(b) for b in bytes(packed_ip))


def inet_pton(address_family, ip_string):
    try:
        if address_family == AF_INET:
            return _ipaddress.IPv4Address(ip_string).packed
        if address_family == AF_INET6:
            return _ipaddress.IPv6Address(ip_string).packed
    except ValueError:
        raise OSError("illegal IP address string passed to inet_pton") from None
    raise OSError("unknown address family")


def inet_ntop(address_family, packed_ip):
    try:
        if address_family == AF_INET:
            return str(_ipaddress.IPv4Address(bytes(packed_ip)))
        if address_family == AF_INET6:
            return str(_ipaddress.IPv6Address(bytes(packed_ip)))
    except ValueError:
        raise ValueError("invalid length of packed IP address string") from None
    raise ValueError("unknown address family")


def if_nameindex():
    return []


def close(fd):
    raise OSError("Bad file descriptor")
