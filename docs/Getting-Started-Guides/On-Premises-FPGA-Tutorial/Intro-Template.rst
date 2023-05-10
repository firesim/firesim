|fpga_name| Getting Started
=======================================

The tutorials that follow this page will guide you through the complete flow for
getting an example FireSim simulation up and running using an on-premise |fpga_name| FPGA.
This tutorial is setting up a single node on-premise cluster (i.e. running FPGA bitstream builds and simulations on a single machine) for FireSim use.
This single machine will serve as the "Manager Machine" that acts as a "head" node that all work will be completed on.
At the end of this
tutorial, you'll have a simulation that simulates a single quad-core Rocket
Chip-based node with a 4 MB last level cache, 16 GB DDR3, and no NIC.
The final tutorial
will show you how to build your own FPGA images with customized hardware.
After you complete these tutorials, you can look at the "Advanced Docs"
in the sidebar to the left.

Here's a high-level outline of what we'll be doing in our tutorials:

#. **FPGA Setup**: Installing the FPGA board and relevant software.

#. **On-Premises Machine Setup**

   #. Setting up a "Manager Machine" from which you will coordinate building
      and deploying simulations locally.

#. **Single-node simulation tutorial**: This tutorial guides you through the
   process of running one simulation locally consisting of a single
   |fpga_name|, using our pre-built public FireSim |bit_type| bitstream.

#. **Building your own hardware designs tutorial (Chisel to FPGA Image)**:
   This tutorial guides you through the full process of taking Rocket Chip RTL
   and any custom RTL plugged into Rocket Chip and producing a FireSim bitstream
   to plug into your simulations. This automatically runs Chisel elaboration,
   FAME-1 Transformation, and the |build_type| FPGA flow.

Generally speaking, you only need to follow Step 4 if you're modifying Chisel
RTL or changing non-runtime configurable hardware parameters.
