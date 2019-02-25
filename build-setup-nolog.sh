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

# ignore riscv-tools for submodule init recursive
# you must do this globally (otherwise riscv-tools deep
# in the submodule tree will get pulled anyway
git config --global submodule.riscv-tools.update none
git config --global submodule.experimental-blocks.update none
git submodule update --init --recursive #--jobs 8
# unignore riscv-tools,catapult-shell2 globally
git config --global --unset submodule.riscv-tools.update
git config --global --unset submodule.experimental-blocks.update

FASTINSTALL=false

# A lazy way to get fast riscv-tools installs for most users:
# 1) If user runs ./build-setup.sh fast :
#   a) shallow clone, depth 1 the prebuilt risc-v tools repo
#   b) check if HASH in that repo matches the hash of target-design/firechip/riscv-tools
#   c) if so, just copy it into riscv-tools-install, otherwise go to 2
# 2) if no riscv-tools installed because either fast was not specified, or
# the hash doesn't match, install as usual (from source)
if [ $# -eq 0 ]; then
    # handle case where no args passed. default to FASTINSTALL false
    FASTINSTALL=false
elif [ "$1" = "submodules-only" ]; then
    # Only initialize submodules
    exit
elif [ "$1" = "fast" ]; then
    git clone https://github.com/firesim/firesim-riscv-tools-prebuilt.git
    cd firesim-riscv-tools-prebuilt
    git checkout 4a2f79d5c5a8a93f3e6a83bd32a0926cdb8983c5
    PREBUILTHASH="$(cat HASH)"
    cd ../target-design/firechip
    git submodule update --init riscv-tools
    cd riscv-tools
    GITHASH="$(git rev-parse HEAD)"
    echo "prebuilt hash: $PREBUILTHASH"
    echo "git      hash: $GITHASH"
    cd $RDIR
    if [ "$PREBUILTHASH" = "$GITHASH" ]; then
        echo "using fast install for riscv-tools"
        FASTINSTALL=true
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
    cd target-design/firechip
    git submodule update --init --recursive riscv-tools #--jobs 8
    cd riscv-tools
    export MAKEFLAGS="-j16"
    ./build.sh
    # build static libfesvr library for linking into driver
    cd riscv-fesvr/build
    $RDIR/scripts/build-static-libfesvr.sh
    # build linux toolchain
    cd $RDIR
    cd target-design/firechip/riscv-tools/riscv-gnu-toolchain/build
    make -j16 linux
    cd $RDIR
    cd sw
    ./install-qemu.sh
    cd $RDIR
fi

echo "export RISCV=$RISCV" > env.sh
echo "export PATH=$RISCV/bin:$RDIR/$DTCversion:\$PATH" >> env.sh
echo "export LD_LIBRARY_PATH=$RISCV/lib" >> env.sh

cd "$RDIR/platforms/f1/aws-fpga/sdk/linux_kernel_drivers/xdma"
make

# Set up firesim-software
cd $RDIR
sudo pip3 install -r sw/firesim-software/python-requirements.txt

# commands to run only on EC2
# see if the instance info page exists. if not, we are not on ec2.
# this is one of the few methods that works without sudo
if wget -T 1 -t 3 -O /dev/null http://169.254.169.254/; then
    # run sourceme-f1-full.sh once on this machine to build aws libraries and
    # pull down some IP, so we don't have to waste time doing it each time on
    # worker instances
    cd $RDIR
    bash sourceme-f1-full.sh
fi

cd $RDIR
./gen-tags.sh

echo "Setup complete!"
echo "To use the manager to deploy builds/simulations, source sourceme-f1-manager.sh to setup your environment."
echo "To run builds/simulations manually on this machine, source sourceme-f1-full.sh to setup your environment."
