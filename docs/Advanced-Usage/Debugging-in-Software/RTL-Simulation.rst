.. _metasimulation:

Debugging & Testing with Metasimulation
=========================================

When discussing RTL simulation in FireSim, we are generally referring to
`metasimulation`: simulating the FireSim simulator's RTL, typically using VCS
or Verilator. In contrast, we'll refer to simulation of the target's unmodified
(by GoldenGate decoupling, host and target transforms) RTL as `target-level`
simulation. Target-level simulation in Chipyard is described at length `here
<https://chipyard.readthedocs.io/en/latest/Simulation/Software-RTL-Simulation.html>`_.

Metasimulation is the most productive way to catch bugs
before generating an AGFI, and a means for reproducing bugs seen on the FPGA.
By default, metasimulation uses an abstract but fast model of the host: the
FPGA's DRAM controllers are modeled with a single-cycle memory system, the PCI-E subsystem is not
simulated, instead the driver presents DMA and MMIO traffic directly on the FPGATop interfaces.
Since FireSim simulations are robust against timing differences
across hosts, target behavior observed in an FPGA-hosted simulation should be
exactly reproducible in a metasimulation.

As a final note, metasimulations are generally only slightly slower than
target-level simulations. Example performance numbers can be found at
:ref:`metasimulation-performance`.

.. _metasimulation-supported-host-sims:

Supported Host Simulators
----------------------------------------------------

Currently, the following host simulators are supported for metasimulation:

* `Verilator <https://www.veripool.org/verilator/>`_

  * FOSS, automatically installed during FireSim setup.

  * Referred to throughout the codebase as ``verilator``.

* `Synopsys VCS <https://www.synopsys.com/verification/simulation/vcs.html>`_

  * License required.

  * Referred to throughout the codebase as ``vcs``.


Pull requests to add support for other simulators are welcome.


Running Metasimulations using the FireSim Manager
----------------------------------------------------

The FireSim manager supports running metasimulations using the standard
``firesim {launchrunfarm, infrasetup, runworkload, terminaterunfarm}`` flow
that is also used for FPGA-accelerated simulations. Rather than using FPGAs,
these metasimulations run within one of the aforementioned software simulators
(:ref:`metasimulation-supported-host-sims`) on standard compute hosts (i.e.
those without FPGAs). This allows users to write a single definition of
a target (configured design and software workload), while seamlessly moving
between software-only metasimulations and FPGA-accelerated simulations.

As an example, if you have the default ``config_runtime.yaml`` that is setup for
FPGA-accelerated simulations (e.g. the one used for the 8-node networked
simulation from the :ref:``cluster-sim`` section), a few modifications to the
configuration files can convert it to running a distributed metasimulation.

First, modify the existing ``metasimulation`` mapping in
``config_runtime.yaml`` to the following:

.. code-block:: yaml

    metasimulation:
        metasimulation_enabled: true
        # vcs or verilator. use vcs-debug or verilator-debug for waveform generation
        metasimulation_host_simulator: verilator
        # plusargs passed to the simulator for all metasimulations
        metasimulation_only_plusargs: "+fesvr-step-size=128 +max-cycles=100000000"
        # plusargs passed to the simulator ONLY FOR vcs metasimulations
        metasimulation_only_vcs_plusargs: "+vcs+initreg+0 +vcs+initmem+0"


This configures the manager to run Verilator-hosted metasimulations (without
waveform generation) for the target specified in ``config_runtime.yaml``.  When
in metasimulation mode, the ``default_hw_config`` that you specify in
``target_config`` references an entry in ``config_build_recipes.yaml`` instead
of an entry in ``config_hwdb.ini``.

As is the case when the manager runs FPGA-accelerated simulations, the number
of metasimulations that are run is determined by the parameters in the
``target_config`` section, e.g. ``topology`` and ``no_net_num_nodes``. Many
parallel metasimulations can then be run by writing a FireMarshal workload with
a corresponding number of jobs.

In metasimulation mode, the run farm configuration must be able to support the
required number of metasimulations (see
:ref:`run-farm-config-in-config-runtime` for details). The ``num_metasims``
parameter on a run farm host specification defines how many metasimulations are
allowed to run on a particular host. This corresponds with the ``num_fpgas``
parameter used in FPGA-accelerated simulation mode. However ``num_metasims``
does not correspond as tightly with any physical property of the host; it can
be tuned depending on the complexity of your design and the compute/memory
resources on a host.

For example, in the case of the AWS EC2 run farm (``aws_ec2.yaml``), we define
three instance types (``z1d.{3, 6, 12}xlarge``) by default that loosely
correspond with ``f1.{2, 4, 16}xlarge`` instances, but instead have no FPGAs
and run only metasims (of course, the ``f1.*`` instances could run metasims,
but this would be wasteful):

.. code-block:: yaml

    run_farm_hosts_to_use:
        - z1d.3xlarge: 0
        - z1d.6xlarge: 0
        - z1d.12xlarge: 1

    run_farm_host_specs:
        - z1d.3xlarge:
            num_fpgas: 0
            num_metasims: 1
            use_for_switch_only: false
        - z1d.6xlarge:
            num_fpgas: 0
            num_metasims: 2
            use_for_switch_only: false
        - z1d.12xlarge:
            num_fpgas: 0
            num_metasims: 8
            use_for_switch_only: false


In this case, the run farm will use a ``z1d.12xlarge`` instance to host
8 metasimulations.

To generate waveforms in a metasimulation, change
``metasimulation_host_simulator`` to a simulator ending in ``-debug`` (e.g.
``verilator-debug``).  When running with a simulator with waveform generation,
make sure to add ``waveform.vpd`` to the ``common_simulation_outputs`` area of
your workload JSON file, so that the waveform is copied back to your manager
host when the simulation completes.

A last notable point is that unlike the normal FPGA simulation case, there are
two output logs in metasimulations.  There is the expected ``uartlog`` file
that holds the ``stdout`` from the metasimulation (as in FPGA-based
simulations).  However, there will also be a ``metasim_stderr.out`` file that
holds ``stderr`` coming out of the metasimulation, commonly populated by
``printf`` calls in the RTL, including those that are not marked for ``printf``
synthesis.  If you want to copy ``metasim_stderr.out`` to your manager when
a simulation completes, you must add it to the ``common_simulation_outputs`` of
the workload JSON.

Other than the changes discussed in this section, manager behavior is identical
between FPGA-based simulations and metasimulations. For example, simulation
outputs are stored in ``deploy/results-workload/`` on your manager host,
FireMarshal workload definitions are used to supply target software, etc.  All
standard manager functionality is supported in metasimulations, including
running networked simulations and using existing FireSim debugging tools (i.e.
AutoCounter, TracerV, etc).

Once the configuration changes discussed thus far in this section are made, the
standard ``firesim {launchrunfarm, infrasetup, runworkload, terminaterunfarm}``
set of commands will run metasimulations.

If you are planning to use FireSim metasimulations as your primary simulation
tool while developing a new target design, see the (optional) ``firesim
builddriver`` command, which can build metasimulations through the manager
without requiring run farm hosts to be launched or accessible. More about this
command is found in the :ref:`firesim-builddriver` section.


Understanding a Metasimulation Waveform
----------------------------------------

Module Hierarchy
++++++++++++++++
To build out a simulator, Golden Gate adds multiple layers of module hierarchy
to the target design and performs additional hierarchy mutations to implement
bridges and resource optimizations. Metasimulation uses the ``FPGATop`` module
as the top-level module, which excludes the platform shim layer (``F1Shim``,
for EC2 F1).  The original top-level of the input design is nested three levels
below FPGATop:

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

Running Metasimulations through Make
------------------------------------

.. Warning:: This section is for advanced developers; most metasimulation users
   should use the manager-based metasimulation flow described above.

Metasimulations are run out of the ``firesim/sim`` directory.

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

Run all RISCV-tools assembly and benchmark tests on a Verilated simulator with
waveform dumping.

::

    make verilator-debug
    make -j run-asm-tests-debug
    make -j run-bmark-tests-debug

Run ``rv64ui-p-simple`` (a single assembly test) on a Verilated simulator.

::

    make
    make $(pwd)/output/f1/FireSim-FireSimRocketConfig-BaseF1Config/rv64ui-p-simple.out

Run ``rv64ui-p-simple`` (a single assembly test) on a VCS simulator with
waveform dumping.

::

    make vcs-debug
    make EMUL=vcs $(pwd)/output/f1/FireSim-FireSimRocketConfig-BaseF1Config/rv64ui-p-simple.vpd


.. _metasimulation-performance:

Metasimulation vs. Target simulation performance
---------------------------------------------------------

Generally, metasimulations are only slightly slower than target-level
simulations. This is illustrated in the chart below.

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
``rv64ui-v-add``. Frequencies are given in target-Hz. Presently, the default
compiler flags passed to Verilator and VCS differ from level to level. Hence,
these numbers are only intended to give ball park simulation speeds, not provide
a scientific comparison between simulators. VCS numbers collected on a local
Berkeley machine, Verilator numbers collected on a ``c4.4xlarge``.
(metasimulation Verilator version: 4.002, target-level Verilator version:
3.904)

