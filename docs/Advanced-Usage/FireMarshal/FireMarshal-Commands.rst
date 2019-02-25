.. _firemarshal-commands:

FireMarshal Commands
=======================

.. attention::

   FireMarshal is still in alpha. You are encouraged to try it out and use it
   for new workloads. The old-style workload generation is still supported (see
   :ref:`defining-custom-workloads` for details).


Core Options
--------------------
The base ``marshal`` command provides a number of options that apply to most
sub-commands. You can also run ``marshal -h`` for the most up-to-date
documentation.

``--workdir``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
By default, FireMarshal will search the same directory as the provided
configuration file for ``base`` references and the workload source directory.
This option instructs FireMarshal to look elsewhere for these references.

``-i --initramfs``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
By default, FireMarshal assumes that your workload includes both a rootfs and a
boot-binary. However, it may be necessary (e.g. when using spike) to build the
rootfs into the boot-binary and load it into RAM during boot. This is only
supported on linux-based workloads. This option instructs FireMarshal too use
the \*-initramfs boot-binary instead of the disk-based outputs.

``-v --verbose``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
FireMarshal will redirect much of it's output to a log file in order to keep
standard out clean. This option instructs FireMarshal to print much more output to
standard out (in addition to logging it).

build
--------------------------------------
The build command is used to generate the rootfs's and boot-binaries from the
workload configuration file. The output will be ``images/NAME-JOBNAME-bin`` and
``images/NAME-JOBNAME.img`` files for each job in the workload. If you passed
the --initramfs option to FireMarshal, a ``images/NAME-JOBNAME-bin-initramfs``
file will also be created.

::

  ./marshal build [-B] [-I] config [config]

You may provide multiple config files to build at once.

``-I -B``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
These options allow you to build only the image (rootfs) or boot-binary
(respectively). This is occasionally useful if you have incomplete changes in
the image or binary definitions but would still like to test the other.

launch
--------------------------------------
The launch command will run the workload in either Qemu (a high-performance
functional simulator) or spike (the official RISC-V ISA simulator). Qemu will
be used by default and is the best choice in most circumstances.

::

  ./marshal launch [-s] [-j [JOB]] config

``-j --job``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
FireMarshal currently only supports launching one node at a time. By default,
only the main workload will be run, you can specify jobs (using the job 'name')
to run using the --job option.

``-s --spike``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
In some cases, you may need to boot your workload in spike (typically due to a
custom ISA extension or hardware model). In that case, you may use the -s
option. Note that spike currently does not support network or block devices.
You must pass the --initramfs option to FireMarshal when using spike.

clean
--------------------------------------
Deletes all outputs for the provided configuration (rootfs and bootbinary).
Running the build command multiple times will re-run guest-init scripts and
re-apply any files, but will not re-produce the base image. If you need to
inherit changes from an updated base config, or generate a clean image (e.g. if
the filesystem was corrupted), you must clean first.

test
--------------------------------------
The test command will build and run the workload, and compare its output
against the ``testing`` specification provided in its configuration. See
:ref:`firemarshal-config` for details of the testing specification. If jobs
are specified, all jobs will be run independently and their outputs will be
included in the output directory.

``-s --spike``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
Test using spike instead of qemu (requires the --initramfs option to the
``marshal`` command).

``-m testDir --manual testDir``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
Do not build and launch the workload, simply compare it's ``testing``
specification against a pre-existing output. This allows you to check the
output of firesim runs against a workload. It is also useful when developing a
workload test.

install
--------------------------------------
.. _firemarshal-install:

Creates a firesim workload definition file in ``firesim/deploy/workloads`` with
all appropriate links to the generated workload. This allows you to launch the
workload in firesim using standard commands (see :ref:`running_simulations`).
