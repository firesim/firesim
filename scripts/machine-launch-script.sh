#!/bin/bash
echo "machine launch script started" > /home/centos/machine-launchstatus
sudo yum install -y mosh
sudo yum groupinstall -y "Development tools"
sudo yum install -y gmp-devel mpfr-devel libmpc-devel zlib-devel vim git java java-devel
curl https://bintray.com/sbt/rpm/rpm | sudo tee /etc/yum.repos.d/bintray-sbt-rpm.repo
sudo yum install -y sbt texinfo gengetopt
sudo yum install -y expat-devel libusb1-devel ncurses-devel cmake "perl(ExtUtils::MakeMaker)"
# deps for poky
sudo yum install -y python34 patch diffstat texi2html texinfo subversion chrpath git wget
# deps for qemu
sudo yum install -y gtk3-devel
# install DTC. it's not available in repos in FPGA AMI
DTCversion=dtc-1.4.4
wget https://git.kernel.org/pub/scm/utils/dtc/dtc.git/snapshot/$DTCversion.tar.gz
tar -xvf $DTCversion.tar.gz
cd $DTCversion
make -j16
make install
cd ..
rm -rf $DTCversion.tar.gz
rm -rf $DTCversion

# get a proper version of git
sudo yum -y remove git
sudo yum -y install epel-release
sudo yum -y install https://centos7.iuscommunity.org/ius-release.rpm
sudo yum -y install git2u

# install verilator
git clone http://git.veripool.org/git/verilator
cd verilator/
git checkout v4.002
autoconf && ./configure && make -j16 && sudo make install
cd ..

# bash completion for manager
sudo yum -y install bash-completion

# graphviz for manager
sudo yum -y install graphviz python-devel

# these need to match what's in deploy/requirements.txt
sudo pip install fabric==1.14.0
sudo pip install boto3==1.6.2
sudo pip install colorama==0.3.7
sudo pip install argcomplete==1.9.3
sudo pip install graphviz==0.8.3
# for some of our workload plotting scripts
sudo pip install matplotlib==2.2.2
sudo pip install pandas==0.22.0

sudo activate-global-python-argcomplete

# get a regular prompt
echo "PS1='\u@\H:\w\\$ '" >> /home/centos/.bashrc
echo "machine launch script completed" >> /home/centos/machine-launchstatus
