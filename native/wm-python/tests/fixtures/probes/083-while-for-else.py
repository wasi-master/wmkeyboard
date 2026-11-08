out = []
i = 0
while i < 5:
    i += 1
    if i == 2:
        continue
    if i == 4:
        break
    out.append(i)
else:
    out.append("else")
for j in range(3):
    pass
else:
    out.append("for-else")
out
