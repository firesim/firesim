#!/usr/bin/env bash

set -e
set -o pipefail
set -x

RDIR=$(pwd)

cd $RDIR

# check if this is running on EC2
# see if the instance info page exists. if not, we are not on ec2.
# this is one of the few methods that works without sudo
set +e
wget -T 1 -t 3 -O /dev/null http://169.254.169.254/ 2>&1 >/dev/null
IS_EC2=$?
set -e

IS_CENTOS=false
IS_UBUNTU=false
OS_TYPE=$(awk -F= '/^NAME/{print $2}' /etc/os-release)
case $OS_TYPE in
    *"CentOS"*)
        IS_CENTOS=true
        ;;
    *"Ubuntu"*)
        IS_UBUNTU=true
        ;;
    *)
        echo "Unknown OS: Only Ubuntu/CentOS supported"
        exit 3
esac

if [ "$IS_CENTOS" == true ]; then
    # classic setup - manager running on EC2 + CentOS
    cd "$RDIR/platforms/f1/aws-fpga/sdk/linux_kernel_drivers/xdma"
    make

    # Setup for using qcow2 images
    cd $RDIR
    ./scripts/install-nbd-kmod.sh

    if [ $IS_EC2 -eq 0 ]; then
        # Source {sdk,hdk}_setup.sh once on this machine to build aws libraries and
        # pull down some IP, so we don't have to waste time doing it each time on
        # worker instances
        AWSFPGA=$RDIR/platforms/f1/aws-fpga
        cd $AWSFPGA
        bash -c "source ./sdk_setup.sh"
        bash -c "source ./hdk_setup.sh"
    else
        # launch AMI instance using awstools
        python $RDIR/deploy/awstools/awstools.py launch --inst_amt 1 2>&1 | tee /tmp/fsim-setup-ipaddr
        # retrieve the public IP
        IP_ADDR=$(sed -n "s/.*public.*\[.\(.*\).\].*/\1/p" /tmp/fsim-setup-ipaddr | head -n 1)
    
        # get current hash
        CUR_HASH=$(git rev-parse HEAD)
    
        ssh -o StrictHostKeyChecking=no centos@$IP_ADDR << EOF
            git clone https://github.com/firesim/firesim.git
            cd ~/firesim
            git checkout $CUR_HASH
    
            source ./scripts/machine-launch-script.sh
    
            git submodule update --init --recursive platforms/f1/aws-fpga
    
            # Source {sdk,hdk}_setup.sh once on this machine to build aws libraries and
            # pull down some IP, so we don't have to waste time doing it each time on
            # worker instances
            cd ~/firesim/platforms/f1/aws-fpga
            bash -c "source ./sdk_setup.sh"
            bash -c "source ./hdk_setup.sh"
EOF
    
        # copy back built results (prevent overwriting the current dir)
        rsync -avzp --ignore-existing -e 'ssh -o StrictHostKeyChecking=no' centos@$IP_ADDR:firesim/ $RDIR
    
        # terminate AMI instance using awstools
        python $RDIR/deploy/awstools/awstools.py terminate
        rm -rf /tmp/fsim-setup-ipaddr
    fi
else 
    # manager running on Ubuntu

    # launch AMI instance using awstools
    python $RDIR/deploy/awstools/awstools.py launch --inst_amt 1 2>&1 | tee /tmp/fsim-setup-ipaddr
    # retrieve the public IP
    IP_ADDR=$(sed -n "s/.*public.*\[.\(.*\).\].*/\1/p" /tmp/fsim-setup-ipaddr | head -n 1)

    # get current hash
    CUR_HASH=$(git rev-parse HEAD)

    ssh -o StrictHostKeyChecking=no centos@$IP_ADDR << EOF
        git clone https://github.com/firesim/firesim.git
        cd ~/firesim
        git checkout $CUR_HASH

	cd ~
        source ~/firesim/scripts/machine-launch-script.sh

	cd ~/firesim
        git submodule update --init --recursive platforms/f1/aws-fpga

        cd ~/firesim/platforms/f1/aws-fpga/sdk/linux_kernel_drivers/xdma
        make
    
        # Setup for using qcow2 images
        cd ~/firesim
        ./scripts/install-nbd-kmod.sh

        # Source {sdk,hdk}_setup.sh once on this machine to build aws libraries and
        # pull down some IP, so we don't have to waste time doing it each time on
        # worker instances
        cd ~/firesim/platforms/f1/aws-fpga
        bash -c "source ./sdk_setup.sh"
        bash -c "source ./hdk_setup.sh"
EOF

    # copy back built results (prevent overwriting the current dir)
    rsync -avzp --ignore-existing -e 'ssh -o StrictHostKeyChecking=no' centos@$IP_ADDR:firesim/ $RDIR

    # terminate AMI instance using awstools
    python $RDIR/deploy/awstools/awstools.py terminate
    rm -rf /tmp/fsim-setup-ipaddr
fi
