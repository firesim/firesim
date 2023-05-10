FPGA Board Setup
===================

FPGA Setup
----------

.. warning:: Currently, FireSim only supports a single type of FPGA (i.e only |fpga_name| FPGAs) installed on a machine.
   This includes not mixing the use of Xilinx Vitis/XRT-enabled FPGAs on the system.

.. Warning:: Power-users can skip this setup and just create the database file listed below by hand if you want to target specific fpgas.

We need to flash the |fpga_name| FPGA(s) with a dummy XDMA-enabled design and determine the PCI-e ID (or BDF) associated with the serial number of the FPGA.
First, we need to flash the FPGA's with the dummy XDMA-enabled design so that the PCI-e subsystem can be initially configured.
Afterwards, we will generate the mapping from FPGA serial number to BDF.
We provide a a set of scripts to do this.

First lets obtain the sample bitstream, let's find the URL to download the file to the machine with the FPGA.
Below find the HWDB entry called |hwdb_entry_name|.

.. literalinclude:: /../deploy/sample-backup-configs/sample_config_hwdb.yaml
   :language: yaml
   :start-after: DOCREF START: Xilinx Alveo HWDB Entries
   :end-before: DOCREF END: Xilinx Alveo HWDB Entries

Look for the ``bitstream_tar: <URL>`` line within |hwdb_entry_name| and keep note of the URL.
We will replace the ``BITSTREAM_TAR`` bash variable below with that URL.

Next, lets flash all FPGAs in the system with the dummy bitstream.

.. code-block:: bash
   :substitutions:

   # enter the firesim directory checked out
   cd firesim

   cd platforms/|platform_name|/scripts

   vivado -mode tcl -source get_serial_dev_for_fpgas.tcl
   # get the UID/serial number's from this script

   BITSTREAM_TAR=<# replace me!>
   tar xvf $BITSTREAM_TAR
   ./program_fpga.py --serial_no $SERIAL_NO |platform_name|/*.bit

Next, **warm reboot** the computer.
This will reconfigure your PCI-E settings such that FireSim can detect the XDMA-enabled bitstream.
After the machine is rebooted, you may need to re-insert the XDMA kernel module.
Then verify that you can see the XDMA module with:

.. code-block:: bash

   lsmod | grep -i xdma

Also, verify that the FPGA programming worked by looking at the ``lspci`` output.
For example, we should see ``Serial controller`` for BDF's that were flashed.

.. code-block:: bash

   lspci | grep -i xilinx

   # example output
   04:00.0 Serial controller: Xilinx Corporation Device 903f (rev ff)
   83:00.0 Serial controller: Xilinx Corporation Device 903f (rev ff)

.. Warning:: Anytime the host computer is rebooted you may need to re-run parts of the setup process (i.e. re-insert XDMA kernel module).
     Before continuing to FireSim simulations after a host computer reboot, ensure that ``cat /proc/devices | grep xdma`` command is successful.
     Also ensure that you see ``Serial controller`` for the BDF of the FPGA you would like to use in ``lspci | grep -i xilinx`` (otherwise, re-run this setup).

Next, let's generate the mapping from FPGA serial numbers to the BDF.
Re-enter the FireSim repository and run the following commands to re-setup the repo after reboot.

.. code-block:: bash
   :substitutions:

   cd firesim

   # rerunning this since the machine rebooted
   source sourceme-f1-manager.sh --skip-ssh-setup

Next, open up the ``deploy/config_runtime.yaml`` file and replace the following keys to be the following:

* ``default_platform`` should be |deploy_manager_code|

* ``default_simulation_dir`` should point to a temporary simulation directory of your choice

* ``default_hw_config`` should be |hwdb_entry_name|

Then, run the following command to generate a mapping from a PCI-E BDF to FPGA UID/serial number.

.. code-block:: bash
   :substitutions:

   firesim enumeratefpgas

This will generate a database file in ``/opt/firesim-db.json`` that has this mapping.

Now you're ready to continue with other FireSim setup!
