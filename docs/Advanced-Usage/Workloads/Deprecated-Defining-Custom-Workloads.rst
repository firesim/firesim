.. _deprecated-defining-custom-workloads:

[DEPRECATED] Defining Custom Workloads
================================================

.. DANGER:: This version of the Defining Custom Workloads page is kept here to
   document some of the legacy workload configurations still present in
   ``deploy/workloads/``. New workloads should NOT be generated using these
   instructions.

This page documents the ``JSON`` input format that FireSim uses to understand
your software workloads that run on the target design. Most of the time, you
should not be writing these files from scratch. Instead, use :ref:`firemarshal`
to build a workload (including Linux kernel images and root filesystems) and
use ``firemarshal``'s ``install`` command to generate an initial ``.json`` file
for FireSim. Once you generate a base ``.json`` with FireMarshal, you can add
some of the options noted on this page to control additional files used as
inputs/outputs to/from simulations.

**Workloads** in FireSim consist of a series of **Jobs** that are assigned to
be run on individual simulations. Currently, we require that a Workload defines
either:

- A single type of job, that is run on as many simulations as specfied by the user.
  These workloads are usually suffixed with ``-uniform``, which indicates that
  all nodes in the workload run the same job. An example of such a workload is
  :gh-file-ref:`deploy/workloads/dummy.json`.

- Several different jobs, in which case there must be exactly as many
  jobs as there are running simulated nodes. An example of such a workload is
  :gh-file-ref:`deploy/workloads/non-uniform-example.json`.


FireSim can take these workload definitions and perform two functions:

- Building workloads using :gh-file-ref:`deploy/workloads/gen-benchmark-rootfs.py`

- Deploying workloads using the manager

In the following subsections, we will go through the two aforementioned example
workload configurations, describing how these two functions use each part
of the JSON file inline.

**ERRATA**: You will notice in the following JSON files the field "workloads"
this should really be named "jobs" -- we will fix this in a future release.

Uniform Workload JSON
----------------------------

:gh-file-ref:`deploy/workloads/dummy.json` is an example of a "uniform"
style workload, where each simulated node runs the same software configuration.

Let's take a look at this file:

.. include:: /../deploy/workloads/dummy.json
   :code: json

There is also a corresponding directory named after this workload/file: ``deploy/workloads/dummy``.
We will elaborate on this later.

Looking at the JSON file, you'll notice that this is a relatively simple
workload definition.

In this "uniform" case, the manager will name simulations after the
``benchmark_name`` field, appending a number for each simulation using the
workload (e.g.  ``dummy0``, ``dummy1``, and so on). It is
standard pratice to keep ``benchmark_name``, the JSON filename, and the above
directory name the same. In this case, we have set all of them to
``dummy``.

Next, the ``common_bootbinary`` field represents the binary that the simulations
in this workload are expected to boot from. The manager will copy this binary
for each of the nodes in the simulation (each gets its own copy). The ``common_bootbinary`` path is
relative to the workload's directory, in this case
:gh-file-ref:`deploy/workloads/dummy`. In this case, this is a dummy workload so a dummy binary exists.

Similarly, the ``common_rootfs`` field represents the disk image that the simulations
in this workload are expected to boot from. The manager will copy this root
filesystem image for each of the nodes in the simulation (each gets its own copy).
The ``common_rootfs`` path is
relative to the workload's directory, in this case
:gh-file-ref:`deploy/workloads/dummy`. In this case, this is a dummy workload so no root filesystem image exists.

The ``common_outputs`` field is a list of outputs that the manager will copy out of
the root filesystem image AFTER a simulation completes. You can add multiple paths
here.

The ``common_simulation_outputs`` field is a list of outputs that the manager
will copy off of the simulation host machine AFTER a simulation completes. In
this example, when a workload running on a simulated cluster with
``firesim runworkload``
completes, the ``uartlog`` (an automatically generated file that contains the
full console output of the simulated system) and ``memory_stats.csv`` files
will be copied out of the simulation's base directory on the host instance and
placed in the job's output directory within the workload's output directory
(see the :ref:`firesim-runworkload` section). You can add multiple
paths here.

**ERRATA**: "Uniform" style workloads currently do not support being
automatically built -- you can currently hack around this by building the
rootfs as a single-node non-uniform workload, then deleting the ``workloads``
field of the JSON to make the manager treat it as a uniform workload. This will
be fixed in a future release.


Non-uniform Workload JSON (explicit job per simulated node)
---------------------------------------------------------------

Now, we'll look at the ``non-uniform-example`` workload, which explicitly defines a
job per simulated node.

.. include:: /../deploy/workloads/non-uniform-example.json
   :code: json

Additionally, let's take a look at the state of the required ``non-uniform-example`` directory:

.. code-block:: bash

	centos@ip-172-30-2-111.us-west-2.compute.internal:~/firesim-new/deploy/workloads/non-uniform-example$ ls -la
	...
	drwxrwxr-x  3 centos centos         16 May 17 21:58 overlay
	-rw-rw-r--  1 centos centos          0 May 17 21:58 unusedboot.bin


First, let's identify some of these files:

- ``unusedboot.bin``: Just like in the ``dummy`` case, this workload is a dummy workload that doesn't use it's boot binary.

Additionally, let's look at the ``overlay`` subdirectory:

.. code-block:: bash

    centos@ip-172-30-2-111.us-west-2.compute.internal:~/firesim-new/deploy/workloads/non-uniform-example/overlay$ ls -la */*
    -rwxrwxr-x 1 centos centos 249 May 17 21:58 bin/unused.sh

This is a file that's actually committed to the repo, that in theory would run the benchmark we want to
run on one of our simulated systems. In this case, it's unused.

Now, let's take a look at how we got here. First, let's review some of the new
fields present in this JSON file:

- ``common_files``: This is an array of files that will be included in ALL of the job rootfses when they're built. This is relative to a path that we'll pass to the script that generates rootfses.
- ``workloads``: This time, you'll notice that we have this array, which is populated by objects that represent individual jobs. Each job has some additional fields:

   - ``name``: In this case, jobs are each assigned a name manually. These names MUST BE UNIQUE within a particular workload.
   - ``files``: Just like ``common_files``, but specific to this job.
   - ``command``: This is the command that will be run automatically immediately when the simulation running this job boots up. This is usually the command that starts the workload we want.
   - ``simulation_outputs``: Just like ``common_simulation_outputs``, but specific to this job.
   - ``outputs``: Just like ``common_outputs``, but specific to this job.


In this example, we specify one node that boots up and runs
``unused.sh && poweroff -f`` while the other just runs ``poweroff -f``.

You can run works like this with the manager
by setting ``workload_name: non-uniform-example.json`` in ``config_runtime.yaml``. The manager
will automatically look for the generated rootfses (based on workload and job names
that it reads from the json) and distribute work appropriately.

Just like in the uniform case, it will copy back the results that we specify
in the JSON file. We'll end up with a directory in ``firesim/deploy/results-workload/``
named after the workload name, with a subdirectory named after each job in the workload,
which will contain the output files we want.
