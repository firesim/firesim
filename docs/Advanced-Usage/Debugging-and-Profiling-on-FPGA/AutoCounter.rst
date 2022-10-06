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

Chisel Interface
----------------

AutoCounter enables the addition of ad-hoc counters using the ``PerfCounter``
object in the `midas.targetutils` package. PerfCounters counters can be added in one of two modes:

1. `Accumulate`, using the standard ``PerfCounter.apply`` method. Here the annotated UInt (1 or
   more bits) is added to a 64b accumulation register: the target is treated as
   representing an N-bit UInt and will increment the counter by a value between [0, 2^n - 1] per cycle.
2. `Identity`, using the ``PerfCounter.identity`` method. Here the annotated UInt is sampled directly. This can be used
   to annotate a sample with values are not accumulator-like (e.g., a PC),
   and permits the user to define more complex instrumentation logic in the target itself.

We give examples of using PerfCounter below:

.. code-block:: scala

    // A standard boolean event. Increments by 1 or 0 every local clock cycle.
    midas.targetutils.PerfCounter(en_clock, "gate_clock", "Core clock gated")

    // A multibit example. If the core can retire three isntructions per cycle,
    // encode this as a two-bit unit. Extra-width is OK but the encoding to the UInt
    // (e.g., doing a pop count), must be done by the user.
    midas.targetutils.PerfCounter(insns_ret, "iret", "Instructions retired")

    // An identity value. Note: the pc here must be <= 64b wide.
    midas.targetutils.PerfCounter.identity(pc, "pc", "The value of the program counter at the time of a sample")


See the `PerfCounter Scala API docs
<https://fires.im/firesim/latest/api/midas/targetutils/PerfCounter$.html>`_ for more detail about the Chisel-side interface.


Enabling AutoCounter in Golden Gate
-------------------------------------
By default, annotated events are not synthesized into AutoCounters.  To enable
AutoCounter when compiling a design, prepend the ``WithAutoCounter`` config to
your ``PLATFORM_CONFIG``. During compilation, Golden Gate will print the
signals it is generating counters for.


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

1. An annotation attached to the module for which cover functions should be
   turned into AutoCounters.  The annotation requires a ``ModuleTarget`` which
   can be pointed to any module in the design.  Alternatively, the current
   module can be annotated as follows:

.. code-block:: scala

  class SomeModule(implicit p: Parameters) extends Module
  {
    chisel3.experimental.annotate(AutoCounterCoverModuleAnnotation(
        Module.currentModule.get.toTarget))
  }

2. An input file with a list of module names. This input file is named
   ``autocounter-covermodules.txt``, and includes a list of module names
   separated by new lines (no commas).

.. _autocounter-runtime-parameters:

AutoCounter Runtime Parameters
---------------------------------
AutoCounter currently takes a single runtime configurable parameter, defined
under the ``autocounter:`` section in the ``config_runtime.yaml`` file.  The
``read_rate`` parameter defines the rate at which the counters should be read,
and is measured in target-cycles of the base target-clock (clock 0 produced by the ClockBridge).
Hence, if the read_rate is defined to be 100 and the tile frequency is 2x the base clock (ex., which may drive the uncore),
the simulator will read and print the values of the counters every 200 core-clock cycles.
If the core-domain clock is the base clock, it would do so every 100 cycles.
By default, the read_rate is set to 0 cycles, which disables AutoCounter.

.. code-block:: yaml

   autocounter:
       # read counters every 100 cycles
       read_rate: 100


.. Note:: AutoCounter is designed as a coarse-grained observability mechanism, as sampling
      each counter requires two (blocking) MMIO reads (each read takes O(100) ns on EC2 F1).
      As a result sampling at intervals less than O(10000) cycles may adversely affect
      simulation performance for large numbers of counters.
      If you intend on reading counters at a finer granularity, consider using
      synthesizable printfs.

AutoCounter CSV Output Format
---------------------------------
AutoCounter output files are CSVs generated in the working directory where the
simulator was invoked (this applies to metasimulators too), with the default
names ``AUTOCOUNTERFILE<i>.csv``, one per clock domain. The CSV output format is
depicted below, assuming a sampling period of ``N`` base clock cycles.

.. csv-table:: AutoCounter CSV Format
    :file: autocounter-csv-format.csv

Column Notes:

#. Each column beyond the first two corresponds to a PerfCounter instance in the clock domain.
#. Column 0 past the header corresponds to the base clock cycle of the sample.
#. The local_cycle counter (column 1) is implemented as an always enabled
   single-bit event, and increments even when the target is under reset.

Row Notes:

#. Header row 0: autocounter csv format version, an integer.
#. Header row 1: clock domain information.
#. Header row 2: the label parameter provided to PerfCounter suffixed with the instance path.
#. Header row 3: the description parameter provided to PerfCounter. Quoted.
#. Header row 4: the width of the field annotated in the target.
#. Header row 5: the width of the accumulation register. Not configurable, but makes it clear when to expect rollover.
#. Header row 6: indicates the accumulation scheme. Can be "Identity" or "Accumulate".
#. Sample row 0: sampled values at the bitwidth of the accumulation register.
#. Sample row k: ditto above, k * N base cycles later

Using TracerV Trigger with AutoCounter
-----------------------------------------
In order to collect AutoCounter results from only from a particular region of
interest in the simulation, AutoCounter has been integrated with TracerV
triggers. See the :ref:`tracerv-trigger` section for more information.


AutoCounter using Synthesizable Printfs
------------------------------------------------
The AutoCounter transformation in Golden Gate includes an event-driven
mode that uses Synthesizable Printfs (see
:ref:`printf-synthesis`) to export counter results `as they are updated` rather than sampling them
periodically with a dedicated Bridge. This mode can be enabled by prepending the
``WithAutoCounterCoverPrintf`` config to your ``PLATFORM_CONFIG`` instead of
``WithAutoCounterCover``. Based on the selected event mode the printfs will have the following runtime behavior:

* `Accumulate`: On a non-zero increment, the local cycle count and the new
  counter value are printed. This produces a series of prints with
  monotonically increasingly values.
* `Identity`: On a transition of the annotated target, the local cycle count and
  the new value are printed. Thus a target that transitions every cycle will
  produce printf traffic every cycle.

This mode may be useful for temporally fine-grained observation of counters.
The counter values will be printed to the same output stream as other
synthesizable printfs.  This mode uses considerably more FPGA resources per
counter, and may consume considerable amounts of DMA bandwidth (since it prints
every cycle a counter increments), which may adversly affect simulation
performance (increased FMR).

Reset & Timing Considerations
------------------------------
* Events and identity values provided while under local reset, or while the
  ``GlobalResetCondition``  asserted, are zero-ed out. Similarly, printfs that
  might otherwise be active under a reset are masked out.
* The sampling period in slower clock domains is currently calculated using a truncating
  division of the period in the base clock domain. Thus, when the base clock
  period can not be cleanly divided, samples in the slower clock domain will
  gradually fall out of phase with samples in the base clock domain. In all
  cases, the "local_cycle" column is most accurate measure of sample time.

