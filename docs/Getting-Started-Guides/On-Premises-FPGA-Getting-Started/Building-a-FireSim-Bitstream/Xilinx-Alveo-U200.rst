.. |fpga_name| replace:: Xilinx Alveo U200
.. |hwdb_entry_name| replace:: ``alveo_u200_firesim_rocket_singlecore_no_nic``
.. |hwdb_entry_name_non_code| replace:: alveo_u200_firesim_rocket_singlecore_no_nic
.. |builder_name| replace:: Xilinx Vivado
.. |bit_builder_path| replace:: ``bit-builder-recipes/xilinx_alveo_u200.yaml``
.. |vivado_with_version| replace:: Vivado 2021.1
.. |vivado_version_number_only| replace:: 2021.1
.. |vivado_default_install_path| replace:: ``/tools/Xilinx/Vivado/2021.1``
.. |board_package_install| replace:: Download the ``au200`` board support package directory from https://github.com/Xilinx/open-nic-shell/tree/main/board_files/Xilinx and place the directory in ``/tools/Xilinx/Vivado/2021.1/data/xhub/boards/XilinxBoardStore/boards/Xilinx/``.

Building Your Own Hardware Designs
===================================================================

This section will guide you through building a |fpga_name| FPGA bitstream to run FireSim simulations.

.. include:: Xilinx-XDMA-Build-Farm-Setup-Template.rst

.. include:: Xilinx-All-Bitstream-Template.rst
