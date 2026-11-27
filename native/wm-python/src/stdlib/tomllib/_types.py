# CPython 3.13's Lib/tomllib/_types.py, unchanged. Python Software Foundation License
# Version 2; the text ships as app/src/main/assets/licenses/psf-2.0.txt.

# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: 2021 Taneli Hukkinen
# Licensed to PSF under a Contributor Agreement.

from typing import Any, Callable, Tuple

# Type annotations
ParseFloat = Callable[[str], Any]
Key = Tuple[str, ...]
Pos = int
