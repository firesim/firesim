.. _single-node-sim:

Running a Single Node Simulation
===================================

Now that we've completed the setup of our manager instance, it's time to run
a simulation! In this section, we will simulate **1 target node**, for which we
will need a single ``f1.2xlarge`` (1 FPGA) instance.

Make sure you are ``ssh`` or ``mosh``'d into your manager instance and have sourced
``sourceme-f1-manager.sh`` before running any of these commands.


Building target software
------------------------

In these instructions, we'll assume that you want to boot Linux on your
simulated node. To do so, we'll need to build our FireSim-compatible RISC-V
Linux distro. For this tutorial, we will use a simple buildroot-based
distribution. You can do this like so:

::

    cd firesim/sw/firesim-software
    ./sw-manager.py -c br-disk.json build

This process will take about 10 to 15 minutes on a ``c4.4xlarge`` instance.
Once this is completed, you'll have the following files:

-  ``firesim/sw/firesim-software/images/br-disk-bin`` - a bootloader + Linux
   kernel image for the nodes we will simulate.
-  ``firesim/sw/firesim-software/images/br-disk.img`` - a disk image for
   each the nodes we will simulate

These files will be used to form base images to either build more complicated
workloads (see the :ref:`defining-custom-workloads` section) or to copy around
for deploying.

Setting up the manager configuration
-------------------------------------

All runtime configuration options for the manager are set in a file called
``firesim/deploy/config_runtime.ini``. In this guide, we will explain only the
parts of this file necessary for our purposes. You can find full descriptions of
all of the parameters in the :ref:`manager-configuration-files` section.

If you open up this file, you will see the following default config (assuming
you have not modified it):

.. include:: /../deploy/sample-backup-configs/sample_config_runtime.ini
   :code: ini

We'll need to modify a couple of these lines.

First, let's tell the manager to use the correct numbers and types of instances.
You'll notice that in the ``[runfarm]`` section, the manager is configured to
launch a Run Farm named ``mainrunfarm``, consisting of one ``f1.16xlarge`` and
no ``m4.16xlarge``\ s or ``f1.2xlarge``\ s. The tag specified here allows the
manager to differentiate amongst many parallel run farms (each running
a workload) that you may be operating -- but more on that later.

Since we only want to simulate a single node, let's switch to using one
``f1.2xlarge`` and no ``f1.16xlarge``\s. To do so, change this section to:

::

    [runfarm]
    # per aws restrictions, this tag cannot be longer than 255 chars
    runfarmtag=mainrunfarm
    f1_16xlarges=0
    m4_16xlarges=0
    f1_2xlarges=1

You'll see other parameters here, like ``runinstancemarket``,
``spotinterruptionbehavior``, and ``spotmaxprice``. If you're an experienced
AWS user, you can see what these do by looking at the
:ref:`manager-configuration-files` section. Otherwise, don't change them.

Now, let's change the ``[targetconfig]`` section to model the correct target design.
By default, it is set to model an 8-node cluster with a cycle-accurate network.
Instead, we want to model a single-node with no network. To do so, we will need
to change a few items in this section:

::

    [targetconfig]
    topology=no_net_config
    no_net_num_nodes=1
    linklatency=6405
    switchinglatency=10
    netbandwidth=200

    # This references a section from config_hwconfigs.ini
    # In homogeneous configurations, use this to set the hardware config deployed
    # for all simulators
    defaulthwconfig=firesim-quadcore-no-nic-ddr3-llc4mb


Note that we changed three of the parameters here: ``topology`` is now set to
``no_net_config``, indicating that we do not want a network. Then,
``no_net_num_nodes`` is set to ``1``, indicating that we only want to simulate
one node. Lastly, we changed ``defaulthwconfig`` from
``firesim-quadcore-nic-ddr3-llc4mb`` to
``firesim-quadcore-no-nic-ddr3-llc4mb``.  Notice the subtle difference in this
last option? All we did is switch to a hardware configuration that does not
have a NIC. This hardware configuration models a Quad-core Rocket Chip with 4
MB of L2 cache and 16 GB of DDR3, and **no** network interface card.

We will leave the last section (``[workload]``) unchanged here, since we do
want to run the buildroot-based Linux on our simulated system. The ``terminateoncompletion``
feature is an advanced feature that you can learn more about in the
:ref:`manager-configuration-files` section.

As a final sanity check, your ``config_runtime.ini`` file should now look like this:

::

	# RUNTIME configuration for the FireSim Simulation Manager
	# See docs/Configuration-Details.rst for documentation of all of these params.

	[runfarm]
	runfarmtag=mainrunfarm

	f1_16xlarges=0
	m4_16xlarges=0
	f1_2xlarges=1

	runinstancemarket=ondemand
	spotinterruptionbehavior=terminate
	spotmaxprice=ondemand

	[targetconfig]
	topology=no_net_config
	no_net_num_nodes=1
	linklatency=6405
	switchinglatency=10
	netbandwidth=200

	# This references a section from config_hwconfigs.ini
	# In homogeneous configurations, use this to set the hardware config deployed
	# for all simulators
	defaulthwconfig=firesim-quadcore-no-nic-ddr3-llc4mb

	[workload]
	workloadname=linux-uniform.json
	terminateoncompletion=no


.. attention::

    **[Advanced users] Simulating BOOM instead of Rocket Chip**: If you would like to simulate a single-core `BOOM <https://github.com/ucb-bar/riscv-boom>`__ as a target, set ``defaulthwconfig`` to ``fireboom-singlecore-no-nic-ddr3-llc4mb``.


Launching a Simulation!
-----------------------------

Now that we've told the manager everything it needs to know in order to run
our single-node simulation, let's actually launch an instance and run it!

Starting the Run Farm
^^^^^^^^^^^^^^^^^^^^^^^^^



First, we will tell the manager to launch our Run Farm, as we specified above.
When you do this, you will start getting charged for the running EC2 instances
(in addition to your manager).

To do launch your run farm, run:

::

    firesim launchrunfarm

You should expect output like the following:

::

	centos@ip-172-30-2-111.us-west-2.compute.internal:~/firesim-new/deploy$ firesim launchrunfarm
	FireSim Manager. Docs: http://docs.fires.im
	Running: launchrunfarm

	Waiting for instance boots: f1.16xlarges
	Waiting for instance boots: m4.16xlarges
	Waiting for instance boots: f1.2xlarges
	i-0d6c29ac507139163 booted!
	The full log of this run is:
	/home/centos/firesim-new/deploy/logs/2018-05-19--00-19-43-launchrunfarm-B4Q2ROAK0JN9EDE4.log


The output will rapidly progress to ``Waiting for instance boots: f1.2xlarges``
and then take a minute or two while your ``f1.2xlarge`` instance launches.
Once the launches complete, you should see the instance id printed and the instance
will also be visible in your AWS EC2 Management console. The manager will tag
the instances launched with this operation with the value you specified above
as the ``runfarmtag`` parameter from the ``config_runtime.ini`` file, which we left
set as ``mainrunfarm``. This value allows the manager to tell multiple Run Farms
apart -- i.e., you can have multiple independent Run Farms running different
workloads/hardware configurations in parallel. This is detailed in the
:ref:`manager-configuration-files` and the :ref:`firesim-launchrunfarm` 
sections -- you do not need to be familiar with it here.

Setting up the simulation infrastructure
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The manager will also take care of building and deploying all software
components necessary to run your simulation. The manager will also handle
flashing FPGAs. To tell the manager to setup our simulation infrastructure,
let's run:

::

    firesim infrasetup


For a complete run, you should expect output like the following:

::

	centos@ip-172-30-2-111.us-west-2.compute.internal:~/firesim-new/deploy$ firesim infrasetup
	FireSim Manager. Docs: http://docs.fires.im
	Running: infrasetup

	Building FPGA software driver for FireSimNoNIC-FireSimRocketChipQuadCoreConfig-FireSimDDR3FRFCFSLLC4MBConfig
	[172.30.2.174] Executing task 'instance_liveness'
	[172.30.2.174] Checking if host instance is up...
	[172.30.2.174] Executing task 'infrasetup_node_wrapper'
	[172.30.2.174] Copying FPGA simulation infrastructure for slot: 0.
	[172.30.2.174] Installing AWS FPGA SDK on remote nodes.
	[172.30.2.174] Unloading EDMA Driver Kernel Module.
	[172.30.2.174] Copying AWS FPGA EDMA driver to remote node.
	[172.30.2.174] Clearing FPGA Slot 0.
	[172.30.2.174] Flashing FPGA Slot: 0 with agfi: agfi-0eaa90f6bb893c0f7.
	[172.30.2.174] Loading EDMA Driver Kernel Module.
	The full log of this run is:
	/home/centos/firesim-new/deploy/logs/2018-05-19--00-32-02-infrasetup-9DJJCX29PF4GAIVL.log

Many of these tasks will take several minutes, especially on a clean copy of
the repo.  The console output here contains the "user-friendly" version of the
output. If you want to see detailed progress as it happens, ``tail -f`` the
latest logfile in ``firesim/deploy/logs/``.

At this point, the ``f1.2xlarge`` instance in our Run Farm has all the infrastructure
necessary to run a simulation. 

So, let's launch our simulation!


Running a simulation!
^^^^^^^^^^^^^^^^^^^^^^^^^

Finally, let's run our simulation! To do so, run:

::

	firesim runworkload


This command boots up a simulation and prints out the live status of the simulated
nodes every 10s. When you do this, you will initially see output like:

::

	centos@ip-172-30-2-111.us-west-2.compute.internal:~/firesim-new/deploy$ firesim runworkload
	FireSim Manager. Docs: http://docs.fires.im
	Running: runworkload

	Creating the directory: /home/centos/firesim-new/deploy/results-workload/2018-05-19--00-38-52-linux-uniform/
	[172.30.2.174] Executing task 'instance_liveness'
	[172.30.2.174] Checking if host instance is up...
	[172.30.2.174] Executing task 'boot_simulation_wrapper'
	[172.30.2.174] Starting FPGA simulation for slot: 0.
	[172.30.2.174] Executing task 'monitor_jobs_wrapper'

If you don't look quickly, you might miss it, since it will get replaced with a
live status page:

::

	FireSim Simulation Status @ 2018-05-19 00:38:56.062737
	--------------------------------------------------------------------------------
	This workload's output is located in:
	/home/centos/firesim-new/deploy/results-workload/2018-05-19--00-38-52-linux-uniform/
	This run's log is located in:
	/home/centos/firesim-new/deploy/logs/2018-05-19--00-38-52-runworkload-JS5IGTV166X169DZ.log
	This status will update every 10s.
	--------------------------------------------------------------------------------
	Instances
	--------------------------------------------------------------------------------
	Instance IP:   172.30.2.174 | Terminated: False
	--------------------------------------------------------------------------------
	Simulated Switches
	--------------------------------------------------------------------------------
	--------------------------------------------------------------------------------
	Simulated Nodes/Jobs
	--------------------------------------------------------------------------------
	Instance IP:   172.30.2.174 | Job: linux-uniform0 | Sim running: True
	--------------------------------------------------------------------------------
	Summary
	--------------------------------------------------------------------------------
	1/1 instances are still running.
	1/1 simulations are still running.
	--------------------------------------------------------------------------------


This will only exit once all of the simulated nodes have shut down. So, let's let it 
run and open another ssh connection to the manager instance. From there, ``cd`` into
your firesim directory again and ``source sourceme-f1-manager.sh`` again to get
our ssh key setup. To access our simulated system, ssh into the IP address being
printed by the status page, **from your manager instance**. In our case, from
the above output, we see that our simulated system is running on the instance with
IP ``172.30.2.174``. So, run:

::

	[RUN THIS ON YOUR MANAGER INSTANCE!]
	ssh 172.30.2.174

This will log you into the instance running the simulation. Then, to attach to the
console of the simulated system, run:

::

	screen -r fsim0

Voila! You should now see Linux booting on the simulated system and then be prompted
with a Linux login prompt, like so:


::

	[truncated Linux boot output]
	[    0.020000] VFS: Mounted root (ext2 filesystem) on device 254:0.
	[    0.020000] devtmpfs: mounted
	[    0.020000] Freeing unused kernel memory: 140K
	[    0.020000] This architecture does not have kernel memory protection.
	mount: mounting sysfs on /sys failed: No such device
	Starting logging: OK
	Starting mdev...
	mdev: /sys/dev: No such file or directory
	modprobe: can't change directory to '/lib/modules': No such file or directory
	Initializing random number generator... done.
	Starting network: ip: SIOCGIFFLAGS: No such device
	ip: can't find device 'eth0'
	FAIL
	Starting dropbear sshd: OK

	Welcome to Buildroot
	buildroot login:


You can ignore the messages about the network -- that is expected because we
are simulating a design without a NIC.

Now, you can login to the system! The username is ``root`` and the password is
``firesim``. At this point, you should be presented with a regular console,
where you can type commands into the simulation and run programs. For example:

::

	Welcome to Buildroot
	buildroot login: root
	Password:
	# uname -a
	Linux buildroot 4.15.0-rc6-31580-g9c3074b5c2cd #1 SMP Thu May 17 22:28:35 UTC 2018 riscv64 GNU/Linux
	#


At this point, you can run workloads as you'd like. To finish off this tutorial,
let's poweroff the simulated system and see what the manager does. To do so,
in the console of the simulated system, run ``poweroff -f``:


::

	Welcome to Buildroot
	buildroot login: root
	Password:
	# uname -a
	Linux buildroot 4.15.0-rc6-31580-g9c3074b5c2cd #1 SMP Thu May 17 22:28:35 UTC 2018 riscv64 GNU/Linux
	# poweroff -f

You should see output like the following from the simulation console:

::

	# poweroff -f
	[   12.456000] reboot: Power down
	Power off
	time elapsed: 468.8 s, simulation speed = 88.50 MHz
	*** PASSED *** after 41492621244 cycles
	Runs 41492621244 cycles
	[PASS] FireSimNoNIC Test
	SEED: 1526690334
	Script done, file is uartlog

	[screen is terminating]


You'll also notice that the manager polling loop exited! You'll see output like this
from the manager:

::

	FireSim Simulation Status @ 2018-05-19 00:46:50.075885
	--------------------------------------------------------------------------------
	This workload's output is located in:
	/home/centos/firesim-new/deploy/results-workload/2018-05-19--00-38-52-linux-uniform/
	This run's log is located in:
	/home/centos/firesim-new/deploy/logs/2018-05-19--00-38-52-runworkload-JS5IGTV166X169DZ.log
	This status will update every 10s.
	--------------------------------------------------------------------------------
	Instances
	--------------------------------------------------------------------------------
	Instance IP:   172.30.2.174 | Terminated: False
	--------------------------------------------------------------------------------
	Simulated Switches
	--------------------------------------------------------------------------------
	--------------------------------------------------------------------------------
	Simulated Nodes/Jobs
	--------------------------------------------------------------------------------
	Instance IP:   172.30.2.174 | Job: linux-uniform0 | Sim running: False
	--------------------------------------------------------------------------------
	Summary
	--------------------------------------------------------------------------------
	1/1 instances are still running.
	0/1 simulations are still running.
	--------------------------------------------------------------------------------
	FireSim Simulation Exited Successfully. See results in:
	/home/centos/firesim-new/deploy/results-workload/2018-05-19--00-38-52-linux-uniform/
	The full log of this run is:
	/home/centos/firesim-new/deploy/logs/2018-05-19--00-38-52-runworkload-JS5IGTV166X169DZ.log


If you take a look at the workload output directory given in the manager output (in this case, ``/home/centos/firesim-new/deploy/results-workload/2018-05-19--00-38-52-linux-uniform/``), you'll see the following:

::

	centos@ip-172-30-2-111.us-west-2.compute.internal:~/firesim-new/deploy/results-workload/2018-05-19--00-38-52-linux-uniform$ ls -la */*
	-rw-rw-r-- 1 centos centos  797 May 19 00:46 linux-uniform0/memory_stats.csv
	-rw-rw-r-- 1 centos centos  125 May 19 00:46 linux-uniform0/os-release
	-rw-rw-r-- 1 centos centos 7316 May 19 00:46 linux-uniform0/uartlog

What are these files? They are specified to the manager in a configuration file
(``firesim/deploy/workloads/linux-uniform.json``) as files that we want
automatically copied back to our manager after we run a simulation, which is
useful for running benchmarks automatically. The
:ref:`defining-custom-workloads` section describes this process in detail.

For now, let's wrap-up our tutorial by terminating the ``f1.2xlarge`` instance
that we launched. To do so, run:

::

	firesim terminaterunfarm

Which should present you with the following:

::

	centos@ip-172-30-2-111.us-west-2.compute.internal:~/firesim-new/deploy$ firesim terminaterunfarm
	FireSim Manager. Docs: http://docs.fires.im
	Running: terminaterunfarm

	IMPORTANT!: This will terminate the following instances:
	f1.16xlarges
	[]
	m4.16xlarges
	[]
	f1.2xlarges
	['i-0d6c29ac507139163']
	Type yes, then press enter, to continue. Otherwise, the operation will be cancelled.

You must type ``yes`` then hit enter here to have your instances terminated. Once
you do so, you will see:

::

	[ truncated output from above ]
	Type yes, then press enter, to continue. Otherwise, the operation will be cancelled.
	yes
	Instances terminated. Please confirm in your AWS Management Console.
	The full log of this run is:
	/home/centos/firesim-new/deploy/logs/2018-05-19--00-51-54-terminaterunfarm-T9ZAED3LJUQQ3K0N.log

**At this point, you should always confirm in your AWS management console that
the instance is in the shutting-down or terminated states. You are ultimately
responsible for ensuring that your instances are terminated appropriately.**

Congratulations on running your first FireSim simulation! At this point, you can
check-out some of the advanced features of FireSim in the sidebar to the left
(for example, we expect that many people will be interested in the ability to
automatically run the SPEC17 benchmarks: :ref:`spec-2017`), or you can continue
on with the cluster simulation tutorial.

