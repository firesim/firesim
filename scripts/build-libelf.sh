#!/bin/sh

set -e
if [ $# -ne 1 ]; then
    echo "$0 expects one argument, the installation prefix."
    exit 1
fi

prefix=$1

export CFLAGS+=" -Wno-unused-variable"
# leave the assertions in elfutils
export CPPFLAGS="${CPPFLAGS/-DNDEBUG/}"

cd sim/firesim-lib/src/main/cc/lib/elfutils
test -f configure || autoreconf -i -f
./configure --prefix="${prefix}" --enable-maintainer-mode
make
make install
# The build process modifies tracked sources. This to clean up after ourselves.
git reset --hard
