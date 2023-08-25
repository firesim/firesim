.. |fpga_name| replace:: RHS Research Nitefury II
.. _fpga_name: https://rhsresearch.com/collections/rhs-public/products/nitefury-xilinx-artix-fpga-kit-in-nvme-ssd-form-factor-2280-key-m
.. |fpga_power_info| replace:: This step is not required for the Nitefury, since all power is delivered via M.2. or Thunderbolt.
.. |hwdb_entry_name| replace:: ``nitefury_firesim_rocket_singlecore_no_nic``
.. |platform_name| replace:: rhsresearch_nitefury_ii
.. |board_name| replace:: nitefury_ii
.. |tool_type| replace:: Xilinx Vivado
.. |tool_type_lab| replace:: Xilinx Vivado Lab
.. |example_var| replace:: ``XILINX_VIVADO``
.. |deploy_manager_code| replace:: ``RHSResearchNitefuryIIInstanceDeployManager``
.. |fpga_spi_part_number| replace:: ``s25fl256xxxxxx0-spi-x1_x2_x4``
.. |fpga_attach_prereq| replace:: into either an open M.2. slot on your machine or into an M.2. to Thunderbolt enclosure (then attach the enclosure to your system via a Thunderbolt cable). We have successfully used this enclosure: https://www.amazon.com/ORICO-Enclosure-Compatible-Thunderbolt-Type-C-M2V01/dp/B08R9DMFFT. Before permanently installing your Nitefury into your M.2. slot or enclosure, ensure that you have attached the ribbon cable that will be used for JTAG to the underside of the board (see step 4 below).
.. |jtag_help| replace:: JTAG. For the Nitefury, this requires attaching the 14-pin JTAG adapter included with the board to the board using the included ribbon cable, then attaching a USB to JTAG adapter such as the Digilent HS2: https://digilent.com/shop/jtag-hs2-programming-cable/.
.. |extra_mcs| replace:: file from step 7.
.. |mcs_info| replace:: Inside, you will find three files; the one we are currently interested in will be called ``firesim.mcs``. Note the full path of this ``firesim.mcs`` file for the next step.
.. |dip_switch_extra| replace:: power).
.. |nitefury_patch_xdma| replace:: The directory you are now in contains the XDMA kernel module. For the Nitefury to work, we will need to make one modification to the driver. Find the line containing ``#define XDMA_ENGINE_XFER_MAX_DESC``. Change the value on this line from ``0x800`` to ``16``. Then, build and install the driver:
.. |jtag_cable_reminder| replace:: Remember to keep the USB cable for JTAG connected at all times when running FireSim simulations (it is used to program the FPGA).

.. include:: Xilinx-XDMA-Template.rst
