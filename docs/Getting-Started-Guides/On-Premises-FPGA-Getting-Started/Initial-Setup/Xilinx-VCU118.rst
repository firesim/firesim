.. |fpga_name| replace:: Xilinx VCU118
.. _fpga_name: https://www.xilinx.com/products/boards-and-kits/vcu118.html
.. |fpga_power_info| replace:: For the VCU118, this is usually ATX 4-pin peripheral power (**NOT** PCIe power) from the system's PSU, attached to the FPGA via the "ATX Power Supply Adapter Cable" that comes with the VCU118.
.. |hwdb_entry_name| replace:: ``xilinx_vcu118_firesim_rocket_singlecore_4GB_no_nic``
.. |platform_name| replace:: xilinx_vcu118
.. |board_name| replace:: vcu118
.. |tool_type| replace:: Xilinx Vivado
.. |tool_type_lab| replace:: Xilinx Vivado Lab
.. |example_var| replace:: ``XILINX_VIVADO``
.. |deploy_manager_code| replace:: ``XilinxVCU118InstanceDeployManager``
.. |fpga_spi_part_number| replace:: ``mt25qu01g-spi-x1_x2_x4_x8``
.. |mcs_info| replace:: Inside, you will find four files; the ones we are currently interested in will be called ``firesim.mcs`` and ``firesim_secondary.mcs``. Note the full path of the ``firesim.mcs`` and ``firesim_secondary.mcs`` files for the next step.
.. |fpga_attach_prereq| replace:: into an open PCIe slot in the machine. Also, ensure that the SW16 switches on the board are set to ``0101`` to enable QSPI flashing over JTAG (i.e., ``position 1 = 0``, ``position 2 = 1``, ``position 3 = 0``, and ``position 4 = 1``. Having the switch set to the side of the position label indicates 0.)
.. |jtag_help| replace:: JTAG.
.. |extra_mcs| replace:: file from step 7 and for Configuration file 2, choose the ``firesim_secondary.mcs`` file from step 7.
.. |dip_switch_extra| replace:: power). Then, set the SW16 switches on the board to ``0001`` to set the board to automatically program the FPGA from the QSPI at boot (i.e., ``position 1 = 0``, ``position 2 = 0``, ``position 3 = 0``, and ``position 4 = 1``. Having the switch set to the side of the position label indicates 0.)
.. |nitefury_patch_xdma| replace:: The directory you are now in contains the XDMA kernel module. Now, let's build and install it:
.. |jtag_cable_reminder| replace:: If necessary, you can remove the USB cable for JTAG (the FPGA is programmed through PCIe for FireSim simulations on the Xilinx VCU118). However, we still recommend leaving the cable attached, since it will allow you to re-flash the SPI in case there are issues.

.. include:: Xilinx-XDMA-Template.rst
