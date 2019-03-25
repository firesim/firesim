Supernode - Multiple Simulated SoCs Per FPGA
============================================

Supernode allows users to run multiple simulated SoCs per-FPGA in order to improve
FPGA resource utilization and reduce cost. For example, in the case of using
FireSim to simulate a datacenter scale system, supernode mode allows realistic
rack topology simulation (32 simulated nodes) using a single ``f1.16xlarge``
instance (8 FPGAs).

Below, we outline the build and runtime configuration changes needed to utilize
supernode designs. Supernode is currently only enabled for RocketChip designs
with NICs. More details about supernode can be found in the `FireSim ISCA 2018
Paper <https://sagark.org/assets/pubs/firesim-isca2018.pdf>`__.

Introduction
--------------

By default, supernode packs 4 identical designs into a single FPGA, and
utilizes all 4 DDR channels available on each FPGA on AWS F1 instances. It
currently does so by generating a wrapper top level target which encapsualtes
the four simulated target nodes. The packed nodes are treated as 4 separate
nodes, are assigned their own individual MAC addresses, and can perform any
action a single node could: run different programs, interact with each other
over the network, utilize different block device images, etc. In the networked
case, 4 separate network links are presented to the switch-side.

Building Supernode Designs
----------------------------

Here, we outline some of the changes between supernode and regular simulations
that are required to build supernode designs.

The Supernode target configuration wrapper can be found in
``firesim/sim/src/main/scala/firesim/TargetConfigs.scala``.  An example wrapper
configuration is:

::

    class SupernodeFireSimRocketChipConfig extends Config(new WithNumNodes(4)
    ++ new FireSimRocketChipConfig)

In this example, ``SupernodeFireSimRocketChipConfig`` is the wrapper, while
``FireSimRocketChipConfig`` is the target node configuration. To simulate a
different target configuration, we will generate a new supernode wrapper, with
the new target configuration. For example, to simulate 4 quad-core nodes on one
FPGA, you can use:

::

    class SupernodeFireSimRocketChipQuadCoreConfig extends Config(new
    WithNumNodes(4) ++ new FireSimRocketChipQuadCoreConfig)


Next, when defining the build recipe, we must remmber to use the supernode
configuration: The ``DESIGN`` parameter should always be set to
``FireSimSupernode``, while the ``TARGET_CONFIG`` parameter should be set to
the wrapper configuration that was defined in
``firesim/sim/src/main/scala/firesim/TargetConfigs.scala``.  The
``PLATFORM_CONFIG`` can be selected the same as in regular FireSim
configurations.  For example:

::

    DESIGN=FireSimSupernode
    TARGET_CONFIG=SupernodeFireSimRocketChipQuadCoreConfig
    PLATFORM_CONFIG=FireSimDDR3FRFCFSLLC4MBConfig90MHz
    instancetype=c4.4xlarge
    deploytriplet=None


We currently provide a single pre-built AGFI for supernode of 4 quad-core
RocketChips with DDR3 memory models. You can build your own AGFI, using the supplied samples in
``config_build_recipes.ini``.  Importantly, in order to meet FPGA timing
contraints, Supernode target may require lower host clock frequencies.
host clock frequencies can be configured as parts of the PLATFORM_CONFIG in
``config_build_recipes.ini``.

Running Supernode Simulations
-----------------------------

Running FireSim in supernode mode follows the same process as in
"regular" mode. Currently, the only difference is that the main simulation
screen remains with the name ``fsim0``, while the three other simulation screens
can be accessed by attaching ``screen`` to ``uartpty1``, ``uartpty2``, ``uartpty3``
respectively. All simulation screens will generate uart logs (``uartlog1``,
``uartlog2``, ``uartlog3``). Notice that you must use ``sudo`` in order to
attach to the uartpty or view the uart logs. The additional uart logs will not
be copied back to the manager instance by default (as in a "regular" FireSim
simulation). It is neccessary to specify the copying of the additional uartlogs
(uartlog1, uartlog2, uartlog3) in the workload definition.

Supernode topologies utilize a ``FireSimSuperNodeServerNode`` class in order to
represent one of the 4 simulated target nodes which also represents a single
FPGA mapping, while using a ``FireSimDummyServerNode`` class which represent
the other three simulated target nodes which do not represent an FPGA mapping.
In supernode mode, topologies should always add nodes in pairs of 4, as one
``FireSimSuperNodeServerNode`` and three ``FireSimDummyServerNode`` s.

Various example Supernode topologies are provided, ranging from 4 simulated
target nodes to 1024 simulated target nodes.

Below are a couple of useful examples as templates for writing custom
Supernode topologies.


A sample Supernode topology of 4 simulated target nodes which can fit on a
single ``f1.2xlarge`` is:

::

    def supernode_example_4config(self):
        self.roots = [FireSimSwitchNode()]
        servers = [FireSimSuperNodeServerNode()] + [FireSimDummyServerNode() for x in range(3)]
        self.roots[0].add_downlinks(servers)


A sample Supernode topology of 32 simulated target nodes which can fit on a
single ``f1.16xlarge`` is:

::

    def supernode_example_32config(self):
        self.roots = [FireSimSwitchNode()]
        servers = UserTopologies.supernode_flatten([[FireSimSuperNodeServerNode(), FireSimDummyServerNode(), FireSimDummyServerNode(), FireSimDummyServerNode()] for y in range(8)])
        self.roots[0].add_downlinks(servers)


Supernode ``config_runtime.ini`` requires selecting a supernode agfi in conjunction with a defined supernode topology.


Work in Progress!
--------------------

We are currently working on restructuring supernode to support a
wider-variety of use cases (including non-networked cases, and increased
packing of nodes). More documentation will follow.
Not all FireSim features are currently available on Supernode. As a
rule-of-thumb, target-related features have a higher likelihood of being
supported "out-of-the-box", while features which involve external interfaces
(such as TracerV) has a lesser likelihood of being supported "out-of-the-box"
