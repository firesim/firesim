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
'-j16'). The empty string results in maximum parallelism.

.. _config-rootfs-size:

``rootfs-margin``
^^^^^^^^^^^^^^^^^^^^^^^^^
By default, FireMarshal shrinks the rootfs of each workload to contain only
``rootfs-margin`` free space. Workloads can explicitly override this size
requirement with the :ref:`workload-rootfs-size` option). Increasing this can
drastically increase host-machine disk requirements as every image generated
will be larger.
