"""Transport adapters. Every request a plugin makes goes through the
keyboard, so an adapter mounted on a Session changes nothing; HTTPAdapter
is here so code that mounts one (for max_retries) still runs."""


class BaseAdapter:
    """The base transport adapter."""

    def close(self):
        pass


class HTTPAdapter(BaseAdapter):
    """Accepts requests' arguments. The keyboard does not retry: catch
    requests.exceptions.ConnectionError and try again yourself."""

    def __init__(self, pool_connections=10, pool_maxsize=10, max_retries=0, pool_block=False):
        self.max_retries = max_retries
        self.config = {}
