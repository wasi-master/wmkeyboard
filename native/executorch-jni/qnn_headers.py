#!/usr/bin/env python3
"""Extracts include/QNN from Qualcomm's QAIRT SDK zip without downloading it.

The SDK is a 1.35 GB zip on Qualcomm's public software centre; ExecuTorch's
Qualcomm delegate compiles against its include/QNN headers alone (a few
hundred KB). This reads the zip's central directory and then only the header
entries, through HTTP range requests, and checks the extracted tree against
a pinned digest: SHA-256 over every file's relative path and SHA-256, sorted,
one "path  sha256" line each.

    qnn_headers.py URL DIGEST DEST

Writes DEST/QNN/... Standard library only.
"""
import hashlib
import io
import os
import sys
import time
import urllib.request
import zipfile

BLOCK = 1 << 20


class RangeFile(io.RawIOBase):
    """A seekable read-only file over HTTP range requests, in cached blocks."""

    def __init__(self, url):
        self.url = url
        self.pos = 0
        self.blocks = {}
        self.size = int(self._get(0, 0)[1].split("/")[-1])

    def _get(self, start, end):
        req = urllib.request.Request(self.url, headers={
            "Range": f"bytes={start}-{end}", "User-Agent": "wmkeyboard-build"})
        for attempt in range(5):
            try:
                with urllib.request.urlopen(req, timeout=120) as r:
                    if r.status != 206:
                        raise OSError(f"no range support: HTTP {r.status}")
                    return r.read(), r.headers["Content-Range"]
            except OSError as e:
                if attempt == 4:
                    raise
                print(f"range {start}-{end} failed ({e}), retrying", file=sys.stderr)
                time.sleep(10 * (attempt + 1))

    def _block(self, n):
        if n not in self.blocks:
            start = n * BLOCK
            self.blocks[n] = self._get(start, min(start + BLOCK, self.size) - 1)[0]
        return self.blocks[n]

    def readable(self):
        return True

    def seekable(self):
        return True

    def tell(self):
        return self.pos

    def seek(self, offset, whence=0):
        base = {0: 0, 1: self.pos, 2: self.size}[whence]
        self.pos = base + offset
        return self.pos

    def readinto(self, b):
        n = min(len(b), self.size - self.pos)
        out = bytearray()
        while len(out) < n:
            blk, off = divmod(self.pos + len(out), BLOCK)
            out += self._block(blk)[off:off + n - len(out)]
        b[:n] = out
        self.pos += n
        return n


def tree_digest(root):
    lines = []
    for dirpath, _, files in os.walk(root):
        for name in files:
            path = os.path.join(dirpath, name)
            with open(path, "rb") as f:
                digest = hashlib.sha256(f.read()).hexdigest()
            lines.append(f"{os.path.relpath(path, root)}  {digest}\n")
    return hashlib.sha256("".join(sorted(lines)).encode()).hexdigest()


def main():
    url, want, dest = sys.argv[1:4]
    zf = zipfile.ZipFile(io.BufferedReader(RangeFile(url), BLOCK))
    marker = "/include/QNN/"
    names = [n for n in zf.namelist() if marker in n and not n.endswith("/")]
    if not names:
        sys.exit("no include/QNN in the archive")
    out = os.path.join(dest, "QNN")
    for name in names:
        rel = name.split(marker, 1)[1]
        target = os.path.join(out, rel)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with zf.open(name) as src, open(target, "wb") as dst:
            dst.write(src.read())
    got = tree_digest(out)
    if got != want:
        sys.exit(f"QNN headers digest is {got}, expected {want}")
    print(f"{len(names)} QNN headers into {out}")


if __name__ == "__main__":
    main()
