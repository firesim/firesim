.. _metasimulation:

Debugging & Testing with Metasimulation
=========================================

When we speak of RTL simulation in FireSim, we are generally referring to
`metasimulation`: simulating the FireSim simulator's RTL, typically using VCS or
Verilator. In contrast, we we'll refer to native simulation of the target's RTL
as `target-level` simulation. Target-level simulation in Chipyard is described at length
`here <https://chipyard.readthedocs.io/en/latest/Simulation/Software-RTL-Simulation.html>`_.

Meta-simulation is the most productive way to catch bugs
before generating an AGFI, and a means for reproducing bugs seen on the FPGA.
By default, metasimulation uses an abstract but fast model of the host: the
FPGA's DRAM controllers are modeled with DRAMSim2, the PCI-E subsystem is not
simulated, instead the driver presents DMA and MMIO traffic directly via
verilog DPI. Since FireSim simulations are robust against timing differences
across hosts, target behavior observed in an FPGA-hosted simulation should be
exactly reproducible in a metasimulation.

Generally, meta-simulators are only slightly slower than target-level
ones. This illustrated in the chart below.

====== ===== =======  ========= ============= =============
Type   Waves VCS      Verilator Verilator -O1 Verilator -O2
====== ===== =======  ========= ============= =============
Target Off   4.8 kHz  3.9 kHz   6.6 kHz       N/A
Target On    0.8 kHz  3.0 kHz   5.1 kHz       N/A
Meta   Off   3.8 kHz  2.4 kHz   4.5 kHz       5.3 KHz
Meta   On    2.9 kHz  1.5 kHz   2.7 kHz       3.4 KHz
====== ===== =======  ========= ============= =============

Note that using more aggressive optimization levels when compiling the
Verilated-design dramatically lengthens compile time:

====== ===== =======  ========= ============= =============
Type   Waves VCS      Verilator Verilator -O1 Verilator -O2
====== ===== =======  ========= ============= =============
Meta   Off   35s      48s       3m32s         4m35s
Meta   On    35s      49s       5m27s         6m33s
====== ===== =======  ========= ============= =============

Notes: Default configurations of a single-core, Rocket-based instance running
rv64ui-v-add. Frequencies are given in target-Hz. Presently, the default
compiler flags passed to Verilator and VCS differ from level to level. Hence,
these numbers are only intended to ball park simulation speeds, not provide a
scientific comparison between simulators. VCS numbers collected on a local Berkeley machine,
Verilator numbers collected on a c4.4xlarge. (metasimulation Verilator version: 4.002, target-level
Verilator version: 3.904)


Running Metasimulation
------------------------

Meta-simulations are run out of the ``firesim/sim`` directory.

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
``firesim/sourceme-f1-manager.sh`` or ``firesim/env.sh``, and type:

::

    make run-<asm|bmark>-tests EMUL=<vcs|verilator>


To run only a single test, the make target is the full path to the output.
Specifically:

::

    make EMUL=<vcs|verilator> $PWD/output/f1/<DESIGN>-<TARGET_CONFIG>-<PLATFORM_CONFIG>/<RISCV-TEST-NAME>.<vpd|out>

A ``.vpd`` target will use (and, if required, build) a simulator with waveform dumping enabled,
whereas a ``.out`` target will use the faster waveform-less simulator.

Additionally, you can run a unique binary in the following way:

::

    make SIM_BINARY=<PATH_TO_BINARY> run-<vcs|verilator>
    make SIM_BINARY=<PATH_TO_BINARY> run-<vcs|verilator>-debug


Examples
++++++++

Run all RISCV-tools assembly and benchmark tests on a Verilated simulator.

::

    [in firesim/sim]
    make
    make -j run-asm-tests
    make -j run-bmark-tests

Run all RISCV-tools assembly and benchmark tests on a Verilated simulator with waveform dumping.

::

    make verilator-debug
    make -j run-asm-tests-debug
    make -j run-bmark-tests-debug

Run rv64ui-p-simple (a single assembly test) on a Verilated simulator.

::

    make
    make $(pwd)/output/f1/FireSim-FireSimRocketConfig-BaseF1Config/rv64ui-p-simple.out

Run rv64ui-p-simple (a single assembly test) on a VCS simulator with waveform dumping.

::


    make vcs-debug
    make EMUL=vcs $(pwd)/output/f1/FireSim-FireSimRocketConfig-BaseF1Config/rv64ui-p-simple.vpd


Understanding A Metasimulation Waveform
----------------------------------------

Module Hierarchy
++++++++++++++++
To build out a simulator, Golden Gate adds multiple layers of module hierarchy
to the target design and performs additional hierarchy mutations to implement bridges and
resource optimizations. Metasimulation uses the ``FPGATop`` module as the
top-level module, which excludes the platform shim layer (``F1Shim``, for EC2 F1). 
The original top-level of the input design is nested three levels below FPGATop:

.. figure:: /img/metasim-module-hierarchy.png

    The module hierarchy visible in a typical metasimulation.

Note that many other bridges (under ``FPGATop``), channel implementations
(under ``SimWrapper``), and optimized models (under ``FAMETop``) may be
present, and vary from target to target. Under the ``FAMETop`` module instance
you will find the original top-level module (``FireSimPDES_``, in this case),
however it has now been host-decoupled using the default LI-BDN FAME
transformation and is referred to as the `hub model`. It will have ready-valid
I/O interfaces for all of the channels bound to it, and internally containing
additional channel enqueue and clock firing logic to control the advance of
simulated time. Additionally, modules for bridges and optimized models will no
longer be found contained in this submodule hierarchy. Instead, I/O for those
extracted modules will now be as channel interfaces.


Clock Edges and Event Timing
++++++++++++++++++++++++++++
Since FireSim derives target clocks by clock gating a single host clock, and
since bridges and optimized models may introduce stalls of their own, timing of
target clock edges in a metasimulation will appear contorted relative to a
conventional target-simulation. Specifically, the host-time between clock edges
will not be proportional to target-time elapsed over that interval, and
will vary in the presence of simulator stalls.

Finding The Source Of Simulation Stalls
+++++++++++++++++++++++++++++++++++++++
In the best case, FireSim simulators will be able to launch new target clock
pulses on every host clock cycle. In other words, for single-clock targets the
simulation can run at FMR = 1. In the single clock case delays are introduced by
bridges (like FASED memory timing models) and optimized models (like a
multi-cycle Register File model). You can identify which bridges are responsible
for additional delays between target clocks by filtering for ``*sink_valid`` and
``*source_ready`` on the hub model.  When ``<channel>_sink_valid`` is
deasserted, a bridge or model has not yet produced a token for the current
timestep, stalling the hub. When ``<channel>_source_ready`` is deasserted, a
bridge or model is back-pressuring the channel.

Scala Tests
-----------

To make it easier to do metasimulation-based regression testing, the ScalaTests
wrap calls to Makefiles, and run a limited set of tests on a set of selected
designs, including all of the MIDAS examples and a handful of Chipyard-based
designs. This is described in greater detail
in the :ref:`Developer documentation <Scala Integration Tests>`.
