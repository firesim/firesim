FireAxe - Partitioning onto Multiple FPGAs
==========================================

Although FPGA capacity has become large enough to simulate many large SoCs, there still
are cases when a design does not fit on a single FPGA. When the design contains multiple
duplicate modules, you should refer to the :ref:`Multithreading <FAME-5>` section first.
When there aren't enough duplicate modules you can use FireAxe to obtain higher
simulation capacity. FireAxe is also compatible with :ref:`Multithreading <FAME-5>` as
well which enables scaling the size of the design even further.

.. toctree::
    :maxdepth: 3
    :caption: FireAxe Partitioning onto Multiple FPGAs:

    FireAxe-Overview.rst
    Running-Fast-Mode-Simulations.rst
    Running-Exact-Mode-Simulations.rst
    Running-NoC-Partition-Mode-Simulations.rst
    Miscellaneous.rst
