#!/bin/bash
set -e

if [ "$#" -eq "1" ]; then
    LNX=$1
else
    LNX=.
fi

if [ "$ARCH" == "" ]; then
    ARCH=riscv
fi

echo "Building cscope.out in $LNX for $ARCH"

pushd $LNX
find  .                                                                \
    -path "./arch/*" -prune -o               \
    -path "./tmp*" -prune -o                                           \
    -path "./Documentation*" -prune -o                                 \
    -path "./scripts*" -prune -o                                       \
    -path "./tools/*" -prune -o \
    -path "./debian/*" -prune -o \
    -name "*.[chxsS]" -print > cscope.files

find . \
  -path "./arch/$ARCH/*" \
  -name "*.[chxsS]" >> cscope.files

cscope -bk
popd

echo "$LNX/cscope.out now available"
