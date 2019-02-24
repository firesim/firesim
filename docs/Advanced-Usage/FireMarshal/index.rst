.. _firemarshal:

FireMarshal (alpha)
=======================================
.. attention::

   FireMarshal is still in alpha. You are encouraged to try it out and use it
   for new workloads. The old-style workload generation is still supported (see
   :ref:`defining-custom-workloads` for details).

Workload generation in FireSim is handled by a tool called **FireMarshal** in
``firesim/sw/firesim-software/``.

**Workloads** in FireMarshal consist of a series of **Jobs** that are assigned
to logical nodes in the target system. If no jobs are specified, then the
workload is considered ``uniform`` and only a single image will be produced for
all nodes in the system. Workloads are described by a json file and a
corresponding workload directory and can inherit their definitions from
existing workloads. Typically, workload configurations are kept in
``workloads`` although you can use any directory you like. We provide a few
basic workloads to start with including buildroot or Fedora-based linux
distributions and bare-metal.

Once you define a workload, the ``marshal`` command will produce a
corresponding boot-binary and rootfs for each job in the workload. This binary
and rootfs can then be launched on qemu or spike (for functional simulation), or
installed to firesim for running on real RTL.

.. toctree::
   :maxdepth: 2

   FireMarshal-QuickStart
   FireMarshal-Commands
   FireMarshal-Config
