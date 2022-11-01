.. _dromajo:

Dromajo Co-simulation with BOOM designs
==================================================

Instead of using TracerV to provide a cycle-by-cycle trace of a target
CPU's architectural state, you can use the `Dromajo co-simulator <https://github.com/chipsalliance/dromajo>`_ to verify
the functionality of a BOOM design.

.. note:: This work is highly experimental. We hope to integrate this into FireSim in a cleaner fashion at a later point.

.. note:: This work currently only works for single core BOOM designs.

.. _dromajo-bridge:

Building a Design with Dromajo
-------------------------------

In all FireChip designs, TracerV is included by default.
To enable Dromajo, you just need to add the Dromajo bridge (``WithDromajoBridge``) to your BOOM target design config (default configs. located in ``$CHIPYARD/generators/firechip/src/main/scala/TargetConfigs.scala``).
An example configuration with Dromajo is shown below:

.. code-block:: scala

    class FireSimLargeBoomConfig extends Config(
      new WithDromajoBridge ++ // add Dromajo bridge to simulation
      new WithDefaultFireSimBridges ++
      new WithDefaultMemModel ++
      new WithFireSimConfigTweaks ++
      new chipyard.LargeBoomConfig)

At this point, you should run the ``firesim buildbitstream`` command for the BOOM config wanted.

Running a FireSim Simulation
----------------------------

To run a simulation with Dromajo, you must modify the workload ``json`` to support Dromajo.
The following is an example using the base Linux workload generated from FireMarshal and modifying it for Dromajo.
Here is the modified workload json (renamed to ``br-base-dromajo`` from ``br-base``):

.. code-block:: json

    {
      "benchmark_name": "br-base-dromajo",
      "common_simulation_outputs": [
        "uartlog",
        "dromajo_snap.re_regs"
      ],
      "common_bootbinary": "../../../../../software/firemarshal/images/br-base-bin",
      "common_rootfs": "../../../../../software/firemarshal/images/br-base.img",
      "common_simulation_inputs": [
        "br-base-bin.rom",
        "br-base-bin.dtb"
      ]
    }

You will notice there are two extra simulation inputs needed compared to the "base" unmodified
``br-base`` workload: a bootrom (``rom``) and a device tree blob (``dtb``).
Both files are found in your generated sources and should be moved into the workload directory (i.e. ``workloads/br-base-dromajo``).

.. code-block:: shell

    cd $CHIPYARD

    # copy/rename the rom file and put in the proper folder
    cp sim/generated-src/f1/<LONG_NAME>/<LONG_NAME>.rom $FIRESIM/deploy/workloads/br-base-dromajo/br-base-bin.rom

    # copy/rename the dtb file and put in the proper folder
    cp sim/generated-src/f1/<LONG_NAME>/<LONG_NAME>.dtb $FIRESIM/deploy/workloads/br-base-dromajo/br-base-bin.dtb

After this process, you should see the following ``workloads/br-base-dromajo`` folder layout:

.. code-block:: shell

    br-base-dromajo/
        br-base-bin.rom
        br-base-bin.dtb
        README

.. note:: The name of the ``rom`` and ``dtb`` files must match the name of the workload binary i.e. ``common_bootbinary``.

At this point you are ready to run the simulation with Dromajo.
The commit log trace will by default print to the ``uartlog``.
However, you can avoid printing it out by changing ``verbose == false`` in the ``dromajo_cosim.cpp`` file
located in ``$CHIPYARD/tools/dromajo/dromajo-src/src/`` folder.

Troubleshooting Dromajo Simulations with Meta-Simulations
----------------------------------------------------------

If FPGA simulation fails with Dromajo, you can use metasimulation to determine if your Dromajo setup is correct.
First refer to :ref:`metasimulation` for more information on metasimulation.
The main difference between those instructions and simulations with Dromajo is that you need to manually point to the ``dtb``, ``rom``, and binary files when invoking the simulator.
Here is an example of a ``make`` command that can be run to check for a correct setup.

.. code-block:: shell

    # enter simulation directory
    cd $FIRESIM/sim/

    # make command to run a binary
    # <BIN> - absolute path to binary
    # <DTB> - absolute path to dtb file
    # <BOOTROM> - absolute path to rom file
    # <YourBoomConfig> - Single-core BOOM configuration to test
    make TARGET_CONFIG=<YourBoomConfig> SIM_BINARY=<BIN> EXTRA_SIM_ARGS="+drj_dtb=<DTB> +drj_rom=<BOOTROM> +drj_bin=<BIN>" run-vcs

It is important to have the ``+drj_*`` arguments, otherwise Dromajo will not match the simulation running on the DUT.

.. note:: Sometimes simulations in VCS will diverge unless a ``+define+RANDOM=0`` is added to the VCS flags in ``sim/midas/src/main/cc/rtlsim/Makefrag-vcs``.

.. warning:: Dromajo currently only works in VCS and FireSim simulations.

