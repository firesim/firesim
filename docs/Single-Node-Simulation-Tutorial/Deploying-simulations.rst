Deploying FireSim Simulations
===============================

Now that we have started our Run Farm of F1 instances, it's time to
launch a simulation! All of our simulations are launched from our
"Manager Instance", so ``ssh`` back into that instance now.

Building target software
------------------------

In these instructions, we'll assume that you want to boot Linux on your
simulated nodes. To do so, we'll need to build our RISC-V Linux distro,
like so:

::

    cd firesim/sw/firesim-software
    ./build.sh

This process will take about 10 minutes on a ``c4.8xlarge`` instance.
Once this is completed, you'll have the following files:

-  ``firesim/sw/firesim-software/bbl-vmlinux`` - a bootloader/Linux
   kernel image for the nodes we will simulate. One copy is sufficient
   since this binary is only read, not written.
-  ``firesim/sw/firesim-software/rootfs[0-7].ext2`` - a disk image for
   each of the 8 nodes we will simulate

Setting up the simulation manager
---------------------------------

Now, head to the ``firesim/`` directory. To make sure our environment is
setup correctly, run:

::

    source sourceme-f1-manager.sh

You may be prompted to enter your ``firesim.pem`` key's passphrase if it
has one.

Next, open ``deploy/config_runtime.ini`` in your editor, and
paste the private IP address of your ``f1.16xlarge`` instance under
``[f1.16xlarges]``. Remove any other IP addresses listed under this
header, such that you only have a single IP address listed.

Finally, let's take a look at the ``[targetconfig]`` section of this
file. You'll notice that the topology is set to ``example_8config``.
This config simulates one root switch attached to 8 simulated nodes:

::

    [targetconfig]
    topology=example_8config

This is what we want to simulate, so we don't need to change anything
here. If you would like to simulate only a single node, you can change
this to ``example_1config``.

Finally, we need to set our AGFI in the ``deploy/config_runtime.ini`` and
``deploy/config_agfidb.ini`` files so that all FPGAs in our Run Farm are
flashed with our AGFI. Since our ``config_agfidb.ini`` was copied from an existing
template by the manager when we ran ``firesim managerinit``,
you will notice many pre-existing AGFIs in
the ``[agfis]`` section, one of which (``quad-90mhz-l2-nic-perf``) is
assigned to ``defaultserver`` in ``[targetconfig]`` in
``deploy/config_runtime.ini``.

First, to use your own AGFI instead of this one, add it under the
``[agfis]`` section in ``deploy/config_agfidb.ini``, like so (you can leave the
existing lines as-is, just add a new line with myagfi):

::

    [agfis]
    myagfi=agfi-ASDFGHJK...

Then, in the ``[targetconfig]`` section in ``deploy/config_runtime.ini``, set:

::

    defaultserver=myagfi

Now, your AGFI will be used to flash FPGAs when booting a simulation.

Copy infrastructure to Run Farm nodes
-------------------------------------

Run the following to do this. This may take a while:

::

    firesim infrasetup

This builds switch models for your topology, then copies all software
drivers, dependencies, and switch models to the "Run Farm" instances. It
will also flash all FPGAs with the AGFI you specified in the ``load.sh``
script earlier.

Run a simulation
----------------

Now, it's time to boot up our simulation! Run the following:

::

    firesim boot

Interact with a simulation
--------------------------

You can directly interact with a simulation as if it were a real machine
with a console. To do so, ssh into your ``f1.16xlarge`` instance, then
run:

::

    screen -r fsim0

You will now be connected to the UART console of the simulated node. You
can login with:

::

    user: root
    password: firesim

Now, you can run benchmarks at the command line.

Shutting down a simulation
--------------------------

If want to see the simulation rate, run ``poweroff`` at the command line
in one of the simulated nodes. This will shut down that simulated node
and print the global simulation rate.

Finally, to kill all of the simulations, return to your "Manager
Instance" and run:

::

    firesim kill

At this point, simulations are shutdown on your ``f1.16xlarge``
instance.

Terminating your Run Farm
-------------------------

Once you are finished with running simulations, you can terminate all
the instances in your Run Farm by running:

::

    firesim terminaterunfarm

This will print out all of the instance IDs of the instances in your Run
Farm that will be terminated. To proceed, you must type "yes", then hit
enter to complete the termination process.

If you've followed this guide end to end, you should now only have the
"Manager Instance" running at this point - build farm instances are
terminated automatically when a build finishes, and you just manually
terminated your "Run Farm" instances.

You should confirm that there are no other instances running by checking
your AWS Management Console for your region.
