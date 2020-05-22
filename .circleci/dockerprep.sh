#!/bin/bash

set -e

pwd
# expect firesim to be checked out for prep scripts.
ls -la /

ls -la /home/centos/

ls -la /home/centos/project/

# fixes to machine launch script to merge later:
sudo yum -y install dtc # TODO: replace in machine-launch script, now available in epel
# TODO: fix machine launch script, manually install the right numpy BEFORE matplotlib:
sudo pip2 install numpy==1.16.6
sudo pip2 install kiwisolver==1.1.0






