Debugging Using AutoCounter
================================

FireSim can provide visibility into the CPU's architectural and microarchitectural
state over the course of execution through the use of counters. These are
similar to performance counters provided by processor vendors, and more
general counters provided by architectural simulators. 
This functionality is provided by the AutoCounter feature, and can be used
for profiling and debugging.
Since AutoCounter injects counters only in simulation (unlike target-level performance
counters), these counters do not affect the behavior of the simulated machine. 


Ad-hoc Performance Counters
------------------------------
AutoCounter enables the additions of ad-hoc counters using the ``PerfCounter`` function.
The PerfCounter function takes 3 arguments: A boolean signal to be counted, a counter label,
and the counter description. An example counter declaration would be:

.. code-block:: scala

    midas.targetutils.PerfCounter(s1_pc, "s1_pc", "stage 1 program counter")


Building a Design with AutoCounter
-------------------------------------

To enable AutoCounter when building a design, prepend ``WithAutoCounter`` Config to your
PLATFORM_CONFIG. During compilation, FireSim will print the
signals it is generating counters for. If AutoCounter has been enabled, the
``autocounter_t`` bridge driver will be automatically instantiated.


Rocket Chip Cover Functions
------------------------------
Cover functions are unimplemented function interfaces embedded in the Rocket Chip generator
repository to represent points of interest for coverage. In FireSim, these functions can be used
as a hook for automatic generation of counters.

Since cover functions are embedded throughout the code of Rocket Chip (and possibly other code repositories),
AutoCounter provides a filtering mechanism based on module granulariy. As such, only cover functions that appear
within selected modules will generate counters.  

The filtered modules can be indicated using one of two methods:
1. A module selection annotation within the top-level configuration implementation. 
To use this method, add the ``AutoCounterCoverModuleAnnotation``
annotation with the name of the module that you want the cover functions to be turned into AutoCounters. 
The following example will generate counters from cover functions within the StreamWriter module:

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

2. An input file with a list of module names. This input file is named ``autocounter-covermodules.txt``,
   an includes a list of module names separated by new lines (no commas).


AutoCounter Runtime Parameters
---------------------------------
AutoCounter currently takes a single runtime configurable parameter, defined under the ``[autocounter]``
section in the ``config_runtime.ini`` file. 
The ``readrate`` parameter defines the rate at which the counters should be read, 
and is measured in target-cycles. Hence, if the read-rate is defined to be 100, 
the simulator will read and print the values of the counters every 100 cycles.
By default, the read-rate is set to 0 cycles, which is equivalent to disabling AutoCounter.

.. code-block:: ini

   [autocounter]
   readrate=XXXX


Now when you run a workload, an AutoCounter output file will be placed in the
`sim_slot_<slot #>` directory on the F1 instance under the name AUTOCOUNTERFILE.

.. Note:: AutoCounter is designed as a coarse-grained observability mechanism. It assumes the counters will be read at intervals greater than O(10000) cycles. If you intend on reading counters at a finer granularity, please consider using synthesizable printfs (otherwise, simulation performance may degrade more than necessary)

Using TracerV Trigger with AutoCounter
-----------------------------------------
In order to observe AutoCounter results from only from a particular region of interest in
the simulation, AutoCounter has been integrated with the TracerV trigger. Therefore, when
enabling a TracerV trigger condition, the selected region of interest will automatically be
reflected in the AutoCounter output file as well.


AutoCounter using Synthesizable Printfs
------------------------------------------------
The AutoCounter transformation in the Golden Gate compiler includes a legacy mode that uses
Synthesizable Printfs to export counter results rather than a dedicated Bridge. This mode can
be enabled by prepending ``WithAutoCounterCoverPrintf`` Config to your PLATFORM_CONFIG instead
of ``WithAutoCounterCover``. In this mode, the counter values will be printed using a synthesizable
printf every time the counter is incremented (hence, you will observe a series of printfs incrementing
by 1). 
Nevertheless, the Printf statements include the exact cycle of the printf, and therefore
this mode may be useful for fine grained observation regarding counter incrementation. 
The counter values will be printed to the same output stream as other synthesizable printfs. 
This mode may export a large amount of data (since it prints every cycle a counter increments), 
and therefore it is not recommended.
