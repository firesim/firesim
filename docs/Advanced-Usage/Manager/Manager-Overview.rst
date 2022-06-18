Overview
========================

When you source ``sourceme-f1-manager.sh`` in your copy of the FireSim repo,
you get access to a new command, ``firesim``, which is the FireSim simulation
manager. If you've used tools like Vagrant or Docker, the ``firesim`` program
is to FireSim what ``vagrant`` and ``docker`` are to Vagrant and Docker
respectively. In essence, ``firesim`` lets us manage the entire lifecycle
of FPGA simulations, just like ``vagrant`` and ``docker`` do for VMs and
containers respectively.

"Inputs" to the Manager
-------------------------

The manager gets configuration information from several places:

- Command Line Arguments, consisting of:

  - Paths to configuration files to use

  - A task to run

  - Arguments to the task

- Configuration Files

- Environment Variables

- Topology definitions for networked simulations (``user_topology.py``)

The following sections detail these inputs. Hit Next to continue.


Logging
---------------

The manager produces detailed logs when you run any command, which is useful
to share with the FireSim developers for debugging purposes in case you
encounter issues. The logs contain more detailed output than the manager
sends to stdout/stderr during normal operation, so it's also useful if you
want to take a peek at the detailed commands manager is running to facilitate
builds and simulations. Logs are stored in ``firesim/deploy/logs/``.

