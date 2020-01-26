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

# Disable the Chipyard submodule initially, and enable if we're not in library mode
git config submodule.target-design/chipyard.update none
git config submodule.sw/firesim-software.update none
git submodule update --init --recursive #--jobs 8

# firesim-software should not be recursively initialized by default (use
# sw/firesim-software/init-submodules.sh if you need linux deps)
git config --unset submodule.sw/firesim-software.update
git submodule update --init sw/firesim-software

if [ "$IS_LIBRARY" = false ]; then
    git config --unset submodule.target-design/chipyard.update
    git submodule update --init target-design/chipyard
    cd $RDIR/target-design/chipyard
    ./scripts/init-submodules-no-riscv-tools.sh --no-firesim
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
    target_chipyard_dir=$RDIR/../..
else
    target_chipyard_dir=$RDIR/target-design/chipyard
fi


# Restrict the devtoolset environment to a subshell
#
# The devtoolset wrapper around sudo does not correctly pass options
# through, which causes an aws-fpga SDK setup script to fail:
# platforms/f1/aws-fpga/sdk/userspace/install_fpga_mgmt_tools.sh
(
    # Enable latest Developer Toolset for GNU make 4.x
    devtoolset=''
    for dir in /opt/rh/devtoolset-* ; do
        ! [ -x "${dir}/root/usr/bin/make" ] || devtoolset="${dir}"
    done
    if [ -n "${devtoolset}" ] ; then
        echo "Enabling ${devtoolset##*/}"
        . "${devtoolset}/enable"
    fi

    # Build the toolchain through chipyard (whether as top or as library)
    cd "$target_chipyard_dir"
    if [ "$FASTINSTALL" = "true" ] ; then
        ./scripts/build-toolchains.sh ec2fast
    else
        ./scripts/build-toolchains.sh
    fi
)

#generate env.sh file which sources the chipyard env.sh file
echo "if [ -f \"$target_chipyard_dir/env.sh\" ]; then" > env.sh
echo "  source $target_chipyard_dir/env.sh" >> env.sh
echo "  export FIRESIM_ENV_SOURCED=1" >> env.sh
echo "else" >> env.sh
echo "  echo \"Error: You may have forgot to build or source the toolchains (build them independently in firesim-as-a-library mode)\"" >> env.sh
echo "fi" >> env.sh

if  [ "$IS_LIBRARY" = false ]; then
    echo "export FIRESIM_STANDALONE=1" >> env.sh
fi

cd $RDIR

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
    wget https://git.kernel.org/pub/scm/fs/ext2/e2fsprogs.git/snapshot/e2fsprogs-1.45.4.tar.gz
    tar xvzf e2fsprogs-1.45.4.tar.gz
    cd e2fsprogs-1.45.4/
    mkdir build && cd build
    ../configure
    make
    sudo make install
    cd ../..
    rm -rf e2fsprogs*

    # Setup for using qcow2 images
    cd $RDIR
    ./scripts/install-nbd-kmod.sh

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
