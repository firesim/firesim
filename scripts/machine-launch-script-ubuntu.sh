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
sudo apt-get -y install bash-completion

# graphviz for manager
sudo apt-get -y install graphviz python-dev

# used for CI
sudo apt-get -y install expect

sudo apt-get -y install python-pip

# pip2 installations are done on a per-user basis
# In the event it is (as on an older AMI), upgrade it just in case
pip2 install --user --upgrade pip==20.3.4
# these need to match what's in deploy/requirements.txt
pip2 install --user fabric==1.14.0
pip2 install --user boto3==1.6.2
pip2 install --user colorama==0.3.7
pip2 install --user argcomplete==1.9.3
pip2 install --user graphviz==0.8.3
# for some of our workload plotting scripts
pip2 install --user --upgrade --ignore-installed pyparsing
pip2 install --user numpy==1.16.6
pip2 install --user kiwisolver==1.1.0
pip2 install --user matplotlib==2.2.2
pip2 install --user pandas==0.22.0
# use awscli that works with our versions of boto3
pip2 install --user awscli==1.15.76
# pip2 should install pytest 4.6.X as it's the last py2 release. see:
# https://pytest.org/en/latest/py27-py34-deprecation.html#what-this-means-for-general-users
pip2 install --user pytest
# moto 1.3.1 is newest version that will work with boto3 1.6.2
pip2 install --user moto==1.3.1
# needed for the awstools cmdline parsing
pip2 install --user pyyaml

sudo apt-get -y install python-argcomplete python3-argcomplete
sudo activate-global-python-argcomplete

# Upgrading pip2 clobbers the pip3 installation paths.
sudo apt-get -y install --reinstall python3-pip

} 2>&1 | tee $HOME/machine-launchstatus.log

echo "machine launch script completed" >> $HOME/machine-launchstatus
