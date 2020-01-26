# This script produces the nbd kernel module to use on run farm nodes.
set -e

# deps
sudo yum -y install xmlto asciidoc hmaccalc newt-devel pesign elfutils-devel binutils-devel audit-libs-devel numactl-devel pciutils-devel python-docutils "perl(ExtUtils::Embed)"

# update this as FPGA Dev AMI updates
KSRC="3.10.0-957.5.1.el7"

# other vars
KRPM="kernel-$KSRC.src.rpm"
KURL="http://vault.centos.org/7.6.1810/updates/Source/SPackages/$KRPM"

GENERICBUILDDIR=$(pwd)/build
NBDBUILDDIR=$GENERICBUILDDIR/nbdbuild

mkdir -p $GENERICBUILDDIR
cd $GENERICBUILDDIR
echo $(pwd)
mkdir -p $NBDBUILDDIR/{BUILD,BUILDROOT,RPMS,SOURCES,SPECS,SRPMS}

wget $KURL

# unpack
rpm --define="_topdir $NBDBUILDDIR" -ivh $KRPM

cd $NBDBUILDDIR/SPECS
rpmbuild --define="_topdir $NBDBUILDDIR" -bp --target=$(uname -m) kernel.spec

KBUILDDIR=$NBDBUILDDIR/BUILD/kernel-$KSRC/linux-$KSRC.x86_64/
cd $KBUILDDIR

# this file is not kept up to date and does not compile, need to patch it
sed -i 's/REQ_TYPE_SPECIAL/REQ_TYPE_DRV_PRIV/g' $KBUILDDIR/drivers/block/nbd.c

# now build
make -j32 prepare
make -j32 modules_prepare

# turn on NBD in the config
sed -i 's/# CONFIG_BLK_DEV_NBD is not set/CONFIG_BLK_DEV_NBD=m/g' $KBUILDDIR/.config

make -j32
make M=drivers/block -j32
modinfo drivers/block/nbd.ko

OUTPUTFILE=$GENERICBUILDDIR/nbd.ko
cp $KBUILDDIR/drivers/block/nbd.ko $OUTPUTFILE
echo "NBD kernel module available in $OUTPUTFILE"
