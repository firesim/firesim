.. _defining-custom-workloads:

Defining Custom Workloads
================================================

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
  :gh-file-ref:`deploy/workloads/linux-uniform.json`.

- Several different jobs, in which case there must be exactly as many
  jobs as there are running simulated nodes. An example of such a workload is
  :gh-file-ref:`deploy/workloads/ping-latency.json`.

FireSim uses these workload definitions to help the manager deploy your
simulations. Historically, there was also a script to build workloads using
these JSON files, but this has been replaced with a more powerful tool,
:ref:`firemarshal`. New workloads should always be built with :ref:`firemarshal`.

In the following subsections, we will go through the two aforementioned example
workload configurations, describing the how the manager uses each part of the JSON file
inline.


The following examples use the default buildroot-based linux
distribution (br-base). In order to customize Fedora, you should refer to the :ref:`booting-fedora`
page.


Uniform Workload JSON
----------------------------

:gh-file-ref:`deploy/workloads/linux-uniform.json` is an example of a "uniform"
style workload, where each simulated node runs the same software configuration.

Let's take a look at this file:

.. include:: /../deploy/workloads/linux-uniform.json
   :code: json

There is also a corresponding directory named after this workload/file:

::

	centos@ip-192-168-2-7.ec2.internal:~/firesim/deploy/workloads/linux-uniform$ ls -la
        total 4
        drwxrwxr-x  2 centos centos   69 Feb  8 00:07 .
        drwxrwxr-x 19 centos centos 4096 Feb  8 00:39 ..
        lrwxrwxrwx  1 centos centos   47 Feb  7 00:38 br-base-bin -> ../../../sw/firesim-software/images/br-base-bin
        lrwxrwxrwx  1 centos centos   53 Feb  8 00:07 br-base-bin-dwarf -> ../../../sw/firesim-software/images/br-base-bin-dwarf
        lrwxrwxrwx  1 centos centos   47 Feb  7 00:38 br-base.img -> ../../../sw/firesim-software/images/br-base.img



We will elaborate on this later.

Looking at the JSON file, you'll notice that this is a relatively simple
workload definition.

In this "uniform" case, the manager will name simulations after the
``benchmark_name`` field, appending a number for each simulation using the
workload (e.g.  ``linux-uniform0``, ``linux-uniform1``, and so on). It is
standard pratice to keep ``benchmark_name``, the JSON filename, and the above
directory name the same. In this case, we have set all of them to
``linux-uniform``.

Next, the ``common_bootbinary`` field represents the binary that the simulations
in this workload are expected to boot from. The manager will copy this binary
for each of the nodes in the simulation (each gets its own copy). The ``common_bootbinary`` path is
relative to the workload's directory, in this case
:gh-file-ref:`deploy/workloads/linux-uniform`. You'll notice in the above output
from ``ls -la`` that this is actually just a symlink to ``br-base-bin`` that
is built by the :ref:`FireMarshal <firemarshal>` tool.

Similarly, the ``common_rootfs`` field represents the disk image that the simulations
in this workload are expected to boot from. The manager will copy this root
filesystem image for each of the nodes in the simulation (each gets its own copy).
The ``common_rootfs`` path is
relative to the workload's directory, in this case
:gh-file-ref:`deploy/workloads/linux-uniform`. You'll notice in the above output
from ``ls -la`` that this is actually just a symlink to ``br-base.img`` that
is built by the :ref:`FireMarshal <firemarshal>` tool.

The ``common_outputs`` field is a list of outputs that the manager will copy out of
the root filesystem image AFTER a simulation completes. In this simple example,
when a workload running on a simulated cluster with ``firesim runworkload``
completes, ``/etc/os-release`` will be copied out from each rootfs and placed
in the job's output directory within the workload's output directory (See
the :ref:`firesim-runworkload` section). You can add multiple paths
here. Additionally, you can use bash globbing for file names (ex: ``file*name``).

The ``common_simulation_outputs`` field is a list of outputs that the manager
will copy off of the simulation host machine AFTER a simulation completes. In
this example, when a workload running on a simulated cluster with
``firesim runworkload``
completes, the ``uartlog`` (an automatically generated file that contains the
full console output of the simulated system) and ``memory_stats.csv`` files
will be copied out of the simulation's base directory on the host instance and
placed in the job's output directory within the workload's output directory
(see the :ref:`firesim-runworkload` section). You can add multiple
paths here. Additionally, you can use bash globbing for file names
(ex: ``file*name``).

..
  TODO: this is no longer relevant with firemarshal
  **ERRATA**: "Uniform" style workloads currently do not support being
  automatically built -- you can currently hack around this by building the
  rootfs as a single-node non-uniform workload, then deleting the ``workloads``
  field of the JSON to make the manager treat it as a uniform workload. This will
  be fixed in a future release.


Non-uniform Workload JSON (explicit job per simulated node)
---------------------------------------------------------------

Now, we'll look at the ``ping-latency`` workload, which explicitly defines a
job per simulated node.

.. include:: /../deploy/workloads/ping-latency-firemarshal.json
   :code: json

Additionally, let's take a look at the state of the ``ping-latency`` directory
AFTER the workload is built (assume that a tool like :ref:`firemarshal` already
created the rootfses and linux images):

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
- ``.gitignore``: This just ignores the generated rootfses, which you probably don't want to commit to the repo.
- ``idler-[1-6].ext2``, ``pingee.ext2``, ``pinger.ext2``: These are rootfses
  that we want to run on different nodes in our simulation. They can be
  generated with a tool like :ref:`firemarshal`.

Next, let's review some of the new fields present in this JSON file:

- ``common_simulation_inputs``: This is an array of extra files that you would
  like to supply to the simulator as *input*. One example is supplying files
  containing DWARF debugging info for TracerV + Stack Unwinding. See the
  :ref:`tracerv-flamegraph-workload-description` section of the
  :ref:`tracerv-with-flamegraphs` page for an example.
- ``no_post_run_hook``: This is a placeholder for running a script on your
  manager automatically once your workload completes. To use this option, rename
  it to ``post_run_hook`` and supply a command to be run. The manager will
  automatically suffix the command with the path of the workload's results
  directory.
- ``workloads``: This time, you'll notice that we have this array, which is
  populated by objects that represent individual jobs (note the naming discrepancy
  here, from here on out, we will refer to the contents of this array as **jobs**
  rather than **workloads**). Each job has some additional fields:

   - ``name``: In this case, jobs are each assigned a name manually. These names MUST BE UNIQUE within a particular workload.
   - ``simulation_inputs``: Just like ``common_simulation_inputs``, but specific to this job.
   - ``simulation_outputs``: Just like ``common_simulation_outputs``, but specific to this job.
   - ``outputs``: Just like ``common_outputs``, but specific to this job.

Because each of these jobs do not supply a ``rootfs`` field, the manager instead
assumes that that the rootfs for each job is named ``name``.ext2. To explicitly
supply a rootfs name that is distinct from the job name, add the ``rootfs``
field to a job and supply a path relative to the workload's directory.

Once you specify the ``.json`` for this workload (and assuming you have built
the corresponding rootfses with :ref:`firemarshal`, you can run it with the
manager by setting ``workload_name: ping-latency.json`` in ``config_runtime.ini``.
The manager will automatically look for the generated rootfses (based on
workload and job names that it reads from the JSON) and distribute work
appropriately.

Just like in the uniform case, it will copy back the results that we specify in
the JSON file. We'll end up with a directory in
``firesim/deploy/results-workload/`` named after the workload name, with
a subdirectory named after each job in the workload, which will contain the
output files we want.


