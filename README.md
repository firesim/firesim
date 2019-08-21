FireMarshal (firesim-software)
==================================

This tool builds base images for several linux-based distros that work with qemu,
spike, and firesim. 

This is just a quick primer. To see full documentation, please see the official
firesim documentation. Find pre-built documentation for the latest FireSim here:
https://docs.fires.im/en/latest/Advanced-Usage/FireMarshal/index.html

You can also find the latest FireSim source at:
https://github.com/firesim/firesim

## Requirements
This project was written for python 3.4

python-requirements.txt and centos-requirements.txt are incomplete lists of
required packages for python3. If you find that you need a package not in those
lists, please file an issue.

# Basic Usage
On a fresh clone, run:
    ./marshal init

You can now build workloads. For example:

    ./marshal build workloads/br-base.json

To run in qemu:

    ./marshal launch workloads/br-base.json

To install into FireSim (assuming you cloned this as a submodule of firesim):

    ./marshal install workloads/br-base.json
