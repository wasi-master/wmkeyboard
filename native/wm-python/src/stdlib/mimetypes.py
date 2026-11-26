"""mimetypes: a file name's MIME type from its extension, and back.

The table is CPython's built-in one; nothing reads the device's own
mime.types files, so init() and read_mime_types() add nothing.
"""

import os.path
import urllib.parse

suffix_map = {
    '.svgz': '.svg.gz',
    '.tgz': '.tar.gz',
    '.taz': '.tar.gz',
    '.tz': '.tar.gz',
    '.tbz2': '.tar.bz2',
    '.txz': '.tar.xz',
}

encodings_map = {
    '.gz': 'gzip',
    '.Z': 'compress',
    '.bz2': 'bzip2',
    '.xz': 'xz',
    '.br': 'br',
}

_types_map_default = {
    '.js': 'text/javascript',
    '.mjs': 'text/javascript',
    '.json': 'application/json',
    '.webmanifest': 'application/manifest+json',
    '.doc': 'application/msword',
    '.dot': 'application/msword',
    '.wiz': 'application/msword',
    '.nq': 'application/n-quads',
    '.nt': 'application/n-triples',
    '.bin': 'application/octet-stream',
    '.a': 'application/octet-stream',
    '.dll': 'application/octet-stream',
    '.exe': 'application/octet-stream',
    '.o': 'application/octet-stream',
    '.obj': 'application/octet-stream',
    '.so': 'application/octet-stream',
    '.oda': 'application/oda',
    '.pdf': 'application/pdf',
    '.p7c': 'application/pkcs7-mime',
    '.ps': 'application/postscript',
    '.ai': 'application/postscript',
    '.eps': 'application/postscript',
    '.trig': 'application/trig',
    '.m3u': 'application/vnd.apple.mpegurl',
    '.m3u8': 'application/vnd.apple.mpegurl',
    '.xls': 'application/vnd.ms-excel',
    '.xlb': 'application/vnd.ms-excel',
    '.ppt': 'application/vnd.ms-powerpoint',
    '.pot': 'application/vnd.ms-powerpoint',
    '.ppa': 'application/vnd.ms-powerpoint',
    '.pps': 'application/vnd.ms-powerpoint',
    '.pwz': 'application/vnd.ms-powerpoint',
    '.wasm': 'application/wasm',
    '.bcpio': 'application/x-bcpio',
    '.cpio': 'application/x-cpio',
    '.csh': 'application/x-csh',
    '.dvi': 'application/x-dvi',
    '.gtar': 'application/x-gtar',
    '.hdf': 'application/x-hdf',
    '.h5': 'application/x-hdf5',
    '.latex': 'application/x-latex',
    '.mif': 'application/x-mif',
    '.cdf': 'application/x-netcdf',
    '.nc': 'application/x-netcdf',
    '.p12': 'application/x-pkcs12',
    '.pfx': 'application/x-pkcs12',
    '.ram': 'application/x-pn-realaudio',
    '.pyc': 'application/x-python-code',
    '.pyo': 'application/x-python-code',
    '.sh': 'application/x-sh',
    '.shar': 'application/x-shar',
    '.swf': 'application/x-shockwave-flash',
    '.sv4cpio': 'application/x-sv4cpio',
    '.sv4crc': 'application/x-sv4crc',
    '.tar': 'application/x-tar',
    '.tcl': 'application/x-tcl',
    '.tex': 'application/x-tex',
    '.texi': 'application/x-texinfo',
    '.texinfo': 'application/x-texinfo',
    '.roff': 'application/x-troff',
    '.t': 'application/x-troff',
    '.tr': 'application/x-troff',
    '.man': 'application/x-troff-man',
    '.me': 'application/x-troff-me',
    '.ms': 'application/x-troff-ms',
    '.ustar': 'application/x-ustar',
    '.src': 'application/x-wais-source',
    '.xsl': 'application/xml',
    '.rdf': 'application/xml',
    '.wsdl': 'application/xml',
    '.xpdl': 'application/xml',
    '.zip': 'application/zip',
    '.3gp': 'audio/3gpp',
    '.3gpp': 'audio/3gpp',
    '.3g2': 'audio/3gpp2',
    '.3gpp2': 'audio/3gpp2',
    '.aac': 'audio/aac',
    '.adts': 'audio/aac',
    '.loas': 'audio/aac',
    '.ass': 'audio/aac',
    '.au': 'audio/basic',
    '.snd': 'audio/basic',
    '.mp3': 'audio/mpeg',
    '.mp2': 'audio/mpeg',
    '.opus': 'audio/opus',
    '.aif': 'audio/x-aiff',
    '.aifc': 'audio/x-aiff',
    '.aiff': 'audio/x-aiff',
    '.ra': 'audio/x-pn-realaudio',
    '.wav': 'audio/x-wav',
    '.avif': 'image/avif',
    '.bmp': 'image/bmp',
    '.gif': 'image/gif',
    '.ief': 'image/ief',
    '.jpg': 'image/jpeg',
    '.jpe': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.heic': 'image/heic',
    '.heif': 'image/heif',
    '.png': 'image/png',
    '.svg': 'image/svg+xml',
    '.tiff': 'image/tiff',
    '.tif': 'image/tiff',
    '.ico': 'image/vnd.microsoft.icon',
    '.webp': 'image/webp',
    '.ras': 'image/x-cmu-raster',
    '.pnm': 'image/x-portable-anymap',
    '.pbm': 'image/x-portable-bitmap',
    '.pgm': 'image/x-portable-graymap',
    '.ppm': 'image/x-portable-pixmap',
    '.rgb': 'image/x-rgb',
    '.xbm': 'image/x-xbitmap',
    '.xpm': 'image/x-xpixmap',
    '.xwd': 'image/x-xwindowdump',
    '.eml': 'message/rfc822',
    '.mht': 'message/rfc822',
    '.mhtml': 'message/rfc822',
    '.nws': 'message/rfc822',
    '.css': 'text/css',
    '.csv': 'text/csv',
    '.html': 'text/html',
    '.htm': 'text/html',
    '.md': 'text/markdown',
    '.markdown': 'text/markdown',
    '.n3': 'text/n3',
    '.txt': 'text/plain',
    '.bat': 'text/plain',
    '.c': 'text/plain',
    '.h': 'text/plain',
    '.ksh': 'text/plain',
    '.pl': 'text/plain',
    '.srt': 'text/plain',
    '.rtx': 'text/richtext',
    '.rtf': 'text/rtf',
    '.tsv': 'text/tab-separated-values',
    '.vtt': 'text/vtt',
    '.py': 'text/x-python',
    '.rst': 'text/x-rst',
    '.etx': 'text/x-setext',
    '.sgm': 'text/x-sgml',
    '.sgml': 'text/x-sgml',
    '.vcf': 'text/x-vcard',
    '.xml': 'text/xml',
    '.mp4': 'video/mp4',
    '.mpeg': 'video/mpeg',
    '.m1v': 'video/mpeg',
    '.mpa': 'video/mpeg',
    '.mpe': 'video/mpeg',
    '.mpg': 'video/mpeg',
    '.mov': 'video/quicktime',
    '.qt': 'video/quicktime',
    '.webm': 'video/webm',
    '.avi': 'video/x-msvideo',
    '.movie': 'video/x-sgi-movie',
}

_common_types_default = {
    '.rtf': 'application/rtf',
    '.midi': 'audio/midi',
    '.mid': 'audio/midi',
    '.jpg': 'image/jpg',
    '.pict': 'image/pict',
    '.pct': 'image/pict',
    '.pic': 'image/pict',
    '.xul': 'text/xul',
}

inited = True
knownfiles = []


class MimeTypes:
    def __init__(self, filenames=(), strict=True):
        self.encodings_map = dict(encodings_map)
        self.suffix_map = dict(suffix_map)
        self.types_map = ({}, {})
        self.types_map_inv = ({}, {})
        for ext, type in _types_map_default.items():
            self.add_type(type, ext, True)
        for ext, type in _common_types_default.items():
            self.add_type(type, ext, False)

    def add_type(self, type, ext, strict=True):
        if not type:
            return
        self.types_map[strict][ext] = type
        exts = self.types_map_inv[strict].setdefault(type, [])
        if ext not in exts:
            exts.append(ext)

    def guess_type(self, url, strict=True):
        url = os.fspath(url)
        scheme, rest = "", url
        if ":" in url and "/" not in url.split(":", 1)[0]:
            scheme, rest = url.split(":", 1)
        if scheme == "data":
            comma = rest.find(",")
            if comma < 0:
                return None, None
            semi = rest.find(";", 0, comma)
            type = rest[:semi] if semi >= 0 else rest[:comma]
            if "=" in type or "/" not in type:
                type = "text/plain"
            return type, None
        if scheme:
            path = urllib.parse.urlsplit(url).path
        else:
            path = url
        return self.guess_file_type(path, strict=strict)

    def guess_file_type(self, path, *, strict=True):
        path = os.fspath(path)
        base, ext = os.path.splitext(path)
        while (ext_lower := ext.lower()) in self.suffix_map:
            base, ext = os.path.splitext(base + self.suffix_map[ext_lower])
        if ext in self.encodings_map:
            encoding = self.encodings_map[ext]
            base, ext = os.path.splitext(base)
        elif ext.lower() in self.encodings_map:
            encoding = self.encodings_map[ext.lower()]
            base, ext = os.path.splitext(base)
        else:
            encoding = None
        types_map = self.types_map[True]
        if ext in types_map:
            return types_map[ext], encoding
        if ext.lower() in types_map:
            return types_map[ext.lower()], encoding
        if strict:
            return None, encoding
        types_map = self.types_map[False]
        if ext in types_map:
            return types_map[ext], encoding
        if ext.lower() in types_map:
            return types_map[ext.lower()], encoding
        return None, encoding

    def guess_all_extensions(self, type, strict=True):
        type = type.lower()
        exts = list(self.types_map_inv[True].get(type, []))
        if not strict:
            for ext in self.types_map_inv[False].get(type, []):
                if ext not in exts:
                    exts.append(ext)
        return exts

    def guess_extension(self, type, strict=True):
        exts = self.guess_all_extensions(type, strict)
        return exts[0] if exts else None

    def read(self, filename, strict=True):
        pass

    def readfp(self, fp, strict=True):
        for line in fp:
            words = line.split("#", 1)[0].split()
            if words:
                for suffix in words[1:]:
                    self.add_type(words[0], "." + suffix, strict)

    def read_windows_registry(self, strict=True):
        pass


_db = MimeTypes()
types_map = _db.types_map[True]
common_types = _db.types_map[False]


def guess_type(url, strict=True):
    return _db.guess_type(url, strict)


def guess_file_type(path, *, strict=True):
    return _db.guess_file_type(path, strict=strict)


def guess_all_extensions(type, strict=True):
    return _db.guess_all_extensions(type, strict)


def guess_extension(type, strict=True):
    return _db.guess_extension(type, strict)


def add_type(type, ext, strict=True):
    _db.add_type(type, ext, strict)


def init(files=None):
    pass


def read_mime_types(file):
    return None
