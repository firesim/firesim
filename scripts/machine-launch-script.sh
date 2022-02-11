#!/bin/bash

LAUNCH_STATUS_PATH="/home/centos"

usage()
{
    echo "Usage: $0 [options]"
    echo
    echo "[-help]                  List this help"
    echo "[-launch-status-path]    Path to store machine-launchstatus logs. Default: $LAUNCH_STATUS_PATH/<HERE>"
}

while [ $# -gt 0 ]; do
    case "$1" in
        -help)
            usage
            exit 1
            ;;
        -launch-status-path)
            shift
            LAUNCH_STATUS_PATH=$1
            shift
            ;;
        *)
            echo "Invalid Argument: $1"
            usage
            exit 1
            ;;
    esac
done

set -ex
set -o pipefail

FLAVOR=`grep '^ID=' /etc/os-release | awk -F= '{print $2}' | tr -d '"'`
VERSION=`grep '^VERSION_ID=' /etc/os-release | awk -F= '{print $2}' | tr -d '"'`
MAJOR=${VERSION%.*}
ARCH=`uname -m`

if [ $FLAVOR != "ubuntu" ] || [ $FLAVOR != "centos" ]; then
    echo "Unknown OS flavor $FLAVOR"
    exit 1
fi

echo "machine launch script started" > $LAUNCH_STATUS_PATH/machine-launchstatus
if [ $(whoami) == "centos" ]; then
    sudo chgrp centos $LAUNCH_STATUS_PATH/machine-launchstatus
    sudo chown centos $LAUNCH_STATUS_PATH/machine-launchstatus
fi

{

if [ $FLAVOR == "centos" ]; then
    # setup before downloading normal packages
    sudo yum groupinstall -y "Development tools"
    curl https://www.scala-sbt.org/sbt-rpm.repo | sudo tee /etc/yum.repos.d/scala-sbt-rpm.repo
    sudo yum install -y epel-release
    sudo yum install -y https://repo.ius.io/ius-release-el7.rpm
    sudo yum install -y centos-release-scl

    sudo yum update -y

    RH_LIST=()

    # poky deps
    RH_LIST+=( python36 patch diffstat texi2html texinfo subversion chrpath git wget )
    # qemu deps
    RH_LIST+=( gtk3-devel )
    # firemarshal deps
    RH_LIST+=( python36-pip python36-devel rsync )
    # cross-compile glibc 2.28+ deps
    RH_LIST+=( devtoolset-8-make )
    # other misc deps
    RH_LIST+=(
        sbt \
        ca-certificates \
        mosh \
        gmp-devel \
        mpfr-devel \
        libmpc-devel \
        zlib-devel \
        vim \
        git  \
        java \
        java-devel \
        gengetopt \
        libffi-devel \
        expat-devel \
        libusb1-devel \
        ncurses-devel \
        cmake \
        "perl(ExtUtils::MakeMaker)" \
        dtc \
        graphviz \
        expect
    )

    sudo yum install -y "${RH_LIST[@]}"

    sudo yum remove -y git
    sudo yum install -y git224
elif [ $FLAVOR == "ubuntu" ]; then
    # setup before downloading normal packages
    echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | sudo tee -a /etc/apt/sources.list.d/sbt.list
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | sudo apt-key add
    sudo add-apt-repository ppa:git-core/ppa -y

    sudo apt-get update -y

    UB_LIST=()

    # poky deps
    UB_LIST+=( python3.8 patch diffstat texi2html texinfo subversion chrpath git wget )
    # qemu deps
    UB_LIST+=( libgtk-3-dev )
    # firemarshal deps
    UB_LIST+=( python3-pip python3.8-dev rsync )
    # cross-compile glibc 2.28+ deps
    RH_LIST+=( make )
    # misc deps
    UB_LIST=( \
        sbt \
        ca-certificates \
        mosh \
        libgmp-dev \
        libmpfr-dev \
        libmpc-dev \
        zlib1g-dev \
        vim \
        default-jdk \
        default-jre \
        texinfo \
        gengetopt \
        libffi-dev \
        libexpat1-dev \
        libusb-dev \
        libncurses5-dev \
        cmake \
        libextutils-makemaker-cpanfile-perl \
        device-tree-compiler \
        bash-completion \
        graphviz \
        expect \
    )

    sudo apt install -y "${UB_LIST[@]}"
fi

# install verilator
git clone http://git.veripool.org/git/verilator
cd verilator/
git checkout v4.034
autoconf && ./configure && make -j4 && sudo make install
cd ..

# upgrade pip
sudo pip3 install --upgrade pip==21.3.1
# install requirements
sudo python3 -m pip install fab-classic==1.19.1
sudo python3 -m pip install boto3==1.20.21
sudo python3 -m pip install colorama==0.4.3
sudo python3 -m pip install argcomplete==1.12.3
sudo python3 -m pip install graphviz==0.19
# for some of our workload plotting scripts
sudo python3 -m pip install pyparsing==3.0.6
sudo python3 -m pip install numpy==1.19.5
sudo python3 -m pip install kiwisolver==1.3.1
sudo python3 -m pip install matplotlib==3.3.4
sudo python3 -m pip install pandas==1.1.5
sudo python3 -m pip install awscli==1.22.21
sudo python3 -m pip install pytest==6.2.5
sudo python3 -m pip install moto==2.2.17
# needed for the awstools cmdline parsing
sudo python3 -m pip install pyyaml==5.4.1

# setup argcomplete
activate-global-python-argcomplete

} 2>&1 | tee $LAUNCH_STATUS_PATH/machine-launchstatus.log

if [ $FLAVOR == "ubuntu" ]; then
    # default /bin/sh to bash (not dash)
    sudo dpkg-reconfigure -p critical dash
fi

echo "machine launch script completed" >> $LAUNCH_STATUS_PATH/machine-launchstatus
