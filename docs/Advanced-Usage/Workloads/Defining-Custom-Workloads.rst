.. _defining-custom-workloads:

Defining Custom Workloads
================================================

**Workloads** in FireSim consist of a series of **Jobs** that are assigned to
be run on individual simulations. Currently, we require that a Workload defines
either:

- A single type of job, that is run on as many simulations as specfied by the user.
  These workloads are usually suffixed with ``-uniform``, which indicates that
  all nodes in the workload run the same job. An example of such a workload is
  ``firesim/deploy/workloads/linux-uniform.json``.

- Several different jobs, in which case there must be exactly as many
  jobs as there are running simulated nodes. An example of such a workload is
  ``firesim/deploy/workloads/ping-latency.json``.


FireSim supports can take these workload definitions and perform two functions:

- Building workloads using ``firesim/deploy/workloads/gen-benchmark-rootfs.py``

- Deploying workloads using the manager

In the following subsections, we will go through the two aforementioned example
workload configurations, describing how these two functions use each part
of the json file inline.

**ERRATA**: You will notice in the following json files the field "workloads"
this should really be named "jobs" -- we will fix this in a future release.

**ERRATA**: The following instructions assume the default buildroot-based linux
distribution (br-disk). In order to customize Fedora, you should build the
basic Fedora image (as described in :ref:`booting-fedora`) and modify the image
directly (or use :ref:`FireMarshal <firemarshal>` to generate the
workload). Imporantly, Fedora currently does not support the "command" option
for workloads.

Uniform Workload JSON
----------------------------

``firesim/deploy/workloads/linux-uniform.json`` is an example of a "uniform"
style workload, where each simulated node runs the same software configuration.

Let's take a look at this file:

.. include:: /../deploy/workloads/linux-uniform.json
   :code: json

There is also a corresponding directory named after this workload/file:

::

	centos@ip-172-30-2-111.us-west-2.compute.internal:~/firesim-new/deploy/workloads/linux-uniform$ ls -la
	total 4
	drwxrwxr-x  2 centos centos   42 May 17 21:58 .
	drwxrwxr-x 13 centos centos 4096 May 18 17:14 ..
	lrwxrwxrwx  1 centos centos   41 May 17 21:58 br-base-bin -> ../../../sw/firesim-software/images/br-base-bin
	lrwxrwxrwx  1 centos centos   41 May 17 21:58 br-base.img -> ../../../sw/firesim-software/images/br-base.img

We will elaborate on this later.

Looking at the JSON file, you'll notice that this is a relatively simple
workload definition.

In this "uniform" case, the manager will name simulations after the
``benchmark_name`` field, appending a number for each simulation using the
workload (e.g.  ``linux-uniform0``, ``linux-uniform1``, and so on). It is
standard pratice to keep ``benchmark_name``, the json filename, and the above
directory name the same. In this case, we have set all of them to
``linux-uniform``.

Next, the ``common_bootbinary`` field represents the binary that the simulations
in this workload are expected to boot from. The manager will copy this binary
for each of the nodes in the simulation (each gets its own copy). The ``common_bootbinary`` path is 
relative to the workload's directory, in this case
``firesim/deploy/workloads/linux-uniform``. You'll notice in the above output
from ``ls -la`` that this is actually just a symlink to ``br-base-bin`` that
is built by the :ref:`FireMarshal <firemarshal>` tool.

Similarly, the ``common_rootfs`` field represents the disk image that the simulations
in this workload are expected to boot from. The manager will copy this root
filesystem image for each of the nodes in the simulation (each gets its own copy).
The ``common_rootfs`` path is
relative to the workload's directory, in this case
``firesim/deploy/workloads/linux-uniform``. You'll notice in the above output
from ``ls -la`` that this is actually just a symlink to ``br-base.img`` that
is built by the :ref:`FireMarshal <firemarshal>` tool.

The ``common_outputs`` field is a list of outputs that the manager will copy out of
the root filesystem image AFTER a simulation completes. In this simple example,
when a workload running on a simulated cluster with ``firesim runworkload``
completes, ``/etc/os-release`` will be copied out from each rootfs and placed
in the job's output directory within the workload's output directory (See
the :ref:`firesim-runworkload` section). You can add multiple paths
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

Now, we'll look at the ``ping-latency`` workload, which explicitly defines a 
job per simulated node.

.. include:: /../deploy/workloads/ping-latency.json
   :code: json

Additionally, let's take a look at the state of the ``ping-latency`` directory
AFTER the workload is built:

::

	centos@ip-172-30-2-111.us-west-2.compute.internal:~/firesim-new/deploy/workloads/ping-latency$ ls -la
	total 15203216
	drwxrwxr-x  3 centos centos       4096 May 18 07:45 .
	drwxrwxr-x 13 centos centos       4096 May 18 17:14 ..
	lrwxrwxrwx  1 centos centos         41 May 17 21:58 bbl-vmlinux -> ../linux-uniform/br-base-bin
	-rw-rw-r--  1 centos centos          7 May 17 21:58 .gitignore
	-rw-r--r--  1 centos centos 1946009600 May 18 07:45 idler-1.ext2
	-rw-r--r--  1 centos centos 1946009600 May 18 07:45 idler-2.ext2
	-rw-r--r--  1 centos centos 1946009600 May 18 07:45 idler-3.ext2
	-rw-r--r--  1 centos centos 1946009600 May 18 07:45 idler-4.ext2
	-rw-r--r--  1 centos centos 1946009600 May 18 07:45 idler-5.ext2
	-rw-r--r--  1 centos centos 1946009600 May 18 07:46 idler-6.ext2
	drwxrwxr-x  3 centos centos         16 May 17 21:58 overlay
	-rw-r--r--  1 centos centos 1946009600 May 18 07:44 pingee.ext2
	-rw-r--r--  1 centos centos 1946009600 May 18 07:44 pinger.ext2
	-rw-rw-r--  1 centos centos       2236 May 17 21:58 ping-latency-graph.py


First, let's identify some of these files:

- ``bbl-vmlinux``: This workload just uses the default linux binary generated for the ``linux-uniform`` workload.
- ``.gitignore``: This just ignores the generated rootfses, which we'll learn about below.
- ``idler-[1-6].ext2``, ``pingee.ext2``, ``pinger.ext2``: These are rootfses that are generated from the json script above. We'll learn how to do this shortly.

Additionally, let's look at the ``overlay`` subdirectory:

::

    centos@ip-172-30-2-111.us-west-2.compute.internal:~/firesim-new/deploy/workloads/ping-latency/overlay$ ls -la */*
    -rwxrwxr-x 1 centos centos 249 May 17 21:58 bin/pinglatency.sh

This is a file that's actually committed to the repo, that runs the benchmark we want to
run on one of our simulated systems. We'll see how this is used soon.

Now, let's take a look at how we got here. First, let's review some of the new
fields present in this JSON file:

- ``common_files``: This is an array of files that will be included in ALL of the job rootfses when they're built. This is relative to a path that we'll pass to the script that generates rootfses.
- ``workloads``: This time, you'll notice that we have this array, which is populated by objects that represent individual jobs. Each job has some additional fields:

   - ``name``: In this case, jobs are each assigned a name manually. These names MUST BE UNIQUE within a particular workload.
   - ``files``: Just like ``common_files``, but specific to this job.
   - ``command``: This is the command that will be run automatically immediately when the simulation running this job boots up. This is usually the command that starts the workload we want.
   - ``simulation_outputs``: Just like ``common_simulation_outputs``, but specific to this job.
   - ``outputs``: Just like ``common_outputs``, but specific to this job.


In this example, we specify one node that boots up and runs the
``pinglatency.sh`` benchmark, then powers off cleanly and 7 nodes that just
idle waiting to be pinged.

Given this JSON description, our existing ``pinglatency.sh`` script in the
overlay directory, and the base rootfses generated in ``firesim-software``,
the following command will automatically generate all of the rootfses that you
see in the ``ping-latency`` directory.

::

    [ from the workloads/ directory ]
    python gen-benchmark-rootfs.py -w ping-latency.json -r -b ../../sw/firesim-software/images/br-base.img -s ping-latency/overlay

Notice that we tell this script where the json file lives, where the base rootfs image is, and where we expect to find files
that we want to include in the generated disk images. This script will take care of the rest and we'll end up with 
``idler-[1-6].ext2``, ``pingee.ext2``, and ``pinger.ext2``!

You'll notice a Makefile in the ``workloads/`` directory -- it contains many
similar commands for all of the workloads included with FireSim.

Once you generate the rootfses for this workload, you can run it with the manager
by setting ``workload=ping-latency.json`` in ``config_runtime.ini``. The manager
will automatically look for the generated rootfses (based on workload and job names
that it reads from the json) and distribute work appropriately.

Just like in the uniform case, it will copy back the results that we specify
in the json file. We'll end up with a directory in ``firesim/deploy/results-workload/``
named after the workload name, with a subdirectory named after each job in the workload,
which will contain the output files we want.


