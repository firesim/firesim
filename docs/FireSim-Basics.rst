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
has also been used in the development of commercially-available silicon. FireSim
was originally developed in the Electrical Engineering and Computer Sciences
Department at the University of California, Berkeley, but
now has industrial and academic contributors from all over the world.

This documentation will walk you through getting started with using FireSim on
your platform and also serves as a reference for more advanced FireSim features. For higher-level
technical discussion about FireSim, see the `FireSim website <https://fires.im>`__.


Common FireSim usage models
---------------------------------------

Below are three common usage models for FireSim. The first two are the most common, while the
third model is primarily for those interested in warehouse-scale computer research. The getting
started guides on this documentation site will cover all three models.

1. Single-Node Simulations Using One or More On-Premises FPGAs
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

In this usage model, FireSim allows for simulation of targets consisting of
individual SoC designs (e.g., those produced by `Chipyard <https://chipyard.readthedocs.io/>`__)
at 150+ MHz running on on-premises
FPGAs, such as those attached to your local desktop, laptop, or cluster. Just
like on the cloud, the FireSim manager can automatically distribute and manage
jobs on one or more on-premises FPGAs, including running complex workloads like
SPECInt2017 with full reference inputs.

2. Single-Node Simulations Using Cloud FPGAs
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

This usage model is similar to the previous on-premises case, but instead
deploys simulations on FPGAs attached to cloud instances, rather than requiring
users to obtain and set-up on-premises FPGAs. This allows for dynamically
scaling the number of FPGAs in-use to match workload requirements. For example,
on AWS EC2 F1, it is just as cost effective to run the 10 workloads in SPECInt2017 in parallel
on 10 cloud FPGAs vs. running them serially on one cloud FPGA.

.. note::
    All automation in FireSim works in both the on-premises and cloud
    usage models, which enables a **hybrid usage model** where early development happens
    on one (or a small cluster of) on-premises FPGA(s), while bursting to a large
    number of cloud FPGAs when a high degree of parallelism is necessary.

3. Datacenter/Cluster Simulations on On-Premises or Cloud FPGAs
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

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

Choose your platform to get started
--------------------------------------

FireSim supports many types of FPGAs and FPGA platforms! Click one of the following links to work through the getting started guide for your particular platform.

* :doc:`/Getting-Started-Guides/AWS-EC2-F1-Getting-Started/index`

  * Status: ✅ All FireSim Features Supported.

* :doc:`/Getting-Started-Guides/On-Premises-FPGA-Getting-Started/Xilinx-Alveo-U200-FPGAs`

  * Status: ✅ All FireSim Features Supported.

* :doc:`/Getting-Started-Guides/On-Premises-FPGA-Getting-Started/Xilinx-Alveo-U250-FPGAs`

  * Status: ✅ All FireSim Features Supported.

* :doc:`/Getting-Started-Guides/On-Premises-FPGA-Getting-Started/Xilinx-Alveo-U280-FPGAs`

  * Status: ✅ All FireSim Features Supported.

* :doc:`/Getting-Started-Guides/On-Premises-FPGA-Getting-Started/Xilinx-VCU118-FPGAs`

  * Status: ✅ All FireSim Features Supported.

* :doc:`/Getting-Started-Guides/On-Premises-FPGA-Getting-Started/RHS-Research-Nitefury-II-FPGAs`

  * Status: ✅ All FireSim Features Supported.

* :doc:`Getting-Started-Guides/On-Premises-FPGA-Getting-Started/Xilinx-Vitis-FPGAs`

  * Status: ⚠️  DMA-based Bridges Not Supported. The Vitis-based U250 flow is **not recommended** unless you have specific constraints that require using Vitis. Notably, the Vitis-based flow does not support DMA-based FireSim bridges (e.g., TracerV, Synthesizable Printfs, etc.), while the XDMA-based flows support all FireSim features, as shown above. If you're unsure, use the XDMA-based U250 flow instead: :doc:`/Getting-Started-Guides/On-Premises-FPGA-Getting-Started/Xilinx-Alveo-U250-FPGAs`.
