# you should source this on your manager instance
# you can also source it in your bashrc, but you must cd to this directory
# first

DO_SSH_SETUP=true

function usage
{
    echo "usage: source sourceme-f1-manager.sh [OPTIONS]"
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
export CL_DIR=$AWSFPGA/hdk/cl/developer_designs/cl_firesim

# setup risc-v tools
source ./env.sh

# put the manager on the user path
export PATH=$PATH:$(pwd)/deploy

if [ "$DO_SSH_SETUP" = true ]; then
    # setup ssh-agent
    source deploy/ssh-setup.sh
fi

# flag for scripts to check that this has been sourced
export FIRESIM_SOURCED=1

# this is a prefix added to run farm names. change this to isolate run farms
# if you have multiple copies of firesim
export FIRESIM_RUNFARM_PREFIX=""

# put FlameGraph/other fireperf utils on the user path
export PATH=$(pwd)/utils/fireperf:$(pwd)/utils/fireperf/FlameGraph:$PATH
