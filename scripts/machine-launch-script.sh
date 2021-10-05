#!/bin/bash

set -ex
set -o pipefail

echo "machine launch script started" > /home/centos/machine-launchstatus

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

# these need to match what's in deploy/requirements.txt
# last working pip version before deprecation
sudo pip2 install --upgrade pip==20.3.4
sudo pip2 install fabric==1.14.0
sudo pip2 install boto3==1.6.2
sudo pip2 install colorama==0.3.7
sudo pip2 install argcomplete==1.9.3
sudo pip2 install graphviz==0.8.3
# for some of our workload plotting scripts
sudo pip2 install --upgrade --ignore-installed pyparsing
sudo pip2 install numpy==1.16.6
sudo pip2 install kiwisolver==1.1.0
sudo pip2 install matplotlib==2.2.2
sudo pip2 install pandas==0.22.0
# new awscli on 1.6.0 AMI is broken with our versions of boto3
sudo pip2 install awscli==1.15.76
# pip2 should install pytest 4.6.X as it's the last py2 release. see:
# https://pytest.org/en/latest/py27-py34-deprecation.html#what-this-means-for-general-users
sudo pip2 install pytest
# moto 1.3.1 is newest version that will work with boto3 1.6.2
sudo pip2 install moto==1.3.1

sudo activate-global-python-argcomplete

# Upgrading pip2 clobbers the pip3 installation paths.
sudo yum reinstall -y python36-pip

} 2>&1 | tee /home/centos/machine-launchstatus.log

# get a regular prompt
echo "PS1='\u@\H:\w\\$ '" >> /home/centos/.bashrc
echo "machine launch script completed" >> /home/centos/machine-launchstatus
