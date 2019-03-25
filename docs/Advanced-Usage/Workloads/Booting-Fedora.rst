.. _booting-fedora:

Running Fedora on FireSim
=====================================
All workload-generation related commands and code are in ``firesim/sw/firesim-software``.

FireMarshal comes with a Fedora-based workload that you can use right out of
the box in ``workloads/fedora-base.json``. We begin by building the
workload (filesystem and boot-binary):

::

  ./marshal build workloads/fedora-base.json

The first time you build a workload may take a long time (we need to download
and decompress a pre-built fedora image), but subsequent builds of the same
base will use cached results. Once the command completes, you should see two
new files in ``images/``: ``fedora-base-bin`` and ``fedora-base.img``.  These
are the boot-binary (linux + boot loader) and root filesystem (respectively).
We can now launch this workload in qemu:

::

  ./marshal launch workloads/fedora-base.json

You should now see linux booting and be presented with a login prompt. Sign in
as 'root' with password 'firesim'. From here you can download files, use the
package manager (e.g. 'dnf install'), and generally use the image as if it had
booted on real hardware with an internet connection. Any changes you make here
will be persistent between reboots. Once you are done exploring, simply
shutdown the workload:

::

  $ poweroff

It is typically not a good idea to modify the \*-base workloads directly since
many other workloads might inherit those changes. To make sure that we've
cleaned out any changes, let's clean and rebuild the workload:

::

  ./marshal clean workloads/fedora-base.json
  ./marshal build workloads/fedora-base.json

Note that this build took significantly less time than the first; FireMarshal
caches intermediate build steps whenever possible. The final step is to run
this workload on the real firesim RTL with full timing accuracy. For the basic
fedora distribution, we will use the pre-made firesim config at
``firesim/deploy/workloads/fedora-uniform.json``. Simply change the
``workloadname`` option in ``firesim/deploy/config_runtime.ini`` to
"fedora-uniform.json" and then follow the standard FireSim procedure for
booting a workload (e.g. :ref:`single-node-sim` or :ref:`cluster-sim`).

.. attention:: For the standard distributions we provide pre-built firesim
   workloads. In general, FireMarshal can derive a FireSim workload from
   the FireMarshal configuration using the ``install`` command (see
   :ref:`firemarshal-commands`)
