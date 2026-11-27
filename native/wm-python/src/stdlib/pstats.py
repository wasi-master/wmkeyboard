"""pstats: reading a profiler's statistics. The keyboard's profilers record
none (see profile), so a Stats holds no functions and prints a header."""

import sys as _sys
from enum import Enum as _Enum

__all__ = ["Stats", "SortKey", "FunctionProfile", "StatsProfile"]


class SortKey(str, _Enum):
    CALLS = "calls"
    CUMULATIVE = "cumulative"
    FILENAME = "filename"
    LINE = "line"
    NAME = "name"
    NFL = "nfl"
    PCALLS = "pcalls"
    STDNAME = "stdname"
    TIME = "time"


class FunctionProfile:
    def __init__(self, ncalls, tottime, percall_tottime, cumtime, percall_cumtime, file_name, line_number):
        self.ncalls = ncalls
        self.tottime = tottime
        self.percall_tottime = percall_tottime
        self.cumtime = cumtime
        self.percall_cumtime = percall_cumtime
        self.file_name = file_name
        self.line_number = line_number


class StatsProfile:
    def __init__(self, total_tt, func_profiles):
        self.total_tt = total_tt
        self.func_profiles = func_profiles


class Stats:
    def __init__(self, *args, stream=None):
        self.stream = stream or _sys.stdout
        self.stats = {}
        self.total_calls = 0
        self.prim_calls = 0
        self.total_tt = 0.0
        self.fcn_list = None
        for arg in args:
            self.add(arg)

    def add(self, *arg_list):
        for item in arg_list:
            if isinstance(item, str):
                raise PermissionError(13, "plugins have no file system")
            if isinstance(item, Stats):
                self.stats.update(item.stats)
        return self

    def load_stats(self, arg):
        self.add(arg)

    def dump_stats(self, filename):
        raise PermissionError(13, "plugins have no file system")

    def get_stats_profile(self):
        return StatsProfile(self.total_tt, {})

    def strip_dirs(self):
        return self

    def sort_stats(self, *field):
        return self

    def reverse_order(self):
        return self

    def calc_callees(self):
        pass

    def print_stats(self, *amount):
        print(f"         {self.total_calls} function calls in {self.total_tt:.3f} seconds\n", file=self.stream)
        return self

    def print_callers(self, *amount):
        return self

    def print_callees(self, *amount):
        return self
