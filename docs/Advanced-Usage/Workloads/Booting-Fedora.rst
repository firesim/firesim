.. _booting-fedora:

Running Fedora on FireSim
=====================================

FireSim also supports running a fedora-based linux workload. To build this
workload, you can follow FireMarshal's `quickstart guide
<https://firemarshal.readthedocs.io/en/latest/quickstart.html>`_ (replace all
instances of ``br-base.json`` with ``fedora-base.json``).

To boot Fedora on FireSim, we provide a pre-written FireSim workload JSON
:gh-file-ref:`deploy/workloads/fedora-uniform.json`, that points to the generated
Fedora images. Simply change the ``workload_name`` option in your
``config_runtime.ini`` to ``fedora-uniform.json`` and then follow the standard
FireSim procedure for booting a workload (e.g. :ref:`single-node-sim` or
:ref:`cluster-sim`).


