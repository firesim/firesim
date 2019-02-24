.. _firemarshal-config:

Workload Specification
=================================

.. attention::

   FireMarshal is still in alpha. You are encouraged to try it out and use it
   for new workloads. The old-style workload generation is still supported (see
   :ref:`defining-custom-workloads` for details).

Workloads are defined by a configuration file and corresponding workload source
directory, both typically in the ``firesim/sw/firesim-software/workloads/``
directory. Most paths in the configuration file are assumed to be relative to
the workload source directory.

Example Configuration File
-----------------------------
FireMarshal supports many configuration options (detailed below), many of which
are not commonly used. We will now walk through an example that uses most of
the common options: ``workloads/example-fed.json``. In this example, we produce
a 2-node workload that runs two benchmarks: quicksort and spam-filtering. This
will require installing a number of packages on Fedora, as well as
cross-compiling some code. The configuration is as follows:

.. include:: example-fed.json
  :code: json

The ``name`` field is required and (by convention) should match the name of the
configuration file. Next is the ``base`` (fedora-base.json). This option
specifies an existing workload to base off of. FireMarshal will first build
``fedora-base.json``, and use a copy of its rootfs for example-fed before
applying the remaining options. Additionally, if fedora-base.json specifies any
configuration options that we do not include, we will inherit those (e.g. we
will use the ``linux-config`` option specified by fedora-base). Notice that we
do not specify a workload source directory. FireMarshal will look in
``workloads/example-fed/`` for any sources specified in the remaining options.

Next come a few options that specify common setup options used by all jobs in
this workload. The ``overlay`` option specifies a filesystem overlay to copy
into our rootfs. In this case, it includes the source code for our benchmarks
(see ``workloads/example-fed/overlay``). Next is a ``host-init`` option, this
is a script that should be run on the host before building. In our case, it
cross-compiles the quicksort benchmark (cross-compilation is much faster than
natively compiling).

.. include:: example-fed/host-init.sh
  :code: bash

Next is ``guest-init``, this script should run exactly once natively within our
workload. For example-fed, this script installs a number of packages that are
required by our benchmarks. Note that guest-init scripts are run during the
build process; this can take a long time, especially with fedora. You will see
linux boot messages and may even see a login prompt. There is no need to login
or interact at all, the guest-init script will run in the background. Note that
guest-init.sh ends with a ``poweroff`` command, all guest-init scripts should
include this (leave it off to debug the build process).

.. include:: example-fed/guest-init.sh
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

.. include:: example-fed/runQsort.sh
  :code: bash

We can now build and launch this workload:

::

  ./marshal build workloads/example-fed.json
  ./marshal launch -j qsort workloads/example-fed.json
  ./marshal launch -j spamBench workloads/example-fed.json

For more examples, see the ``test/`` directory that contains many workloads
used for testing FireMarshal.

Bare-Metal Workloads
-------------------------
FireMarshal was primarily designed to support linux-based workloads. However,
it provides basic support for bare-metal workloads. Take ``test/bare.json`` as
an example:

.. include:: bare.json
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

A complete discussion of generating bare-metal boot-binaries is out of scope
for this documentation.

Configuration File Options
----------------------------
Below is a complete list of configuration options available to FireMarshal.

name
^^^^^^^^^
Name to use for the workload. Derived objects (rootfs/bootbin) will be named
according to this option.

*Non-heritable*

base
^^^^^^^^^^
Configuration file to inherit from. FireMarshal will look in the same directory
as the workload config file for the base configuration (or the workdir if
``--workdir`` was passed to the marshal command). A copy of the rootfs from ``base``
will be used when building this workload. Additionally, most configuration
options will be inherited if not explicitly provided (options that cannot be
inherited will be marked as 'non-heritable' in this documentation).

In addition to normal configuration files, you may inherit from several
hard-coded "distros" including: fedora, br (buildroot), and bare. This is not
recommended for the linux-based distros because the fedora-base.json and
br-base.json configurations include useful additions to get things like serial
ports or the network to work. However, basing on the 'bare' distro is the
recommended way to generate bare-metal workloads.

*Non-heritable*

spike
^^^^^^^^^^
Path to binary for spike (riscv-isa-sim) to use when running this
workload in spike. Useful for custom forks of spike to support custom
instructions or hardware models. Defaults to the version of spike on your PATH
(typically the one include with riscv-tools).

linux-src
^^^^^^^^^^^^^^^^
Path to riscv-linux source directory to use when building the boot-binary for
this workload. Defaults to the riscv-linux source submoduled at
``firesim/sw/firesim-software/riscv-linux``.

linux-config
^^^^^^^^^^^^^^^^
Linux configuration file to use when building linux. Take care when using a
custom configuration, FireSim may require certain boot arguments and device
drivers to work properly.

host-init
^^^^^^^^^^^^^^
A script to run natively on your host (i.e., your manager instance where you
invoked FireMarshal) from the workload source directory each time you
explicitly build this workload.

*Non-heritable*

guest-init
^^^^^^^^^^^^^^^ A script to run natively on the guest (i.e., your workload
running in qemu) exactly once while building. The guest init script will be run
from the root directory with root privileges. This script should end with a
call to ``poweroff`` to make the build process fully automated. Otherwise, the
user will need to log in and shut down manually on each build.

post_run_hook
^^^^^^^^^^^^^^^^^
A script or command to run on the output of your run. At least the serial port output of
each run is captured, along with any file outputs specified in the ``outputs``
option. The script will be called like so:

::

  cd workload-dir
  post_run_hook /path/to/output

The output directory will follow roughly the following format:

::

  runOutput/name-DATETIME-RAND/
    name-job/
      uartlog
      OUTPUT_FILE1
      ...
      OUTPUT_FILEN

When running as part of the ``test`` command, there will be a folder for each
job in the workload.

overlay
^^^^^^^^^^^^
Filesystem overlay to apply to the workload rootfs. An overlay should match the
rootfs directory structure, with the overlay directory corresponding to the
root directory. This is especially useful for overriding system configuration
files (e.g. /etc/fstab). The owner of all copied files will be changed to root
in the workload rootfs after copying.

files
^^^^^^^^^^
A list of files to copy into the rootfs. The file list has the following format:

::

  [ ["src1", "dst1"], ["src2", "dst2"], ... ]

The source paths are relative to the workload source directory, the destination
paths are absolute with respect to the workload rootfs (e.g. ["file1",
"/root/"]). The ownership of each file will be changed to 'root' after copying.

outputs
^^^^^^^^^^^^
A list of files to copy out of the workload rootfs after running. Each path
should be absolute with respect to the workload rootfs. Files will be placed
together in the output directory. You cannot specify the directory structure of
the output.

run
^^^^^^^^^^^^^
A script to run automatically every time this workload runs. The script will
run after all other initialization finishes, but does not require the user to
log in (run scripts run concurrently with any user interaction). Run scripts
typically end with a call to ``poweroff`` to make the workload fully automated,
but this can be omitted if you would like to interact with the workload after
its run script has finished.

.. Note:: Unlike FireSim workloads, the FireMarshal launch command uses
  the same rootfs for each run (not a copy), so you should avoid using ``poweroff
  -f`` to prevent filesystem corruption.

*Non-heritable*

command
^^^^^^^^^^^^^
A command to run every time this workload runs. The command will be run from
the root directory and will automatically call ``poweroff`` when complete (the
user does not need to include this). 

*Non-heritable*

workdir
^^^^^^^^^^^
Directory to use as the workload source directory. Defaults to a directory with
the same name as the configuration file.

*Non-heritable*

launch
^^^^^^^^^^^
Enable/Disable launching of a job when running the 'test' command. This is
occasionally needed for special 'dummy' workloads or other special-purpose jobs
that only make sense when running on FireSim. Defaults to 'yes'.

jobs
^^^^^^^^^
A list of configurations describing individual jobs that make up this workload.
This list is ordered (FireSim places these jobs in-order in simulation slots).
Job descriptions have the same syntax and options as normal workloads.  The one
exception is that jobs implicitly inherit from the parent workload unless a
``base`` option is explicitly provided. The job name will be appended to the
workload name when creating boot-binaries and rootfs's. For example, a workload
called "foo" with two jobs named 'bar' and 'baz' would create 3 rootfs's:
foo.img, foo-bar.img, and foo-baz.img.

*Non-heritable*: You cannot use jobs as a ``base``, only base workloads.

bin
^^^^^^^^^
Explicit path to the boot-binary to use. This will override any generated
binaries created during the build process. This is particularly useful for
bare-metal workloads that generate their own raw boot code.

*Non-heritable*

img
^^^^^^^^^
Explicit path to the rootfs to use. This will override any generated rootfs
created during the build process. This is mostly used for debugging.

*Non-heritable*

testing
^^^^^^^^^^^^^
Provide details of how to test this workload. The ``test`` command will ignore
any workload that does not have a ``testing`` field. This option is a map with
the following options (only ``refDir`` is required):

*Non-heritable*

refDir
""""""""""""""
Path to a directory containing reference outputs for this workload. Directory
structures are compared directly (same folders, same file names). Regular files
are compared exactly. Serial outputs (uartlog) need only match a subset of
outputs; the entire reference uartlog contents must exist somewhere
(contiguously) in the test uartlog.

buildTimeout
""""""""""""""""""""
Maximum time (in seconds) that the workload should take to build. The test will
fail if building takes longer than this. Defaults to infinite.

.. Note:: workloads with many jobs and guest-init scripts, could take a very
  long time to build.

runTimeout
""""""""""""""""
Maximum time (in seconds) that any particular job should take to run and exit.
The test will fail if a job runs for longer than this before exiting. Defaults
to infinite.

strip
"""""""""""""
Attempt to clean up the uartlog output before comparing against the reference.
This will remove all lines not generated by a run script or command, as well as
stripping out any extra characters that might be added by the run-system (e.g.
the systemd timestamps on Fedora). This option is highly recommended on Fedora
due to it's non-deterministic output.
