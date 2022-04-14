Optimizing FPGA Resource Utilization
====================================

One advantage of a host-decoupled simulator is the ability to spread expensive operations out over
multiple FPGA cycles while maintaining perfect cycle accuracy. When employing this strategy, a
simulator can rely on a resource-efficient implementation that takes multiple cycles to complete the
underlying computation to determine the next state of the target design. In the abstract, this
corresponds with the simulator having *less* parallelism in its host implementation than the target
design. While this strategy is intrinsic to the design of the compilers that map RTL circuits to
software simulators executing on sequential, general-purpose hardware, it is less prevalent in FPGA
simulation. These *space-time* tradeoffs are mostly restricted to hand-written,
architecture-specific academic simulators or to implementing highly specific host features like I/O
cuts in a partitioned, multi-FPGA environment.

With the Golden Gate compiler, we provide a framework for automating these optimization, as
discussed in `the 2019 ICCAD paper <https://people.eecs.berkeley.edu/~magyar/documents/goldengate-iccad19.pdf>`_
on the design of Golden gate. Furthermore, current versions of FireSim include two optional
optimizations that can radically reduce resource utilization (and therefore simulate much large SoCs).
The first optimization reduces the overhead of memories that are extremely to implement via direct
RTL translation on an FPGA host, including multi-ported register files, while the second applies to
repeated instances of large blocks in the target design by *threading* the work associated with
simulating multiple instances across a single underlying host implementation.

Multi-Ported Memory Optimization
--------------------------------

ASIC multi-ported RAMs are a classic culprit of poor resource utilization in FPGA prototypes, as
they cannot be trivially implemented with Block RAMs (BRAMs) and are instead decomposed into lookup
tables (LUTs), multiplexers and registers. While using double-pumping, BRAM duplication, or
FPGA-optimized microarchitectures can help, Golden Gate can automatically extract such memories and
replace them with a decoupled model that simulates the RAM via serialized accesses to an underlying
implementation that is amenable mapping to an efficiency Block RAM (BRAM). While this serialization
comes at the cost of reduced emulation speed, the resulting simulator can fit larger SoCs onto
existing FPGAs. Furthermore, the decoupling framework of Golden Gate ensures that the simulator will
still produce bit-identical, cycle-accurate results.

While the details of this optimization are discussed at length in the ICCAD paper, it is relatively
simple to deploy. First, the desired memories must be annotated via Chisel annotations to indicate
that they should be optimized; for Rocket- and BOOM-based systems, these annotations are already
provided for the cores' register files, which are the most FPGA-hostile memories in the designs.
Next, with these annotations in place, enabling the optimization requires mixing in the ``MCRams``
class to the platform configuration, as shown in the following example build recipe:

::

    firesim-boom-mem-opt:
        DESIGN: FireSim
        TARGET_CONFIG: WithNIC_DDR3FRFCFSLLC4MB_FireSimLargeBoomConfig
        PLATFORM_CONFIG: MCRams_BaseF1Config
        deploy_triplet: null


Multi-Threading of Repeated Instances
-------------------------------------

While optimizing FPGA-hostile memories can allow up to 50% higher core counts on the AWS-hosted VU9P
FPGAs, significantly larger gains can be had by threading repeated instances in the target system.
The *model multi-threading* optimization extracts these repeated instances and simulates each
instance with a separate thread of execution on a shared underlying physical implementation.

As with the memory optimization, this requires the desired set of instances to be annotated in the
target design. However, since the largest effective FPGA capacity increases for typical Rocket Chip
targets are realized by threading the tiles that each contain a core complex, these instances are
pre-annotated for both Rocket- and BOOM-based systems. To enable this tile multi-threading, it is
necessary to mix in the ``MTModels`` class to the platform configuration, as shown in the following
example build recipe:

::

    firesim-threaded-cores-opt:
        DESIGN: FireSim
        TARGET_CONFIG: WithNIC_DDR3FRFCFSLLC4MB_FireSimQuadRocketConfig
        PLATFORM_CONFIG: MTModels_BaseF1Config
        deploy_triplet: null

This simulator configuration will rely on a single threaded model to simulate the four Rocket tiles.
However, it will still produce bit- and cycle-identical results to any other platform configuration
simulating the same target system.

In practice, the largest benefits will be realized by applying both the ``MCRams`` and ``MCModels``
optimizations to large, multi-core BOOM-based systems. While these simulator platforms will have
reduced throughput relative to unoptimized FireSim simulators, very large SoCs that would otherwise
never fit on a single FPGA can be simulated without the cost and performance drawbacks of
partitioning.

::

    firesim-optimized-big-soc:
        DESIGN: FireSim
        TARGET_CONFIG: MyMultiCoreBoomConfig
        PLATFORM_CONFIG: MTModels_MCRams_BaseF1Config
        deploy_triplet: null
