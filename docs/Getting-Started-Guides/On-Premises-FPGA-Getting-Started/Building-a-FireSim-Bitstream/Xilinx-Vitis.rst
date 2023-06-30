.. |fpga_name| replace:: Xilinx Vitis-enabled U250
.. |hwdb_entry_name| replace:: ``vitis_firesim_rocket_singlecore_no_nic``
.. |hwdb_entry_name_non_code| replace:: vitis_firesim_rocket_singlecore_no_nic
.. |builder_name| replace:: Xilinx Vitis
.. |bit_builder_path| replace:: ``bit-builder-recipes/vitis.yaml``

.. warning:: ⚠️  **We highly recommend using the XDMA-based U250 flow instead of this
   Vitis-based flow. You can find the XDMA-based flow here:** :ref:`u250-standard-flow`.
   The Vitis-based flow does not support DMA-based FireSim bridges (e.g.,
   TracerV, Synthesizable Printfs, etc.), while the XDMA-based flows support
   all FireSim features. If you're unsure, use the XDMA-based U250 flow
   instead: :ref:`u250-standard-flow`

Building Your Own Hardware Designs
===================================================================

This section will guide you through building a |fpga_name| FPGA bitstream to run FireSim simulations.

.. include:: Xilinx-All-Bitstream-Template.rst

