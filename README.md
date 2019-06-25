FireMarshal (firesim-software)
Version 1.6
==================================

This tool builds base images for several linux-based distros that work with qemu,
spike, and firesim. 

This is just a quick primer. To see full documentation, please see the official
firesim documentation. Find pre-built documentation for the latest FireSim here:
https://docs.fires.im/en/latest/Advanced-Usage/FireMarshal/index.html

You can also find the latest FireSim source at:
https://github.com/firesim/firesim

# Requirements
The easiest way to use Marshal is to run it via firesim on Amazon EC2 by
following the instructions at https://docs.fires.im/en/latest/. However, this
is not required. To run Firemarshal independently, you will need the following
dependencies:

## Standard Packages
centos-requirements.txt is a list of packages for centos7 that are needed by
marshal. You can install these with:
```
cat centos-requirements.txt | sudo xargs yum install -y
```

Package names may be different on other distributions.

### Note for Ubuntu
The libguestfs-tools package (needed for the guestmount command) does not work
out of the box on Ubuntu. See
https://github.com/firesim/firesim-software/issues/30 for a workaround.

## Python
This project was written for python 3.6. You can install all dependencies using:
```
pip3 install python-requirements.txt
```

## riscv-tools
In addition to standard libraries, you will need riscv-tools
(https://github.com/firesim/riscv-tools.git). This was last tested with commit
bce7b5e (gcc version 7.2).
