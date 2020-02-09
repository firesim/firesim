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
``sim_slot_<slot #>`` directory on the F1 instance under the name ``TRACEFILE0-C0``.
The first ``0`` in this filename disambiguates between multiple SoCs on one FPGA
if you're running in supernode mode and will always be ``0`` if you're not running
in supernode mode. The ``C0`` represents core 0 in the simulated
SoC. If you have multiple cores, each will have its own file (ending in ``C1``,
``C2``, etc).  To copy all TracerV trace files back to your manager, you can
add ``TRACEFILE*`` to your ``common_simulation_outputs`` or
``simulation_outputs`` in your workload ``.json`` file. See the
:ref:`defining-custom-workloads` section for more information about these
options.

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

See the "Interpreting the Trace Result" section below for a description of
these formats.

Setting a TracerV Trigger
---------------------------

Tracing the entirety of a long-running job like a Linux-based workload can
generate a large trace and you may only care about the state within a
certain timeframe.
Therefore, FireSim allows you to specify a trigger condition for starting and
stopping trace data collection.

By default, TracerV does not use a trigger, so data collection starts at cycle
0 and ends at the last cycle of the simulation. To change this, modify the
following under the ``[tracing]`` section of your ``config_runtime.ini``.
Use the ``selector`` field to choose the type of trigger (options are described
below). The ``start`` and ``end`` fields are used to supply the start and end
values for the trigger.

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


The four triggering methods available in FireSim are as follows:

* **No trigger**: Record trace for the entire simulation. This is option ``0``
  in the ``.ini`` above. The ``start`` and ``end`` fields are ignored.
* **Target cycle trigger**: Specify a start cycle and end cycle, based on the
  simulation cycle count. This is option ``1`` in the ``.ini`` above. The ``start``
  and ``end`` fields are interpreted as decimal integers.
* **Program Counter (PC) value trigger**: Specify a program
  counter value to start collection, and a program counter value in which to
  end collection. This is option ``2`` in the ``.ini`` above. The ``start``
  and ``end`` fields are interpreted as hexadecimal values.
* **Instruction value trigger**: Specify an instruction value upon which
  to start data collection, and an instruction value in which to end
  collection. This method is particularly valuable for setting the trigger from
  within the target software under evaluation, by inserting custom "NOP"
  instructions. This is option ``3`` in the ``.ini`` above. The ``start``
  and ``end`` fields are interpreted as hexadecimal values.


Interpreting the Trace Result
------------------------------
