Setting up an FPGA Run Farm
=============================

In order to run FireSim simulations, we will need to create one or more
F1 instances (which we will call our "Run Farm") to deploy simulations
on. In this section, we will assume that we want to simulate **8 target
nodes** on one ``f1.16xlarge`` (8 FPGA) instance.

Starting a Run Farm
-------------------

The manager automates the process of launching and terminating instances
for the Run Farm. Unlike Build Farm instances however, you must manually
issue commands to launch and terminate instances.

Let's launch a Run Farm with a single ``f1.16xlarge``. Take a look at
this section in ``deploy/config_runtime.ini``:

::

    [runfarm]
    # per aws restrictions, this tag cannot be longer than 255 chars
    runfarmtag=mainrunfarm
    f1_16xlarges=1
    m4_16xlarges=0
    f1_2xlarges=0

This means that the manager is configured to build a Run Farm consisting
of one ``f1.16xlarge`` and no ``m4.16xlarge``\ s or ``f1.2xlarge``\ s.
We could change these values, but we only need a single ``f1.16xlarge``
for this tutorial, so we will leave the file as is.

To launch the Run Farm, run:

::

    firesim launchrunfarm

This will launch all of the instances that you specified in the
``[runfarm]`` section of the config file and apply the tag
``mainrunfarm`` to all of them. This tag is how the manager keeps track
of all the instances you have launched. Tasks that you will run soon,
like ``infrasetup``, ``boot``, ``kill``, and ``terminaterunfarm`` all
rely on this tag, so you should NOT change it once you launch a run
farm.

At this point, you have launched a Run Farm, so we're ready to launch
simulations! Hit Next to continue to the next page.
