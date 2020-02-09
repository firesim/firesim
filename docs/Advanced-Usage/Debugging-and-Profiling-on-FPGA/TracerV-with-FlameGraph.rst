.. _tracerv-with-flamegraphs:

TracerV+Flame Graphs: Profiling Software with Out-of-Band Flame Graph Generation
=================================================================================

FireSim supports generating `Flame Graphs
<http://www.brendangregg.com/flamegraphs.html>`_ out-of-band, to visualize
the performance of software running on simulated processors.

.. image:: http://www.brendangregg.com/FlameGraphs/cpu-mysql-updated.svg


Constructing Flame Graphs in FireSim uses trace data
collected from TracerV and unwinds the stack on the fly to constrct
a stack trace. This trace is then fed to the open-source `FlameGraph stack trace
visualizer <https://github.com/brendangregg/FlameGraph>`_ to produce Flame
Graphs.

Before proceeding, make sure you understand the :ref:`tracerv` section.


This feature was introduced in our
`FirePerf paper at ASPLOS 2020 <https://sagark.org/assets/pubs/fireperf-asplos2020.pdf>`_ .


