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
However, Dromajo currently doesn't work with TracerV.
To disable TracerV and enable Dromajo, you must navigate to the target design (i.e. Chipyard folder)
and change the default FireSim bridges to use Dromajo.

.. code-block:: shell

    # enter the target design directory
    cd $CHIPYARD_DIR

    # open the BridgeBinders.scala file (in whatever editor works for you)
    vim generators/firechip/src/main/scala/BridgeBinders.scala

Now change the ``WithTracerVBridge`` to ``WithDromajoBridge``.
This enables Dromajo on your design.

At this point, you should run the ``firesim buildafi`` command for the BOOM config wanted.
Before an AFI is generated, the command should fail stating that a ``dromajo_params.h`` file
doesn't exist when trying to create the FireSim simualtion driver.
The following steps create this file from the generated sources from the config.

.. code-block:: shell

    cd $FIRESIM

    # enter the gen-srcs of the specified design
    cd sim/generated-src/f1/<LONG_NAME_OF_CONFIG_YOU_WANT_TO_RUN>

    # rename the "<long_name>.dromajo_params.h" file to the necessary file
    mv <long_name>.dromajo_params.h dromajo_params.h

At this point you should be able to re-run the ``buildafi`` command and get a working afi/driver.

Running a FireSim Simulation
----------------------------

To run a simulation with Dromajo, you must modify the workload json to support Dromajo.
The following is an example using the base Linux workload generated from FireMarshal and modifying it
for Dromajo.
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
``br-base`` workload: a bootrom and a ``dtb``.
The ``rom`` bootrom file is located in Chipyard and should be moved to the workload directory
(i.e. ``workloads/br-base-dromajo``).

.. code-block:: shell

    cd $CHIPYARD

    # copy/rename the rom file and put in the proper folder
    cp generators/testchipip/src/main/resources/testchipip/bootrom/bootrom.rv64.img $FIRESIM/deploy/workloads/br-base-dromajo/br-base-bin.rom

The ``dtb`` file needs to be generated from a ``dts`` file that comes from the generated sources.
Then, it must be moved to the same folder location.

.. code-block:: shell

    cd $FIRESIM

    # enter the gen-srcs of the specified design
    cd sim/generated-src/f1/<LONG_NAME_OF_CONFIG_YOU_WANT_TO_RUN>

    # create the dtb from the dts and put in the proper folder
    dtc -I dts -O dtb -o $FIRESIM/deploy/workloads/br-base-dromajo/br-base-bin.dtb <long_name>.dts

After this process, you should see the following ``workloads/br-base-dromajo`` folder layout:

.. code-block:: shell

    br-base-dromajo/
        br-base-bin.rom
        br-base-bin.dtb
        README

At this point you are ready to run the simulation with Dromajo.
The commit log trace will by default print to the ``uartlog``.
However, you can avoid printing it out by changing ``verbose == false`` in the ``dromajo_cosim.cpp`` file
located in ``$CHIPYARD/tools/dromajo/dromajo-src/src/`` folder.
