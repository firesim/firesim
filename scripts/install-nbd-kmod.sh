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
KBUILDDIR=$NBDBUILDDIR/BUILD/kernel-$KSRC/linux-$KSRC.x86_64
OUTPUTFILE=$GENERICBUILDDIR/nbd.ko

mkdir -p $GENERICBUILDDIR
cd $GENERICBUILDDIR
echo $(pwd)
mkdir -p $NBDBUILDDIR/{BUILD,BUILDROOT,RPMS,SOURCES,SPECS,SRPMS}

rm -rf "$NBDBUILDDIR"
rm -rf "$KRPM"* $OUTPUTFILE
wget $KURL

# unpack
cd $GENERICBUILDDIR
rpm --define="_topdir $NBDBUILDDIR" -ivh $KRPM

cd $NBDBUILDDIR/SPECS
rpmbuild --define="_topdir $NBDBUILDDIR" -bp --target=$(uname -m) kernel.spec


# this file is not kept up to date and does not compile, need to patch it
cd $KBUILDDIR
sed -i 's/REQ_TYPE_SPECIAL/REQ_TYPE_DRV_PRIV/g' $KBUILDDIR/drivers/block/nbd.c

# now clean/setup .config
cd $KBUILDDIR
make -j32 mrproper
make -j32 oldconfig

# turn on NBD in the config
cd $KBUILDDIR
sed -i 's/# CONFIG_BLK_DEV_NBD is not set/CONFIG_BLK_DEV_NBD=m/g' $KBUILDDIR/.config
make -j32 modules_prepare

# now build
make -j32
modinfo drivers/block/nbd.ko

cp $KBUILDDIR/drivers/block/nbd.ko $OUTPUTFILE
echo "NBD kernel module available in $OUTPUTFILE"
