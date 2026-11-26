"""requests, built into WM Keyboard's plugins: the requests API, sent
through the keyboard.

    import requests

    r = requests.get("https://api.github.com/repos/psf/requests", timeout=10)
    r.raise_for_status()
    stars = r.json()["stargazers_count"]

Needs the `network` permission, and each address's host in plugin.json's
`hosts`. https only, replies up to 1 MB, 15 seconds at most; no cookies
are sent. Pass then=fn to get the Response later instead of waiting for
it, so the panel stays responsive (on_error=fn hears a failure).
"""

__title__ = "requests"
__version__ = "2.32.3"

from requests import utils
from requests import exceptions
from requests import structures
from requests import status_codes
from requests import cookies
from requests import auth
from requests import adapters
from requests import models
from requests import sessions
from requests import api
from requests.status_codes import codes
from requests.models import PreparedRequest, Request, Response
from requests.sessions import Session, session
from requests.api import delete, get, head, options, patch, post, put, request
from requests.exceptions import (
    ConnectionError, ConnectTimeout, FileModeWarning, HTTPError, JSONDecodeError, ReadTimeout,
    RequestException, Timeout, TooManyRedirects, URLRequired,
)
