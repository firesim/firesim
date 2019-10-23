import sys
import random
import algorithms.sort

# 1M bytes
size = 1000000 / 4
arr = [ random.random() for _ in range(size) ]
# sarr = sorted(arr)
sarr = algorithms.sort.quick_sort(arr)

prev = sarr[0]
for i in sarr[1:]:
    if i <= prev:
        sys.exit("Sorted Wrong")
    prev = i

print("success")
sys.exit()
