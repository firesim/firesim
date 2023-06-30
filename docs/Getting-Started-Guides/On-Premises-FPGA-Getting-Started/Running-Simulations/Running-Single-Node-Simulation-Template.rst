
.. include:: Running-Sims-Top-Template.rst

Setting up the manager configuration
-------------------------------------

All runtime configuration options for the manager are located in
``YOUR_FIRESIM_REPO/deploy/config_runtime.yaml``. In this guide, we will explain only the
parts of this file necessary for our purposes. You can find full descriptions of
all of the parameters in the :ref:`manager-configuration-files` section.

Based on the changes we made earlier, this file will already have everything set
correctly to run a simulation.

Below we'll highlight a few of these lines to explain what is happening:

* At the top, you'll notice the ``run_farm`` mapping, which describes and specifies the machines to run simulations on.

  * By default, we'll be using a ``base_recipe`` of ``run-farm-recipes/externally_provisioned.yaml``, which means that our
    Run Farm machines are pre-configured, and do not require the manager to dynamically launch/terminate them (e.g., as we
    would do for cloud instances).

  * The ``default_platform`` has automatically been set for our FPGA board, to |deploy_manager_code|.

  * The ``default_simulation_dir`` is the directory on the Run Farm Machines where simulations will run out of. The default is likely fine, but you can change it to any directory you have access to on the Run Farm machines.

  * ``run_farm_hosts_to_use`` should be a list of ``- IP-address: machine_spec`` pairs, 
    one pair for each of your Run Farm Machines. ``IP-address`` should be the IP address 
    or hostname of the system (that the Manager Machine can use to ssh into the Run Farm
    Machine) and the ``machine_spec`` should be a value from ``run_farm_host_specs`` 
    in :gh-file-ref:`deploy/run-farm-recipes/externally_provisioned.yaml`. Each spec 
    describes the number of FPGAs attached to a system and other properties about the system. We configured this already in the previous step.

* The ``target_config`` section describes the system that we'd like to simulate.

  * ``topology: no_net_config`` indicates that we're running simulations with no network between them.

  * ``no_net_num_nodes: 1`` indicates that we'll be a simulation of a single SoC

  * The ``default_hw_config`` will be set to a pre-built design (for our FPGA, |hwdb_entry_name|) with a single RISC-V Rocket core. This is usually not set by default, but we already set it in the previous step.

* The ``workload`` section describes the workload that we'd like to run on our simulated systems. In this case, we will leave it as the default, which will boot Linux on all SoCs in the simulation.

.. include:: Running-Sims-Bottom-Template.rst

