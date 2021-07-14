#!/bin/sh

set -e

if [ $# -ne 1 ]; then
    echo "$0 expects one argument, the installation prefix."
    exit 1
fi

prefix=$1

cd sim/firesim-lib/src/main/cc/lib/libdwarf
sh scripts/FIX-CONFIGURE-TIMES
mkdir -p build
cd build
# Get libelf from our local sysroot ($prefix)
../configure --prefix="${prefix}" --enable-shared --disable-static CFLAGS="-g -I${prefix}/include" LDFLAGS="-L${prefix}/lib"
make
make install
