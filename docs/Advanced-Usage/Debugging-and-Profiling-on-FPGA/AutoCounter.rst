.. _autocounter:

AutoCounter: Profiling with Out-of-Band Performance Counter Collection
========================================================================

FireSim can provide visibility into a simulated CPU's architectural and
microarchitectural state over the course of execution through the use of
counters. These are similar to performance counters provided by processor
vendors, and more general counters provided by architectural simulators.

This functionality is provided by the AutoCounter feature (introduced in our
`FirePerf paper at ASPLOS 2020
<https://sagark.org/assets/pubs/fireperf-asplos2020.pdf>`_), and can be used
for profiling and debugging. Since AutoCounter injects counters only in
simulation (unlike target-level performance counters), these counters do not
affect the behavior of the simulated machine, no matter how often they are
sampled.

Ad-hoc Performance Counters
------------------------------

AutoCounter enables the addition of ad-hoc counters using the ``PerfCounter``
function.  The ``PerfCounter`` function takes 3 arguments: A boolean signal to
be counted, a counter label, and the counter description. Here is an example
counter declaration:

.. code-block:: scala

    midas.targetutils.PerfCounter(s1_pc, "s1_pc", "stage 1 program counter")


Building a Design with AutoCounter
-------------------------------------

To enable AutoCounter when building a design, prepend the ``WithAutoCounter``
config to your ``PLATFORM_CONFIG``. During compilation, FireSim will print the
signals it is generating counters for. If AutoCounter has been enabled, the
``autocounter_t`` bridge driver will also be automatically instantiated.


Rocket Chip Cover Functions
------------------------------
The cover function is applied to various signals in the Rocket Chip
generator repository to mark points of interest (i.e., interesting signals) in
the RTL. Tools are free to provide their own implementation of this function to
process these signals as they wish. In FireSim, these functions can be used as
a hook for automatic generation of counters.

Since cover functions are embedded throughout the code of Rocket Chip (and
possibly other code repositories), AutoCounter provides a filtering mechanism
based on module granularity. As such, only cover functions that appear within
selected modules will generate counters.

The filtered modules can be indicated using one of two methods:

1. A module selection annotation within the top-level configuration
   implementation.  To use this method, add the
   ``AutoCounterCoverModuleAnnotation`` annotation with the name of the module
   for which you want the cover functions to be turned into AutoCounters.  The
   following example will generate counters from cover functions within the
   ``StreamWriter`` module:

.. code-block:: scala

   class FireSimDUT(implicit p: Parameters) extends Subsystem
    with HasHierarchicalBusTopology
    with CanHaveMasterAXI4MemPort
    with HasPeripheryBootROM
    with HasPeripherySerial
    with HasPeripheryUART
    with HasPeripheryIceNIC
    with HasPeripheryBlockDevice
    with HasTraceIO
  {
    override lazy val module = new FireSimModuleImp(this)
      
    chisel3.experimental.annotate(AutoCounterCoverModuleAnnotation("StreamWriter"))
  }

2. An input file with a list of module names. This input file is named
   ``autocounter-covermodules.txt``, and includes a list of module names
   separated by new lines (no commas).

.. _autocounter-runtime-parameters:

AutoCounter Runtime Parameters
---------------------------------
AutoCounter currently takes a single runtime configurable parameter, defined
under the ``[autocounter]`` section in the ``config_runtime.ini`` file.  The
``readrate`` parameter defines the rate at which the counters should be read,
and is measured in target-cycles of the base target-clock (clock 0 produced by the ClockBridge). 
Hence, if the read-rate is defined to be 100 and the tile frequency is 2x the base clock (ex., which may drive the uncore),
the simulator will read and print the values of the counters every 200 core-clock cycles.
If the core-domain clock is the base clock, it would do so every 100 cycles.
By default, the read-rate is set to 0 cycles, which disables AutoCounter.

.. code-block:: ini

   [autocounter]
   # read counters every 100 cycles
   readrate=100


Upon setting this value, when you run a workload, an AutoCounter output file
will be placed in the ``sim_slot_<slot #>`` directory on the F1 instance under
the name ``AUTOCOUNTERFILE<N>``, with one file generated per clock domain
containing an AutoCounter event. The header of each output file indicates the
associated clock domain and its frequency relative to the base clock.

.. Note:: AutoCounter is designed as a coarse-grained observability mechanism, as sampling 
      each counter requires two (blocking) MMIO reads (each read takes O(100) ns on EC2 F1).
      As a result sampling at intervals less than O(10000) cycles may adversely affect
      simulation performance for large numbers of counters.
      If you intend on reading counters at a finer granularity, please consider using
      synthesizable printfs.

Using TracerV Trigger with AutoCounter
-----------------------------------------
In order to collect AutoCounter results from only from a particular region of
interest in the simulation, AutoCounter has been integrated with TracerV
triggers. See the :ref:`tracerv-trigger` section for more information.


AutoCounter using Synthesizable Printfs
------------------------------------------------
The AutoCounter transformation in the Golden Gate compiler includes an event-driven
mode that uses Synthesizable Printfs (see
:ref:`printf-synthesis`) to export counter results `as they are updated` rather than sampling them
periodically with a dedicated Bridge. This mode can be enabled by prepending the
``WithAutoCounterCoverPrintf`` config to your ``PLATFORM_CONFIG`` instead of
``WithAutoCounterCover``. In this mode, the counter values and the local cycle count will be printed
every time the counter is incremented using a synthesized printf (hence, you
will observe a series of printfs incrementing by 1). This mode may
be useful for fine-grained observation of counters.  The counter values will be
printed to the same output stream as other synthesizable printfs.  This mode
uses considerably more FPGA resources per counter, and may consume considerable
amounts of DMA bandwidth (since it prints every cycle a counter
increments), which may adversly affect simulation performance (increased FMR).
