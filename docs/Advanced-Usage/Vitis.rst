(Experimental) Using On Premise FPGAs
============================================

FireSim now includes support for Vitis U250 FPGAs!
This section describes a use case on how to setup FireSim for building/running Vitis simulations **locally**.
This section assumes you are very familiar with the normal FireSim setup process, commandline, configuration files, and terminology.

Setup
-----

First, install and setup Vitis/Vivado/XRT to use the U250.

* Install Vitis/Vivado 2021.1 (refer to the Xilinx website for the installers)
* **Important** Build and install XRT manually based off the following commit: https://github.com/Xilinx/XRT/commit/63269b8d4aa04099459c68b283f8512748fb39d6
* Install the U250 board package

Next, setup the FireSim repository.

1. Clone the FireSim repository
2. Use the :gh-file-ref:`scripts/machine-launch-script.sh` to install Conda and the SW packages needed
3. Continue with the FireSim setup as mentioned by :ref:`setting-up-firesim-repo` with the following modifications:

   * Run ``firesim managerinit --platform vitis``

Bitstream Build
---------------

1. Add the following build recipe to your ``config_build_recipes.yaml`` file. This configuration
   is a simple singlecore Rocket configuration with a single DRAM channel and no debugging features.
   Future support will come with more DRAM channels, and the full suite of FireSim debugging features.

::

    firesim_rocket_singlecore_no_nic:
        DESIGN: FireSim
        TARGET_CONFIG: FireSimRocketConfig
        PLATFORM_CONFIG: BaseVitisConfig
        deploy_triplet: null
        platform_config_args:
            fpga_frequency: null
            build_strategy: null
        post_build_hook: null
        metasim_customruntimeconfig: null
        bit_builder_recipe: bit-builder-recipes/vitis.yaml

2. Modify the ``config_build.yaml`` to the following (leaving other sections intact). Note that you
   should modify ``default_build_dir`` appropriately. This sets up running builds locally using the
   externally provisioned build farm.

::

    build_farm:
        base_recipe: build-farm-recipes/externally_provisioned.yaml
        recipe_arg_overrides:
            default_build_dir: <PATH TO WHERE BUILDS SHOULD RUN>

    builds_to_run:
        - firesim_rocket_singlecore_no_nic

3. Run ``firesim buildbitstream``

4. If successful, you should see a ``firesim_rocket_singlecore_no_nic`` HWDB entry in ``deploy/build-hwdb-entries/``.
   It should look something like this:

::

    firesim_rocket_singlecore_no_nic:
        xclbin: <PATH TO BUILT XCLBIN>
        deploy_triplet_override: FireSim-FireSimRocketConfig-BaseVitisConfig
        custom_runtime_config: null

.. Note:: If for some reason the ``buildbitstream`` failed, you can download a pre-built ``xclbin`` here:
   https://people.eecs.berkeley.edu/~abe.gonzalez/firesim_rocket_singlecore_no_nic.xclbin.

Running A Simulation
--------------------

1. Modify the ``config_runtime.yaml`` to the following (leaving other sections intact). Note that you
   should modify ``default_simulation_dir`` appropriately. This sets up running simulations locally using the
   externally provisioned run farm.

::

    run_farm:
      base_recipe: run-farm-recipes/externally_provisioned.yaml
      recipe_arg_overrides:
        default_platform: VitisInstanceDeployManager
        default_simulation_dir: <PATH TO SIMULATION AREA>
        run_farm_hosts_to_use:
            - localhost: one_fpga_spec
        run_farm_host_specs:
            - one_fpga_spec:
                num_fpgas: 1
                num_metasims: 0
                use_for_switch_only: false

    target_config:
        topology: no_net_config
        no_net_num_nodes: 1
        link_latency: 6405
        switching_latency: 10
        net_bandwidth: 200
        profile_interval: -1
        default_hw_config: firesim_rocket_singlecore_no_nic
        plusarg_passthrough: ""

2. Leave or change the single node workload you want to run, and run ``firesim launchrunfarm``,
   ``firesim infrasetup``, ``firesim runworkload``, ``firesim terminaterunfarm`` like normal.
