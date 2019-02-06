Debugging & Testing with RTL Simulation
=======================================

Simulation of a single FireSim node using software RTL simulators like
Verilator, Synopsys VCS, or XSIM, is the most productive way to catch bugs
before generating an AGFI.

FireSim provides flows to do RTL simulation at three different levels of
the design/abstraction hierarchy. Ordered from least to most detailed, they are:

- **Target-Level**: This simulates just the RTL of the target-design (Rocket
  Chip). There are no host-level features being simulated. Supported
  simulators: VCS, Verilator.
- **MIDAS-Level**: This simulates the target-design after it's been transformed
  by MIDAS.  The target- and host-clock are decoupled. FPGA-hosted simulation
  models are present.  Abstract models for host-FPGA provided services, like
  DRAM, memory-mapped IO, and PCIS are used here. Supported simulators: VCS,
  Verilator.
- **FPGA-Level**: This is a complete simulation of the design that will passed
  to the FPGA tools, including clock-domain crossings, width adapters, PLLS,
  FPGA-periphery blocks like DRAM and PCI-E controllers. This leverages the
  simulation flow provided by AWS. Supported simulators: VCS, Vivado XSIM.


Generally, MIDAS-level simulations are only slightly slower than target-level
ones. Moving to FPGA-Level is very expensive. This illustrated in the chart
below.

====== ===== =======  ========= ============= ============= =======
Level  Waves VCS      Verilator Verilator -O1 Verilator -O2 XSIM
====== ===== =======  ========= ============= ============= =======
Target Off   4.8 kHz  3.9 kHz   6.6 kHz       N/A           N/A
Target On    0.8 kHz  3.0 kHz   5.1 kHz       N/A           N/A
MIDAS  Off   3.8 kHz  2.4 kHz   4.5 kHz       5.3 KHz       N/A
MIDAS  On    2.9 kHz  1.5 kHz   2.7 kHz       3.4 KHz       N/A
FPGA   On    2.3  Hz  N/A       N/A           N/A           0.56 Hz
====== ===== =======  ========= ============= ============= =======

Note that using more agressive optimization levels when compiling the
Verilated-design dramatically lengths compile time:

====== ===== =======  ========= ============= =============
Level  Waves VCS      Verilator Verilator -O1 Verilator -O2
====== ===== =======  ========= ============= =============
MIDAS  Off   35s      48s       3m32s         4m35s
MIDAS  On    35s      49s       5m27s         6m33s
====== ===== =======  ========= ============= =============

Notes: Default configurations of a single-core, Rocket-based instance running
rv64ui-v-add. Frequencies are given in target-Hz. Presently, the default
compiler flags passed to Verilator and VCS differ from level to level. Hence,
these numbers are only intended to ball park simulation speeds, not provide a
scientific comparison between simulators. VCS numbers collected on Millenium,
Verilator numbers collected on a c4.4xlarge. (ML verilator version: 4.002, TL
verilator version: 3.904)

Target-Level Simulation
--------------------------

This is described in :ref:`target-level-simulation`, as part of the *Developing
New Devices* tutorial.

MIDAS-Level Simulation
------------------------

MIDAS-level simulations are run out of the ``firesim/sim`` directory. Currently, FireSim
lacks support for MIDAS-level simulation of the NIC since DMA\_PCIS is not yet
supported. So here we'll be setting ``DESIGN=FireSimNoNIC``. To compile a simulator,
type:

::

    [in firesim/sim]
    make <verilator|vcs>

To compile a simulator with full-visibility waveforms, type:

::

    make <verilator|vcs>-debug

As part of target-generation, Rocket Chip emits a make fragment with recipes
for running suites of assembly tests. MIDAS puts this in
``firesim/sim/generated-src/f1/<DESIGN>-<TARGET_CONFIG>-<PLATFORM_CONFIG>/firesim.d``.
Make sure your ``$RISCV`` environment variable is set by sourcing
``firesim/source-me*.sh`` or ``firesim/env.sh``, and type:

::

    make run-<asm|bmark>-tests EMUL=<vcs|verilator>


To run only a single test, the make target is the full path to the output.
Specifically:

::

    make EMUL=<vcs|verilator> $PWD/output/f1/<DESIGN>-<TARGET_CONFIG>-<PLATFORM_CONFIG>/<RISCV-TEST-NAME>.<vpd|out>

A ``.vpd`` target will use (and, if required, build) a simulator with waveform dumping enabled,
whereas a ``.out`` target will use the faster waveform-less simulator.


--------
Examples
--------

Run all RISCV-tools assembly and benchmark tests on a verilated simulator.

::

    [in firesim/sim]
    make DESIGN=FireSimNoNIC
    make DESIGN=FireSimNoNIC -j run-asm-tests
    make DESIGN=FireSimNoNIC -j run-bmark-tests
    
Run all RISCV-tools assembly and benchmark tests on a verilated simulator with waveform dumping.

::

    make DESIGN=FireSimNoNIC verilator-debug
    make DESIGN=FireSimNoNIC -j run-asm-tests-debug
    make DESIGN=FireSimNoNIC -j run-bmark-tests-debug

Run rv64ui-p-simple (a single assembly test) on a verilated simulator.

::

    make DESIGN=FireSimNoNIC
    make DESIGN=FireSimNoNIC $(pwd)/output/f1/FireSimNoNIC-FireSimRocketChipConfig-FireSimConfig/rv64ui-p-simple.out

Run rv64ui-p-simple (a single assembly test) on a VCS simulator with waveform dumping.

::


    make DESIGN=FireSimNoNIC vcs-debug
    make DESIGN=FireSimNoNIC EMUL=vcs $(pwd)/output/f1/FireSimNoNIC-FireSimRocketChipConfig-FireSimConfig/rv64ui-p-simple.vpd


FPGA-Level Simulation
----------------------------

Like MIDAS-level simulation, there is currently no support for DMA\_PCIS, so
we'll restrict ourselves to instances without a NIC by setting `DESIGN=FireSimNoNIC`.  As
with MIDAS-level simulations, FPGA-level simulations run out of
``firesim/sim``.

Since FPGA-level simulation is up to 1000x slower than MIDAS-level simulation,
FPGA-level simulation should only be used in two cases:

1. MIDAS-level simulation of the simulation is working, but running the
   simulator on the FPGA is not.
2. You've made changes to the AWS Shell/IP/cl\_firesim.sv in aws-fpga
   and want to test them.

FPGA-level simulation consists of two components:

1. A FireSim-f1 driver that talks to a simulated DUT instead of the FPGA
2. The DUT, a simulator compiled with either XSIM or VCS, that receives commands from the aforementioned
   FireSim-f1 driver

-----
Usage
-----

To run a simulation you need to make both the DUT and driver targets by typing:

::

    make xsim
    make xsim-dut <VCS=1> & # Launch the DUT
    make run-xsim SIM_BINARY=<PATH/TO/BINARY/FOR/TARGET/TO/RUN> # Launch the driver


When following this process, you should wait until ``make xsim-dut`` prints
``opening driver to xsim`` before running ``make run-xsim`` (getting these prints from
``make xsim-dut`` will take a while). Additionally, you will want to use
``DESIGN=FireSimNoNIC``, since the XSim scripts included with ``aws-fpga`` do
not support DMA PCIS.

Once both processes are running, you should see:

::

    opening driver to xsim
    opening xsim to driver

This indicates that the DUT and driver are successfully communicating.
Eventually, the DUT will print a commit trace Rocket Chip. There will
be a long pause (minutes, possibly an hour, depending on the size of the
binary) after the first 100 instructions, as the program is being loaded
into FPGA DRAM.

XSIM is used by default, and will work on EC2 instances with the FPGA developer
AMI.  If you have a license, setting ``VCS=1`` will use VCS to compile the DUT
(4x faster than XSIM). Berkeley users running on the Millennium machines should
be able to source ``firesim/scripts/setup-vcsmx-env.sh`` to setup their
environment for VCS-based FPGA-level simulation.

The waveforms are dumped in the FPGA build directories(
``firesim/platforms/f1/aws-fpga/hdk/cl/developer_designs/cl_<DESIGN>-<TARGET_CONFIG>-<PLATFORM_CONFIG>``).

For XSIM:

::

    <BUILD_DIR>/verif/sim/vivado/test_firesim_c/tb.wdb

And for VCS:

::

    <BUILD_DIR>/verif/sim/vcs/test_firesim_c/test_null.vpd


When finished, be sure to kill any lingering processes if you interrupted simulation prematurely.

Scala Tests
-----------

To make it easier to do RTL-simulation-based regression testing, the scala
tests wrap calls to Makefiles, and run a limited set of tests on a set of selected
designs, including all of the MIDAS examples, FireSimNoNIC and FireBoomNoNIC.

The selected tests, target configurations, as well as the type of RTL simulator
to compile can be modified by changing the scala tests that reside at
``firesim/sim/src/test/scala/<target-project>/``.

To run all tests, with the sbt console open, do the familiar:

::

    test

To run only tests on Rocket-Chip based targets:

::

    testOnly firesim.firesim.*

To run only the MIDAS examples:

::

    testOnly firesim.midasexamples.*

