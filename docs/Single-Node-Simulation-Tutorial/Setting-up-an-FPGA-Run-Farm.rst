Setting up an FPGA Run Farm
=============================

In order to run FireSim simulations, we will need to launch one or more
F1 instances (which we will call a "Run Farm") to deploy simulations
on. In this section, we will continue under the assumption that that we want to
simulate **1 target node**, for which we will need a single ``f1.2xlarge`` (1
FPGA) instance.

Starting a Run Farm
-------------------

The manager automates the process of launching and terminating instances
for the Run Farm with the ``launchrunfarm`` and ``terminaterunfarm`` commands.

Let's use the manager to launch a Run Farm with a single ``f1.2xlarge``. Take
a look at this section in the default ``deploy/config_runtime.ini``:

::

    [runfarm]
    # per aws restrictions, this tag cannot be longer than 255 chars
    runfarmtag=mainrunfarm
    f1_16xlarges=1
    m4_16xlarges=0
    f1_2xlarges=0

This means that the manager is configured to build a Run Farm, named
``mainrunfarm`` consisting of one ``f1.16xlarge`` and no ``m4.16xlarge``\ s or
``f1.2xlarge``\ s. The tag specified here allows the manager to differentiate
amongst many parallel run farms (each running a workload) that you may be
operating -- but more on that later.

To run our single-node simulation, let's change this to instead launch one
``f1.2xlarge`` and no ``f1.16xlarge``\s:

::

    [runfarm]
    # per aws restrictions, this tag cannot be longer than 255 chars
    runfarmtag=mainrunfarm
    f1_16xlarges=0
    m4_16xlarges=0
    f1_2xlarges=1

Now that we've told the manager what it should be doing, we can tell the manager
to actually launch the instances by running:

::

    firesim launchrunfarm

This will launch all of the instances that you specified in the ``[runfarm]``
section of the config file and apply the tag ``mainrunfarm`` to all of them
(you can confirm this in your AWS management console). This tag is how the
manager keeps track of all the instances you have launched. Tasks that you will
run soon, like ``infrasetup``, ``boot``, ``kill``, and ``terminaterunfarm`` all
rely on this tag, so you should NOT change it once you launch a run farm. You
can however have multiple run farms running separate workloads running at the
same time. See the advanced manager docs for more details (TODO).

Now that we've launched the necessary F1 instances, we're ready to run
simulations!

Hit Next to continue to the next page.
