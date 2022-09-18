#!/bin/bash
echo "Number of CPUs:"
cat /proc/cpuinfo | grep processor | wc -l

sync
poweroff -f
