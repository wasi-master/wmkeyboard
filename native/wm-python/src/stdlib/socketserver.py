"""socketserver, for a plugin, which cannot listen for connections.

The handler classes are here to subclass. Making a server raises
PermissionError.
"""

__all__ = [
    "BaseServer", "TCPServer", "UDPServer", "ThreadingUDPServer", "ThreadingTCPServer", "BaseRequestHandler",
    "StreamRequestHandler", "DatagramRequestHandler", "ThreadingMixIn", "ForkingMixIn", "UnixStreamServer",
    "UnixDatagramServer", "ThreadingUnixStreamServer", "ThreadingUnixDatagramServer", "ForkingTCPServer",
    "ForkingUDPServer",
]

_CANNOT = "plugins cannot listen for connections"


class BaseServer:
    timeout = None

    def __init__(self, server_address, RequestHandlerClass):
        raise PermissionError(_CANNOT)

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass


class TCPServer(BaseServer):
    address_family = 2
    socket_type = 1
    request_queue_size = 5
    allow_reuse_address = False
    allow_reuse_port = False

    def __init__(self, server_address, RequestHandlerClass, bind_and_activate=True):
        raise PermissionError(_CANNOT)


class UDPServer(TCPServer):
    socket_type = 2
    max_packet_size = 8192


class UnixStreamServer(TCPServer):
    address_family = 1


class UnixDatagramServer(UDPServer):
    address_family = 1


class ThreadingMixIn:
    daemon_threads = False
    block_on_close = True


class ForkingMixIn:
    timeout = 300
    active_children = None
    max_children = 40
    block_on_close = True


class ThreadingTCPServer(ThreadingMixIn, TCPServer):
    pass


class ThreadingUDPServer(ThreadingMixIn, UDPServer):
    pass


class ThreadingUnixStreamServer(ThreadingMixIn, UnixStreamServer):
    pass


class ThreadingUnixDatagramServer(ThreadingMixIn, UnixDatagramServer):
    pass


class ForkingTCPServer(ForkingMixIn, TCPServer):
    pass


class ForkingUDPServer(ForkingMixIn, UDPServer):
    pass


class BaseRequestHandler:
    def __init__(self, request, client_address, server):
        self.request = request
        self.client_address = client_address
        self.server = server
        self.setup()
        try:
            self.handle()
        finally:
            self.finish()

    def setup(self):
        pass

    def handle(self):
        pass

    def finish(self):
        pass


class StreamRequestHandler(BaseRequestHandler):
    rbufsize = -1
    wbufsize = 0
    timeout = None
    disable_nagle_algorithm = False


class DatagramRequestHandler(BaseRequestHandler):
    pass
