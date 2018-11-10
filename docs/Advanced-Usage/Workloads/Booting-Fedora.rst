.. _booting-fedora:

Running Fedora on FireSim
===========================

You can boot Fedora disk images pulled from upstream on FireSim simulations.
These instructions assume you've already run through the tutorials.

To download and build a Fedora-based linux distro for firesim, simply do the following:

::

    cd firesim/sw/firesim-software
    ./sw-manager.py -c fedora-disk.json build

Testing or customizing the target software using Qemu
-----------------------------------------------------
Before running this target software on firesim, you may choose to boot the
image in qemu (a high-performance functional simulator). From here, you will
have access to the internet and can install packages (e.g. by using ``dnf
install foo``), download files, or perform any configuration tasks you'd like
before booting in Firesim. To boot an image in qemu, simply use the launch
command:

::

    ./sw-manager.py -c fedora-disk.json launch

.. attention::

  Qemu currently cannot emulate clusters. Whatever changes you make to the
  image in qemu will be replicated to all nodes in the firesim cluster
  simulation.

Running in Firesim
------------------
In order to run fedora in firesim, simply change your workload to
``fedora-uniform.json`` in runtime_config.ini and boot as normal.
