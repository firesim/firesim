.. |fpga_type| replace:: Xilinx Vitis-enabled U250
.. |deploy_manager| replace:: VitisInstanceDeployManager
.. |runner| replace:: Xilinx XRT/Vitis
.. |hwdb_entry| replace:: vitis_firesim_rocket_singlecore_no_nic
.. |quintuplet| replace:: FireSim-FireSimRocketConfig-BaseVitisConfig

.. include:: Running-Single-Node-Simulation-Template.rst

.. warning:: Currently, FireSim simulations with bridges that use the  PCI-E DMA interface are not supported (i.e. TracerV, NIC, Dromajo, Printfs).
	This will be added in a future FireSim release.

.. warning:: In some cases, simulation may fail because you might need to update the |fpga_type| DRAM offset that is currently hard coded in both the FireSim Vitis/XRT driver code and platform shim.
	To verify this, run ``xclbinutil --info --input <YOURXCLBIN>``, obtain the ``bank0`` ``MEM_DDR4`` offset. If it differs from the hardcoded ``0x40000000`` given in
	driver code (``u250_dram_expected_offset`` variable in ``sim/midas/src/main/cc/simif_vitis.cc``) and platform shim (``araddr``/``awaddr`` offset in
	``sim/midas/src/main/scala/midas/platform/VitisShim.scala``) replace both areas with the new offset given by ``xclbinutil`` and regenerate the bitstream.
