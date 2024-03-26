.. _spike:

Spike Co-simulation with BOOM designs
==================================================

Instead of using TracerV to provide a cycle-by-cycle trace of a target
CPU's architectural state, you can use the `Spike co-simulator <https://github.com/riscv-software-src/riscv-isa-sim>`_ to verify
the functionality of a BOOM design.

.. note:: This work currently only works for single core BOOM designs.

.. note:: Cospike only supports non block-device simulations at this time.


.. _cospike-bridge:

Building a Design with Cospike
-------------------------------

In all FireChip designs, TracerV is included by default.
To enable Cospike, you just need to add the Cospike bridge (``WithCospikeBridge``) to your BOOM target design config (default configs. located in ``$CHIPYARD/generators/firechip/src/main/scala/TargetConfigs.scala``).
An example configuration with Cospike is shown below:

.. code-block:: scala

    class FireSimLargeBoomConfig extends Config(
      new WithCospikeBridge ++ // add Cospike bridge to simulation
      new WithDefaultFireSimBridges ++
      new WithDefaultMemModel ++
      new WithFireSimConfigTweaks ++
      new chipyard.LargeBoomV3Config)

At this point, you should run the ``firesim buildbitstream`` command for the BOOM config wanted.
At this point you are ready to run the simulation with Cospike by default enabled.

Troubleshooting Cospike Simulations with Meta-Simulations
----------------------------------------------------------

If FPGA simulation fails with Cospike, you can use metasimulation to determine if your Cospike setup is correct.
First refer to :ref:`metasimulation` for more information on metasimulation.

.. note:: Sometimes simulations in VCS will diverge unless a ``+define+RANDOM=0`` is added to the VCS flags in ``sim/midas/src/main/cc/rtlsim/Makefrag-vcs``.
