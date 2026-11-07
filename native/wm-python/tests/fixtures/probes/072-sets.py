s = {3, 1, 2}
s.add(4)
s.discard(10)
s.remove(1)
t = {2, 5}
[s, s | t, s & t, s - t, s ^ t, s.union([9]), s.issubset({2, 3, 4, 5}), s.isdisjoint({7}), set(), {1, 2} == {2, 1}, sorted(s.copy())]
