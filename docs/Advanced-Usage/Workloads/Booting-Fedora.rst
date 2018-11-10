.. _booting-fedora:

Running Fedora on FireSim
===========================

You can boot Fedora disk images pulled from upstream on FireSim simulations.
These instructions assume you've already run through the tutorials.


Building a FireSim-compatible Fedora Image
-----------------------------------------------

To download and build a Fedora-based Linux distro for FireSim, do the following:

::

    cd firesim/sw/firesim-software
    ./sw-manager.py -c fedora-disk.json build

Testing or customizing the target software using QEMU
-----------------------------------------------------
Before running this target software on FireSim, you may choose to boot the
image in QEMU (a high-performance functional simulator). From here, you will
have access to the internet and can install packages (e.g. by using ``dnf
install foo``), download files, or perform any configuration tasks you'd like
before booting in FireSim. To boot an image in QEMU, simply use the launch
command:

::

    ./sw-manager.py -c fedora-disk.json launch


Booting Fedora on FireSim
----------------------------

In order to boot Fedora on FireSim, change your workload to
``fedora-uniform.json`` in runtime_config.ini and boot as usual.
