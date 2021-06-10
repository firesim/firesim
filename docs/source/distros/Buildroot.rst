.. _buildroot-distro:

The Buildroot Distribution
=============================
Buildroot is a minimal Linux distribution intended for embedded systems (you
can find more information on their `website <https://buildroot.org/>`_. The
advantage of buildroot is that it requires little disk space and boots quickly.
It is also less complex which can make some workloads easier to build and
debug. However, Buildroot does not have a convential package management system,
instead you must configure any packages or options you need at compile time. To
do this, you will need to modify the :ref:`distro options <distro-configuration>` in
your workload.

Options
---------------------------

configs
^^^^^^^^^^^^^^^^^^^^^^^^^^
A list of buildroot configuration fragments. Buildroot uses the kconfig system
from Linux for configuration and the behavior of kernel fragments is the same
as in :ref:`Linux <linux-config>`.

Some Buildroot options require a path to external files (e.g. a custom busybox
configuration). You should not use relative paths for these as the buildroot
source directory may move in future versions of FireMarshal. Instead,
FireMarshal provides the absolute path to your workload directory via an
environment variable named ``$(WORKLOAD_NAME_PATH)`` where "WORKLOAD_NAME" is
the name of your workload with any '-' characters replaced with underscore.
This variable may be used in your buildroot configuration file. For example,
suppose we have a workload named ``foo-bar`` that wishes to provide a custom
busybox configuration (placed in foo-bar's workdir). It would include a
buildroot configuration fragment with the following option:

..

   BR2_PACKAGE_BUSYBOX_CONFIG=$(FOO_BAR_PATH)/myBusyBoxConfig

.. Note:: Because the custom busybox configuration is referenced only by the
   buildroot configuration, FireMarshal cannot detect changes to that file. You
   will have to explicitly clean and rebuild the workload to pick up changes to
   dependencies like that.

environment
^^^^^^^^^^^^^^^^^^^^^^^^^^
In addition to the provided ``$(WORKLOAD_NAME_PATH)`` variable, users may
provide aditional environment variables to customize their buildroot
configuration or build. These variables will be available for use in the
workload's buildroot configuration and will be passed when invoking ``make`` on
buildroot. Variables are provided as a dictionary of ``{ 'VARIABLE_NAME' :
'value' }``. You may include variable substitutions from the local environment
or the special ``$(WORKLOAD_NAME_PATH)`` variable. Continuing with the example
``foo-bar`` above, let's assume we've defined ``$ENV_VARIABLE`` before invoking
FireMarshal. We can then include the following in our workload:

..

   { "EXAMPLE_VAR" : "${FOO_BAR_PATH}/${ENV_VARIABLE}/baz" }
