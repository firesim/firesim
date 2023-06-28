AWS EC2 F1 Getting Started Guide
=====================================

The getting started guides that follow this page will guide you through the complete flow for
getting an example FireSim simulation up and running using AWS EC2 F1. At the end of this
guide, you'll have a simulation that simulates a single quad-core Rocket
Chip-based node with a 4 MB last level cache, 16 GB DDR3, and no NIC. After
this, you can continue to a guide that shows you how to simulate
a globally-cycle-accurate cluster-scale FireSim simulation. The final guide
will show you how to build your own FPGA images with customized hardware.
After you complete these guides, you can look at the "Advanced Docs"
in the sidebar to the left.

Here's a high-level outline of what we'll be doing in our AWS EC2 F1 getting started guides:

#. **Initial Setup/Installation**

   a. Background/Terminology: We will discuss some key terminology that will be used in
      the rest of the guides.

   #. First-time AWS User Setup: You can skip this if you already have an AWS
      account/payment method set up.

   #. Configuring required AWS resources in your account: This sets up the
      appropriate VPCs/subnets/security groups required to run FireSim.

   #. Setting up a "Manager Instance" from which you will coordinate building
      and deploying simulations.

#. **Single-node simulation guide**: This guide walks you through the process of running one simulation on a Run Farm consisting of a single ``f1.2xlarge``, using our pre-built public FireSim AGFIs.

#. **Cluster simulation guide**: This guide walks you through the process of running an 8-node cluster simulation on a Run Farm consisting of one ``f1.16xlarge``, using our pre-built public FireSim AGFIs and switch models.

#. **Building your own hardware designs guide (Chisel to FPGA Image)**: This guide walks you through the full process of taking Rocket Chip RTL and any custom RTL plugged into Rocket Chip and producing a FireSim AGFI to plug into your simulations. This automatically runs Chisel elaboration, FAME-1 Transformation, and the Vivado FPGA flow.

Generally speaking, you only need to follow step 4 if you're modifying Chisel
RTL or changing non-runtime configurable hardware parameters.

.. toctree::
   :maxdepth: 2

   Initial-Setup/index
   Running-Simulations/index
   Building-a-FireSim-AFI
