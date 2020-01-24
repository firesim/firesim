.. _booting-fedora:

Running Fedora on FireSim
=====================================
FireSim supports running a fedora-based linux workload. To build this workload,
you can follow FireMarshal's `quickstart guide
<https://firemarshal.readthedocs.io/en/latest/quickstart.html>`_ (replace all
instances of ``br-base.json`` with ``fedora-base.json``).

The final step is to run this workload on the real firesim RTL with full timing
accuracy. For the basic fedora distribution, we will use the pre-made firesim
config at ``firesim/deploy/workloads/fedora-uniform.json``. Simply change the
``workloadname`` option in ``firesim/deploy/config_runtime.ini`` to
"fedora-uniform.json" and then follow the standard FireSim procedure for
booting a workload (e.g. :ref:`single-node-sim` or :ref:`cluster-sim`).

.. attention:: For the standard distributions we provide pre-built firesim
   workloads. In general, FireMarshal can derive a FireSim workload from
   the FireMarshal configuration using the ``install`` command. For more
   information, see the official `FireMarshal documentation
   <https://firemarshal.readthedocs.io/en/latest/>`_.
