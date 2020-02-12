#!/bin/sh

set -e
test -n "${RISCV}" || exit 1

cd sim/firesim-lib/src/main/cc/lib/elfutils
test -f configure || autoreconf -i -f
./configure --prefix="${RISCV}" --enable-maintainer-mode
make
make install
