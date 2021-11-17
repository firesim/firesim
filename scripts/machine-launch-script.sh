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
sudo yum -y install graphviz

# used for CI
sudo yum -y install expect

# upgrade pip
pip3 install --user --upgrade pip
# install requirements
python3 -m pip install --user fab-classic
python3 -m pip install --user boto3
python3 -m pip install --user colorama
python3 -m pip install --user argcomplete
python3 -m pip install --user graphviz
# for some of our workload plotting scripts
python3 -m pip install --user --upgrade --ignore-installed pyparsing
python3 -m pip install --user numpy
python3 -m pip install --user kiwisolver
python3 -m pip install --user matplotlib
python3 -m pip install --user pandas
python3 -m pip install --user awscli
python3 -m pip install --user pytest
python3 -m pip install --user moto
# needed for the awstools cmdline parsing
python3 -m pip install --user pyyaml

# setup argcomplete
activate-global-python-argcomplete --user
echo "for bcfile in /home/centos/.bash_completion.d/* ; do" >> /home/centos/.bash_completion
echo "  [ -f "$bcfile" ] && . $bcfile" >> /home/centos/.bash_completion
echo "done" >> /home/centos/.bash_completion

} 2>&1 | tee /home/centos/machine-launchstatus.log

# get a regular prompt
echo "PS1='\u@\H:\w\\$ '" >> /home/centos/.bashrc
echo "machine launch script completed" >> /home/centos/machine-launchstatus
