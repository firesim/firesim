.. _tracerv-with-flamegraphs:

TracerV + Flame Graphs: Profiling Software with Out-of-Band Flame Graph Generation
====================================================================================

FireSim supports generating `Flame Graphs
<http://www.brendangregg.com/flamegraphs.html>`_ out-of-band, to visualize
the performance of software running on simulated processors. This feature was
introduced in our `FirePerf paper at ASPLOS 2020
<https://sagark.org/assets/pubs/fireperf-asplos2020.pdf>`_ .

Before proceeding, make sure you understand the :ref:`tracerv` section.

What are Flame Graphs?
-----------------------

.. figure:: http://www.brendangregg.com/FlameGraphs/cpu-mysql-updated.svg
    :align: center
    :alt: Example Flame Graph

    Example Flame Graph (from http://www.brendangregg.com/FlameGraphs/)


Flame Graphs are a type of histogram that shows where software is spending its
time, broken down by components of the stack trace (e.g., function calls).
The x-axis represents the portion of total runtime spent in a part of the stack
trace, while the y-axis represents the stack depth at that point in time. Entries
in the flame graph are labeled with and sorted by function name (not time).

Given this visualization, time-consuming routines can easily be identified:
they are leaves (top-most horizontal bars) of the stacks in the flame graph and
consume a significant proportion of overall runtime, represented by the width
of the horizontal bars.

Traditionally, data to produce Flame Graphs is collected using tools like
``perf``, which sample stack traces on running systems in software. However,
these tools are limited by the fact that they are ultimately running additional
software on the system being profiled, which can change the behavior of the
software that needs to be profiled. Furthermore, as sampling frequency is
increased, this effect becomes worse.

In FireSim, we use the out-of-band trace collection provided by TracerV to
collect these traces *cycle-exactly* and *without perturbing running software*.
On the host-software side, TracerV unwinds the stack based on DWARF information
about the running binary that you supply. This stack trace is then fed to
the open-source `FlameGraph stack trace visualizer
<https://github.com/brendangregg/FlameGraph>`_ to produce Flame Graphs.

Prerequisites
-----------------

#. Make sure you understand the :ref:`tracerv` section.
#. You must have a design that integrates the TracerV bridge. See the :ref:`tracerv-bridge` section.


Enabling Flame Graph generation in ``config_runtime.yaml``
--------------------------------------------------------------

To enable Flame Graph generation for a simulation, you must set ``enable: yes`` and
``output_format: 2`` in the ``tracing`` section of your ``config_runtime.yaml``
file, for example:

.. code-block:: yaml

    tracing:
        enable: yes

        # Trace output formats. Only enabled if "enable" is set to "yes" above
        # 0 = human readable; 1 = binary (compressed raw data); 2 = flamegraph (stack
        # unwinding -> Flame Graph)
        output_format: 2

        # Trigger selector.
        # 0 = no trigger; 1 = cycle count trigger; 2 = program counter trigger; 3 =
        # instruction trigger
        selector: 1
        start: 0
        end: -1


The trigger selector settings can be set as described in the
:ref:`tracerv-trigger` section. In particular, when profiling the OS only when
a desired application is running (e.g., ``iperf3`` in our `ASPLOS 2020 paper
<https://sagark.org/assets/pubs/fireperf-asplos2020.pdf>`_), instruction value
triggering is extremely useful. See the :ref:`tracerv-inst-value-trigger`
section for more.


Producing DWARF information to supply to the TracerV driver
----------------------------------------------------------------

When running in FirePerf mode, the TracerV software driver expects a binary
containing DWARF debugging information, which it will use to obtain labels
for stack unwinding.

TracerV expects this file to be named exactly as your ``bootbinary``, but
suffixed with ``-dwarf``. For example (and as we will see in the following
section), if your ``bootbinary`` is named ``br-base-bin``, TracerV will
require you to provide a file named ``br-base-bin-dwarf``.

If you are generating a Linux distribution with FireMarshal, this file
containing debug information for the generated Linux kernel will automatically
be provided (and named correctly) in the directory containing your images. For
example, building the ``br-base.json`` workload will automatically produce
``br-base-bin``, ``br-base-bin-dwarf`` (for TracerV flame graph generation),
and ``br-base.img``.


.. _tracerv-flamegraph-workload-description:

Modifying your workload description
-------------------------------------

Finally, we must make three modifications to the workload description to
complete the flame graph flow. For general documentation on workload
descriptions, see the :ref:`defining-custom-workloads` section.

#. We must add the file containing our DWARF information as one of the ``simulation_inputs``, so that it is automatically copied to the remote F1 instance running the simulation.
#. We must modify ``simulation_outputs`` to copy back the generated trace file.
#. We must set the ``post_run_hook`` to ``gen-all-flamegraphs-fireperf.sh`` (which FireSim puts on your path by default), which will produce flame graphs from the trace files.

To concretize this, let us consider the default ``linux-uniform.json`` workload,
which does not support Flame Graph generation:

.. include:: /../deploy/workloads/linux-uniform.json
   :code: json


Below is the modified version of this workload, ``linux-uniform-flamegraph.json``,
which makes the aforementioned three changes:

.. include:: /../deploy/workloads/linux-uniform-flamegraph.json
   :code: json


Note that we are adding ``TRACEFILE*`` to ``common_simulation_outputs``, which
will copy back all generated trace files to your workload results directory.
The ``gen-all-flamegraphs-fireperf.sh`` script will automatically produce a
flame graph for each generated trace.

Lastly, if you have created a new workload definition, make sure you update
your ``config_runtime.yaml`` to use this new workload definition.


Running a simulation
-----------------------

At this point, you can follow the standard FireSim flow to run a workload.
Once your workload completes, you will find trace files with stack traces
(as opposed to instruction traces) and generated flame graph SVGs in your
workload's output directory.

Caveats
------------

The current stack trace construction code does not distinguish between
different userspace programs, instead consolidating them into one entry.
Expanded support for userspace programs will be available in a future release.
