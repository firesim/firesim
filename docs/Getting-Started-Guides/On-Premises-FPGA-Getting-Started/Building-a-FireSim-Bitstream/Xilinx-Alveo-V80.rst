.. |fpga_name| replace:: Xilinx Alveo V80

.. |hwdb_entry_name| replace:: ``xilinx_alveo_v80_firesim_rocket_singlecore_no_nic``

.. |hwdb_entry_name_non_code| replace:: xilinx_alveo_v80_firesim_rocket_singlecore_no_nic

.. |builder_name| replace:: Xilinx Vivado

.. |bit_builder_path| replace:: ``bit-builder-recipes/xilinx_alveo_v80.yaml``

.. |vivado_with_version| replace:: Vivado 2025.1

.. |vivado_version_number_only| replace:: 2025.1

.. |vivado_default_install_path| replace:: ``/tools/Xilinx/2025.1/Vivado``

.. |vivado_settings64_path| replace:: /tools/Xilinx/2025.1/Vivado/settings64.sh

.. |board_package_install| replace:: No separate board support package is required for
    the V80; its board files ship with Vivado 2025.1. Move on to the next step.

Building Your Own Hardware Designs
==================================

This section will guide you through building a |fpga_name| FPGA bitstream to run FireSim
simulations. For the V80, the build produces a Versal device image (``firesim.pdi``)
instead of a ``.bit`` file.

.. include:: Xilinx-XDMA-Build-Farm-Setup-Template.rst

.. include:: Xilinx-All-Bitstream-Template.rst
