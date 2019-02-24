Quick Start
--------------------------------------

.. attention::

   FireMarshal is still in alpha. You are encouraged to try it out and use it
   for new workloads. The old-style workload generation is still supported (see
   :ref:`defining-custom-workloads` for details).


All workload-generation related commands and code are in ``firesim/sw/firesim-software``.

FireMarshal comes with a few basic workloads that you can build right out of
the box (in ``workloads/``). In this example, we will build and test the
buildroot-based linux distribution (called *br-base*). We begin by building the
workload:

::

  ./marshal build workloads/br-base.json

The first time you build a workload may take a long time (buildroot must
download and cross-compile a large number of packages), but subsequent builds
of the same base will use cached results. Once the command completes, you
should see two new files in ``images/``: ``br-base-bin`` and ``br-base.img``.
These are the boot-binary (linux + boot loader) and root filesystem
(respectively). We can now launch this workload in qemu:

::

  ./marshal launch workloads/br-base.json

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

  ./marshal clean workloads/br-base.json
  ./marshal build workloads/br-base.json

Note that this build took significantly less time than the first; FireMarshal
caches intermediate build steps whenever possible. The final step is to run
this workload on the real firesim RTL with full timing accuracy. To do that we
must first install the workload:

::

  ./marshal install workloads/br-base.json

This command creates a firesim workload file at
``firesim/deploy/workloads/br-base.json``. You can now run this workload using
the standard FireSim commands (e.g. :ref:`single-node-sim`, just change the
``workloadname`` option to "br-base.json" from "linux-uniform.json"). 

.. attention:: While the FireMarshal ``install`` command is the recommended way to create
  firesim configurations, you can still hand-create firesim workloads if needed.
  For example, the linux-uniform workload described in :ref:`single-node-sim` is
  a manually created workload that uses the br-base-bin and br-base.img files
  directly. 
