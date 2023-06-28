.. |fpga_name| replace:: Xilinx Vitis-enabled U250
.. |bit_type| replace:: ``xclbin``
.. |build_type| replace:: Xilinx Vitis

.. include:: Intro-Template.rst

#. **FPGA Setup**: Installing the FPGA board and relevant software.

#. **On-Premises Machine Setup**

   #. Setting up a "Manager Machine" from which you will coordinate building
      and deploying simulations locally.

#. **Single-node simulation guide**: This guide walks you through the
   process of running one simulation locally consisting of a single
   |fpga_name|, using our pre-built public FireSim |bit_type| bitstream.

#. **Building your own hardware designs guide (Chisel to FPGA Image)**:
   This guide walks you through the full process of taking Rocket Chip RTL
   and any custom RTL plugged into Rocket Chip and producing a FireSim bitstream
   to plug into your simulations. This automatically runs Chisel elaboration,
   FAME-1 Transformation, and the |build_type| FPGA flow.

Generally speaking, you only need to follow Step 4 if you're modifying Chisel
RTL or changing non-runtime configurable hardware parameters.

.. toctree::
   :maxdepth: 3

   Initial-Setup/Xilinx-Vitis-FPGAs
   Running-Simulations/Running-Single-Node-Simulation-Xilinx-Vitis
   Building-a-FireSim-Bitstream/Xilinx-Vitis
