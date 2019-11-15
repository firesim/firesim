#!/bin/bash
echo "Number of CPUs:"
cat /proc/cpuinfo | grep processor | wc -l

echo "Memory:"
MEMKB=$(cat /proc/meminfo | grep MemTotal | awk '{print $2}')
# Round to the nearest MB to deal with subtle differences in spike vs qemu
echo $(expr $MEMKB / 1024)
sync
poweroff -f
