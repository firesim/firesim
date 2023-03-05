.. _onprem-single-node-sim:

Running a Single Node Simulation
===================================

Now that we've completed the setup of our manager machine, it's time to run
a simulation! In this section, we will simulate **1 target node**, for which we
will need a single U250 FPGA.

**Make sure you have sourced
``sourceme-f1-manager.sh --skip-ssh-setup`` before running any of these commands.**

Building target software
------------------------

In these instructions, we'll assume that you want to boot Linux on your
simulated node. To do so, we'll need to build our FireSim-compatible RISC-V
Linux distro. For this tutorial, we will use a simple buildroot-based
distribution. You can do this like so:

::

    cd firesim/sw/firesim-software
    ./init-submodules.sh
    ./marshal -v build br-base.json

Once this is completed, you'll have the following files:

-  ``firesim/sw/firesim-software/images/br-base-bin`` - a bootloader + Linux
   kernel image for the nodes we will simulate.
-  ``firesim/sw/firesim-software/images/br-base.img`` - a disk image for
   each the nodes we will simulate

These files will be used to form base images to either build more complicated
workloads (see the :ref:`defining-custom-workloads` section) or to copy around
for deploying.

Setting up the manager configuration
-------------------------------------

All runtime configuration options for the manager are set in a file called
``firesim/deploy/config_runtime.yaml``. In this guide, we will explain only the
parts of this file necessary for our purposes. You can find full descriptions of
all of the parameters in the :ref:`manager-configuration-files` section.

If you open up this file, you will see the following default config (assuming
you have not modified it):

.. include:: DOCS_EXAMPLE_config_runtime.yaml
   :code: yaml

We'll need to modify a couple of these lines.

First, let's tell the manager to use the single U250 FPGA.
You'll notice that in the ``run_farm`` mapping which describes and specifies the machines to run simulations on.
First notice that the ``base_recipe`` maps to ``run-farm-recipes/externally_provisioned.yaml``.
This indicates to the FireSim manager that the machines allocated to run simulations will be provided by the user through IP addresses
instead of automatically launched and allocated (e.g. launching instances on-demand in AWS).
Let's modify the ``default_platform`` to be ``VitisInstanceDeployManager`` so that we can launch simulations using Vitis/XRT.
Next, modify the ``default_simulation_dir`` to a directory that you want to store temporary simulation collateral to.
When running simulations, this directory is used to store any temporary files that the simulator creates (e.g. a uartlog emitted by a Linux simulation).
Next, lets modify the ``run_farm_hosts_to_use`` mapping.
This maps IP addresses (i.e. ``localhost``) to a description/specification of the simulation machine.
In this case, we have only one U250 FPGA so we will change the description of ``localhost`` to ``one_fpga_spec``.

Now, let's verify that the ``target_config`` mapping will model the correct target design.
By default, it is set to model a single-node with no network.
It should look like the following:

::

    target_config:
        topology: no_net_config
        no_net_num_nodes: 1
        link_latency: 6405
        switching_latency: 10
        net_bandwidth: 200
        profile_interval: -1

        # This references a section from config_hwdb.yaml
        # In homogeneous configurations, use this to set the hardware config deployed
        # for all simulators
        default_hw_config: firesim_rocket_quadcore_no_nic_l2_llc4mb_ddr3

Note ``topology`` is set to
``no_net_config``, indicating that we do not want a network. Then,
``no_net_num_nodes`` is set to ``1``, indicating that we only want to simulate
one node. Lastly, the ``default_hw_config`` is
``firesim_rocket_quadcore_no_nic_l2_llc4mb_ddr3``.
Let's modify the ``default_hw_config`` (the target design) to ``firesim_rocket_singlecore_no_nic``.
This new hardware configuration does not
have a NIC and is pre-built for the U250 FPGA.
This hardware configuration models a Single-core Rocket Chip SoC and **no** network interface card.

We will leave the ``workload`` mapping unchanged here, since we do
want to run the buildroot-based Linux on our simulated system. The ``terminate_on_completion``
feature is an advanced feature that you can learn more about in the
:ref:`manager-configuration-files` section.

As a final sanity check, in the mappings we changed, the ``config_runtime.yaml`` file should now look like this (with ``PATH_TO_SIMULATION_AREA`` replaced with your simulation collateral temporary directory):

::

    run_farm:
      base_recipe: run-farm-recipes/externally_provisioned.yaml
      recipe_arg_overrides:
        default_platform: VitisInstanceDeployManager
        default_simulation_dir: <PATH_TO_SIMULATION_AREA>
        run_farm_hosts_to_use:
            - localhost: one_fpga_spec

    target_config:
        topology: no_net_config
        no_net_num_nodes: 1
        link_latency: 6405
        switching_latency: 10
        net_bandwidth: 200
        profile_interval: -1
        default_hw_config: firesim_rocket_singlecore_no_nic
        plusarg_passthrough: ""

    workload:
	workload_name: linux-uniform.json
	terminate_on_completion: no
	suffix_tag: null

Next, let's provide the pre-built hardware target design U250 FPGA image (called a xclbin) to the FireSim manager.
This is done by adding an entry to the ``config_hwdb.yaml``, a database of built FPGA image metadata (e.g. pointer to FPGA image files).
In the ``config_hwdb.yaml``, add the following lines pointing to a pre-built FPGA xclbin:

::

    firesim_rocket_singlecore_no_nic:
        xclbin: https://firesim-ci-vitis-xclbins.s3.us-west-2.amazonaws.com/firesim_rocket_singlecore_no_nic_d148b73.xclbin
        deploy_triplet_override: FireSim-FireSimRocketConfig-BaseVitisConfig
        custom_runtime_config: null

Notice how this entry has the same name (``firesim_rocket_singlecore_no_nic``) given in the ``target_config`` section ``default_hw_config`` mapping.

Launching a Simulation!
-----------------------------

Now that we've told the manager everything it needs to know in order to run
our single-node simulation, let's actually run it!

Starting the Run Farm
^^^^^^^^^^^^^^^^^^^^^^^^^

First, we will tell the manager to launch our Run Farm with a single machine called ``localhost``. Run:

::

    firesim launchrunfarm

In this case, since we are already running the machine with the FPGA (``localhost``),
this command should not launch any machine and should be quick.

You should expect output like the following:

::

    TBD

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

	TBD

Many of these tasks will take several minutes, especially on a clean copy of
the repo.  The console output here contains the "user-friendly" version of the
output. If you want to see detailed progress as it happens, ``tail -f`` the
latest logfile in ``firesim/deploy/logs/``.

At this point, our single Run Farm ``localhost`` machine has all the infrastructure
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

	TBD

If you don't look quickly, you might miss it, since it will get replaced with a
live status page:

::

	FireSim Simulation Status @ 2018-05-19 00:38:56.062737
	--------------------------------------------------------------------------------
	This workload's output is located in:
	.../firesim/deploy/results-workload/2018-05-19--00-38-52-linux-uniform/
	This run's log is located in:
	.../firesim/deploy/logs/2018-05-19--00-38-52-runworkload-JS5IGTV166X169DZ.log
	This status will update every 10s.
	--------------------------------------------------------------------------------
	Instances
	--------------------------------------------------------------------------------
	Hostname/IP:   localhost | Terminated: False
	--------------------------------------------------------------------------------
	Simulated Switches
	--------------------------------------------------------------------------------
	--------------------------------------------------------------------------------
	Simulated Nodes/Jobs
	--------------------------------------------------------------------------------
	Hostname/IP:   localhost | Job: linux-uniform0 | Sim running: True
	--------------------------------------------------------------------------------
	Summary
	--------------------------------------------------------------------------------
	1/1 instances are still running.
	1/1 simulations are still running.
	--------------------------------------------------------------------------------


This will only exit once all of the simulated nodes have completed simulations. So, let's let it
run and open another terminal to the manager machine. From there, ``cd`` into
your FireSim directory again and ``source sourceme-f1-manager.sh --skip-ssh-setup``.
Since we are running the simulation on the same machine (i.e. ``localhost``) we can directly
attach to the console of the simulated system using ``screen``, run:

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
let's power off the simulated system and see what the manager does. To do so,
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
	[PASS] FireSim Test
	SEED: 1526690334
	Script done, file is uartlog

	[screen is terminating]


You'll also notice that the manager polling loop exited! You'll see output like this
from the manager:

::

	FireSim Simulation Status @ 2018-05-19 00:46:50.075885
	--------------------------------------------------------------------------------
	This workload's output is located in:
	.../firesim/deploy/results-workload/2018-05-19--00-38-52-linux-uniform/
	This run's log is located in:
	.../firesim/deploy/logs/2018-05-19--00-38-52-runworkload-JS5IGTV166X169DZ.log
	This status will update every 10s.
	--------------------------------------------------------------------------------
	Instances
	--------------------------------------------------------------------------------
	Hostname/IP:   172.30.2.174 | Terminated: False
	--------------------------------------------------------------------------------
	Simulated Switches
	--------------------------------------------------------------------------------
	--------------------------------------------------------------------------------
	Simulated Nodes/Jobs
	--------------------------------------------------------------------------------
	Hostname/IP:   172.30.2.174 | Job: linux-uniform0 | Sim running: False
	--------------------------------------------------------------------------------
	Summary
	--------------------------------------------------------------------------------
	1/1 instances are still running.
	0/1 simulations are still running.
	--------------------------------------------------------------------------------
	FireSim Simulation Exited Successfully. See results in:
	.../firesim/deploy/results-workload/2018-05-19--00-38-52-linux-uniform/
	The full log of this run is:
	.../firesim/deploy/logs/2018-05-19--00-38-52-runworkload-JS5IGTV166X169DZ.log


If you take a look at the workload output directory given in the manager output (in this case, ``.../firesim/deploy/results-workload/2018-05-19--00-38-52-linux-uniform/``), you'll see the following:

::

	$ ls -la firesim/deploy/results-workload/2018-05-19--00-38-52-linux-uniform/*/*
	-rw-rw-r-- 1 centos centos  797 May 19 00:46 linux-uniform0/memory_stats.csv
	-rw-rw-r-- 1 centos centos  125 May 19 00:46 linux-uniform0/os-release
	-rw-rw-r-- 1 centos centos 7316 May 19 00:46 linux-uniform0/uartlog

What are these files? They are specified to the manager in a configuration file
(:gh-file-ref:`deploy/workloads/linux-uniform.json`) as files that we want
automatically copied back from the temporary simulation directory into the ``results-workload`` directory (on our manager machine - which is also ``localhost`` for this tutorial) after we run a simulation, which is
useful for running benchmarks automatically. The
:ref:`defining-custom-workloads` section describes this process in detail.

For now, let's wrap-up our tutorial by terminating the Run Farm that we launched.
To do so, run:

::

	firesim terminaterunfarm

Which should present you with the following:

::

	TBD

Since we are re-using an existing machine that is already booted, this command should do nothing and be quick.

Congratulations on running your first FireSim simulation! At this point, you can
check-out some of the advanced features of FireSim in the sidebar to the left
(for example, we expect that many people will be interested in the ability to
automatically run the SPEC17 benchmarks: :ref:`spec-2017`).
