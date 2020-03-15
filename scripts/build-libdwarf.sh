#!/bin/sh

set -e
test -n "${RISCV}" || exit 1

cd sim/firesim-lib/src/main/cc/lib/libdwarf
sh scripts/FIX-CONFIGURE-TIMES
./configure --prefix="${RISCV}" --enable-shared --disable-static CFLAGS="-g -I${RISCV}/include" LDFLAGS="-L${RISCV}/lib"
make
make install
