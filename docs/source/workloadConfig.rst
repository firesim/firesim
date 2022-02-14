.. _workload-config:

Workload Specification
=================================

Workloads are defined by a JSON or YAML-formatted configuration file and
corresponding workload source directory. These files can be anywhere on your
filesystem. Most paths in the configuration file are assumed to be relative to
the workload source directory.

.. _workload-search-paths:

Workload Search Paths
-------------------------
FireMarshal will search through several locations when attempting to find a
reference workload (either as the target, or as a parent). In order (with later
options taking precedence over earlier options):

#. Default builtin workloads (defined by the board you are using). These
   include things like ``br-base.json`` or ``fedora-base.json`` etc.
#. The :ref:`config-workload-dirs` configuration option.
#. The :ref:`command-opt-workdir` command line option.
#. The parent directory of the target workload path provided on the command
   line. In other words, treat the target as a relative path and look in the
   same directory as the target for parent workloads.

Workloads are identified uniquely by the name of the configuration file (e.g.
`br-base.json`), not by their absolute paths. If a file with the same name is
found later in the search order, it will be used instead of earlier
occurrences. The :ref:`workload-name` must also be unique.

Configuration Options
-------------------------

.. _workload-name:

name
^^^^^^^^
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

qemu
^^^^^^^^
Path to binary to use for qemu (qemu-system-riscv64) when launching this
workload. Defaults to the version of qemu-system-riscv64 on your $PATH.

spike
^^^^^^^^^^
Path to binary for spike (riscv-isa-sim) to use when running this
workload in spike. Useful for custom forks of spike to support custom
instructions or hardware models. Defaults to the version of spike on your PATH.

.. _workload-linux-src:


.. _distro-configuration:

distro
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
Controls options related to your base distribution (e.g. buildroot or fedora).
See :ref:`Base Distributions <distros>` for more information on distributions.

Since distributions sit at the bottom of the inheritance chain, changing
distro-related options creates a fork of all parents. This new set of parents
will be suffixed by a unique identifier for this distro configuration (the
identifier is distro specific). For example, suppose we have a buildroot-based
workload called 'example.json' that changes some buildroot options. In this
case, we would have two copies of br-base (the board's base workload) and br
(the distro itself), one for the default configuration and one for this new
configuration (identified by 0FD2 in this example):

::

   br     -> br-base.json
   br0FD2 -> br-base.json0FD2 -> example.json

These forked parents are simply intermediates in the build process, they aren't
full workloads in the convential sense (e.g. you can't ``marshal launch
br-base.json0FD2``).

name
+++++++++++++++++
Which distro you are based on. This option is required but is mostly for
documentation purposes and to aid in parsing the configuration. You should not
generally change this option (it is used by the base workloads in your board). 

opts
+++++++++++++++++
Options to pass to your distribution. The details of this option are
distro-specific (documented in :ref:`Base Distributions <distros>`).

linux-src (Deprecated)
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
See the 'linux/source' option for the new equivalent.

linux-config (Deprecated)
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
See the 'linux/config' option for the new equivalent.

linux
^^^^^^^^^^^^^^^^^
Specification for linux customization.

source
+++++++++++++++++
Path to linux source directory to use when building the boot-binary for
this workload. Will default to your board's linux source. 

.. _linux-config:

config
++++++++++++++++++
Linux configuration fragment to use. This file has the same format as linux
configuration files but only contains the options required by this workload.
Marshal will include a few options on top of the RISC-V default configuration,
and then apply the workload linux-config before building. Workload
configurations will override Marshal defaults, you should strive to include
only the minimum necessary changes for your workload. In particular, you should
avoid specifying a custom initramfs since Marshal provides it's own for loading
platform drivers.

Inheritance happens by merging the current workload's linux-config with all
parent configs, with more recent options taking precedence of earlier options.

modules
+++++++++++++++++++
A dictionary of the form {MODULE_NAME : PATH_TO_MODULE_SRC, ...}

Kernel modules to compile and load automatically in your workload. Modules will
be loaded in the initramfs before loading the main user root. Modules are
identified by the .ko file name (the name listed by lsmod). Workload-defined
modules with the same name as an inherited module will take precidence,
allowing you to override system default drivers. If the path is null, the
module will not be included. This is useful for disabling inherited modules.

If you need to manually load a module (it shouldn't be automatically loaded),
you should manually compile it in a post-bin script instead.

The specified module directory must contain a Makefile that can be invoked as:

   make LINUXSRC=PATH/TO/LINUX

It outputs one or more .ko files to be loaded. See test/kmod/ for an example.

pk-src (Deprecated)
^^^^^^^^^^^^^^^^^^^^
See the ‘firmware/bbl-src’ option for the new equivalent.

firmware
^^^^^^^^^^^^^^^^^^^
Firmware-related options

opensbi-src
+++++++++++++++++++++
Path to alternative opensbi source directory. Defaults to the board-provided
opensbi.

opensbi-build-args
+++++++++++++++++++++
Additional command line arguments to the 'make' command used to build opensbi.

bbl-src
++++++++++++++++++
Path to riscv-pk source directory to use for this workload. This provides the
bootloader (bbl). Defaults to the riscv-pk source included with the board.

bbl-build-args
++++++++++++++++++
Additional arguments to be passed to the configure script for bbl. Arguments
will be appended to any system defaults.

use-bbl
++++++++++++++++++
Use BBL as the firmware rather than the default OpenSBI. If you specified
'bbl-src', that will be used, otherwise the default implementation from the
board will be used.

host-init
^^^^^^^^^^^^^^
A script to run natively on your host (i.e., the machine where you
invoked FireMarshal) from the workload source directory each time you
explicitly build this workload. This option may include arguments for the script, e.g.
``"host-init" : "foo.sh bar baz"``.


*Non-heritable*: The host-init script will not be re-run for child workloads.
However, any affects that host-init has on the resulting rootfs *will* be
reflected in the child.

post-bin
^^^^^^^^^^^^^^
A script to run natively on your host (i.e., the machine where you
invoked FireMarshal) from the workload source directory after you build the
binary(s) for this workload but before the image(s) is/are built.
This option is useful for such things as building kernel modules against the specific
kernel and ``kconfig`` used or analyzing the kernel binary to extract debugging or tracing
information.

You can expect to have the Linux ``.config`` file that built the Linux binary in the Linux source
directory. Additionally, you can expect all build artifacts from building the kernel to be
present in the Linux source directory (i.e no ``make clean`` or equivalent will be run in the
kernel source).

This option may include arguments for the script, e.g. ``"post-bin" : "foo.sh bar baz"``.
Additionally, you may use the ``FIREMARSHAL_LINUX_SRC`` and ``FIREMARSHAL_LINUX_BIN`` environment
variables within the script (they give the path to the Linux source used and the binary created).

*Non-heritable*: The post-bin script will not be re-run for child workloads.
However, any affects that post-bin has on the resulting rootfs *will* be
reflected in the child.

guest-init
^^^^^^^^^^^^^^
A script to run natively on the guest (in qemu) exactly once while building.
The guest init script will be run from the root directory with root privileges.
This script should end with a call to ``poweroff`` to make the build process
fully automated. Otherwise, the user will need to log in and shut down manually
on each build. This option may include arguments for the script, e.g.
``"guest-init" : "foo.sh bar baz"``.

*Non-heritable*: The guest-init script will not be re-run for child workloads.
However, any affects that guest-init has on the resulting rootfs *will* be
reflected in the child.

post_run_hook
^^^^^^^^^^^^^^^^
A script or command to run on the output of your run. At least the uart output of
each run is captured, along with any file outputs specified in the `outputs`_
option. This option may include arguments for the script, e.g.
``"post_run_hook" : "foo.sh bar baz"``. The script will be called like so:

::

  cd workload-dir
  post_run_hook ARGS /path/to/output

Where ARGS are any arguments you included in the post_run_hook option. The
output directory will follow roughly the following format:

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

.. _workload-rootfs-size:

rootfs-size
^^^^^^^^^^^^^^^^
The desired rootfs size (in human-readable units, e.g. "4GB"). This number must
either be >= to the parent workload's image size or set to 0. If set to 0, the
rootfs will be shrunk to have only a modest amount of free space (the exact
margin is set by the :ref:`config-rootfs-size` global configuration option,
256MiB by default).

.. Note:: It is only necessary to set this option if you intend to copy in
   large amounts of files or your workload generates large intermediate files.
   The base workloads all have the default rootfs-margin included.

run
^^^^^^^^^^^^
A script to run automatically every time this workload runs. The script will
run after all other initialization finishes, but does not require the user to
log in (run scripts run concurrently with any user interaction). Run scripts
typically end with a call to ``poweroff`` to make the workload fully automated,
but this can be omitted if you would like to interact with the workload after
its run script has finished. This option may include arguments for the script,
e.g.  ``"run" : "foo.sh bar baz"``.

.. Note:: The FireMarshal launch command uses the same rootfs for each run (not
  a copy), so you should avoid using ``poweroff -f`` to prevent filesystem
  corruption.

*Non-heritable*

command
^^^^^^^^^^^^
A command to run every time this workload runs. The command will be run from
the root directory and will automatically call ``poweroff`` when complete (the
user does not need to include this).

*Non-heritable*

.. _config-workdir:

workdir
^^^^^^^^^^
Directory to use as the workload source directory. Defaults to a directory with
the same name as the configuration file.

*Non-heritable*

launch
^^^^^^^^^^
Enable/Disable launching of a job when running the 'test' command. This is
occasionally needed for special 'dummy' workloads or other special-purpose jobs
that only make sense when running on real RTL. Defaults to 'yes'.

jobs
^^^^^^^^
A list of configurations describing individual jobs that make up this workload.
This list is ordered (on platforms that support ordering like FireSim, these jobs will be placed in-order in simulation slots).
Job descriptions have the same syntax and options as normal workloads. The one
exception is that jobs implicitly inherit from the parent workload unless a
``base`` option is explicitly provided. The job name will be appended to the
workload name when creating boot-binaries and rootfs's. For example, a workload
called "foo" with two jobs named 'bar' and 'baz' would create 3 rootfs's:
foo.img, foo-bar.img, and foo-baz.img.

*Non-heritable*: You cannot use jobs as a ``base``, only base workloads.

bin
^^^^^^^^
Explicit path to the boot-binary to use. This will override any generated
binaries created during the build process. This is particularly useful for
bare-metal workloads that generate their own raw boot code.

*Non-heritable*

img
^^^^^^^^
Explicit path to the rootfs to use. This will override any generated rootfs
created during the build process. This means filesystem-related options like
overlay or 'run'/'command' will no longer work.

*Non-heritable*

.. _config-testing:

testing
^^^^^^^^^^^^
Provide details of how to test this workload. The ``test`` command will ignore
any workload that does not have a ``testing`` field. This option is a map with
the following options (only ``refDir`` is required):

*Non-heritable*

refDir
++++++++++++++++
Path to a directory containing reference outputs for this workload. Directory
structures are compared directly (same folders, same file names). Regular files
are compared exactly. Serial outputs (uartlog) need only match a subset of
outputs; the entire reference uartlog contents must exist somewhere
(contiguously) in the test uartlog.

buildTimeout
+++++++++++++++++++++
Maximum time (in seconds) that the workload should take to build. The test will
fail if building takes longer than this. Defaults to infinite.

.. Note:: workloads with many jobs and guest-init scripts, could take a very
  long time to build.

runTimeout
+++++++++++++++++
Maximum time (in seconds) that any particular job should take to run and exit.
The test will fail if a job runs for longer than this before exiting. Defaults
to infinite.

strip
+++++++++++++++
Attempt to clean up the uartlog output before comparing against the reference.
This will remove all lines not generated by a run script or command, as well as
stripping out any extra characters that might be added by the run-system (e.g.
the systemd timestamps on Fedora). This option is highly recommended on Fedora
due to it's non-deterministic output.

spike-args
^^^^^^^^^^^^^^
Provide additional commandline arguments to spike when launching or testing
this workload. These may not override builtin options. Do not use this for
setting cpu or memory sizes, see 'cpus' and 'mem' for how to change those
options.

qemu-args
^^^^^^^^^^^^^^
Provide additional commandline arguments to Qemu when launching or testing
this workload. These may not override builtin options. Do not use this for
setting cpu or memory sizes, see 'cpus' and 'mem' for how to change those
options.

cpus
^^^^^^^^^^^^
Set the number of cpus to use when launching or testing this workload in
functional simulation. Does not affect the 'install' command.

mem
^^^^^^^^^^^^
Set the amount of memory to use when launching or testing this workload in
functional simulation. Does not affect the 'install' command. This value can be
either a string with standard size annotations (e.g. "4GiB") or an integer
representing the number of megabytes to use.
