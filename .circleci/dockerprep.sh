#!/bin/bash

set -e

# expect firesim to be checked out for prep scripts.
ls -la /

ls -la /root/

ls -la /root/project/

yum -y install sudo epel-release
yum -y install python-pip # aws fpga dev ami comes with this: pip2

# you will be root, so:
adduser centos
usermod -aG wheel centos

# let the new acct run sudo without password
echo "centos ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

# fixes to machine launch script to merge later:
sudo yum -y install dtc # TODO: replace in machine-launch script, now available in epel
# TODO: fix machine launch script, manually install the right numpy BEFORE matplotlib:
sudo pip2 install numpy==1.16.6
sudo pip2 install kiwisolver==1.1.0


