# you should source this only if you plan to run build/simulations locally,
# without using the manager at all.

DO_SSH_SETUP=true

function usage
{
    echo "usage: source sourceme-f1-full.sh [OPTIONS]"
    echo "options:"
    echo "   --skip-ssh-setup: if set, skips ssh setup checks."
}

while test $# -gt 0
do
   case "$1" in
        --skip-ssh-setup)
            DO_SSH_SETUP=false;
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

unamestr=$(uname)
RDIR=$(pwd)
AWSFPGA=$RDIR/platforms/f1/aws-fpga

# setup risc-v tools
source ./env.sh

# setup AWS tools
cd $AWSFPGA
source ./hdk_setup.sh
source ./sdk_setup.sh
export CL_DIR=$AWSFPGA/hdk/cl/developer_designs/cl_firesim
cd $RDIR

# put the manager on the user path
export PATH=$PATH:$(pwd)/deploy

if [ "$DO_SSH_SETUP" = true ]; then
    # setup ssh-agent
    source deploy/ssh-setup.sh
fi

# flag for scripts to check that this has been sourced
export FIRESIM_SOURCED=1
