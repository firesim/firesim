FireSim Repo Setup
==============================

.. |manager_machine| replace:: **Manager Machine**
.. |build_farm_machine| replace:: **Build Farm Machines**
.. |run_farm_machine| replace:: **Run Farm Machines**

.. |mach_or_inst| replace:: Machine
.. |mach_or_inst_l| replace:: machines
.. |mach_details| replace:: your local desktop or server
.. |mach_or_inst2| replace:: local machines
.. |simple_setup| replace:: In the simplest setup, a single host machine (e.g. your desktop) can serve the function of all three of these: as the manager machine, the build farm machine (assuming Vivado is installed), and the run farm machine (assuming an FPGA is attached).


In this step, we will perform final setup by cloning FireSim on your Manager Machine
and completing setup in the repo.


Setting up the FireSim Repo
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

**Machine:** From this point forward, run everything on your Manager Machine, unless otherwise instructed.

We're finally ready to fetch FireSim's sources. This should be done on your Manager Machine. Run:

.. code-block:: bash
   :substitutions:

    git clone https://github.com/firesim/firesim
    cd firesim
    # checkout latest official firesim release
    # note: this may not be the latest release if the documentation version != "stable"
    git checkout |overall_version|

Next, we will bootstrap the machine by installing Miniforge Conda, our software package manager, and set up a default software environment using Conda.

You should select a location where you want conda to be installed. This can be an existing Miniforge Conda
install, or a directory (that does not exist) where you would like conda to be installed.

Replace ``REPLACE_ME_USER_CONDA_LOCATION`` in the command below with your chosen path and run it:

.. code-block:: bash

   ./scripts/machine-launch-script.sh --prefix REPLACE_ME_USER_CONDA_LOCATION


Among other setup steps, the script will install Miniforge Conda (https://github.com/conda-forge/miniforge) and create a default environment called ``firesim``.

When prompted, you should allow the Conda installer to modify your ``~/.bashrc`` to automatically place you in the conda environment when opening a new shell.

**Once the** ``machine-launch-script.sh`` **completes, ensure that you log out of the machine / exit out of the terminal so that the** ``.bashrc`` **modifications can apply**.

After re-logging back into the machine, you should be in the ``firesim`` Conda environment.

Verify this by running:

.. code-block:: bash

   conda env list

If you are not in the ``firesim`` environment and the environment exists, you can run the following to "activate" or enter the environment:

.. code-block:: bash

   conda activate firesim

Next, return to your clone of the FireSim repo and run:

.. code-block:: bash

    ./build-setup.sh

The ``build-setup.sh`` script will validate that you are on a tagged branch,
otherwise it will prompt for confirmation. Then, it will automatically
initialize submodules and install the RISC-V tools and other dependencies.

Once ``build-setup.sh`` completes, run:

.. code-block:: bash

    source sourceme-manager.sh --skip-ssh-setup

This will perform various environment setup steps, such as adding the RISC-V tools to your
path. Sourcing this the first time will take some time -- however each subsequent sourcing should be instantaneous.

**Every time you want to use FireSim, you should** ``cd`` **into
your FireSim directory and source** ``sourceme-manager.sh`` **again with the arguments shown above.**


Initializing FireSim Config Files
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The FireSim manager contains a command that will automatically provide a fresh
set of configuration files for a given platform.

To run it, do the following:

.. code-block:: bash
   :substitutions:

    firesim managerinit --platform |platform_name|

This will produce several initial configuration files, which we will edit in the next
section.


Configuring the FireSim manager to understand your Run Farm Machine setup
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

As our final setup step, we will edit FireSim's configuration files so that the
manager understands our Run Farm machine setup and the set of FPGAs attached to
each machine.

Inside the cloned FireSim repo, open up the ``deploy/config_runtime.yaml`` file and replace the following keys to be the following:

* ``default_platform`` should be |deploy_manager_code|

* ``default_simulation_dir`` should point to a temporary simulation directory of your choice

* ``default_hw_config`` should be |hwdb_entry_name|

Then, run the following command to generate a mapping from a PCI-E BDF to FPGA UID/serial number.

.. code-block:: bash
   :substitutions:

   firesim enumeratefpgas

This will generate a database file in ``/opt/firesim-db.json`` that has this mapping.

Now you're ready to run your first FireSim simulation! Hit Next to continue with the guide.

