Running a Single Node Simulation
===================================

Now that we've completed all the basic setup steps, it's time to run
a simulation! In this section, we will simulate a single target node, for which
we will use a single |fpga_type|.

**Make sure you have sourced** ``sourceme-manager.sh --skip-ssh-setup`` **before running any of these commands.**

Building target software
------------------------

In this guide, we'll boot Linux on our
simulated node. To do so, we'll need to build our RISC-V SoC-compatible
Linux distro. For this guide, we will use a simple buildroot-based
distribution. We can build the Linux distribution like so:

.. code-block:: bash

    # assumes you already cd'd into your firesim repo
    # and sourced sourceme-manager.sh
    #
    # then:
    cd sw/firesim-software
    ./init-submodules.sh
    ./marshal -v build br-base.json

Once this is completed, you'll have the following files:

-  ``YOUR_FIRESIM_REPO/sw/firesim-software/images/firechip/br-base/br-base-bin`` - a bootloader + Linux
   kernel image for the RISC-V SoC we will simulate.
-  ``YOUR_FIRESIM_REPO/sw/firesim-software/images/firechip/br-base/br-base.img`` - a disk image for
   the RISC-V SoC we will simulate

These files will be used to form base images to either build more complicated
workloads (see the :ref:`defining-custom-workloads` section) or directly as a
basic, interactive Linux distribution.

Setting up the manager configuration
-------------------------------------

All runtime configuration options for the manager are located in
``YOUR_FIRESIM_REPO/deploy/config_runtime.yaml``. In this guide, we will explain only the
parts of this file necessary for our purposes. You can find full descriptions of
all of the parameters in the :ref:`manager-configuration-files` section.

Based on the changes we made earlier, this file will already have everything set
correctly to run a simulation.

Below we'll highlight a few of these lines to explain what is happening:

* At the top, you'll notice the ``run_farm`` mapping, which describes and specifies the machines to run simulations on.
  * By default, we'll be using a ``base_recipe`` of ``run-farm-recipes/externally_provisioned.yaml``, which means that our
    Run Farm machines are pre-configured, and do not require the manager to dynamically launch/terminate them (e.g., as we
    would do for cloud instances).
  * The ``default_platform`` has automatically been set for our FPGA board, to |deploy_manager_code|.
  * The ``default_simulation_dir`` is the directory on the Run Farm Machines where simulations will run out of. The default is likely fine, but you can change it to any directory you have access to on the Run Farm machines.
  * ``run_farm_hosts_to_use`` should be a list of ``- IP-address: machine_spec`` pairs, one pair for each of your Run Farm Machines. ``IP-address`` should be the IP address or hostname
  of the system (that the Manager Machine can use to ssh into the Run Farm Machine) and the ``machine_spec`` should be a value from ``run_farm_host_specs`` in :gh-file-ref:`deploy/run-farm-recipes/externally_provisioned.yaml`. Each spec describes the number of FPGAs attached to a system and other properties about the system. We configured this already in the previous step.
* The ``target_config`` section describes the system that we'd like to simulate.
  * ``topology: no_net_config`` indicates that we're running simulations with no network between them.
  * ``no_net_num_nodes: 1`` indicates that we'll be a simulation of a single SoC
  * The ``default_hw_config`` will be set to a pre-built design (for our FPGA, |hwdb_entry_name|) with a single RISC-V Rocket core. This is usually not set by default, but we already set it in the previous step.
* The ``workload`` section describes the workload that we'd like to run on our simulated systems. In this case, we will leave it as the default, which will boot Linux on all SoCs in the simulation.


Launching a Simulation!
-----------------------------

Now that we've told the manager everything it needs to know in order to run
our single-node simulation, let's actually run it!

Setting up simulation infrastructure on the Run Farm Machines
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The manager automates the process of building and deploying all
components necessary to run your simulation on the Run Farm, including
programming FPGAs. To tell the manager to setup all of our simulation
infrastructure, run the following:

.. code-block:: bash

        firesim infrasetup


For a complete run, you should expect output like the following:

.. code-block:: bash

        $ firesim infrasetup
        FireSim Manager. Docs: https://docs.fires.im
        Running: infrasetup

        Building FPGA software driver for |quintuplet|
        ...
        [localhost] Checking if host instance is up...
        [localhost] Copying FPGA simulation infrastructure for slot: 0.
        [localhost] Clearing all FPGA Slots.
        The full log of this run is:
        .../firesim/deploy/logs/2023-03-06--01-22-46-infrasetup-35ZP4WUOX8KUYBF3.log

Many of these tasks will take several minutes, especially on a clean copy of
the repo.  The console output here contains the "user-friendly" version of the
output. If you want to see detailed progress as it happens, ``tail -f`` the
latest logfile in ``firesim/deploy/logs/``.

At this point, our single Run Farm machine has all the infrastructure
necessary to run a simulation, so let's launch our simulation!

Running a simulation
^^^^^^^^^^^^^^^^^^^^^^^^^

Finally, let's run our simulation! To do so, run:

.. code-block:: bash

        firesim runworkload


This command boots up a simulation and prints out the live status of the simulated
nodes every 10s. When you do this, you will initially see output like:

.. code-block:: bash

        $ firesim runworkload
        FireSim Manager. Docs: https://docs.fires.im
        Running: runworkload

        Creating the directory: .../firesim/deploy/results-workload/2023-03-06--01-25-34-linux-uniform/
        [localhost] Checking if host instance is up...
        [localhost] Starting FPGA simulation for slot: 0.

If you don't look quickly, you might miss it, since it will get replaced with a
live status page:

.. code-block:: text

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


This will only exit once all of the simulated nodes have powered off. So, let's let it
run and open another terminal on the manager machine. From there, ``cd`` into
your FireSim directory again and ``source sourceme-manager.sh --skip-ssh-setup``.

Next, let's ``ssh`` into the Run Farm machine. If your Run Farm and Manager Machines are
the same, replace ``RUN_FARM_IP_OR_HOSTNAME`` with ``localhost``, otherwise replace it
with your Run Farm Machine's IP or hostname.

.. code-block:: bash

        source ~/.ssh/AGENT_VARS
        ssh RUN_FARM_IP_OR_HOSTNAME

Next, we can directly attach to the console of the simulated system using ``screen``, run:

.. code-block:: bash

        screen -r fsim0

Voila! You should now see Linux booting on the simulated system and then be prompted
with a Linux login prompt, like so:


.. code-block:: bash

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

Now, you can login to the system! The username is ``root`` and there is no password.
At this point, you should be presented with a regular console,
where you can type commands into the simulation and run programs. For example:

.. code-block:: bash

        Welcome to Buildroot
        buildroot login: root
        Password:
        # uname -a
        Linux buildroot 4.15.0-rc6-31580-g9c3074b5c2cd #1 SMP Thu May 17 22:28:35 UTC 2018 riscv64 GNU/Linux
        #


At this point, you can run workloads as you'd like. To finish off this guide,
let's power off the simulated system and see what the manager does. To do so,
in the console of the simulated system, run ``poweroff -f``:


.. code-block:: bash

        Welcome to Buildroot
        buildroot login: root
        Password:
        # uname -a
        Linux buildroot 4.15.0-rc6-31580-g9c3074b5c2cd #1 SMP Thu May 17 22:28:35 UTC 2018 riscv64 GNU/Linux
        # poweroff -f

You should see output like the following from the simulation console:

.. code-block:: bash

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

.. code-block:: text

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

.. code-block:: bash

        $ ls -la firesim/deploy/results-workload/2018-05-19--00-38-52-linux-uniform/*/*
        -rw-rw-r-- 1 centos centos  797 May 19 00:46 linux-uniform0/memory_stats.csv
        -rw-rw-r-- 1 centos centos  125 May 19 00:46 linux-uniform0/os-release
        -rw-rw-r-- 1 centos centos 7316 May 19 00:46 linux-uniform0/uartlog

What are these files? They are specified to the manager in a configuration file
(:gh-file-ref:`deploy/workloads/linux-uniform.json`) as files that we want
automatically copied back from the Run Farm Machine into the ``results-workload`` directory on our manager machine, which is
useful for running benchmarks automatically. The
:ref:`defining-custom-workloads` section describes this process in detail.

Congratulations on running your first FireSim simulation! At this point, you can
check-out some of the advanced features of FireSim in the sidebar to the left
(for example, we expect that many people will be interested in the ability to
automatically run the SPEC17 benchmarks: :ref:`spec-2017`).

