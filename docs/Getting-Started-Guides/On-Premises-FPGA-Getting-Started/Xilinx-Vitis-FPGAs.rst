.. |fpga_name| replace:: (Experimental) Xilinx Alveo U250 Vitis-based
.. _fpga_name: https://www.xilinx.com/products/boards-and-kits/alveo/u250.html
.. |bit_type| replace:: ``xclbin``
.. |build_type| replace:: Xilinx Vitis

.. warning:: ⚠️  **We highly recommend using the XDMA-based U250 flow instead of this
   Vitis-based flow. You can find the XDMA-based flow here:** :ref:`u250-standard-flow`.
   The Vitis-based flow does not support DMA-based FireSim bridges (e.g.,
   TracerV, Synthesizable Printfs, etc.), while the XDMA-based flows support
   all FireSim features. If you're unsure, use the XDMA-based U250 flow
   instead: :ref:`u250-standard-flow`

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
