#!/bin/bash

# This is an example of the sort of thing you might want to do in an init script.
# Note that this script will be run exactly once on your image in qemu. While
# we could have chosen to cross compile qsort (probably a better choice in this
# case), other benchmarks might not support cross-compilation.
# 
# You can also download stuff, and configure your system in this script.
cd /root/qsort
make
