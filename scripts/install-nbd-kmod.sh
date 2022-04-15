#!/bin/sh
# This script produces the nbd kernel module to use on run farm nodes.
set -ex

# deps
sudo yum -y install rpm-build xmlto asciidoc hmaccalc newt-devel pesign elfutils-devel binutils-devel audit-libs-devel numactl-devel pciutils-devel python-docutils python-devel "perl(ExtUtils::Embed)" bison java-devel ncurses-devel

# update this as FPGA Dev AMI updates
KSRC='3.10.0-957.5.1.el7'

# other vars
TARGET=x86_64
DISTSITE='http://mirror.nsc.liu.se/centos-store/7.6.1810'

GENERICBUILDDIR=$(pwd)/build
NBDBUILDDIR=$GENERICBUILDDIR/nbdbuild
OUTPUTFILE=$GENERICBUILDDIR/nbd.ko

rm -rf -- "$NBDBUILDDIR" "$OUTPUTFILE"

# fetch and unpack source RPM
rpm -i --verbose --define="_topdir $NBDBUILDDIR" \
    "${DISTSITE}/updates/Source/SPackages/kernel-${KSRC}.src.rpm"

# run %prep stage
cd "${NBDBUILDDIR}/SPECS"
rpmbuild --define="_topdir $NBDBUILDDIR" -bp --target="$TARGET" kernel.spec

cd "${NBDBUILDDIR}/BUILD/kernel-${KSRC}/linux-${KSRC}.${TARGET}"

# acquire Module.symvers from kernel-devel binary package;
# this enables proper symbol versioning (modversions) without requiring
# a full kernel build
rpm2cpio "${DISTSITE}/updates/${TARGET}/Packages/kernel-devel-${KSRC}.${TARGET}.rpm" |
    cpio -iv --to-stdout "./usr/src/kernels/${KSRC}.${TARGET}/Module.symvers" > Module.symvers

# this file is not kept up to date and does not compile, need to patch it
sed -i 's/REQ_TYPE_SPECIAL/REQ_TYPE_DRV_PRIV/g' drivers/block/nbd.c

# use non-debug kernel config
(
    export LC_ALL='' LC_COLLATE=C
    for KCFG in configs/kernel-*-"$TARGET".config ; do break ; done
    test -r "$KCFG"

    # turn on NBD in the config
    sed 's/# CONFIG_BLK_DEV_NBD is not set/CONFIG_BLK_DEV_NBD=m/g' "$KCFG" > .config
)

make olddefconfig
make prepare
make modules_prepare
make M=drivers/block nbd.ko

KMOD=drivers/block/nbd.ko

modinfo "$KMOD"
modprobe --dump-modversions "$KMOD" | grep -F module_layout
cp "$KMOD" "$OUTPUTFILE"
