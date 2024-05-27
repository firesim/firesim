FireAxe Partitioning onto Multiple FPGAs
=============================================

Although FPGA capacity has become large enough to simulate many large SoCs, there
still are cases when your design does not fit on a single FPGA.
When the design contains multiple duplicate modules, you should refer to the
:ref:`Multithreading<FAME-5>` section first. When the design doesn't fit even with :ref:`Multithreading<FAME-5>`,
FireAxe can be used with :ref:`Multithreading<FAME-5>` to provide extra simulation capacity.

.. toctree::
   :maxdepth: 3
   :caption: FireAxe Partitioning onto Multiple FPGAs:

   FireAxe-Overview.rst
   Running-Fast-Mode-Simulations.rst
   Running-Exact-Mode-Simulations.rst
   Running-NoC-Partition-Mode-Simulations.rst
   Miscellaneous.rst
