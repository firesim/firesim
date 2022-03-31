#!/bin/bash

set -ex
set -o pipefail

echo "machine launch script started" > /home/centos/machine-launchstatus
sudo chgrp centos /home/centos/machine-launchstatus
sudo chown centos /home/centos/machine-launchstatus

{
sudo yum install -y ca-certificates
sudo yum install -y mosh
sudo yum groupinstall -y "Development tools"
sudo yum install -y gmp-devel mpfr-devel libmpc-devel zlib-devel vim git java java-devel
curl https://www.scala-sbt.org/sbt-rpm.repo | sudo tee /etc/yum.repos.d/scala-sbt-rpm.repo
sudo yum install -y sbt texinfo gengetopt libffi-devel
sudo yum install -y expat-devel libusb1-devel ncurses-devel cmake "perl(ExtUtils::MakeMaker)"
# deps for poky
sudo yum install -y python36 patch diffstat texi2html texinfo subversion chrpath git wget
# deps for qemu
sudo yum install -y gtk3-devel
# deps for firesim-software (note that rsync is installed but too old)
sudo yum install -y python36-pip python36-devel rsync
# Install GNU make 4.x (needed to cross-compile glibc 2.28+)
sudo yum install -y centos-release-scl
sudo yum install -y devtoolset-8-make

# install DTC
sudo yum -y install dtc

# get a proper version of git
sudo yum -y remove git
sudo yum -y install epel-release
sudo yum -y install https://repo.ius.io/ius-release-el7.rpm
sudo yum -y install git224

# install verilator
git clone http://git.veripool.org/git/verilator
cd verilator/
git checkout v4.034
autoconf && ./configure && make -j4 && sudo make install
cd ..

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
sudo python3 -m pip install sure==2.0.0
# needed for the awstools cmdline parsing
sudo python3 -m pip install pyyaml==5.4.1

# setup argcomplete
activate-global-python-argcomplete

} 2>&1 | tee /home/centos/machine-launchstatus.log

# get a regular prompt
echo "PS1='\u@\H:\w\\$ '" >> /home/centos/.bashrc
echo "machine launch script completed" >> /home/centos/machine-launchstatus
