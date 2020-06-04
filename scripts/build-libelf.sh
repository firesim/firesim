#!/bin/sh

set -e
if [ -z "$RISCV" ]; then
    echo "You must set \$RISCV to run this script."
    exit 1
fi

cd sim/firesim-lib/src/main/cc/lib/elfutils
test -f configure || autoreconf -i -f
./configure --prefix="${RISCV}" --enable-maintainer-mode
make
make install
