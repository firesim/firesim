#!/usr/bin/env bash

# FireSim initial setup script. This script will:
# 1) Initalize submodules (only the required ones, minimizing duplicates
# 2) Install RISC-V tools, including linux tools
# 3) Installs python requirements for firesim manager

# TODO: build FireSim linux distro here?

# exit script if any command fails
set -e
set -o pipefail

unamestr=$(uname)
RISCV=$(pwd)/riscv-tools-install
RDIR=$(pwd)

FASTINSTALL=false
IS_LIBRARY=false
SUBMODULES_ONLY=false

function usage
{
    echo "usage: build-setup.sh [ fast | --fast] [--submodules-only] [--library]"
    echo "   fast: if set, pulls in a pre-compiled RISC-V toolchain for an EC2 manager instance"
    echo "   submodules-only: if set, skips toolchain handling (cloning or building)"
    echo "   library: if set, initializes submodules assuming FireSim is being used"
    echo "            as a library submodule"
}

if [ "$1" == "--help" -o "$1" == "-h" -o "$1" == "-H" ]; then
    usage
    exit 3
fi

while test $# -gt 0
do
   case "$1" in
        fast | --fast) # I don't want to break this api
            FASTINSTALL=true
            ;;
        --library)
            IS_LIBRARY=true;
            ;;
        --submodules-only)
            SUBMODULES_ONLY=true;
            ;;
        -h | -H | --help)
            usage
            exit
            ;;
        --*) echo "ERROR: bad option $1"
            usage
            exit 1
            ;;
        *) echo "ERROR: bad argument $1"
            usage
            exit 2
            ;;
    esac
    shift
done

# We're a submodule of rebar, so don't initalize the submodule
# ignore riscv-tools for submodule init recursive
# you must do this globally (otherwise riscv-tools deep
# in the submodule tree will get pulled anyway
git config --global submodule.riscv-tools.update none
git config --global submodule.experimental-blocks.update none
git config --global submodule.sims/firesim.update none
# Disable the REBAR submodule initially, and enable if we're not in library mode
git config submodule.target-design/chipyard.update none
git submodule update --init --recursive #--jobs 8
# unignore riscv-tools,catapult-shell2 globally
git config --global --unset submodule.sims/firesim.update
git config --global --unset submodule.riscv-tools.update
git config --global --unset submodule.experimental-blocks.update

if [ "$IS_LIBRARY" = false ]; then
    git config --unset submodule.target-design/chipyard.update
    git submodule update --init target-design/chipyard
    cd $RDIR/target-design/chipyard
    ./scripts/init-submodules-no-riscv-tools.sh
    cd $RDIR
fi

if [ "$SUBMODULES_ONLY" = true ]; then
    # Only initialize submodules
    exit
fi

# A lazy way to get fast riscv-tools installs for most users:
# 1) If user runs ./build-setup.sh fast :
#   a) clone the prebuilt risc-v tools repo
#   b) check if HASH in that repo matches the hash of target-design/chipyard/riscv-tools
#   c) if so, just copy it into riscv-tools-install, otherwise croak forcing
#   the user to rerun this script without --fast
# 2) If fast was not specified, but the toolchain from source
if [ "$IS_LIBRARY" = true ]; then
    chipyard_dir=$RDIR/../../
else
    chipyard_dir=$RDIR/target-design/chipyard/
fi

if [ "$FASTINSTALL" = "true" ]; then
    git clone https://github.com/firesim/firesim-riscv-tools-prebuilt.git
    cd firesim-riscv-tools-prebuilt
    git checkout 5fee18421a32058ab339572128201f4904354aaa
    PREBUILTHASH="$(cat HASH)"
    cd $chipyard_dir
    git submodule update --init toolchains/riscv-tools
    cd toolchains/riscv-tools
    GITHASH="$(git rev-parse HEAD)"
    echo "prebuilt hash: $PREBUILTHASH"
    echo "git      hash: $GITHASH"
    cd $RDIR
    if [ "$PREBUILTHASH" = "$GITHASH" ]; then
        echo "using fast install for riscv-tools"
    else
        echo "Error: hash of precompiled toolchain doesn't match the riscv-tools submodule hash."
        exit
    fi
fi

if [ "$FASTINSTALL" = true ]; then
    cd firesim-riscv-tools-prebuilt
    ./installrelease.sh
    mv distrib ../riscv-tools-install
    # copy HASH in case user wants it later
    cp HASH ../riscv-tools-install/
    cd $RDIR
    rm -rf firesim-riscv-tools-prebuilt
else
    # install risc-v tools
    mkdir -p riscv-tools-install
    export RISCV="$RISCV"
    cd $target_toolchain_dir
    git submodule update --init --recursive riscv-tools #--jobs 8
    cd riscv-tools
    export MAKEFLAGS="-j16"
    # Copied from riscv-tools build.sh
    source build.common
    echo "Starting RISC-V Toolchain build process"
    build_project riscv-fesvr --prefix=$RISCV
    build_project riscv-isa-sim --prefix=$RISCV --with-fesvr=$RISCV
    build_project riscv-gnu-toolchain --prefix=$RISCV
    CC= CXX= build_project riscv-pk --prefix=$RISCV --host=riscv64-unknown-elf
    build_project riscv-tests --prefix=$RISCV/riscv64-unknown-elf
    echo -e "\\nRISC-V Toolchain installation completed!"

    # build static libfesvr library for linking into driver
    cd riscv-fesvr/build
    $RDIR/scripts/build-static-libfesvr.sh
    cd $RDIR
    # build linux toolchain
    cd $target_toolchain_dir/riscv-tools/riscv-gnu-toolchain/build
    make -j16 linux
    cd $RDIR

    # build QEMU
    cd sw/qemu
    ./configure --target-list=riscv64-softmmu --prefix=$RISCV
    make -j16
    make install
    cd $RDIR
fi

echo "export FIRESIM_ENV_SOURCED=1" > env.sh
echo "export RISCV=$RISCV" >> env.sh
echo "export PATH=$RISCV/bin:$RDIR/$DTCversion:\$PATH" >> env.sh
echo "export LD_LIBRARY_PATH=$RISCV/lib" >> env.sh

if  [ "$IS_LIBRARY" = false ]; then
    echo "export FIRESIM_STANDALONE=1" >> env.sh
fi

# commands to run only on EC2
# see if the instance info page exists. if not, we are not on ec2.
# this is one of the few methods that works without sudo
if wget -T 1 -t 3 -O /dev/null http://169.254.169.254/; then
    cd "$RDIR/platforms/f1/aws-fpga/sdk/linux_kernel_drivers/xdma"
    make

    # Install firesim-software dependencies 
    cd $RDIR
    sudo pip3 install -r sw/firesim-software/python-requirements.txt
    cat sw/firesim-software/centos-requirements.txt | sudo xargs yum install -y

    # run sourceme-f1-full.sh once on this machine to build aws libraries and
    # pull down some IP, so we don't have to waste time doing it each time on
    # worker instances
    cd $RDIR
    bash sourceme-f1-full.sh
fi

cd $RDIR
./gen-tags.sh

echo "Setup complete!"
echo "To generate simulator RTL and run sw-RTL simulation, source env.sh"
echo "To use the manager to deploy builds/simulations on EC2, source sourceme-f1-manager.sh to setup your environment."
echo "To run builds/simulations manually on this machine, source sourceme-f1-full.sh to setup your environment."
