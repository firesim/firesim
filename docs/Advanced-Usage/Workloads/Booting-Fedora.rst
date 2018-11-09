Running Fedora on FireSim
===========================

You can boot Fedora disk images pulled from upstream on FireSim simulations.
These instructions assume you've already run through the tutorials.

Fedora currently requires some tweaks to the Linux configuration. To rebuild
Linux with this configuration, first head to ``sw/firesim-software`` and
replace the ``linux-config-firesim`` file with ``deploy/workloads/fedora-uniform/linux-config-firesim``
and then re-run ``./build.sh`` in ``sw/firesim-software``. This will build a copy
of ``bbl-vmlinux`` that is compatible with Fedora.

Next, head to
``deploy/workloads`` and run ``make fedora-uniform``. This will download the
latest version of the disk image and apply some patches to it to ensure it
functions correctly on FireSim.

Finally, you can change your workload to ``fedora-uniform.json`` to boot Fedora on your simulations.
