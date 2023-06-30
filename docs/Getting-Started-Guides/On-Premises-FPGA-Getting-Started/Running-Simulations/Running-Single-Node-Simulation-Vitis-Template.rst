
.. include:: Running-Sims-Top-Template.rst

Setting up the manager configuration
------------------------------------------------------------

All runtime configuration options for the manager are set in a file called
``firesim/deploy/config_runtime.yaml``. In this guide, we will explain only the
parts of this file necessary for our purposes. You can find full descriptions of
all of the parameters in the :ref:`manager-configuration-files` section.

If you open up this file, you will see the following default config (assuming
you have not modified it):

.. include:: DOCS_EXAMPLE_config_runtime.yaml
   :code: yaml

We'll need to modify a couple of these lines.

First, let's tell the manager to use the single |fpga_type| FPGA.
You'll notice that in the ``run_farm`` mapping which describes and specifies the machines to run simulations on.
First notice that the ``base_recipe`` maps to ``run-farm-recipes/externally_provisioned.yaml``.
This indicates to the FireSim manager that the machines allocated to run simulations will be provided by the user through IP addresses
instead of automatically launched and allocated (e.g. launching instances on-demand in AWS).
Let's modify the ``default_platform`` to be |deploy_manager_code| so that we can launch simulations using |runner|.
Next, modify the ``default_simulation_dir`` to a directory that you want to store temporary simulation collateral to.
When running simulations, this directory is used to store any temporary files that the simulator creates (e.g. a uartlog emitted by a Linux simulation).
Next, lets modify the ``run_farm_hosts_to_use`` mapping.
This maps IP addresses (i.e. ``localhost``) to a description/specification of the simulation machine.
In this case, we have only one |fpga_type| FPGA so we will change the description of ``localhost`` to ``one_fpga_spec``.

Now, let's verify that the ``target_config`` mapping will model the correct target design.
By default, it is set to model a single-node with no network.
It should look like the following:

.. code-block:: yaml

    target_config:
        topology: no_net_config
        no_net_num_nodes: 1
        link_latency: 6405
        switching_latency: 10
        net_bandwidth: 200
        profile_interval: -1

        # This references a section from config_hwdb.yaml
        # In homogeneous configurations, use this to set the hardware config deployed
        # for all simulators
        default_hw_config: firesim_rocket_quadcore_no_nic_l2_llc4mb_ddr3

Note ``topology`` is set to
``no_net_config``, indicating that we do not want a network. Then,
``no_net_num_nodes`` is set to ``1``, indicating that we only want to simulate
one node. Lastly, the ``default_hw_config`` is
``firesim_rocket_quadcore_no_nic_l2_llc4mb_ddr3``.
Let's modify the ``default_hw_config`` (the target design) to "|hwdb_entry_name|".
This new hardware configuration does not
have a NIC and is pre-built for the |fpga_type| FPGA.
This hardware configuration models a Single-core Rocket Chip SoC and **no** network interface card.

We will leave the ``workload`` mapping unchanged here, since we do
want to run the buildroot-based Linux on our simulated system. The ``terminate_on_completion``
feature is an advanced feature that you can learn more about in the
:ref:`manager-configuration-files` section.

As a final sanity check, in the mappings we changed, the ``config_runtime.yaml`` file should now look like this (with ``PATH_TO_SIMULATION_AREA`` replaced with your simulation collateral temporary directory):

.. code-block:: text
   :substitutions:

    run_farm:
      base_recipe: run-farm-recipes/externally_provisioned.yaml
      recipe_arg_overrides:
        default_platform: |deploy_manager|
        default_simulation_dir: <PATH_TO_SIMULATION_AREA>
        run_farm_hosts_to_use:
            - localhost: one_fpga_spec

    target_config:
        topology: no_net_config
        no_net_num_nodes: 1
        link_latency: 6405
        switching_latency: 10
        net_bandwidth: 200
        profile_interval: -1
        default_hw_config: |hwdb_entry_name|
        plusarg_passthrough: ""

    workload:
        workload_name: linux-uniform.json
        terminate_on_completion: no
        suffix_tag: null


.. include:: Running-Sims-Bottom-Template.rst

