#!/bin/sh

set -e
if [ -z "$RISCV" ]; then
    echo "You must set \$RISCV to run this script."
    exit 1
fi

if [ $# -ne 1 ]; then
    echo "$0 expects one argument, the installation prefix."
    exit 1
fi

prefix=$1

cd sim/firesim-lib/src/main/cc/lib/libdwarf
sh scripts/FIX-CONFIGURE-TIMES
mkdir build
cd build
../configure --prefix="${prefix}" --enable-shared --disable-static CFLAGS="-g -I${RISCV}/include" LDFLAGS="-L${RISCV}/lib"
make
make install
