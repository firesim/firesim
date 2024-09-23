FireAxe Overview
================

Since the initial release of FireSim, the proliferation of open hardware IP has enabled
researchers to generate new SoC configurations that no longer fit in a single FPGA.
FireAxe builds on top of FireSim to enable *partitioning of large designs onto multiple
FPGAs*, overcoming the single-FPGA limitation for monolithic RTL designs.

Partition Modes
===============

FireAxe provides users with three options (modes) to perform partitioning: exact-mode,
fast-mode, and the NoC-partition-mode. These options are passed on to the compiler which
will generate the correct circuitry to deal with the odd things that happen on the
partition interface. A more detailed explanation about the circuitry and the partition
modes are provided in `the FireAxe paper
<https://joonho3020.github.io/assets/ISCA2024-FireAxe.pdf>`_. However, if you just want
to use FireAxe, the below explanations are sufficient for you to get started.

Exact-Mode
----------

In the exact-mode, users can choose the modules to partition out and place on separate
FPGAs. The partitioned simulation will behave *exactly* the same as when running the
target on a software RTL simulator. This is useful when the partition boundary is not
latency insensitive (i.e. the interface is not ready-valid interface nor credit-based)
and when the boundary contains combinational logic running through it.

An example of a module that can be partitioned out using the exact-mode is a RoCC
accelerator since the ports to access the page-table-walker boundaries are
combinationally dependent to each other.

Fast-Mode
---------

When the partition boundary is latency insensitive (i.e., the interface is ready-valid
or credit based), you can use the fast-mode to perform partitioned simulations. Similar
to exact-mode, users can choose the modules to partition out. However, fast-mode
provides higher simulation throughput by trading off simulation accuracy with
performance. By injecting a single cycle of latency on the partition boundary, simulated
design will run nearly 2x faster than the exact-mode.

Example modules that can be partitioned out are core tiles as the tile to bus
connections are ready-valid and the interrupt signals are also latency insensitive. In
practice, adding a cycle of latency on the partition boundary has nearly zero accuracy
implications.

NoC-Partition-Mode
------------------

In the NoC partition mode, we exploit the fact that the NoC router boundaries are
latency insensitive (credit based). Users simply has to specify the router nodes to be
grouped together and the compiler will automatically group the modules to partition out
with the selected routers. The NoC-partition-mode works only when partitioning tiles
out.

Supported Platforms
===================

Like all FireSim simulations, FireAxe can be run on both F1 and local FPGAs.

EC2 F1
------

To improve simulation performance on AWS EC2 F1 cloud FPGAs, we utilize their direct
peer-to-peer inter-FPGA PCIe communication mechanism to reduce token exchange latency
`AWS PCIe Peer to Peer Guides
<https://github.com/awslabs/aws-fpga-app-notes/tree/master/Using-PCIe-Peer2Peer>`_. The
f1.16xlarge and f1.4xlarge instances each contain multiple FPGAs (8 or 2 respectively)
that can send and receive AXI4 transactions directly to/from one another without going
through the host. This provides the simulator up to 1MHz of simulation throughput.

Local FPGAs w/ QSFP Cables
--------------------------

For on-premises FPGAs, we achieve an even lower link latency by utilizing cheap,
off-the-shelf `QSFP direct-attach-cables <https://www.10gtek.com/qsfp28dac>`_ and
integrating IP for the `Aurora <https://docs.amd.com/v/u/en-US/aurora_64b66b_ds528>`_
protocol into the FPGA shell. This exposes an AXI4-Stream interface to FireAxe. This
ultra-low-latency interconnect enabled us to achieve a simulation throughput of 2MHz.
