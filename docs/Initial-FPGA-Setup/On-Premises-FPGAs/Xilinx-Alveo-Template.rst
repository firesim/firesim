Software Installation and Validation
-----------------------------------------------------------

Requirements
~~~~~~~~~~~~

We require a base machine that is able to support the |fpga_name| and running Xilinx Vivado.
Please refer to the minimum system requirements given in the following link: https://docs.xilinx.com/r/en-US/ug1301-getting-started-guide-alveo-accelerator-cards/Minimum-System-Requirements.
Next, install the U250 FPGA as indicated: https://docs.xilinx.com/r/en-US/ug1301-getting-started-guide-alveo-accelerator-cards/Card-Installation-Procedures

We require the following programs/packages installed from the Xilinx website in addition to a physical U250 installation:

* Vivado 2021.1

* U250 board package (corresponding with Vivado 2021.1)

  * Ensure you complete the "Installing the Deployment Software" and "Card Bring-Up and Validation" sections in the following link: https://docs.xilinx.com/r/en-US/ug1301-getting-started-guide-alveo-accelerator-cards/Installing-the-Deployment-Software

  * Ensure that the board package is installed to a Vivado accessible location: https://support.xilinx.com/s/article/The-board-file-location-with-the-latest-Vivado-tools?language=en_US

Importantly, using this FPGA with FireSim requires that you have ``sudo`` passwordless access to the machine with the FPGA.
This is needed to flash the FPGA bitstream onto the FPGA.

FPGA Setup
----------------

After installing the |fpga_name| using the Xilinx instructions and installing the specific version of Vivado, we need to flash the |fpga_name| with a dummy XDMA-enabled design to finish setup.
First, lets install the XDMA kernel module in a FireSim known location:

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

Next, let's determine the BDF's (unique ID) of the/any FPGA you want to use with FireSim.

.. code-block:: bash

   # determine BDF of FPGA that you want to use / re-flash
   lspci | grep -i xilinx

   # example output of a 2 U250 FPGA system:
   # 04:00.0 Processing accelerators: Xilinx Corporation Device 5004
   # 04:00.1 Processing accelerators: Xilinx Corporation Device 5005
   # 83:00.0 Processing accelerators: Xilinx Corporation Device 5004
   # 83:00.1 Processing accelerators: Xilinx Corporation Device 5005

   # BDF would be 04:00.0 if you want to flash the '04' FPGA
   # the extended BDF would be 0000: + the BDF from before (i.e. 0000:04:00.0)
   # note: that you BDF to use is the one ending in .0

Keep note of the *extended BDF* of the FPGA you would like to setup.
Next, let's flash each |fpga_name| that you would like to use with the dummy bitstream.
To obtain the sample bitstream, let's find the URL to download the file to the machine with the FPGA.
Below find the HWDB entry called "|hwdb_entry_name|".

.. literalinclude:: /../deploy/sample-backup-configs/sample_config_hwdb.yaml
   :language: yaml
   :start-after: DOCREF START: Xilinx Alveo HWDB Entries
   :end-before: DOCREF END: Xilinx Alveo HWDB Entries

Look for the ``bit_tar: <URL>`` line within "|hwdb_entry_name|" and keep note of the URL.
Next, we will do the following for each FPGA that will be used with FireSim.

#. Create a temporary flashing area that we will delete after flashing the FPGA.
#. Download the bitstream file.
#. Download a temporary FireSim repository to have access to the flashing scripts.
#. Flash the FPGA (with the extended BDF obtained) and the bitstream file.
#. Delete the temporary flashing area.

.. code-block:: bash
   :substitutions:

   mkdir /tmp/tempdownload
   cd /tmp/tempdownload
   wget <BIT_TAR URL SAVED FROM PREVIOUSLY>
   tar xvf firesim.tar.gz
   cd |platform_name|

   git clone --branch |overall_version| https://github.com/firesim/firesim
   EXTENDED_DEVICE_BDF1=<YOUR BDF HERE> ./platforms/|platform_name|/scripts/program_fpga.sh ./firesim.bit |board_name|

Next, **warm reboot** the computer.
This will reconfigure your PCI-E settings such that FireSim can detect the XDMA-enabled bitstream.
After the machine is rebooted, you may need to re-insert the XDMA kernel module.
Then verify that you can see the XDMA module with:

.. code-block:: bash

   cat /proc/devices | grep xdma

Also, verify that the FPGA programming worked by seeing if the ``lspci`` output has changed.
For example, we should see ``Serial controller`` for BDF's that were flashed.


.. code-block:: bash

   lspci | grep -i xilinx

   # example output if only the 0000:04:00.0 FPGA was programmed
   04:00.0 Serial controller: Xilinx Corporation Device 903f (rev ff)
   83:00.0 Processing accelerators: Xilinx Corporation Device 5004
   83:00.1 Processing accelerators: Xilinx Corporation Device 5005

.. Warning:: Anytime the host computer is rebooted you may need to re-run parts of the setup process (i.e. re-insert XDMA kernel module).
     Before continuing to FireSim simulations after a host computer reboot, ensure that the previously mentioned ``cat /proc/devices | grep xdma`` command is successful.
     Also ensure that you see ``Serial controller`` for the BDF of the FPGA you would like to use (otherwise, re-run this setup).

Now you're ready to continue with other FireSim setup!
