"""requests.get, post and the rest: each one request through a Session
of its own, as in requests."""

from requests.sessions import Session


def request(method, url, **kwargs):
    """Sends one request and returns its Response.

    params= a dict (or list of pairs) for the query string; data= a dict
    sent as a form, or text or bytes; json= any value sent as JSON;
    headers= a dict; files= {"field": (name, bytes, type)} for an upload;
    auth=("user", "password") for HTTP Basic; timeout= seconds, 15 at
    most. then=fn returns at once and calls fn(response) later."""
    with Session() as session:
        return session.request(method=method, url=url, **kwargs)


def get(url, params=None, **kwargs):
    """A GET request. params= adds a query string: get(url, params={"q": "milk"})."""
    return request("get", url, params=params, **kwargs)


def options(url, **kwargs):
    """An OPTIONS request, which the keyboard does not send: raises ValueError."""
    return request("options", url, **kwargs)


def head(url, **kwargs):
    """A HEAD request: the status and headers, no body."""
    return request("head", url, **kwargs)


def post(url, data=None, json=None, **kwargs):
    """A POST request: data= sends a form, text or bytes, json= sends JSON, files= a multipart upload."""
    return request("post", url, data=data, json=json, **kwargs)


def put(url, data=None, **kwargs):
    """A PUT request, with data= or json= as for post."""
    return request("put", url, data=data, **kwargs)


def patch(url, data=None, **kwargs):
    """A PATCH request, with data= or json= as for post."""
    return request("patch", url, data=data, **kwargs)


def delete(url, **kwargs):
    """A DELETE request."""
    return request("delete", url, **kwargs)
