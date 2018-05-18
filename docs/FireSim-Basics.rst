FireSim Basics
===================================

FireSim is a cycle-accurate, FPGA-accelerated scale-out computer system
simulation platform developed in the Berkeley Architecture Research Group in
the EECS Department at the University of California, Berkeley.

FireSim is capable of simulating from **one to thousands of multi-core compute
nodes**, derived from **silicon-proven** and **open** target-RTL, with an optional
cycle-accurate network simulation tying them together. FireSim runs on FPGAs in **public
cloud** environments like AWS EC2 F1, removing the high capex traditionally
involved in large-scale FPGA-based simulation.

FireSim is useful both for datacenter architecture research as well as running
many single-node architectural experiments in parallel on FPGAs. By harnessing
a standardized host platform and providing a large amount of
automation/tooling, FireSim drastically simplifies the process of building and
deploying large-scale FPGA-based hardware simulations.

To learn more, see the `FireSim website <https://fires.im>`__ and the FireSim
`ISCA 2018 paper <https://sagark.org/assets/pubs/firesim-isca2018.pdf>`__.

Two common use cases:
--------------------------

Single-Node Simulation, in Parallel
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

In this mode, FireSim allows for simulation of individual Rocket
Chip-based nodes without a network, which allows individual simulations to run
at ~150 MHz. The FireSim manager has the ability to automatically distribute
jobs to many parallel simulations, expediting the process of running large
workloads like SPEC. For example, users can run all of SPECInt2017 on Rocket Chip
in ~1 day by running the 10 separate workloads in parallel on 10 FPGAs.

Datacenter/Cluster Simulation
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

In this mode, FireSim also models a cycle-accurate network with
parameterizeable bandwidth and link latency, as well as configurable
topology, to accurately model current and future datacenter-scale
systems. For example, FireSim has been used to simulate 1024 quad-core
Rocket Chip-based nodes, interconnected by a 200 Gbps, 2us network.

Background/Terminology
---------------------------

.. figure:: img/firesim_env.png
   :alt: FireSim Infrastructure Setup

   FireSim Infrastructure Diagram

-  **FireSim Manager**: This program (located in ``firesim/deploy/``,
   called ``firesim``) automates the work required to launch FPGA builds
   and run simulations. Most users will only have to interact with the
   manager most of the time.
-  **Manager Instance**: This is the machine on AWS that you will
   SSH-into. It only needs to be powerful enough to run Chisel
   builds/target software builds.
-  **Build Farm**: These are instances that are elastically
   started/terminated by the FireSim manager when you run FPGA builds.
   The manager will automatically ship builds to these instances and run
   the Verilog -> FPGA Image process on them.
-  **Run Farm**: These are F1 (and M4) instances that the manager
   automatically launches and deploys simulations onto.

To disambiguate between the computers being simulated and the computers doing the simulating, we define:

-  **Target**: The design and environment under simulation. Generally, a
   network of one or more multi-core RISC-V microprocessors.
-  **Host**: The computers executing the FireSim simulation -- the Run Farm.

We frequently prefix words with these terms. For example, software can run
on the simulated RISCV system (*target*-software) or on a host x86 machine (*host*-software).

Using FireSim/The FireSim Workflow
-------------------------------------

The tutorials that follow this page will guide you through the complete flow for
getting an example FireSim simulation up and running. At the end of this
tutorial, you'll have a simulation that simulates a single quad-core Rocket
Chip-based node with a 4 MB last level cache, 16 GB DDR3, and no NIC. After this, you'll
have the option to continue on to a tutorial that describes how to simulate
many of these single-node simulations in parallel or how to simulate
a globally-cycle-accurate cluster-scale FireSim simulation. The final tutorial
will show you how to build your own FPGA images with customized hardware. 

Here's a high-level outline of what we'll be doing:

1. Initial Setup/Installation

    a. First-time AWS User Setup
       You can skip this if you already have an AWS account/payment method
       set up.
    b. Configuring required AWS resources in your account: 
       This sets up the appropriate VPCs/subnets/security groups required to
       run FireSim.
    c. Setting up a "Manager Instance" from which you will coordinate
       building and deploying simulations.

2. Single-node simulation tutorial: This tutorial guides you through the process of running one simulation on a single ``f1.2xlarge``, using our pre-built public FireSim AGFIs.

    a. Launching instances for an FPGA "Run Farm" consisting of ``f1.2xlarge`` instances.
    b. Deploying simulations on your "Run Farm" once you have an AFI/AGFI.

3. Cluster simulation tutorial: This tutorial guides you through the process of running 16 networked simulations on 2 ``f1.16xlarge``\s and one ``m4.16xlarge``, using our pre-built public FireSim AGFIs.

    a. Launching instances for an FPGA "Run Farm" consisting of
       ``f1.16xlarge`` and ``m4.16xlarge`` instances.
    b. Deploying simulations on your "Run Farm" once you have an AFI/AGFI.

4. Building your own hardware designs tutorial (Chisel -> FPGA Image)

    a. Building a FireSim AFI: Running a build process that goes from Chisel -> Verilog and then
       Verilog -> AFI/AGFI (Amazon's FPGA Image). This process automatically creates "Build Farm" instances,
       runs builds on them, and terminates them once the AGFIs has been generated.
       All Vivado reports/outputs are copied onto your Manager
       Instance before Build Farm instances are terminated.

Generally speaking, you only need to follow step 4 if you're modifying
Chisel RTL or changing non-runtime configurable hardware parameters.

Now, hit next to proceed with setup.
