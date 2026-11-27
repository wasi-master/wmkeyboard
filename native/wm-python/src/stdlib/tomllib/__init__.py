# CPython 3.13's Lib/tomllib/__init__.py, unchanged. Python Software Foundation License
# Version 2; the text ships as app/src/main/assets/licenses/psf-2.0.txt.

# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: 2021 Taneli Hukkinen
# Licensed to PSF under a Contributor Agreement.

__all__ = ("loads", "load", "TOMLDecodeError")

from ._parser import TOMLDecodeError, load, loads

# Pretend this exception was created here.
TOMLDecodeError.__module__ = __name__
