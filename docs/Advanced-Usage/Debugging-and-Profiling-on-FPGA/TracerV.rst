.. _tracerv:

Capturing RISC-V Instruction Traces with TracerV
==================================================

FireSim can provide a cycle-by-cycle trace of a target CPU's architectural
state over the course of execution, including fields like instruction address,
raw instruction bits, privilege level, exception/interrupt status and cause,
and a valid signal. This can be useful for profiling or debugging.
**TracerV** is the FireSim bridge that provides this functionality. This
feature was introduced in our `FirePerf paper at ASPLOS 2020
<https://sagark.org/assets/pubs/fireperf-asplos2020.pdf>`_ .

This section details how to capture these traces in cycle-by-cycle formats,
usually for debugging purposes.

For profiling purposes, FireSim also supports automatically producing stack
traces from this data and producing Flame Graphs, which is documented in the
:ref:`tracerv-with-flamegraphs` section.

.. _tracerv-bridge:

Building a Design with TracerV
-------------------------------

In all FireChip designs, TracerV is included by default. Other targets can
enable it by attaching a TracerV Bridge to the RISC-V trace port of each core
they wish to trace (there should be one bridge per core).  By default, only the
cycle number, instruction address, and valid bit are collected.

.. _tracerv-enabling:

Enabling Tracing at Runtime
----------------------------

To improve simulation preformance, FireSim does not collect and record data
from the TracerV Bridge by default. To enable collection, modify the ``enable``
flag in the ``tracing`` section in your ``config_runtime.yaml`` file to ``yes``
instead of ``no``:

.. code-block:: ini

    tracing:
        enable: yes

Now when you run a workload, a trace output file will be placed in the
``sim_slot_<slot #>`` directory on the F1 instance under the name ``TRACEFILE-C0``.
The ``C0`` represents core 0 in the simulated
SoC. If you have multiple cores, each will have its own file (ending in ``C1``,
``C2``, etc).  To copy all TracerV trace files back to your manager, you can
add ``TRACEFILE*`` to your ``common_simulation_outputs`` or
``simulation_outputs`` in your workload ``.json`` file. See the
:ref:`defining-custom-workloads` section for more information about these
options.

.. _tracerv-output-format:

Selecting a Trace Output Format
---------------------------------

FireSim supports three trace output formats, which can be set in your
``config_runtime.yaml`` file with the ``output_format`` option in the
``tracing`` section:

.. code-block:: ini

   tracing:
       enable: yes

       # Trace output formats. Only enabled if "enable" is set to "yes" above
       # 0 = human readable; 1 = binary (compressed raw data); 2 = flamegraph (stack
       # unwinding -> Flame Graph)
       output_format: 0

See the "Interpreting the Trace Result" section below for a description of
these formats.

.. _tracerv-trigger:

Setting a TracerV Trigger
---------------------------

Tracing the entirety of a long-running job like a Linux-based workload can
generate a large trace and you may only care about the state within a
certain timeframe.
Therefore, FireSim allows you to specify a trigger condition for starting and
stopping trace data collection.

By default, TracerV does not use a trigger, so data collection starts at cycle
0 and ends at the last cycle of the simulation. To change this, modify the
following under the ``tracing`` section of your ``config_runtime.yaml``.
Use the ``selector`` field to choose the type of trigger (options are described
below). The ``start`` and ``end`` fields are used to supply the start and end
values for the trigger.

.. code-block:: ini

   tracing
       enable: yes

       # Trace output formats. Only enabled if "enable" is set to "yes" above
       # 0 = human readable; 1 = binary (compressed raw data); 2 = flamegraph (stack
       # unwinding -> Flame Graph)
       output_format: 0

       # Trigger selector.
       # 0 = no trigger; 1 = cycle count trigger; 2 = program counter trigger; 3 =
       # instruction trigger
       selector: 1
       start: 0
       end: -1


The four triggering methods available in FireSim are as follows:

No trigger
^^^^^^^^^^^^^^

Records the trace for the entire simulation.

This is option ``0`` in the ``.yaml`` above.

The ``start`` and ``end`` fields are ignored.

Target cycle trigger
^^^^^^^^^^^^^^^^^^^^^^^

Trace recording begins when a specified start cycle is reached and ends when a
specified end cycle is reached. Cycles are specified in base target-clock
cycles (the zeroth output clock from the ClockBridge). For example, if the base
clock drives the uncore, and the core clock frequency is 2x the uncore frequency,
specifying start and end cycles of 100 and 200 result in instructions being collected
between core-clock cycles 200 and 400.

This is option ``1`` in the ``.yaml`` above.

The ``start`` and ``end`` fields are interpreted as decimal integers.


Program Counter (PC) value trigger
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Trace recording begins when a specified program counter value is reached
and ends when a specified program counter value is reached.

This is option ``2`` in the ``.yaml`` above.

The ``start`` and ``end`` fields are interpreted as hexadecimal values.


.. _tracerv-inst-value-trigger:

Instruction value trigger
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Trace recording begins when a specific instruction is seen in the instruction
trace and ends when a specific instruction is seen in the instruction
trace. This method is particularly valuable for setting the trigger from
within the target software under evaluation, by inserting custom "NOP"
instructions. Linux distributions included with FireSim include small trigger
programs by default for this purpose; see the end of this subsection.

This is option ``3`` in the ``.yaml`` above.

The ``start`` and ``end`` fields are interpreted as hexadecimal values. For
each, the field is a 64-bit value, with the upper 32-bits representing a
mask and the lower 32-bits representing a comparison value. That is, the
start or stop condition will be satisfied when the following evaluates to true:

.. code-block:: C

    ((inst value) & (upper 32 bits)) == (lower 32 bits)


That is, setting ``start: ffffffff00008013`` will cause recording to start when
the instruction value is exactly ``00008013`` (the ``addi x0, x1, 0``
instruction in RISC-V).


This form of triggering is useful when recording traces only when a particular
application is running within Linux. To simplify the use of this triggering
mechanism, workloads derived from ``br-base.json`` in FireMarshal automatically
include the commands ``firesim-start-trigger`` and ``firesim-end-trigger``,
which issue a ``addi x0, x1, 0`` and ``addi x0, x2, 0`` instruction
respectively. In your ``config_runtime.yaml``, if you set the following
trigger settings:

.. code-block:: yaml

    selector: 3
    start: ffffffff00008013
    end: ffffffff00010013


And then run the following at the bash prompt on the simulated system:

.. code-block:: bash

    $ firesim-start-trigger && ./my-interesting-benchmark && firesim-end-trigger


The trace will contain primarily only traces for the duration of
``my-interesting-benchmark``.  Note that there will be a small amount of extra
trace information from ``firesim-start-trigger`` and ``firesim-end-trigger``,
as well as the OS switching between these and ``my-interesting-benchmark``.


.. Attention::  While it is unlikely that a compiler will generate the
   aforementioned trigger instructions within normal application code, it is also
   a good idea to confirm that these instructions are not inadvertently present
   within the section of code you wish to profile. This will result in the trace
   recording inadvertently turning on and off in the middle of the workload.

   On the flip-side, a developer can deliberately insert the aforementioned ``addi``
   instructions into the code they wish to profile, to enable more fine-grained
   control.


Interpreting the Trace Result
------------------------------

Human readable output
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

This is ``output_format: 0``.

The human readable trace output format looks like so:

.. include:: TRACERV-HUMAN-READABLE-EXAMPLE
   :code: yaml

In this output, each line begins with the cycle (in decimal) in the core's
clock domain that instruction was committed. For a given cycle, the instruction
address (in hex) of each committed is prefixed ``I<#>`` according to their
appearance in program order: ``I0`` is the oldest instruction committed, ``I1``
is the second oldest, and so forth. If no instructions were committed in a
given cycle, that cycle will be skipped in the output file.

.. code-block:: ini

    Cycle: 0000000000000337 I0: 0000000000010010
    Cycle: 0000000000000337 I1: 0000000000010014
           |--------------|  ^        |--------|
                  |          |            └ 40 bits of instruction address (hex)
                  |          └ per-cycle commit-order
                  └ 64-bit local-cycle count

Binary output
^^^^^^^^^^^^^^^^^

This is ``output_format: 1``.

This simply writes the 512 bits received from the FPGA each cycle to the output
file in binary. Each 512-bit chunk is stored little-endian. The lowermost 64 bits stores the cycle,
the second 64-bits stores the address and valid bits of committed instruction  0 in little-endian, the next
64-bits stores the address and valid bits of committed instruction 1 in little-endian, and so on, up to a maximum of 7 instructions.

Flame Graph output
^^^^^^^^^^^^^^^^^^^^

This is ``output_format: 2``. See the :ref:`tracerv-with-flamegraphs` section.

Caveats
--------------------

There are currently a few restrictions / manual tweaks that are required
when using TracerV under certain conditions:

* TracerV by default outputs only instruction address and a valid bit and assumes
  that the combination of these fits within 64 bits. Changing this requires
  modifying ``sim/firesim-lib/src/main/scala/bridges/TracerVBridge.scala``.
* The maximum IPC of the traced core cannot exceed 7.
* Please reach out on the FireSim mailing list if you need help addressing any
  of these restrictions: https://groups.google.com/forum/#!forum/firesim
