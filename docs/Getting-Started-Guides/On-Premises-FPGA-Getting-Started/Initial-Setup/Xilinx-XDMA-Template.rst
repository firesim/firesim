FPGA Setup
==========

The following installation steps are FPGA-specific and should be run on all **run farm
machines** that install an FPGA. You might need ``sudo`` access to setup the FPGA.

1. Poweroff your machine.
2. Insert your |fpga_name|_ FPGA |fpga_attach_prereq|
3. Attach any additional power cables between the FPGA and the host machine.
   |fpga_power_info|
4. Attach the USB cable between the FPGA and the host machine for |jtag_help|
5. Boot the machine.
6. Obtain an existing bitstream tar file for your FPGA by opening the ``bitstream_tar``
   URL listed under |hwdb_entry_name| in the following file:
   ``${CY_DIR}/sims/firesim-staging/sample_config_hwdb.yaml``.
7. Download/extract the ``.tar.gz`` file to a known location. |mcs_info|
8. Open Vivado Lab and click "Open Hardware Manager". Then click "Open Target" and "Auto
   connect".
9. Right-click on your FPGA and click "Add Configuration Memory Device". For a
   |fpga_name|_, choose |fpga_spi_part_number| as the Configuration Memory Part. Click
   "OK" when prompted to program the configuration memory device.
10. For Configuration file, choose the ``firesim.mcs`` |extra_mcs|
11. Uncheck "Verify" and click OK.
12. Right-click on your FPGA and click "Boot from Configuration Memory Device".
13. When programming the configuration memory device is completed, power off your
    machine fully (i.e., the FPGA should completely lose |dip_switch_extra|)
14. Cold-boot the machine. A cold boot is required for the FPGA to be successfully
    re-programmed from its flash.
15. Once the machine has booted, run the following to ensure that your FPGA is set up
    properly:

.. code-block:: bash

    lspci -vvv -d 10ee:903f

If successful, this should show an entry with Xilinx as the manufacturer and two memory
regions. There should be one entry for each FPGA you've added to the Run Farm Machine.

.. note::

    |jtag_cable_reminder|
