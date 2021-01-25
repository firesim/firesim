.. FireMarshal documentation master file, created by
   sphinx-quickstart on Wed Sep 25 11:32:32 2019.
   You can adapt this file completely to your liking, but it should at least
   contain the root `toctree` directive.

FireMarshal Documentation
=======================================
FireMarshal is a workload generation tool for RISC-V based systems. It
currently only supports the FireSim FPGA-accelerated simulation platform.

**Workloads** in FireMarshal consist of a series of **Jobs** that are assigned
to logical nodes in the target system. If no jobs are specified, then the
workload is considered ``uniform`` and only a single image will be produced for
all nodes in the system. Workloads are described by a JSON or YAML file and a
corresponding workload directory and can inherit their definitions from
existing workloads. Typically, workload configurations are kept in
``workloads/`` although you can use any directory you like. We provide a few
basic workloads to start with including buildroot or Fedora-based linux
distributions and bare-metal.

Once you define a workload, the ``marshal`` command will produce a
corresponding boot-binary and rootfs for each job in the workload. This binary
and rootfs can then be launched on qemu or spike (for functional simulation), or
installed to a platform for running on real RTL (currently only FireSim is
supported).

.. toctree::
   :maxdepth: 3
   :caption: Contents:

   Tutorials/index
   commands
   workloadConfig
   marshalConfig
   distros/index
   internal/index


.. todo:: Setup autodoc and add code documentation (via docstrings)

.. Indices and tables
.. ==================
..
.. * :ref:`genindex`
.. * :ref:`modindex`
.. * :ref:`search`
