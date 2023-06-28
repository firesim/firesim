|fpga_name| Getting Started Guide
=======================================

This getting started guide will guide you through the complete flow
for getting an example FireSim simulation up and running using an on-premises
|fpga_name| FPGA.

First, we will set up a single machine to run all
FireSim-related tasks, or a full cluster to handle different tasks separately.

In general, there are three "types" of machines in a FireSim cluster: Manager
machines (where you'll write code and run most commands), Build Farm machines
(where you'll run Vivado to build bitstreams), and Run Farm machines (where
you have FPGAs attached and run simulations).

In a single-machine setup (where you edit code, run Vivado to build bitstreams, and
have an FPGA-attached, all on one machine), your machine will serve as **all three** of
these machine types.

Once we set up our machine or cluster, we will then walk through running a FireSim
simulation of a RISC-V SoC on your FPGA, booting Linux.

Finally, we will walk you through building your own FPGA
bitstreams with customized hardware. After you complete this guide, you
can look at the "Advanced Docs" in the sidebar to the left to learn more.

Here's a high-level outline of what we'll be doing in this guide:
