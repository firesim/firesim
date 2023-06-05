|fpga_name| Getting Started
=======================================

The tutorials that follow this page will guide you through the complete flow for
getting an example FireSim simulation up and running using an on-premises |fpga_name| FPGA.
This tutorial is setting up a single node on-premises cluster (i.e. running FPGA bitstream builds and simulations on a single machine) for FireSim use.
This single machine will serve as the "Manager Machine" that acts as a "head" node that all work will be completed on.
At the end of this
tutorial, you'll have a simulation that simulates a single quad-core Rocket
Chip-based node with a 4 MB last level cache, 16 GB DDR3, and no NIC.
The final tutorial
will show you how to build your own FPGA images with customized hardware.
After you complete these tutorials, you can look at the "Advanced Docs"
in the sidebar to the left.

Here's a high-level outline of what we'll be doing in our tutorials:
