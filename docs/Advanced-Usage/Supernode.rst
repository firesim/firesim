Supernode
===============

Supernode support is currently in beta. Supernode is designed to improve FPGA
resource utilization for smaller designs and allow realistic rack topology
simulation (32 simulated nodes) using a single ``f1.16xlarge`` instance.  The
supernode beta can be found on the ``supernode-beta`` branch of the FireSim
repository. Supernode requires slight changes in build and runtime
configurations. More details about supernode can be found in the `FireSim ISCA
2018 Paper <https://sagark.org/assets/pubs/firesim-isca2018.pdf>`__.

Intro
-----------

Supernode packs 4 identical designs into a single FPGA, and utilizes all 4 DDR
channels available for each FPGA on AWS F1 instances. It currently does so by
generating a wrapper top level target which encapsualtes the four simulated
target nodes. The packed nodes are treated as 4 separate nodes, are assigned their
own individual MAC addresses, and can perform any action a single node could:
run different programs, interact with each other over the network, utilize
different block device images, etc.

Build
-----------

The Supernode beta can be found on the ``supernode-beta`` branch of the FireSim
repo.  Here, we outline some of the changes between supernode and regular
simulations. The Supernode target wrapper can be found in
``firesim/sim/src/main/scala/SimConfigs.scala``.  For example:

::

    class SupernodeFireSimRocketChipConfig extends Config(
      new WithNumNodes(4) ++
      new FireSimRocketChipConfig)

In this example, ``SupernodeFireSimRocketChipConfig`` is the wrapper, while
``FireSimRocketChipConfig`` is the target node configuration. Therefore, if we
want to simulate a different target configuration, we will generate a new
Supernode wrapper, with the new target configuration. For example:

::

    class SupernodeFireSimRocketChipQuadCoreConfig extends Config(
      new WithNumNodes(4) ++
      new FireSimRocketChipQuadCoreConfig)

Next, when defining the build recipe, we must remmber to use the supernode
configuration: The ``DESIGN`` parameter should always be set to
``SupernodeTop``, while the ``TARGET_CONFIG`` parameter should be set to the
wrapper configuration that was defined in
``firesim/sim/src/main/scala/SimConfigs.scala``.  The ``PLATFORM_CONFIG`` can
be selected the same as in regular FireSim configurations.  For example:

::

    DESIGN=SupernodeTop
    TARGET_CONFIG=SupernodeFireSimRocketChipQuadCoreConfig
    PLATFORM_CONFIG=FireSimDDR3FRFCFSLLC4MBConfig
    instancetype=c4.4xlarge
    deploytriplet=None


We currently do not provide pre-built AGFIs for supernode. You must build your
own, using the supplied samples on the ``supernode-beta`` branch.

Running simulations
--------------------

Running FireSim in supernode mode follows the same process as in
"regular" mode. Currently, the only difference is that the standard input and
standard output of the simulated nodes are written to files in the dispatched
simulation directory, rather than the main simulation screen.

Here are some important pieces that you can use to run an example 32-node config
on a single ``f1.16xlarge``. Better documentation will be available later:

- Sample runtime config: https://github.com/firesim/firesim/blob/supernode-beta/deploy/sample-backup-configs/sample_config_runtime.ini
- Sample topology definition: https://github.com/firesim/firesim/blob/supernode-beta/deploy/runtools/user_topology.py#L33


Work in Progress!
--------------------

We are currently working on restructuring supernode support to support a
wider-variety of use cases. More documentation will follow once we complete
this rewrite.
