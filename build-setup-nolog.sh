#!/usr/bin/env bash

# FireSim initial setup script.

# exit script if any command fails
set -e
set -o pipefail

FDIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
cd "$FDIR"

USE_PINNED_DEPS=true

function usage
{
    echo "usage: build-setup.sh [OPTIONS]"
    echo "options:"
    echo "   --library: if set, initializes submodules assuming FireSim is being used"
    echo "            as a library submodule."
    echo "   --unpinned-deps: if set, use unpinned conda package dependencies"
}

while test $# -gt 0
do
   case "$1" in
        --unpinned-deps)
            USE_PINNED_DEPS=false;
            ;;
        -h | -H | --help | -help)
            usage
            exit 3
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

# Remove and backup the existing env.sh if it exists
# The existing of env.sh implies this script completely correctly
if [ -f env.sh ]; then
    mv -f env.sh env.sh.backup
fi

# This will be flushed out into a complete env.sh which will be written out
# upon completion.
env_string="# This file was generated by $0"

# Initially, create a env.sh that suggests build.sh did not run correctly.
cat >env.sh <<END_INITIAL_ENV_SH
${env_string}
echo "ERROR: build-setup.sh did not execute correctly or was terminated prematurely."
echo "Please review build-setup-log for more information."
END_INITIAL_ENV_SH

function env_append {
    env_string+=$(printf "\n$1")
}

env_append "export FIRESIM_ENV_SOURCED=1"

#### Conda setup ####

# note: lock file must end in .conda-lock.yml - see https://github.com/conda-incubator/conda-lock/issues/154
if [ "$USE_PINNED_DEPS" = false ]; then
    # auto-gen the lockfile
    ./scripts/generate-conda-lockfile.sh
fi
LOCKFILE="$(find $FDIR/conda-reqs/*.conda-lock.yml)"

# create environment with conda-lock
conda-lock install --conda $(which conda) -p $FDIR/.conda-env $LOCKFILE

# activate environment for downstream steps
source $FDIR/.conda-env/etc/profile.d/conda.sh
conda activate $FDIR/.conda-env

# add conda activation to env.sh
# provide a sourceable snippet that can be used in subshells that may not have
# inhereted conda functions that would be brought in under a login shell that
# has run conda init (e.g., VSCode, CI)
read -r -d '\0' CONDA_ACTIVATE_PREAMBLE <<'END_CONDA_ACTIVATE'
if ! type conda >& /dev/null; then
    echo "::ERROR:: you must have conda in your environment first"
fi

# if we're sourcing this in a sub process that has conda in the PATH but not as a function, init it again
conda activate --help >& /dev/null || source $(conda info --base)/etc/profile.d/conda.sh
\0
END_CONDA_ACTIVATE
env_append "$CONDA_ACTIVATE_PREAMBLE"
env_append "conda activate $FDIR/.conda-env"

# add other toolchain utilities to environment (spike, fesvr, pk)
./scripts/build-toolchain-extra.sh -p $RISCV

git submodule update --init --recursive

cd "$FDIR"

#### EC2-only setup ####

# see if the instance info page exists. if not, we are not on ec2.
# this is one of the few methods that works without sudo
if wget -T 1 -t 3 -O /dev/null http://169.254.169.254/latest/; then

    (
	# ensure that we're using the system toolchain to build the kernel modules
	# newer gcc has --enable-default-pie and older kernels think the compiler
	# is broken unless you pass -fno-pie but then I was encountering a weird
	# error about string.h not being found
	export PATH=/usr/bin:$PATH

	cd "$FDIR/platforms/f1/aws-fpga/sdk/linux_kernel_drivers/xdma"
	make

	# the only ones missing are libguestfs-tools
	sudo yum install -y libguestfs-tools bc

	# Setup for using qcow2 images
	cd "$FDIR"
	./scripts/install-nbd-kmod.sh
    )

    (
	if [[ "${CPPFLAGS:-zzz}" != "zzz" ]]; then
	    # don't set it if it isn't already set but strip out -DNDEBUG because
	    # the sdk software has assertion-only variable usage that will end up erroring
	    # under NDEBUG with -Wall and -Werror
	    export CPPFLAGS="${CPPFLAGS/-DNDEBUG/}"
	fi


	# Source {sdk,hdk}_setup.sh once on this machine to build aws libraries and
	# pull down some IP, so we don't have to waste time doing it each time on
	# worker instances
	AWSFPGA="$FDIR/platforms/f1/aws-fpga"
	cd "$AWSFPGA"
	bash -c "source ./sdk_setup.sh"
	bash -c "source ./hdk_setup.sh"
    )

fi

cd "$FDIR"
set +e
./gen-tags.sh
set -e

read -r -d '\0' NDEBUG_CHECK <<'END_NDEBUG'
# Ensure that we don't have -DNDEBUG anywhere in our environment

# check and fixup the known place where conda will put it
if [[ "$CPPFLAGS" == *"-DNDEBUG"* ]]; then
    echo "::INFO:: removing '-DNDEBUG' from CPPFLAGS as we prefer to leave assertions in place"
    export CPPFLAGS="${CPPFLAGS/-DNDEBUG/}"
fi

# check for any other occurances and warn the user
env | grep -v 'CONDA_.*_BACKUP' | grep -- -DNDEBUG && echo "::WARNING:: you still seem to have -DNDEBUG in your environment. This is known to cause problems."
true # ensure env.sh exits 0
\0
END_NDEBUG
env_append "$NDEBUG_CHECK"

# Write out the generated env.sh indicating successful completion.
echo "$env_string" > env.sh

echo "Setup complete!"
echo "To get started, source sourceme-manager.sh to setup your environment."
echo "For more information, see docs at https://docs.fires.im/."
