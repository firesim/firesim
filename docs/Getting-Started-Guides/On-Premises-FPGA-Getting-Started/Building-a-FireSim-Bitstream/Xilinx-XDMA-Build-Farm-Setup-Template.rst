System Setup
----------------------------------

Here, we'll do some final one-time setup for your Build Farm Machines so that we
can build bitstreams for FireSim simulations automatically. 

**These steps assume that you have already followed the earlier setup steps
required to run simulations.**

As noted earlier, it is highly recommended that you use Ubuntu 20.04 LTS as the
host operating system for all machine types in an on-premises setup, as this is
the OS recommended by Xilinx.

Also recall that we make a distinction between the Manager Machine, the Build
Farm Machine(s), and the Run Farm Machine(s). In a simple setup, these can
all be a single machine, in which case you should run the Build Farm Machine
setup steps below on your single machine.


1. Install Vivado for Builds
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

**Machines:** Build Farm Machines.

Running builds for |fpga_name| in FireSim requires |vivado_with_version|.
Other versions are unlikely to work out-of-the-box.

On each Build Farm machine, do the following:

1. Install |vivado_with_version| from the `Xilinx Downloads Website <https://www.xilinx.com/support/download.html>`_. By default, Vivado will be installed to |vivado_default_install_path|. We recommend keeping this default. If you change it to something else, you will need to adjust the path in the rest of the setup steps.

2. Add the following to ``~/.bashrc`` so that ``vivado`` is available when ``ssh``-ing into the machine:

.. code-block:: bash
   :substitutions:

   source /tools/Xilinx/Vivado/|vivado_version_number_only|/settings64.sh 

3. |board_package_install|


If you have multiple Build Farm Machines, you should repeat this process for each.


2. Verify Build Farm Machine environment
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

**Machines:** Manager Machine and Run Farm Machines

Finally, let's ensure that |vivado_with_version| is properly sourced in
your shell setup (i.e. ``.bashrc``) so that any shell on your Build Farm Machines
can use the corresponding programs.  The environment variables should be
visible to any non-interactive shells that are spawned.

You can check this by running the following on the Manager Machine,
replacing ``BUILD_FARM_IP`` with ``localhost`` if your Build Farm machine
and Manager machine are the same machine, or replacing it with the Build Farm 
machine's IP address if they are different machines. 

.. code-block:: bash

    ssh BUILD_FARM_IP printenv


Ensure that the output of the command shows that the |vivado_with_version| tools are
present in the printed environment variables (i.e., ``PATH`` and ``XILINX_VIVADO``).

If you have multiple Build Farm machines, you should repeat this process for
each Build Farm machine, replacing ``BUILD_FARM_IP`` with a different Build Farm Machine's
IP address.


