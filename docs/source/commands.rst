.. _firemarshal-commands:

FireMarshal Commands
=======================

.. Note:: Marshal commands may execute scripts defined in the workload with
  full user permissions. Users should excercise caution when building and running
  untrusted workloads.

Core Options
--------------------
The base ``marshal`` command provides a number of options that apply to most
sub-commands. You can also run ``marshal -h`` for the most up-to-date
documentation.

.. _command-opt-workdir:

``--workdir``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
By default, FireMarshal will search the same directory as the provided
configuration file for ``base`` references and the workload source directory.
This option instructs FireMarshal to look elsewhere for these references. See
:ref:`workload-search-paths` for details of how workloads are located.

``-d --no-disk``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
By default, FireMarshal assumes that your workload includes both a rootfs and a
boot-binary. However, it may be necessary (e.g. when using spike) to build the
rootfs into the boot-binary and load it into RAM during boot. This is only
supported on linux-based workloads. This option instructs FireMarshal too use
the \*-nodisk boot-binary instead of the disk-based outputs.

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
the --no-disk option to FireMarshal, a ``images/NAME-JOBNAME-bin-nodisk``
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

The launch command will run the workload in either Qemu (a high-performance functional simulator) or spike (the official RISC-V ISA simulator). Qemu will be used by default and is the best choice in most circumstances.

::

  ./marshal launch [-s] [-a] [-j JOB] config

Each workload (root/job) is run in its own screen session. In order to interact with or observe a workload, one can attach to the corresponding screen session using standard screen syntax and the identifier listed in the output of ``launch``.

::

  screen -r <screen-identifier>

When running a single workload, FireMarshal attaches to its screen session by default. When running multiple workloads, the user must manually attach to a session of their choice.

FireMarshal only exits after all launched workloads exit.

``-a --all``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
Launch all jobs in the workload. Jobs will be run sequentially. See the
``--job`` option documentation for details of job launching.

``-j --job``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
In workloads with multiple jobs, you can specify which job(s) to launch.
FireMarshal supports running multiple jobs concurrently but does not support networked jobs yet. Jobs are identified by their ``name`` attribute. Multiple ``-j``
options may be passed to invoke multiple jobs. Use ``--all`` to launch all jobs
in the workload. If neither ``--job`` nor ``--all`` are provided, the root
workload will be run. The root workload is the parent of all the jobs (i.e. the
top level config in your workload). This can be useful for debugging a multi-job
workload.

``-s --spike``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
In some cases, you may need to boot your workload in spike (typically due to a
custom ISA extension or hardware model). In that case, you may use the -s
option. Note that spike currently does not support network or block devices.
You must pass the --no-disk option to FireMarshal when using spike.

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
:ref:`config-testing` for details of the testing specification. If jobs
are specified, all jobs will be run independently and their outputs will be
included in the output directory.

``-s --spike``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
Test using spike instead of qemu (requires the --no-disk option to the
``marshal`` command).

``-m testDir --manual testDir``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
Do not build and launch the workload, simply compare its ``testing``
specification against a pre-existing output. This allows you to check the
output of firesim runs against a workload. It is also useful when developing a
workload test.

.. _command-install:

install
--------------------------------------
Install the workload to an external service (e.g. an RTL simulator). The
available installation targets depends on your board.

.. Note:: If FireMarshal was cloned as a
  submodule of either `FireSim <https://www.fires.im>`_ or `Chipyard
  <https://chipyard.readthedocs.io/en/latest/>`_, and you are using the default
  'firechip' board, the 'firesim' installation target should work out of the
  box. Otherwise, you will need to configure your installation targets in
  marshal-config.yaml.
