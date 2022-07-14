.. _tutorial-custom:

Custom Workloads
======================
While FireMarshal provides some basic built-in workloads for getting started
(described in :ref:`tutorial-quickstart`), the real power lies in its ability
to define custom workloads.

A custom workload takes the form of a workload configuration file, and a
directory containing any files needed by the workload. Additionally, workloads
can be based on a parent workload to avoid duplicate work. These files can
be anywhere you like, but FireMarshal must be able to find the workload
descriptions. See :ref:`workload-search-paths` for details on how FireMarshal
searches for workloads.

We now walk through two examples of building a custom workload. For full
documentation of every option, see :ref:`workload-config`.

Fedora-Based
-----------------------------
``example-workloads/example-fed.json``

In this example, we produce a 2-node workload that runs two benchmarks:
quicksort and spam-filtering. This will require installing a number of packages
on Fedora, as well as cross-compiling some code. The configuration is as
follows:

.. include:: ../../../example-workloads/example-fed.json
  :code: json

The ``name`` field is required and (by convention) should match the name of the
configuration file. Next is the ``base`` (fedora-base.json). This option
specifies an existing workload to base off of. FireMarshal will first build
``fedora-base.json``, and use a copy of its rootfs for example-fed before
applying the remaining options. Additionally, if fedora-base.json specifies any
configuration options that we do not include, we will inherit those (e.g. we
will use the ``linux-config`` option specified by fedora-base).

Next come a few options that specify common setup options used by all jobs in
this workload. The ``overlay`` option specifies a filesystem overlay to copy
into our rootfs. In this case, it includes the source code for our benchmarks
(see ``example-workloads/example-fed/overlay``). Next is a ``host-init``
option, this is a script that should be run on the host before building. In our
case, it cross-compiles the quicksort benchmark (cross-compilation is much
faster than natively compiling).

.. include:: ../../../example-workloads/example-fed/host-init.sh
  :code: bash

Next is ``guest-init``, this script should run exactly once natively within our
workload. For example-fed, this script installs a number of packages that are
required by our benchmarks. Note that guest-init scripts are run during the
build process; this can take a long time, especially with fedora. You will see
linux boot messages and may even see a login prompt. There is no need to login
or interact at all, the guest-init script will run in the background. Note that
guest-init.sh ends with a ``poweroff`` command, all guest-init scripts should
include this (leave it off to debug the build process).

.. include:: ../../../example-workloads/example-fed/guest-init.sh
  :code: bash

Finally, we specify the two jobs that will run on each simulated node. Job
descriptions have the same format and options as normal workloads. However,
notice that the job descriptions are much shorter than the basic descriptions.
Jobs implicitly inherit from the root configuration. In this case, both qsort
and spamBench will have the overlay and host/guest-init scripts already set up
for them. If needed, you could override these options with a different ``base``
option in the job description. In our case, we need only provide a custom
``run`` option to each workload. The run option specifies a script that should
run natively in each job every time the job is launched. In our case, we run
each benchmark, collecting some statistics along the way, and then shutdown.
Finishing a run script with ``poweroff`` is a common pattern that allows
workloads to run automatically (no need to log-in or interact at all).

.. include:: ../../../example-workloads/example-fed/runQsort.sh
  :code: bash

We can now build and launch this workload:

::

  ./marshal build workloads/example-fed.json
  ./marshal launch -j qsort workloads/example-fed.json
  ./marshal launch -j spamBench workloads/example-fed.json

You will see each job launch and can inspect the live input. The outputs and
uart log will be saved in in the ``runOutputs`` directory with a name like
``runOutputs/example-fed-launch-2020-01-23--01-33-41-X17CZDQ4P3DW1QRI``.

Bare-Metal Workload
-------------------------
``test/bare.yaml``

FireMarshal was primarily designed to support linux-based workloads. However,
it provides basic support for bare-metal workloads. Take ``test/bare.yaml`` as
an example:

.. include:: ../../../test/bare.yaml
  :code: json

This workload creates a simple "Hello World" bare-metal workload. This workload
simply inherits from the "bare" distro in its ``base`` option. This tells
FireMarshal to not attempt to build any linux binaries or rootfs's for this
workload. It then includes a simple host-init script that simply calls the
makefile to build the bare-metal boot-binary. Finally, it hard-codes a path to
the generated boot-binary. Note that we can still use all the standard
FireMarshal commands with bare-metal workloads. In this case, we provide a
testing specification that simply compares the serial port output against the
known good output of "Hello World!".

.. todo:: Add (or link to) more detailed examples of bare-metal and rocc workloads.

Next Steps
-------------------
For more examples, you can look in the ``test/`` directory. The full set of
workload configuration options is documented at :ref:`workload-config`. You can
also customize much of the default behavior of FireMarshal through config files
or environment variables (see :ref:`marshal-config`). Finally, the available
commands and their options are documented in :ref:`firemarshal-commands`.
