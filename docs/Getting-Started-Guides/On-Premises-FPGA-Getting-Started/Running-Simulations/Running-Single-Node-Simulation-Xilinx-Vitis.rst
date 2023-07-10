.. |fpga_type| replace:: Xilinx Vitis-enabled U250
.. |deploy_manager| replace:: VitisInstanceDeployManager
.. |deploy_manager_code| replace:: ``VitisInstanceDeployManager``
.. |runner| replace:: Xilinx XRT/Vitis
.. |hwdb_entry_name| replace:: vitis_firesim_rocket_singlecore_no_nic

.. warning:: ⚠️  **We highly recommend using the XDMA-based U250 flow instead of this
   Vitis-based flow. You can find the XDMA-based flow here:** :ref:`u250-standard-flow`.
   The Vitis-based flow does not support DMA-based FireSim bridges (e.g.,
   TracerV, Synthesizable Printfs, etc.), while the XDMA-based flows support
   all FireSim features. If you're unsure, use the XDMA-based U250 flow
   instead: :ref:`u250-standard-flow`

.. include:: Running-Single-Node-Simulation-Vitis-Template.rst

.. warning:: In some cases, simulation may fail because you might need to update the |fpga_type| DRAM offset that is currently hard coded in both the FireSim |runner| driver code and platform shim.
	To verify this, run ``xclbinutil --info --input <YOUR_XCL_BIN>``, obtain the ``bank0`` ``MEM_DDR4`` offset.
	If it differs from the hardcoded ``0x40000000`` given in driver code (``u250_dram_expected_offset`` variable in :gh-file-ref:`sim/midas/src/main/cc/simif_vitis.cc`) and
	platform shim (``araddr``/``awaddr`` offset in :gh-file-ref:`sim/midas/src/main/scala/midas/platform/VitisShim.scala`) replace both areas with the new offset given by
        ``xclbinutil`` and regenerate the bitstream.
