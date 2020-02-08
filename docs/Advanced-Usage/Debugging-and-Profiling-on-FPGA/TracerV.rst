Capturing RISC-V Instruction Traces with TracerV
==================================================

FireSim can provide a cycle-by-cycle trace of a target CPU's architectural
state over the course of execution, including fields like instruction address,
raw instruction bits, privilege level, exception/interrupt status and cause,
and a valid signal. This can be useful for profiling or debugging.

**TracerV** is the FireSim bridge that provides this functionality.

This section details how to capture these traces in cycle-by-cycle formats,
usually for debugging purposes.

For profiling purposes, FireSim also supports automatically producing stack
traces from this data and producing Flame Graphs, which is documented in the
:ref:`tracerv-with-flamegraphs` section.

Building a Design with TracerV
-------------------------------

In all FireChip designs, TracerV is included by default. Other targets can
enable it by attaching a TracerV Bridge to the RISC-V trace port of one-or-more
cores. By default, only the cycle number, instruction address, and valid bit
are collected.

Enabling Tracing at Runtime
----------------------------

To improve simulation preformance, FireSim does not collect and record data
from the TracerV Bridge by default. To enable collection, modify the ``enable``
flag in the ``[tracing]`` section in your ``config_runtime.ini`` file to ``yes``
instead of ``no``:

.. code-block:: ini

    [tracing]
    enable=yes

Now when you run a workload, a trace output file will be placed in the
````sim_slot_<slot #>```` directory on the F1 instance under the name ``TRACEFILE0``.
You can add ``TRACEFILE0`` to your ``common_simulation_outputs`` or
``simulation_outputs`` in your workload ``.json`` file to have this automatically
copied back to your manager.  See the :ref:`defining-custom-workloads` section
for more information about these options.

Selecting a Trace Output Format
---------------------------------

FireSim supports three trace output formats, which can be set in your
``config_runtime.ini`` file with the ``output_format`` option in the
``[tracing]`` section:

.. code-block:: ini

   [tracing]
   enable=no

   # Trace output formats. Only enabled if "enable" is set to "yes" above
   # 0 = human readable; 1 = binary (compressed raw data); 2 = flamegraph (stack
   # unwinding -> Flame Graph)
   output_format=0

The human readable format looks like so:

TODO

Setting a TracerV Trigger
---------------------------

Tracing the entirety of a long-running job like a Linux-based workload can
generate a pretty large image, and you may only care about the state within a
certain timeframe.
Therefore, FireSim allows you to specify a trigger condition for starting and
stopping trace data collection. FireSim currently provides three possible trigger
conditions:

* Simulation cycles: Specify a start cycle and end cycle, based on the
  simulation cycle count
* Program Counter (PC) value: Specify a program
  counter value to start collection, and a program counter value in which to
  end collection.
* Instruction value: Specify an instruction value upon which
  to start data collection, and an instruction value in which to end
  collection. This method is particularly valuable for setting the trigger from
  within the target software under evaluation, by using custom "NOP"
  instructions. As such, one may use the  TODO @alonamid


By default, TracerV does not use a trigger, so data collection starts at cycle
0 and ends at the last cycle of the simulation. To change this, modify the
following under the ``[tracing]`` section of your ``config_runtime.ini``.
Use the ``selector`` field to choose the type of trigger, and there use the ``start`` and ``end`` fields
to select the start and end values for the trigger.

.. code-block:: ini

   [tracing]
   enable=no

   # Trace output formats. Only enabled if "enable" is set to "yes" above
   # 0 = human readable; 1 = binary (compressed raw data); 2 = flamegraph (stack
   # unwinding -> Flame Graph)
   output_format=0

   # Trigger selector.
   # 0 = no trigger; 1 = cycle count trigger; 2 = program counter trigger; 3 =
   # instruction trigger
   selector=1
   start=0
   end=-1



Interpreting the Trace Result
------------------------------
