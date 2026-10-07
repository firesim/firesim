.. |fpga_name| replace:: Corigine MimicTurbo GT

.. _fpga_name: https://www.corigine.com/Newsdetail-464.html

.. |fpga_power_info| replace:: For the MimicTurbo GT, this is ATX 4-pin peripheral power
    (**NOT** PCIe power) from the system's PSU, attached to connector J5 on the FPGA via
    the ATX power supply adapter cable that comes with the MimicTurbo GT. Do not plug a
    PC ATX power connector directly into J5; this can damage the board.

.. |hwdb_entry_name| replace:: ``corigine_mimicturbo_gt_firesim_rocket_singlecore_4GB_no_nic``

.. |platform_name| replace:: corigine_mimicturbo_gt

.. |board_name| replace:: mimicturbo_gt

.. |tool_type| replace:: Xilinx Vivado

.. |tool_type_lab| replace:: Xilinx Vivado Lab

.. |example_var| replace:: ``XILINX_VIVADO``

.. |deploy_manager_code| replace:: ``CorigineMimicTurboGTInstanceDeployManager``

.. |fpga_spi_part_number| replace:: ``mt25qu02g-spi-x1_x2_x4``

.. |fpga_attach_prereq| replace:: into an open x16 PCIe slot in the machine. The
    MimicTurbo GT's cooling fan is taller than a standard PCIe card, so it needs three
    adjacent slots: plug it into the middle one and leave the slots on either side free.

.. |jtag_help| replace:: JTAG (micro-B connector J2 on the FPGA).

.. |extra_mcs| replace:: file from step 7. Unlike Xilinx Alveo cards, the MimicTurbo GT's
    flash has no factory fallback ("golden") image, so this replaces the only image in
    the flash.

.. |mcs_info| replace:: Inside, you will find three files; the one we are currently
    interested in will be called ``firesim.mcs``. Note the full path of this
    ``firesim.mcs`` file for the next step.

.. |dip_switch_extra| replace:: power).

.. |nitefury_patch_xdma| replace:: The directory you are now in contains the XDMA kernel
    module. Now, let's build and install it:

.. |jtag_cable_reminder| replace:: Remember to keep the USB cable for JTAG connected at
    all times when running FireSim simulations (it is used to program the FPGA).

.. include:: Xilinx-XDMA-Template.rst
