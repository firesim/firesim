.. _firesim-basics:

FireSim Basics
===================================

FireSim is an open-source
FPGA-accelerated full-system hardware simulation platform that makes
it easy to validate, profile, and debug RTL hardware implementations
at 10s to 100s of MHz. FireSim simplifies co-simulating 
ASIC RTL with cycle-accurate hardware and software models for other system components (e.g. I/Os). FireSim can productively 
scale from individual SoC simulations hosted on on-prem FPGAs (e.g., a single Xilinx Alveo board attached to a desktop) 
to massive datacenter-scale simulations harnessing hundreds of cloud FPGAs (e.g., on Amazon EC2 F1).

FireSim users across academia and industry (at 20+ institutions) have published
over 40 papers using FireSim in many areas, including computer architecture,
systems, networking, security, scientific computing, circuits, design
automation, and more (see the `Publications page <https://fires.im/publications>`__ on
the FireSim website to learn more). FireSim
has also been used in the development of shipping commercial silicon. FireSim
was originally developed in the Electrical Engineering and Computer Sciences
Department at the University of California, Berkeley, but
now has industrial and academic contributors from all over the world.

This documentation will walk you through getting started with using FireSim and
serves as a reference for more advanced FireSim features. For higher-level
technical discussion about FireSim, see the `FireSim website <https://fires.im>`__.


Three common FireSim usage models
---------------------------------------

Below are three common usage models for FireSim. The first two are the most common, while the
third model is primarily for those interested in warehouse-scale computer research. The getting
started guides on this documentation site will cover all three models.

Single-Node Simulations Using One or More On-Premises FPGAs
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

In this usage model, FireSim allows for simulation of targets consisting of
individual SoC designs (e.g., those produced by `Chipyard <https://chipyard.readthedocs.io/>`__)
at 150+ MHz running on on-premises
FPGAs, such as those attached to your local desktop, laptop, or cluster. Just
like on the cloud, the FireSim manager can automatically distribute and manage
jobs on one or more on-premises FPGAs, including running complex workloads like
SPECInt2017 with full reference inputs.

Single-Node Simulations Using Cloud FPGAs
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

This usage model is similar to the previous on-premises case, but instead
deploys simulations on FPGAs attached to cloud instances, rather than requiring
users to obtain and set-up on-premises FPGAs. This allows for dynamically
scaling the number of FPGAs in-use to match workload requirements. For example,
it is just as cost effective to run the 10 workloads in SPECInt2017 in parallel
on 10 cloud FPGAs vs. running them serially on one cloud FPGA.

All automation in FireSim works in both the on-premises and cloud
usage models, which enables a **hybrid usage model** where early development happens
on one (or a small cluster of) on-premises FPGA(s), while bursting to a large
number of cloud FPGAs when a high-degree of parallelism is necessary.

Datacenter/Cluster Simulations on On-Premises or Cloud FPGAs
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

In this mode, FireSim also models a cycle-accurate network with
parameterizeable bandwidth, link latency, and configurable
topology to accurately model current and future datacenter-scale
systems. For example, FireSim has been used to simulate 1024 quad-core
RISC-V Rocket Chip-based nodes, interconnected by a 200 Gbps, 2us Ethernet network. To learn
more about this use case, see our `ISCA 2018 paper
<https://sagark.org/assets/pubs/firesim-isca2018.pdf>`__.


Other Use Cases
---------------------

If you have other use-cases that we haven't covered or don't fit into the above
buckets, feel free to contact us!


Background/Terminology
---------------------------

Before we jump into setting up FireSim, it is important to clarify several terms
that we will use throughout the rest of this documentation.

First, to disambiguate between the hardware being simulated and the computers doing
the simulating, we define:

**Target**
  The design and environment being simulated. Commonly, a
  group of one or more RISC-V SoCs with or without a network between them.

**Host**
  The computers/FPGAs executing the FireSim simulation -- the **Run Farm** below.

We frequently prefix words with these terms. For example, software can run
on the simulated RISC-V system (*target*-software) or on a host x86 machine (*host*-software).


.. figure:: img/firesim_env.png
   :alt: FireSim Infrastructure Setup

   FireSim Infrastructure Diagram

**FireSim Manager** (``firesim``)
  This program (available on your path as ``firesim``
  once we source necessary scripts) automates the work required to launch FPGA
  builds and run simulations. Most users will only have to interact with the
  manager most of the time. If you're familiar with tools like Vagrant or Docker, the ``firesim``
  command is just like the ``vagrant`` and ``docker`` commands, but for FPGA simulators
  instead of VMs/containers.


Machines used to build and run FireSim simulations are broadly classified into
three groups:

**Manager Machine/Instance**
  This is the main host machine (e.g., your local desktop or an AWS EC2
  instance) that you will "do work" on. This is where you'll clone your copy of
  FireSim and use the FireSim Manager to deploy builds/simulations from.

**Build Farm Machines/Instances**
  These are local machines ("build farm machines") or cloud instances ("build
  farm instances") that are used by the FireSim manager to run FPGA bitstream
  builds. The manager will automatically ship all sources necessary to run
  builds to these machines and will run the Verilog to FPGA bitstream build
  process on them.

**Run Farm Machines/Instances**
  These are a collection of local machines ("run farm machines") or cloud
  instances ("run farm instances") with FPGAs attached that the manager manages
  and deploys simulations onto. You can use multiple Run Farms in parallel to
  run multiple separate simulations in parallel.


In the simplest setup, a single host machine (e.g. your desktop) can serve
the function of all three of these: as the manager machine, the build farm
machine (assuming Vivado is installed), and the run farm machine (assuming
an FPGA is attached).

One final piece of terminology will also be referenced throughout these
docs:

**Golden Gate (MIDAS II)**
  The FIRRTL compiler used by FireSim to convert target RTL into a decoupled
  simulator. Formerly named MIDAS.


Choose your platform to get started
--------------------------------------

FireSim supports many types of FPGAs and FPGA platforms! Click one of the following links to work through the getting started guide for your particular platform.

* :doc:`/Getting-Started-Guides/AWS-EC2-F1-Getting-Started/index`

* :doc:`/Getting-Started-Guides/On-Premises-FPGA-Getting-Started/Xilinx-Alveo-U250-FPGAs`

* :doc:`/Getting-Started-Guides/On-Premises-FPGA-Getting-Started/Xilinx-Alveo-U280-FPGAs`

* :doc:`/Getting-Started-Guides/On-Premises-FPGA-Getting-Started/Xilinx-VCU118-FPGAs`

* :doc:`/Getting-Started-Guides/On-Premises-FPGA-Getting-Started/RHS-Research-Nitefury-II-FPGAs`

* (Not recommended) :doc:`Getting-Started-Guides/On-Premises-FPGA-Getting-Started/Xilinx-Vitis-FPGAs`. The Vitis flow is not recommended unless you have specific constraints that require using Vitis. Use the aforementioned :doc:`/Getting-Started-Guides/On-Premises-FPGA-Getting-Started/Xilinx-Alveo-U250-FPGAs` instead.

