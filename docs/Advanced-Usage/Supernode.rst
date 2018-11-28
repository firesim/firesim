Supernode
===============

Supernode is designed to improve FPGA resource utilization for smaller designs
and allow realistic rack topology simulation (32 simulated nodes) using a
single ``f1.16xlarge`` instance. Supernode requires slight changes in build and
runtime configurations. Supernode is currently only enabled for RocketChip
designs with NICs. More details about supernode can be found in the `FireSim
ISCA 2018 Paper <https://sagark.org/assets/pubs/firesim-isca2018.pdf>`__.

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

Since Supernode is currently impelmented as a wrapper top level config, most of
the relevant build components can be found in locations similar to target
components. Here, we outline some of the changes between supernode and regular
simulations. 

The Supernode target configuration wrapper can be found in
``firesim/sim/src/main/scala/TargetConfigs.scala``.  An example wrapper configuration is:

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
``FireSimSupernode``, while the ``TARGET_CONFIG`` parameter should be set to the
wrapper configuration that was defined in
``firesim/sim/src/main/scala/TargetConfigs.scala``.  The ``PLATFORM_CONFIG`` can
be selected the same as in regular FireSim configurations.  For example:

::

    DESIGN=FireSimSupernode
    TARGET_CONFIG=SupernodeFireSimRocketChipQuadCoreConfig
    PLATFORM_CONFIG=FireSimDDR3FRFCFSLLC4MBConfig
    instancetype=c4.4xlarge
    deploytriplet=None


We currently do not provide pre-built AGFIs for supernode. You must build your
own, using the supplied samples in ``config_build_recipes.ini``.
Importantly, in order to meet FPGA timing contraints, you must also manually
change the host clock frequency by editing the clock assignment in 
``firesim/platforms/f1/aws-fpga/hdk/cl/developer_designs/cl_firesim/design/cl_firesim.sv``.
This is done by change the following line from :

::
    assign firesim_internal_clock = clock_gend_90;

to

::
    assign firesim_internal_clock = clock_gend_75;


Running simulations
--------------------

Running FireSim in supernode mode follows the same process as in
"regular" mode. Currently, the only difference is that the main simulation
screen remains with the name ``fsim0``, while the three other simulation screens
can be accessed by attaching ``screen`` to uartpty1, uartpty2, uartpty3
respectively. All simulation screens will generate uart logs which will be
copied to the manager as in a "regular" FireSim simulation

Supernode topologies utilize a ``FireSimSuperNodeServerNode`` class in order to
represent one of the 4 simulated target nodes which also represents a single
FPGA mapping, while using a ``FireSimDummyServerNode`` class which represent
the other three simulated target nodes which do not represent an FPGA mapping.
Various example  Supernode topologies are provided, ranging from 4 simulated
target nodes to 1024 simulated target nodes.

Following are a couple of useful examples as templates for writing custom
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
