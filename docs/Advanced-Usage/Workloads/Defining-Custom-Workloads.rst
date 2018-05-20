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
	lrwxrwxrwx  1 centos centos   41 May 17 21:58 bbl-vmlinux -> ../../../sw/firesim-software/bbl-vmlinux0
	lrwxrwxrwx  1 centos centos   41 May 17 21:58 rootfs.ext2 -> ../../../sw/firesim-software/rootfs0.ext2

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
from ``ls -la`` that this is actually just a symlink to ``bbl-vmlinux0`` that
is built by the FireSim Linux distro in ``firesim/sw/firesim-software``.

Similarly, the ``common_rootfs`` field represents the disk image that the simulations
in this workload are expected to boot from. The manager will copy this root
filesystem image for each of the nodes in the simulation (each gets its own copy).
The ``common_rootfs`` path is 
relative to the workload's directory, in this case
``firesim/deploy/workloads/linux-uniform``. You'll notice in the above output
from ``ls -la`` that this is actually just a symlink to ``rootfs0.ext2`` that
is built by the FireSim Linux distro in ``firesim/sw/firesim-software``.

The ``common_outputs`` field is a list of outputs that the manager will copy out of
the root filesystem image AFTER a simulation completes. In this simple example,
when a workload running on a simulated cluster with ``firesim runworkload``
completes, ``/etc/os-release`` will be copied out from each rootfs and placed
in the job's output directory within the workload's output directory (TODO see
the documentation for ``firesim runworkload``). You can add multiple paths
here.

The ``common_simulation_outputs`` field is a list of outputs that the manager
will copy off of the simulation host machine AFTER a simulation completes. In
this example, when a workload running on a simulated cluster with
``firesim runworkload``
completes, the ``uartlog`` (an automatically generated file that contains the
full console output of the simulated system) and ``memory_stats.csv`` files
will be copied out of the simulation's base directory on the host instance and
placed in the job's output directory within the workload's output directory
(TODO see the documentation for ``firesim runworkload``). You can add multiple
paths here.

**ERRATA**: "Uniform" style workloads currently do not support being
automatically built -- you can currently hack around this by building the
rootfs as a single-node non-uniform workload, then deleting the ``workloads``
field of the JSON to make the manager treat it as a uniform workload. This will
be fixed in a future release.


Non-uniform Workload JSON (explicit job per simulated node)
---------------------------------------------------------------













Although each simulation in such a workload boots the same job.
