"""platform: what the keyboard's Python runs on.

The answers are fixed: Android on a 64-bit ARM phone, and a CPython-style
implementation of Python 3.12 (the language this interpreter follows). Who
the phone is (its name, its exact Android release) is not shared with
plugins, so those answers are empty, as CPython gives when it cannot tell.
"""

import sys

__all__ = [
    "architecture", "machine", "node", "platform", "processor", "python_branch", "python_build", "python_compiler",
    "python_implementation", "python_revision", "python_version", "python_version_tuple", "release", "system",
    "system_alias", "uname", "version", "libc_ver", "mac_ver", "win32_ver", "win32_edition", "win32_is_iot",
    "java_ver", "android_ver", "freedesktop_os_release",
]


def _version():
    v = sys.version_info
    return f"{v[0]}.{v[1]}.{v[2]}"


class uname_result:
    _fields = ("system", "node", "release", "version", "machine")

    def __init__(self, system, node, release, version, machine):
        self.system = system
        self.node = node
        self.release = release
        self.version = version
        self.machine = machine

    @property
    def processor(self):
        return ""

    def __iter__(self):
        return iter((self.system, self.node, self.release, self.version, self.machine, self.processor))

    def __getitem__(self, i):
        return tuple(self)[i]

    def __len__(self):
        return 6

    def __eq__(self, other):
        return tuple(self) == tuple(other)

    def __repr__(self):
        return (
            f"uname_result(system={self.system!r}, node={self.node!r}, release={self.release!r}, "
            f"version={self.version!r}, machine={self.machine!r})"
        )


def uname():
    return uname_result("Android", "", "", "", "aarch64")


def system():
    return "Android"


def node():
    return ""


def release():
    return ""


def version():
    return ""


def machine():
    return "aarch64"


def processor():
    return ""


def architecture(executable=None, bits="", linkage=""):
    return ("64bit", "")


def platform(aliased=False, terse=False):
    return "Android-aarch64" if not terse else "Android"


def system_alias(system, release, version):
    return system, release, version


def python_implementation():
    return "CPython"


def python_version():
    return _version()


def python_version_tuple():
    return tuple(_version().split("."))


def python_branch():
    return ""


def python_revision():
    return ""


def python_build():
    return ("", "")


def python_compiler():
    return ""


def libc_ver(executable=None, lib="", version="", chunksize=16384):
    return (lib, version)


def mac_ver(release="", versioninfo=("", "", ""), machine=""):
    return release, versioninfo, machine


def win32_ver(release="", version="", csd="", ptype=""):
    return release, version, csd, ptype


def win32_edition():
    return None


def win32_is_iot():
    return False


def java_ver(release="", vendor="", vminfo=("", "", ""), osinfo=("", "", "")):
    return release, vendor, vminfo, osinfo


class AndroidVer:
    def __init__(self, release="", api_level=0, manufacturer="", model="", device="", is_emulator=False):
        self.release = release
        self.api_level = api_level
        self.manufacturer = manufacturer
        self.model = model
        self.device = device
        self.is_emulator = is_emulator

    def __iter__(self):
        return iter((self.release, self.api_level, self.manufacturer, self.model, self.device, self.is_emulator))


def android_ver(release="", api_level=0, manufacturer="", model="", device="", is_emulator=False):
    return AndroidVer(release, api_level, manufacturer, model, device, is_emulator)


def freedesktop_os_release():
    raise OSError("plugins have no file system, so there is no os-release file to read")
