"""mmap, for a plugin, which has no files to map. The constants are here;
making a map raises PermissionError."""

ACCESS_DEFAULT = 0
ACCESS_READ = 1
ACCESS_WRITE = 2
ACCESS_COPY = 3
ALLOCATIONGRANULARITY = 4096
PAGESIZE = 4096
MAP_SHARED = 1
MAP_PRIVATE = 2
MAP_ANONYMOUS = MAP_ANON = 32
PROT_READ = 1
PROT_WRITE = 2
PROT_EXEC = 4
MADV_NORMAL = 0
MADV_RANDOM = 1
MADV_SEQUENTIAL = 2
MADV_WILLNEED = 3
MADV_DONTNEED = 4

error = OSError


class mmap:
    def __init__(self, fileno, length, *args, **kwargs):
        raise PermissionError("plugins have no files to map into memory")
