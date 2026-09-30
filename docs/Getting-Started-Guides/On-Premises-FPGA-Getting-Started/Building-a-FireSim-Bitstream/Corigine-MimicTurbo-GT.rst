.. |fpga_name| replace:: Corigine MimicTurbo GT

.. |hwdb_entry_name| replace:: ``corigine_mimicturbo_gt_firesim_rocket_singlecore_4GB_no_nic``

.. |hwdb_entry_name_non_code| replace:: corigine_mimicturbo_gt_firesim_rocket_singlecore_4GB_no_nic

.. |builder_name| replace:: Xilinx Vivado

.. |bit_builder_path| replace:: ``bit-builder-recipes/corigine_mimicturbo_gt.yaml``

.. |vivado_with_version| replace:: Vivado 2025.1

.. |vivado_version_number_only| replace:: 2025.1

.. |vivado_default_install_path| replace:: ``/tools/Xilinx/2025.1/Vivado``

.. |vivado_settings64_path| replace:: /tools/Xilinx/2025.1/Vivado/settings64.sh

.. |board_package_install| replace:: No special board support package is required for the
    MimicTurbo GT. Move on to the next step.

Building Your Own Hardware Designs
==================================

This section will guide you through building a |fpga_name| FPGA bitstream to run FireSim
simulations.

.. warning::

    The MimicTurbo GT has 8 GiB of host DRAM, so a target design can request at most 8 GiB
    of DRAM in total. Most FireChip target configs request 16 GiB (e.g.,
    ``FireSimRocketConfig``) and fail in Golden Gate with ``Total requested DRAM ...
    exceeds host capacity``. Use a smaller variant such as
    ``FireSimRocket4GiBDRAMConfig`` (as |hwdb_entry_name| does), or set ``WithExtMemSize``
    in your target config.

.. include:: Xilinx-XDMA-Build-Farm-Setup-Template.rst

.. include:: Xilinx-All-Bitstream-Template.rst
