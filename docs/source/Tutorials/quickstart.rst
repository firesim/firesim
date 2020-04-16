.. _tutorial-quickstart:

Quick Start (Built-In Workloads)
=========================================

After a fresh clone of the FireMarshal repository, you may need to update
submodules. You may skip this step if you only intend to build bare-metal
workloads:

::

  ./init-submodules.sh

FireMarshal comes with a few basic workloads that you can build right out of
the box. These builtin workloads include: br-base.json (buildroot) and fedora-base.json
(fedora). You can see the source for these workloads at
``boards/firechip/base-workloads``. In this example, we will build and test the
buildroot-based linux distribution (called *br-base*). The instructions are
identical for fedora, just replace br-base.json with fedora-base.json. We begin
by building the workload:

::

  ./marshal build br-base.json

.. Note:: The ``base-workloads`` directory is on the default search path for
   FireMarshal workloads. This means that we do not need to provide the full-path
   to the br-base.json configuration file. See :ref:`workload-search-paths` for
   details on how workloads are located.

The first time you build a workload may take a long time (buildroot must
download and cross-compile a large number of packages), but subsequent builds
of the same base will use cached results. Once the command completes, you
should see two new files in ``images/``: ``br-base-bin`` and ``br-base.img``.
These are the boot-binary (linux + boot loader) and root filesystem
(respectively). We can now launch this workload in qemu:

::

  ./marshal launch br-base.json

You should now see linux booting and be presented with a login prompt. Sign in
as 'root' with password 'firesim'. From here you can manipulate files, run
commands, and generally use the image as if it had booted on real hardware. Any
changes you make here will be persistent between reboots. Once you are done
exploring, simply shutdown the workload:

::

  $ poweroff

It is typically not a good idea to modify the \*-base workloads directly since
many other workloads might inherit those changes. To make sure that we've
cleaned out any changes, let's clean and rebuild the workload:

::

  ./marshal clean br-base.json
  ./marshal build br-base.json

Note that this build took significantly less time than the first; FireMarshal
caches intermediate build steps whenever possible.

Finally, FireMarshal supports installing workloads to the FireSim cycle-exact
simulator. To do this, you will need to use the FireMarshal that comes with
`FireSim <https://www.fires.im>`_ or `Chipyard
<https://chipyard.readthedocs.io/en/latest/>`_ (or manually :ref:`configure firesim <config-firesim>`). To run a workload in FireSim,
you must first install it from FireMarshal:

::

  ./marshal install br-base.json

This command creates a firesim workload file at
``firesim/deploy/workloads/br-base.json``. You can now run this workload using
the standard FireSim commands.
