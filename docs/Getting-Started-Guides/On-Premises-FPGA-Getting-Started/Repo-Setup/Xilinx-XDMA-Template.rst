FireSim Repo Setup
==================

.. |manager_machine| replace:: **Manager Machine**

.. |build_farm_machine| replace:: **Build Farm Machines**

.. |run_farm_machine| replace:: **Run Farm Machines**

.. |mach_or_inst| replace:: Machine

.. |mach_or_inst_l| replace:: machines

.. |mach_details| replace:: your local desktop or server

.. |mach_or_inst2| replace:: local machines

.. |simple_setup| replace:: In the simplest setup, a single host machine (e.g. your
    desktop) can serve the function of all three of these: as the manager machine, the
    build farm machine (assuming Vivado is installed), and the run farm machine
    (assuming an FPGA is attached).

Next, we'll clone FireSim through Chipyard on your Manager Machine and run a few final
setup steps using scripts in the repo.

Setting up the FireSim Repo
---------------------------

**Machine:** From this point forward, run everything on your Manager Machine, unless
otherwise instructed.

We're finally ready to fetch FireSim's sources through Chipyard. Chipyard provides all
the necessary target designs (e.g. RISC-V SoCs) and software (e.g. Linux) used for the
rest of this guide.

.. note::

    This guide was built using Chipyard version |cy_docs_version|. It is recommended to
    use the most up-to-date version of Chipyard with this tutorial.

This should be done on your Manager Machine. Run:

.. code-block:: bash
    :substitutions:

     git clone https://github.com/ucb-bar/chipyard
     cd chipyard
     # ideally use the main chipyard release instead of this
     git checkout |cy_docs_version|
     ./build-setup.sh

Once ``build-setup.sh`` completes, run:

.. code-block:: bash

    cd sims/firesim
    source sourceme-manager.sh --skip-ssh-setup

This will perform various environment setup steps, such as adding the RISC-V tools to
your path. Sourcing this the first time will take some time -- however each subsequent
sourcing should be instantaneous.

.. warning::

    **Every time you want to use FireSim, you should** ``cd`` **into your FireSim
    directory and source** ``sourceme-manager.sh`` **again with the arguments shown
    above.**

Initializing FireSim Config Files
---------------------------------

The FireSim manager contains a command that will automatically provide a fresh set of
configuration files for a given platform.

To run it, do the following:

.. code-block:: bash
    :substitutions:

     firesim managerinit --platform |platform_name|

This will produce several initial configuration files, which we will edit in the next
section.

Configuring the FireSim manager to understand your Run Farm Machine setup
-------------------------------------------------------------------------

As our final setup step, we will edit FireSim's configuration files so that the manager
understands our Run Farm machine setup and the set of FPGAs attached to each Run Farm
machine.

Inside the cloned FireSim repo, open up the ``deploy/config_runtime.yaml`` file and set
the following keys to the indicated values:

- ``default_simulation_dir`` should point to a temporary simulation directory of your
  choice on your Run Farm Machines. This is the directory that simulations will run out
  of.
- ``run_farm_hosts_to_use`` should be a list of ``- IP-address: machine_spec`` pairs,
  one pair for each of your Run Farm Machines. ``IP-address`` should be the IP address
  or hostname of the system (that the Manager Machine can use to ssh into the Run Farm
  Machine) and the ``machine_spec`` should be a value from ``run_farm_host_specs`` in
  :gh-file-ref:`deploy/run-farm-recipes/externally_provisioned.yaml`. Each spec
  describes the number of FPGAs attached to a system and other properties about the
  system.

Here are two examples of how this could be configured:

**Example 1**: Your Run Farm has a single machine with one FPGA attached and this
machine is also your Manager Machine:

.. code-block:: yaml

    ...
        run_farm_hosts_to_use:
            - localhost: one_fpgas_spec
    ...

**Example 2**: You have two Run Farm Machines (separate from your Manager Machine). The
Run Farm Machines are accessible from your manager machine with the hostnames
``firesim-runner1.berkeley.edu`` and ``firesim-runner2.berkeley.edu``, each with eight
FPGAs attached.

.. code-block:: yaml

    ...
        run_farm_hosts_to_use:
            - firesim-runner1.berkeley.edu: eight_fpgas_spec
            - firesim-runner2.berkeley.edu: eight_fpgas_spec
    ...

- ``default_hw_config`` should be |hwdb_entry_name|

Then, run the following command so that FireSim can generate a mapping from the FPGA ID
used for JTAG programming to the PCIe ID used to run simulations. If you ever change the
physical layout of the machine (e.g., which PCIe slot the FPGAs are attached to), you
will need to re-run this command.

.. code-block:: bash

    firesim enumeratefpgas

This will generate a database file in ``/opt/firesim-db.json`` on each Run Farm Machine
that has this mapping.

Now you're ready to run your first FireSim simulation! Hit Next to continue with the
guide.
