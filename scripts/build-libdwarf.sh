#!/bin/sh

set -e
if [ -z "$RISCV" ]; then
    echo "You must set \$RISCV to run this script."
    exit 1
fi

cd sim/firesim-lib/src/main/cc/lib/libdwarf
sh scripts/FIX-CONFIGURE-TIMES
./configure --prefix="${RISCV}" --enable-shared --disable-static CFLAGS="-g -I${RISCV}/include" LDFLAGS="-L${RISCV}/lib"
make
make install
