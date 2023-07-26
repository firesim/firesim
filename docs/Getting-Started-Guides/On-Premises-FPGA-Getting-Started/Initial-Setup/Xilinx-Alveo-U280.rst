.. |fpga_name| replace:: Xilinx Alveo U280
.. _fpga_name: https://www.xilinx.com/products/boards-and-kits/alveo/u280.html
.. |fpga_power_info| replace:: For the U280, this is usually PCIe power coming directly from the system's PSU.
.. |hwdb_entry_name| replace:: ``alveo_u280_firesim_rocket_singlecore_no_nic``
.. |platform_name| replace:: xilinx_alveo_u280
.. |board_name| replace:: au280
.. |tool_type| replace:: Xilinx Vivado
.. |tool_type_lab| replace:: Xilinx Vivado Lab
.. |example_var| replace:: ``XILINX_VIVADO``
.. |deploy_manager_code| replace:: ``XilinxAlveoU280InstanceDeployManager``
.. |fpga_spi_part_number| replace:: ``mt25qu01g-spi-x1_x2_x4``
.. |fpga_attach_prereq| replace:: into an open PCIe slot in the machine.
.. |jtag_help| replace:: JTAG.
.. |extra_mcs| replace:: file from step 7.
.. |mcs_info| replace:: Inside, you will find three files; the one we are currently interested in will be called ``firesim.mcs``. Note the full path of this ``firesim.mcs`` file for the next step.
.. |dip_switch_extra| replace:: power).
.. |nitefury_patch_xdma| replace:: The directory you are now in contains the XDMA kernel module. Now, let's build and install it:
.. |jtag_cable_reminder| replace:: Remember to keep the USB cable for JTAG connected at all times when running FireSim simulations (it is used to program the FPGA).

.. include:: Xilinx-XDMA-Template.rst
