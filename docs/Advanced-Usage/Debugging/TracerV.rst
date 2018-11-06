Debugging Using TracerV
=======================

FireSim can provide a cycle-by-cycle trace of the CPU's architectural state
over the course of execution. This can be useful for profiling or debugging.
The tracing functionality is provided by the TracerV widget.

Building a Design with TracerV
------------------------------

To use TracerV in your design, you must build and use one of the target
configurations that contain the tracer. For instance, if you are using the
FireSimRocketChipQuadCoreConfig, switch to the
FireSimRocketCoreQuadCoreTracedConfig.

In TargetConfigs.scala:

.. code-block:: scala

    class FireSimRocketChipQuadCoreTracedConfig extends Config(
      new WithTraceRocket ++ new FireSimRocketChipQuadCoreConfig)
    
In config_build_recipes.ini:

.. code-block:: ini

    [firesim-quadcore-traced-nic-ddr3-llc4mb]
    DESIGN=FireSim
    TARGET_CONFIG=FireSimRocketChipQuadCoreTracedConfig
    PLATFORM_CONFIG=FireSimDDR3FRFCFSLLC4MBConfig
    instancetype=c4.4xlarge
    deploytriplet=None

In config_build.ini:

.. code-block:: ini

    [builds]
    firesim-quadcore-traced-nic-ddr3-llc4mb

Then run "firesim buildafi" to build an FPGA image. Add the resulting AGFI as
a new entry in config_hwdb.ini.

.. code-block:: ini

    [firesim-quadcore-traced-nic-ddr3-llc4mb]
    agfi=agfi-XXXXX
    deploytripletoverride=None
    customruntimeconfig=None

Finally, use this image as the defaulthwconfig in config_runtime.ini.

Enabling Tracing at Runtime
---------------------------

By default, FireSim will not collect data from the TracerV widget, even if it
is included. To enable collection, add a new section to your config_runtime.ini.

.. code-block:: ini

    [tracing]
    enable=yes

Now when you run a workload, a trace output file will be placed in the
sim_slot_X directory on the F1 instance under the name TRACEFILE.
Tracing the entirety of a long-running job like a Linux-based workload can
generate a pretty large image, and you may only care about the state within a
certain timeframe. Therefore, FireSim allows you to specify a start cycle and
end cycle for collecting data. By default, it starts at cycle 0 and ends at
the last cycle of the simulation. To change this, add the following under
the "tracing" section.

.. code-block:: ini

    startcycle=XXXX
    endcycle=YYYY

Interpreting the Trace Result
-----------------------------
