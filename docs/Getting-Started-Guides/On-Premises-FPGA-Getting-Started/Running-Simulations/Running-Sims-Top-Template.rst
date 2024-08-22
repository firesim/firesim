Running a Single Node Simulation
================================

Now that we've completed all the basic setup steps, it's time to run a simulation! In
this section, we will simulate a single target node, for which we will use a single
|fpga_type|.

**Make sure you have sourced** ``sourceme-manager.sh --skip-ssh-setup`` **before running
any of these commands.**

Building target software
------------------------

In this guide, we'll boot Linux on our simulated node. To do so, we'll need to build our
RISC-V SoC-compatible Linux distro. For this guide, we will use a simple buildroot-based
distribution. We can build the Linux distribution like so:

.. code-block:: bash

    # assumes you already cd'd into your firesim repo
    # and sourced sourceme-manager.sh
    #
    # then:
    cd ${CY_DIR}/software/firemarshal
    ./marshal -v build br-base.json
    ./marshal -v install br-base.json

Once this is completed, you'll have the following files:

- ``${CY_DIR}/software/firemarshal/images/firechip/br-base/br-base-bin`` - a bootloader
  + Linux kernel image for the RISC-V SoC we will simulate.
- ``${CY_DIR}/software/firemarshal/images/firechip/br-base/br-base.img`` - a disk image
  for the RISC-V SoC we will simulate

These files will be used to form base images to either build more complicated workloads
(see the :ref:`deprecated-defining-custom-workloads` section) or directly as a basic,
interactive Linux distribution.
