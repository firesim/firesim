|fpga_name| Getting Started
=======================================

This getting started guide will guide you through the complete flow
for getting an example FireSim simulation up and running using an on-premises
|fpga_name| FPGA.

We will first set up an on-premises FireSim host cluster,
consisting of three types of machines: Manager machines (where you'll write code),
Build Farm machines (where you'll run Vivado), and Run Farm machines (where you'll
run FPGA simulations).

All three of these machine types can map to a single host, for example if
you plan to use one Desktop computer with an FPGA attached and Vivado installed
to run FPGA simulations, run bitstream builds, and do your work (e.g., edit code/RTL).

The first part of this getting started guide will guide you through setting up and running
a FireSim simulation on your FPGA, modeling a quad-core Rocket Chip-based
SoC with a 4 MB LLC and 1 to 16 GB of DDR3, depending on the host DRAM capacity
of your FPGA board.

The second part of this getting started guide will guide you through building your own FPGA
bitstreams with customized hardware. After you complete this guide, you
can look at the "Advanced Docs" in the sidebar to the left to learn more.

Here's a high-level outline of what we'll be doing in this guide:
