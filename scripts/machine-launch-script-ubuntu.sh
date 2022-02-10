#!/bin/bash

set -ex
set -o pipefail

echo "machine launch script started" > $HOME/machine-launchstatus

{
sudo apt-get update
sudo apt-get install -y ca-certificates
sudo apt-get install -y mosh
sudo apt-get install -y libgmp-dev libmpfr-dev libmpc-dev zlib1g-dev vim default-jdk default-jre
# install sbt: https://www.scala-sbt.org/release/docs/Installing-sbt-on-Linux.html#Ubuntu+and+other+Debian-based+distributions
echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | sudo tee -a /etc/apt/sources.list.d/sbt.list
curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | sudo apt-key add
sudo apt-get update
sudo apt-get install -y sbt
sudo apt-get install -y texinfo gengetopt libffi-dev
sudo apt-get install -y libexpat1-dev libusb-dev libncurses5-dev cmake libextutils-makemaker-cpanfile-perl
# deps for poky
sudo apt-get install -y python3.8 patch diffstat texi2html texinfo subversion chrpath git wget
# deps for qemu
sudo apt-get install -y libgtk-3-dev
# deps for firesim-software (note that rsync is installed but too old)
sudo apt-get install -y python3-pip python3.8-dev rsync
# Install GNU make 4.x (needed to cross-compile glibc 2.28+)
sudo apt-get install -y make

# install DTC
sudo apt-get -y install device-tree-compiler

# install git >= 2.17
sudo add-apt-repository ppa:git-core/ppa -y
sudo apt-get update
sudo apt-get install git -y

# install verilator
if ! command -v verilator &> /dev/null
then
    git clone http://git.veripool.org/git/verilator
    cd verilator/
    git checkout v4.034
    autoconf && ./configure && make -j4 && sudo make install
    cd ..
fi

# bash completion for manager
sudo yum -y install bash-completion

# graphviz for manager
sudo yum -y install graphviz python-devel

# used for CI
sudo yum -y install expect

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

# default /bin/sh to bash (not dash)
sudo dpkg-reconfigure -p critical dash

} 2>&1 | tee $HOME/machine-launchstatus.log

echo "machine launch script completed" >> $HOME/machine-launchstatus
