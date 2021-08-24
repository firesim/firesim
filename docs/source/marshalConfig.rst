.. _marshal-config:

FireMarshal Configuration
===================================

The FireMarshal tool itself supports a number of user-defined configuration
options for global behaviors. These can be set by configuration files or
through environment variables.

Configuration Files
----------------------------
Configuration files are in yaml format. Options are documented below and in
``wlutil/default-config.yaml``.

Default
^^^^^^^^^^^^^^^
The default configuration file is located at ``wlutil/default-config.yaml``. You
can use this file as a reference for the configuration file format and options,
as well as documentation of default behaviors. You should not generally change
this file.

User-Defined
^^^^^^^^^^^^^^^^^
FireMarshal looks for configuration files in one of two places (in order):

#. ``$(PWD)/marshal-config.yaml``
#. ``FireMarshal/marshal-config.yaml`` (i.e. in the same directory as the
   `marshal` executable)

Environment Variables
-----------------------------
Configuration files are the primary way to configure FireMarshal, but you can
also set environment variables to override any option. Enviornment variables
should be of the form ``MARSHAL_FOO_BAR``. The option name will be converted to
lower case and underscores will be converted to dashes. E.G.
``MARSHAL_FOO_BAR=baz`` → ``"foo-bar" : 'baz'``.

Configuration Options
----------------------------
All path-like options are interpreted as relative to the location of the
configuration file. Size options can be written in human readable form (e.g.
'4KiB') or simply as the number of bytes (e.g. '4096').

.. _config-workload-dirs:

``workload-dirs``
^^^^^^^^^^^^^^^^^^^^^
List of paths to search when looking up workloads (either the target workload,
or parent workloads). This list is ordered with later entries taking precedence
of earlier entries. See :ref:`workload-search-paths` for details of how
workloads are located.

``board-dir``
^^^^^^^^^^^^^^^^^
Root for default board (platform-specific resources).

``image-dir``
^^^^^^^^^^^^^^^^^^^
Location to store all outputs (binaries and images)

``linux-dir``
^^^^^^^^^^^^^^^^^^^
Default linux source. This is intended to override the global default linux
version, if you need a special kernel for your workload, you should set that in
your workload configuration directly (see :ref:`workload-linux-src`).

.. _config-firesim:

``firesim-dir``
^^^^^^^^^^^^^^^^^^^
Location of the firesim repository to use for the :ref:`command-install` command.

``pk-dir``
^^^^^^^^^^^^^^^^^
Default proxy-kernel source directory. The RISC-V proxy kernel repository
provides code for the Berkeley Boot Loader ('bbl').

``log-dir``
^^^^^^^^^^^^^^^^^^^
Default directory to use when writing logs from FireMarshal runs.

``res-dir``
^^^^^^^^^^^^^^^^^^^^
Default directory to use when writing outputs (results) from a workload (e.g.
uartlog or output files).

``jlevel``
^^^^^^^^^^^^^^^^^^^
FireMarshal calls into makefiles for several of its dependencies. This option
provides the default level of parallelism to use when calling into these
makefiles. The value here will be append to the '-j' option (e.g. jlevel='16' →
'-j16'). Setting to null (the default) will autodetect the number of cores
available on your system and use that.

.. _config-rootfs-size:

``rootfs-margin``
^^^^^^^^^^^^^^^^^^^^^^^^^
By default, FireMarshal shrinks the rootfs of each workload to contain only
``rootfs-margin`` free space. Workloads can explicitly override this size
requirement with the :ref:`workload-rootfs-size` option). Increasing this can
drastically increase host-machine disk requirements as every image generated
will be larger.

``doitOpts``
^^^^^^^^^^^^^^^^^^^^^^^^^
FireMarshal uses a python library called `doit
<https://pydoit.org/contents.html>`_ to track build dependencies and avoid
unnecessary recompilation. You may pass additional options to this library as a
dictionary here. To see a description of these options, consult the doit help
output:

   ``$ doit help run``

``verbosity``
"""""""""""""""""
This is equivalent to the '-v' option when calling doit from the command line.
Note that FireMarshal performs much of its own logging that supersedes this
option. You should not typically change this unless you have a good reason.
Briefly:

   * 0 capture (do not print) stdout/stderr from task.
   * 1 capture stdout only.
   * 2 do not capture anything (print everything immediately).

``dep_file``
""""""""""""""""
Doit requires a database to track dependencies and build artifacts. Without
this database, it will conservatively rebuild all tasks. For most use-cases,
you can leave this option as '' (the empty string) to default to a centralized
database for all invocations of marshal. Since FireMarshal uses absolute paths
to identify most tasks, this should be safe. However, you may use a different
location for this database if needed by setting this option to a path (the path
will be taken as relative to wherever the marshal command was invoked).
