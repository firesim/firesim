FPGA Software Setup
===================

System requirements and Setup
------------------------------

The below sections outline what you need to install to run FireSim on each
machine type in a FireSim cluster. Note that the below three machine types
can all map to a single machine in your setup; in this case, you should follow
all the installation instructions on your single machine.

We highly recommend using Ubuntu 20.04 LTS as the host operating system for
all machine types in an on-premises setup, as this is the OS recommended by
Xilinx. 


Manager Machine
--------------------

The manager machine requires no special setup at this stage. We will clone
the FireSim repo and set up dependencies for the manager in a later step.

Run Farm Machine(s)
----------------------

To set up your Run Farm Machines, please do the following:

First, please refer to the 
`minimum system requirements on the Xilinx website <https://docs.xilinx.com/r/en-US/ug1301-getting-started-guide-alveo-accelerator-cards/Minimum-System-Requirements>`_
to ensure that your intended Run Farm machine is sufficient for hosting a |fpga_name|.






Build Farm Machines(s)
-------------------------

If you are not planning to run bitstream builds, you can skip this section
for now and return later.




Next, install the U250 FPGA as indicated: https://docs.xilinx.com/r/en-US/ug1301-getting-started-guide-alveo-accelerator-cards/Card-Installation-Procedures

We require the following programs/packages installed from the Xilinx website in addition to a physical U250 installation:

* Vivado 2021.1 or 2022.2

* U250 board package (corresponding with Vivado 2021.1 or 2022.2)

  * Ensure you complete the "Installing the Deployment Software" and "Card Bring-Up and Validation" sections in the following link: https://docs.xilinx.com/r/en-US/ug1301-getting-started-guide-alveo-accelerator-cards/Installing-the-Deployment-Software

  * Ensure that the board package is installed to a Vivado accessible location: https://support.xilinx.com/s/article/The-board-file-location-with-the-latest-Vivado-tools?language=en_US

Importantly, using this FPGA with FireSim requires that you have ``sudo`` **passwordless** access to the machine with the FPGA.
This is needed to flash the FPGA bitstream onto the FPGA.

XDMA Setup
----------

To communicate with the FPGA over PCI-e, we need to install the Xilinx XDMA kernel module.
First, lets install the XDMA kernel module into a FireSim-known location:

.. code-block:: bash

   cd /tmp # or any location you prefer
   git clone https://github.com/Xilinx/dma_ip_drivers
   cd dma_ip_drivers
   git checkout 2022.1.5
   cd XDMA/linux-kernel/xdma
   sudo make clean && sudo make && sudo make install

Next, lets add the kernel module:

.. code-block:: bash

   # the module should be installed in the following location
   # by the `make install` previously run
   sudo insmod /lib/modules/$(uname -r)/extra/xdma.ko poll_mode=1

By default, FireSim will refer to this location to check if the XDMA driver is loaded.
Verify that you can see the XDMA module with:

.. code-block:: bash

   lsmod | grep -i xdma

.. warning:: After the machine is rebooted, you may need to re-insert the XDMA kernel module.

Now you're ready to continue with other FireSim setup!
