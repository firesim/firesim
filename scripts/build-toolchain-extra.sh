#!/usr/bin/env bash

# similar to https://github.com/ucb-bar/chipyard/blob/main/scripts/build-toolchain-extra.sh

# exit script if any command fails
set -e
set -o pipefail

RDIR=$(git rev-parse --show-toplevel)

# Allow user to override MAKE
[ -n "${MAKE:+x}" ] || MAKE=$(command -v gnumake || command -v gmake || command -v make)
readonly MAKE

usage() {
    echo "usage: ${0} [OPTIONS]"
    echo ""
    echo "Options"
    echo "   --prefix -p PREFIX    : Install destination."
    echo "   --clean-after-install : Run make clean in calls to module_make and module_build"
    echo "   --help -h             : Display this message"
    exit "$1"
}

TOOLCHAIN="riscv-tools"
CLEANAFTERINSTALL=""
RISCV=""

# getopts does not support long options, and is inflexible
while [ "$1" != "" ];
do
    case $1 in
        -h | -H | --help | help )
            usage 3 ;;
        -p | --prefix )
            shift
            RISCV=$(realpath $1) ;;
        --clean-after-install )
            CLEANAFTERINSTALL="true" ;;
        * )
            error "invalid option $1"
            usage 1 ;;
    esac
    shift
done

if [ -z "$RISCV" ] ; then
    error "ERROR: Prefix not given. If conda is sourced, do you mean $CONDA_PREFIX/$TOOLCHAIN?"
fi

XLEN=64

echo "Installing extra toolchain utilities to $RISCV"

# install risc-v tools
export RISCV="$RISCV"

cd "${RDIR}"

SRCDIR="$(pwd)/sim/toolchain-utilities"
[ -d "${SRCDIR}" ] || die "unable to find ${SRCDIR}"
. ./scripts/build-util.sh

echo '==>  Installing Spike'
# disable boost explicitly for https://github.com/riscv-software-src/riscv-isa-sim/issues/834
# since we don't have it in our requirements
module_all riscv-isa-sim --prefix="${RISCV}" --with-boost=no --with-boost-asio=no --with-boost-regex=no
# build static libfesvr library for linking into firesim driver (or others)
echo '==>  Installing libfesvr static library'
OLDCLEANAFTERINSTALL=$CLEANAFTERINSTALL
CLEANAFTERINSTALL=""
module_make riscv-isa-sim libfesvr.a
cp -p "${SRCDIR}/riscv-isa-sim/build/libfesvr.a" "${RISCV}/lib/"
CLEANAFTERINSTALL=$OLDCLEANAFTERINSTALL

echo '==>  Installing Proxy Kernel'
CC= CXX= module_all riscv-pk --prefix="${RISCV}" --host=riscv${XLEN}-unknown-elf --with-arch=rv64gc_zifencei

echo "Extra Toolchain Utilities/Tests Build Complete!"
