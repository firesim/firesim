.. _cluster-sim:

Running a Cluster Simulation
===============================

Now, let's move on to simulating a cluster of eight nodes, interconnected
by a network with one 8-port Top-of-Rack (ToR) switch and 200 Gbps, 2μs links.
This will require one ``f1.16xlarge`` (8 FPGA) instance.

Make sure you are ``ssh`` or ``mosh``'d into your manager instance and have sourced
``sourceme-f1-manager.sh`` before running any of these commands.

Returning to a clean configuration
-------------------------------------

If you already ran the single-node tutorial, let's return to a clean FireSim
manager configuration by doing the following:

::

    cd firesim/deploy
    cp sample-backup-configs/sample_config_runtime.ini config_runtime.ini


Building target software
------------------------

If you already built target software during the single-node tutorial, you can
skip to the next part (Setting up the manager configuration). If you haven't followed the single-node tutorial,
continue with this section.

In these instructions, we'll assume that you want to boot the buildroot-based
Linux distribution on each of the nodes in your simulated cluster. To do so,
we'll need to build our FireSim-compatible RISC-V Linux distro. You can do
this like so:

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

For the 8-node cluster simulation, the defaults in this file are exactly what
we want. Let's outline the important parameters:

* ``f1_16xlarges=1``: This tells the manager that we want to launch one ``f1.16xlarge`` when we call the ``launchrunfarm`` command.
* ``topology=example_8config``: This tells the manager to use the topology named ``example_8config`` which is defined in ``deploy/runtools/user_topology.py``. This topology simulates an 8-node cluster with one ToR switch.
* ``linklatency=6405``: This models a network with 6405 cycles of link latency. Since we are modeling processors running at 3.2 Ghz, 1 cycle = 1/3.2 ns, so 6405 cycles is roughly 2 microseconds.
* ``switchinglatency=10``: This models switches with a minimum port-to-port latency of 10 cycles.
* ``netbandwidth=200``: This sets the bandwidth of the NICs to 200 Gbit/s. Currently you can set any integer value less than this without making hardware modifications.
* ``defaulthwconfig=firesim-quadcore-nic-ddr3-llc4mb``: This tells the manager to use a quad-core Rocket Chip configuration with 4 MB of L2 and 16 GB of DDR3, with a NIC, for each of the simulated nodes in the topology.

You'll see other parameters here, like ``runinstancemarket``,
``spotinterruptionbehavior``, and ``spotmaxprice``. If you're an experienced
AWS user, you can see what these do by looking at the
:ref:`manager-configuration-files` section. Otherwise, don't change them.

As in the single-node tutorial, we will leave the last section (``[workload]``)
unchanged here, since we do want to run the buildroot-based Linux on our
simulated system. The ``terminateoncompletion`` feature is an advanced feature
that you can learn more about in the :ref:`manager-configuration-files`
section.

As a final sanity check, your ``config_runtime.ini`` file should now look like this:


.. include:: /../deploy/sample-backup-configs/sample_config_runtime.ini
   :code: ini


.. attention::

    **[Advanced users] Simulating BOOM instead of Rocket Chip**: If you would like to simulate a single-core `BOOM <https://github.com/ucb-bar/riscv-boom>`__ as a target, set ``defaulthwconfig`` to ``fireboom-singlecore-nic-ddr3-llc4mb``.


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
    i-09e5491cce4d5f92d booted!
    Waiting for instance boots: m4.16xlarges
    Waiting for instance boots: f1.2xlarges
    The full log of this run is:
    /home/centos/firesim-new/deploy/logs/2018-05-19--06-05-53-launchrunfarm-ZGVP753DSU1Y9Q6R.log


The output will rapidly progress to ``Waiting for instance boots: f1.16xlarges``
and then take a minute or two while your ``f1.16xlarge`` instance launches.
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
components necessary to run your simulation (including switches for the networked
case). The manager will also handle
flashing FPGAs. To tell the manager to setup our simulation infrastructure,
let's run:

::

    firesim infrasetup


For a complete run, you should expect output like the following:

::

    centos@ip-172-30-2-111.us-west-2.compute.internal:~/firesim-new/deploy$ firesim infrasetup
    FireSim Manager. Docs: http://docs.fires.im
    Running: infrasetup

    Building FPGA software driver for FireSim-FireSimRocketChipQuadCoreConfig-FireSimDDR3FRFCFSLLC4MBConfig
    Building switch model binary for switch switch0
    [172.30.2.178] Executing task 'instance_liveness'
    [172.30.2.178] Checking if host instance is up...
    [172.30.2.178] Executing task 'infrasetup_node_wrapper'
    [172.30.2.178] Copying FPGA simulation infrastructure for slot: 0.
    [172.30.2.178] Copying FPGA simulation infrastructure for slot: 1.
    [172.30.2.178] Copying FPGA simulation infrastructure for slot: 2.
    [172.30.2.178] Copying FPGA simulation infrastructure for slot: 3.
    [172.30.2.178] Copying FPGA simulation infrastructure for slot: 4.
    [172.30.2.178] Copying FPGA simulation infrastructure for slot: 5.
    [172.30.2.178] Copying FPGA simulation infrastructure for slot: 6.
    [172.30.2.178] Copying FPGA simulation infrastructure for slot: 7.
    [172.30.2.178] Installing AWS FPGA SDK on remote nodes.
    [172.30.2.178] Unloading EDMA Driver Kernel Module.
    [172.30.2.178] Copying AWS FPGA EDMA driver to remote node.
    [172.30.2.178] Clearing FPGA Slot 0.
    [172.30.2.178] Clearing FPGA Slot 1.
    [172.30.2.178] Clearing FPGA Slot 2.
    [172.30.2.178] Clearing FPGA Slot 3.
    [172.30.2.178] Clearing FPGA Slot 4.
    [172.30.2.178] Clearing FPGA Slot 5.
    [172.30.2.178] Clearing FPGA Slot 6.
    [172.30.2.178] Clearing FPGA Slot 7.
    [172.30.2.178] Flashing FPGA Slot: 0 with agfi: agfi-09e85ffabe3543903.
    [172.30.2.178] Flashing FPGA Slot: 1 with agfi: agfi-09e85ffabe3543903.
    [172.30.2.178] Flashing FPGA Slot: 2 with agfi: agfi-09e85ffabe3543903.
    [172.30.2.178] Flashing FPGA Slot: 3 with agfi: agfi-09e85ffabe3543903.
    [172.30.2.178] Flashing FPGA Slot: 4 with agfi: agfi-09e85ffabe3543903.
    [172.30.2.178] Flashing FPGA Slot: 5 with agfi: agfi-09e85ffabe3543903.
    [172.30.2.178] Flashing FPGA Slot: 6 with agfi: agfi-09e85ffabe3543903.
    [172.30.2.178] Flashing FPGA Slot: 7 with agfi: agfi-09e85ffabe3543903.
    [172.30.2.178] Loading EDMA Driver Kernel Module.
    [172.30.2.178] Copying switch simulation infrastructure for switch slot: 0.
    The full log of this run is:
    /home/centos/firesim-new/deploy/logs/2018-05-19--06-07-33-infrasetup-2Z7EBCBIF2TSI66Q.log


Many of these tasks will take several minutes, especially on a clean copy of
the repo (in particular, ``f1.16xlarges`` usually take a couple of minutes to
start, so don't be alarmed if you're stuck at ``Checking if host instance is
up...``) .  The console output here contains the "user-friendly" version of the
output. If you want to see detailed progress as it happens, ``tail -f`` the
latest logfile in ``firesim/deploy/logs/``.

At this point, the ``f1.16xlarge`` instance in our Run Farm has all the
infrastructure necessary to run everything in our simulation.

So, let's launch our simulation!

Running a simulation!
^^^^^^^^^^^^^^^^^^^^^^^^^

Finally, let's run our simulation! To do so, run:

::

	firesim runworkload


This command boots up the 8-port switch simulation and then starts 8 Rocket Chip
FPGA Simulations, then prints out the live status of the simulated
nodes and switch every 10s. When you do this, you will initially see output like:

::

	centos@ip-172-30-2-111.us-west-2.compute.internal:~/firesim-new/deploy$ firesim runworkload
	FireSim Manager. Docs: http://docs.fires.im
	Running: runworkload

	Creating the directory: /home/centos/firesim-new/deploy/results-workload/2018-05-19--06-28-43-linux-uniform/
	[172.30.2.178] Executing task 'instance_liveness'
	[172.30.2.178] Checking if host instance is up...
	[172.30.2.178] Executing task 'boot_switch_wrapper'
	[172.30.2.178] Starting switch simulation for switch slot: 0.
	[172.30.2.178] Executing task 'boot_simulation_wrapper'
	[172.30.2.178] Starting FPGA simulation for slot: 0.
	[172.30.2.178] Starting FPGA simulation for slot: 1.
	[172.30.2.178] Starting FPGA simulation for slot: 2.
	[172.30.2.178] Starting FPGA simulation for slot: 3.
	[172.30.2.178] Starting FPGA simulation for slot: 4.
	[172.30.2.178] Starting FPGA simulation for slot: 5.
	[172.30.2.178] Starting FPGA simulation for slot: 6.
	[172.30.2.178] Starting FPGA simulation for slot: 7.
	[172.30.2.178] Executing task 'monitor_jobs_wrapper'


If you don't look quickly, you might miss it, because it will be replaced with
a live status page once simulations are kicked-off:

::

    FireSim Simulation Status @ 2018-05-19 06:28:56.087472
    --------------------------------------------------------------------------------
    This workload's output is located in:
    /home/centos/firesim-new/deploy/results-workload/2018-05-19--06-28-43-linux-uniform/
    This run's log is located in:
    /home/centos/firesim-new/deploy/logs/2018-05-19--06-28-43-runworkload-ZHZEJED9MDWNSCV7.log
    This status will update every 10s.
    --------------------------------------------------------------------------------
    Instances
    --------------------------------------------------------------------------------
    Instance IP:   172.30.2.178 | Terminated: False
    --------------------------------------------------------------------------------
    Simulated Switches
    --------------------------------------------------------------------------------
    Instance IP:   172.30.2.178 | Switch name: switch0 | Switch running: True
    --------------------------------------------------------------------------------
    Simulated Nodes/Jobs
    --------------------------------------------------------------------------------
    Instance IP:   172.30.2.178 | Job: linux-uniform1 | Sim running: True
    Instance IP:   172.30.2.178 | Job: linux-uniform0 | Sim running: True
    Instance IP:   172.30.2.178 | Job: linux-uniform3 | Sim running: True
    Instance IP:   172.30.2.178 | Job: linux-uniform2 | Sim running: True
    Instance IP:   172.30.2.178 | Job: linux-uniform5 | Sim running: True
    Instance IP:   172.30.2.178 | Job: linux-uniform4 | Sim running: True
    Instance IP:   172.30.2.178 | Job: linux-uniform7 | Sim running: True
    Instance IP:   172.30.2.178 | Job: linux-uniform6 | Sim running: True
    --------------------------------------------------------------------------------
    Summary
    --------------------------------------------------------------------------------
    1/1 instances are still running.
    8/8 simulations are still running.
    --------------------------------------------------------------------------------


In cycle-accurate networked mode, this will only exit when any ONE of the
simulated nodes shuts down. So, let's let it run and open another ssh
connection to the manager instance. From there, ``cd`` into your firesim
directory again and ``source sourceme-f1-manager.sh`` again to get our ssh key
setup. To access our simulated system, ssh into the IP address being printed by
the status page, **from your manager instance**. In our case, from the above
output, we see that our simulated system is running on the instance with IP
``172.30.2.178``. So, run:

::

	[RUN THIS ON YOUR MANAGER INSTANCE!]
	ssh 172.30.2.178

This will log you into the instance running the simulation. On this machine,
run ``screen -ls`` to get the list of all running simulation components. 
Attaching to the screens ``fsim0`` to ``fsim7`` will let you attach to the
consoles of any of the 8 simulated nodes. You'll also notice an additional
screen for the switch, however by default there is no interesting output printed
here for performance reasons.

For example, if we want to enter commands into node zero, we can attach
to its console like so:

::

	screen -r fsim0

Voila! You should now see Linux booting on the simulated node and then be prompted
with a Linux login prompt, like so:

::

    [truncated Linux boot output]
    [    0.020000] Registered IceNet NIC 00:12:6d:00:00:02
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
    Starting network: OK
    Starting dropbear sshd: OK

    Welcome to Buildroot
    buildroot login:

If you also ran the single-node no-nic simulation you'll notice a difference
in this boot output -- here, Linux sees the NIC and its assigned MAC address and
automatically brings up the ``eth0`` interface at boot.

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
	[    3.748000] reboot: Power down
	Power off
	time elapsed: 360.5 s, simulation speed = 37.82 MHz
	*** PASSED *** after 13634406804 cycles
	Runs 13634406804 cycles
	[PASS] FireSim Test
	SEED: 1526711978
	Script done, file is uartlog

	[screen is terminating]


You'll also notice that the manager polling loop exited! You'll see output like this
from the manager:

::

	--------------------------------------------------------------------------------
	Instances
	--------------------------------------------------------------------------------
	Instance IP:   172.30.2.178 | Terminated: False
	--------------------------------------------------------------------------------
	Simulated Switches
	--------------------------------------------------------------------------------
	Instance IP:   172.30.2.178 | Switch name: switch0 | Switch running: True
	--------------------------------------------------------------------------------
	Simulated Nodes/Jobs
	--------------------------------------------------------------------------------
	Instance IP:   172.30.2.178 | Job: linux-uniform1 | Sim running: True
	Instance IP:   172.30.2.178 | Job: linux-uniform0 | Sim running: False
	Instance IP:   172.30.2.178 | Job: linux-uniform3 | Sim running: True
	Instance IP:   172.30.2.178 | Job: linux-uniform2 | Sim running: True
	Instance IP:   172.30.2.178 | Job: linux-uniform5 | Sim running: True
	Instance IP:   172.30.2.178 | Job: linux-uniform4 | Sim running: True
	Instance IP:   172.30.2.178 | Job: linux-uniform7 | Sim running: True
	Instance IP:   172.30.2.178 | Job: linux-uniform6 | Sim running: True
	--------------------------------------------------------------------------------
	Summary
	--------------------------------------------------------------------------------
	1/1 instances are still running.
	7/8 simulations are still running.
	--------------------------------------------------------------------------------
	Teardown required, manually tearing down...
	[172.30.2.178] Executing task 'kill_switch_wrapper'
	[172.30.2.178] Killing switch simulation for switchslot: 0.
	[172.30.2.178] Executing task 'kill_simulation_wrapper'
	[172.30.2.178] Killing FPGA simulation for slot: 0.
	[172.30.2.178] Killing FPGA simulation for slot: 1.
	[172.30.2.178] Killing FPGA simulation for slot: 2.
	[172.30.2.178] Killing FPGA simulation for slot: 3.
	[172.30.2.178] Killing FPGA simulation for slot: 4.
	[172.30.2.178] Killing FPGA simulation for slot: 5.
	[172.30.2.178] Killing FPGA simulation for slot: 6.
	[172.30.2.178] Killing FPGA simulation for slot: 7.
	[172.30.2.178] Executing task 'screens'
	Confirming exit...
	[172.30.2.178] Executing task 'monitor_jobs_wrapper'
	[172.30.2.178] Slot 0 completed! copying results.
	[172.30.2.178] Slot 1 completed! copying results.
	[172.30.2.178] Slot 2 completed! copying results.
	[172.30.2.178] Slot 3 completed! copying results.
	[172.30.2.178] Slot 4 completed! copying results.
	[172.30.2.178] Slot 5 completed! copying results.
	[172.30.2.178] Slot 6 completed! copying results.
	[172.30.2.178] Slot 7 completed! copying results.
	[172.30.2.178] Killing switch simulation for switchslot: 0.
	FireSim Simulation Exited Successfully. See results in:
	/home/centos/firesim-new/deploy/results-workload/2018-05-19--06-39-35-linux-uniform/
	The full log of this run is:
	/home/centos/firesim-new/deploy/logs/2018-05-19--06-39-35-runworkload-4CDB78E3A4IA9IYQ.log

In the cluster case, you'll notice that shutting down ONE simulator causes the
whole simulation to be torn down -- this is because we currently do not implement
any kind of "disconnect" mechanism to remove one node from a globally-cycle-accurate
simulation.

If you take a look at the workload output directory given in the manager output (in this case, ``/home/centos/firesim-new/deploy/results-workload/2018-05-19--06-39-35-linux-uniform/``), you'll see the following:

::

	centos@ip-172-30-2-111.us-west-2.compute.internal:~/firesim-new/deploy/results-workload/2018-05-19--06-39-35-linux-uniform$ ls -la */*
	-rw-rw-r-- 1 centos centos  797 May 19 06:45 linux-uniform0/memory_stats.csv
	-rw-rw-r-- 1 centos centos  125 May 19 06:45 linux-uniform0/os-release
	-rw-rw-r-- 1 centos centos 7476 May 19 06:45 linux-uniform0/uartlog
	-rw-rw-r-- 1 centos centos  797 May 19 06:45 linux-uniform1/memory_stats.csv
	-rw-rw-r-- 1 centos centos  125 May 19 06:45 linux-uniform1/os-release
	-rw-rw-r-- 1 centos centos 8157 May 19 06:45 linux-uniform1/uartlog
	-rw-rw-r-- 1 centos centos  797 May 19 06:45 linux-uniform2/memory_stats.csv
	-rw-rw-r-- 1 centos centos  125 May 19 06:45 linux-uniform2/os-release
	-rw-rw-r-- 1 centos centos 8157 May 19 06:45 linux-uniform2/uartlog
	-rw-rw-r-- 1 centos centos  797 May 19 06:45 linux-uniform3/memory_stats.csv
	-rw-rw-r-- 1 centos centos  125 May 19 06:45 linux-uniform3/os-release
	-rw-rw-r-- 1 centos centos 8157 May 19 06:45 linux-uniform3/uartlog
	-rw-rw-r-- 1 centos centos  797 May 19 06:45 linux-uniform4/memory_stats.csv
	-rw-rw-r-- 1 centos centos  125 May 19 06:45 linux-uniform4/os-release
	-rw-rw-r-- 1 centos centos 8157 May 19 06:45 linux-uniform4/uartlog
	-rw-rw-r-- 1 centos centos  797 May 19 06:45 linux-uniform5/memory_stats.csv
	-rw-rw-r-- 1 centos centos  125 May 19 06:45 linux-uniform5/os-release
	-rw-rw-r-- 1 centos centos 8157 May 19 06:45 linux-uniform5/uartlog
	-rw-rw-r-- 1 centos centos  797 May 19 06:45 linux-uniform6/memory_stats.csv
	-rw-rw-r-- 1 centos centos  125 May 19 06:45 linux-uniform6/os-release
	-rw-rw-r-- 1 centos centos 8157 May 19 06:45 linux-uniform6/uartlog
	-rw-rw-r-- 1 centos centos  797 May 19 06:45 linux-uniform7/memory_stats.csv
	-rw-rw-r-- 1 centos centos  125 May 19 06:45 linux-uniform7/os-release
	-rw-rw-r-- 1 centos centos 8157 May 19 06:45 linux-uniform7/uartlog
	-rw-rw-r-- 1 centos centos  153 May 19 06:45 switch0/switchlog


What are these files? They are specified to the manager in a configuration file
(``firesim/deploy/workloads/linux-uniform.json``) as files that we want
automatically copied back to our manager after we run a simulation, which is
useful for running benchmarks automatically. Note that there is a directory for
each simulated node and each simulated switch in the cluster. The
:ref:`defining-custom-workloads` section describes this process in detail.

For now, let's wrap-up our tutorial by terminating the ``f1.16xlarge`` instance
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
	['i-09e5491cce4d5f92d']
	m4.16xlarges
	[]
	f1.2xlarges
	[]
	Type yes, then press enter, to continue. Otherwise, the operation will be cancelled.


You must type ``yes`` then hit enter here to have your instances terminated. Once
you do so, you will see:

::

	[ truncated output from above ]
	Type yes, then press enter, to continue. Otherwise, the operation will be cancelled.
	yes
	Instances terminated. Please confirm in your AWS Management Console.
	The full log of this run is:
	/home/centos/firesim-new/deploy/logs/2018-05-19--06-50-37-terminaterunfarm-3VF0Z2KCAKKDY0ZU.log

**At this point, you should always confirm in your AWS management console that
the instance is in the shutting-down or terminated states. You are ultimately
responsible for ensuring that your instances are terminated appropriately.**

Congratulations on running a cluster FireSim simulation! At this point, you can
check-out some of the advanced features of FireSim in the sidebar to the left.
Or, hit next to continue to a tutorial that shows you how to build your own
custom FPGA images.
