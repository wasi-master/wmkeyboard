"""webbrowser, for a plugin, which cannot open apps itself: open() and its
kin answer False, as CPython's do when no browser can be found. To open a
page for the user, a plugin uses ui.link."""

__all__ = ["Error", "open", "open_new", "open_new_tab", "get", "register"]


class Error(Exception):
    pass


def register(name, klass, instance=None, *, preferred=False):
    pass


def get(using=None):
    raise Error("could not locate runnable browser")


def open(url, new=0, autoraise=True):
    return False


def open_new(url):
    return False


def open_new_tab(url):
    return False


class BaseBrowser:
    args = ["%s"]

    def __init__(self, name=""):
        self.name = name
        self.basename = name

    def open(self, url, new=0, autoraise=True):
        return False

    def open_new(self, url):
        return False

    def open_new_tab(self, url):
        return False


GenericBrowser = BackgroundBrowser = BaseBrowser
