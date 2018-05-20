Overview
========================

When you source ``sourceme-f1-manager.sh`` in your copy of the firesim repo,
you get access to a new command, ``firesim``, which is the FireSim simulation
manager. If you've used tools like Vagrant or Docker, the ``firesim`` program
is to FireSim what ``vagrant`` and ``docker`` are to Vagrant and Docker
respectively. In essence, ``firesim`` lets us manage the entire lifecycle
of FPGA simulations, just like ``vagrant`` and ``docker`` do for VMs and
containers respectively.

"Inputs"
--------------

The manager gets configuration information from several places:

- Command Line Arguments, consisting of:

  - Configuration files to use

  - A task to run

  - Arguments to the task

- Configuration Files

- ``user_topology.py``


The following sections detail these inputs. Hit Next to continue.
