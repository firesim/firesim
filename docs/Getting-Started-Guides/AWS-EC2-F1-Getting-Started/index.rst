.. _aws-f1-getting-started-guide:

AWS EC2 F1 Getting Started Guide
================================

The getting started guides that follow this page will guide you through the complete
flow for getting an example Chipyard-based SoC FireSim simulation up and running using
AWS EC2 F1. At the end of this guide, you'll have a simulation that simulates a single
quad-core Rocket Chip-based node with a 4 MB last level cache, 16 GB DDR3, and no NIC.
After this, you can continue to a guide that shows you how to simulate a
globally-cycle-accurate cluster-scale FireSim simulation. The final guide will show you
how to build your own FPGA images with customized hardware. After you complete these
guides, you can look at the "Advanced Docs" in the sidebar to the left.

Make sure you have run/done the steps listed in :ref:`initial-aws-setup` before running
this guide.

Here's a high-level outline of what we'll be doing in our AWS EC2 F1 getting started
guides:

1. **Setting up the FireSim repo**: Cloning the repository needed for this guide.
2. **Single-node simulation guide**: This guide walks you through the process of running
   one simulation on a Run Farm consisting of a single ``f1.2xlarge``, using Chipyard's
   pre-built public AGFIs.
3. **Cluster simulation guide**: This guide walks you through the process of running an
   8-node cluster simulation on a Run Farm consisting of one ``f1.16xlarge``, using
   Chipyard's pre-built public AGFIs and switch models.
4. **Building your own hardware designs guide (Chisel to FPGA Image)**: This guide walks
   you through the full process of taking Rocket Chip RTL and any custom RTL plugged
   into Rocket Chip and producing a FireSim AGFI to plug into your simulations. This
   automatically runs Chisel elaboration, FAME-1 Transformation, and the Vivado FPGA
   flow.

Generally speaking, you only need to follow step 4 if you're modifying Chisel RTL or
changing non-runtime configurable hardware parameters.

.. note::

    This section uses ${CY_DIR} and ${FS_DIR} to refer to the Chipyard and FireSim
    directories. These are set when sourcing the Chipyard and FireSim environments.

.. toctree::
    :maxdepth: 2

    Setting-Up-The-FireSim-Repo
    Running-Simulations/index
    Building-a-FireSim-AFI
