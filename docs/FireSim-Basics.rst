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
`ISCA 2018 paper <#comingsoon>`__.

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

Using FireSim/The FireSim Workflow
-------------------------------------

The following sequence of sections will guide you through the complete
flow for getting an example FireSim simulation up and running. At the end of
this guide, you'll have a simulation that simulates a small cluster (either
1 or 8 nodes) interconnected by a 200 Gbps Ethernet network. Here is a
general outline of what we'll be doing:

1. First-time AWS User Setup: `Starting from Scratch with
   AWS <#>`__.
   You can skip this if you already have an AWS account/payment method
   set up.
2. Configuring required AWS resources in your account: `Setting up resources in your AWS
   Account <#>`__.
   This sets up the appropriate VPCs/subnets/security groups required to
   run FireSim.
3. Setting up a "Manager Instance" from which you will coordinate
   building/deploying simulations. See the `Setting up your "Manager
   Instance" <#>`__
   section.
4. Running a build process that goes from Chisel -> Verilog and then
   Verilog -> AFI/AGFI (FPGA Image). See the `Building a FireSim AFI
   (FPGA
   Image) <#>`__
   section. This process automatically creates "Build Farm" instances,
   runs builds on them, and terminates them when AGFI completion is
   completed. All Vivado reports/outputs are copied onto your Manager
   Instance before they are terminated.
5. `Launching instances for an FPGA "Run
   Farm" <#>`__
   consisting of ``f1.2xlarge``, ``f1.16xlarge``, and ``m4.16xlarge``
   instances.
6. Deploying simulations on your "Run Farm" once you have an AFI/AGFI.
   See the `Deploying
   simulations <#>`__
   sections.

Generally speaking, you only need to follow step 4 if you're modifying
Chisel RTL or changing non-runtime configurable hardware parameters. If
someone has given you a prebuilt AFI/AGFI, you can skip the instructions
for step 4.
