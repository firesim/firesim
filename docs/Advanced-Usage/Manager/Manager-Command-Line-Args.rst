Manager Command Line Arguments
===================================

The manager provides built-in help output for the command line arguments it
supports if you run ``firesim --help``

.. include:: HELP_OUTPUT
   :code: bash

On this page, we will go through some of these options -- others are more
complicated, so we will give them their own section on the following pages.

``--runtimeconfigfile`` ``FILENAME``
-----------------------------------------

This lets you specify a custom **runtime** config file. By default, ``config_runtime.yaml``
is used. See :ref:`config-runtime` for what this config file does.


``--buildconfigfile`` ``FILENAME``
------------------------------------------

This lets you specify a custom **build** config file. By default, ``config_build.yaml``
is used. See :ref:`config-build` for what this config file does.

``--buildrecipesconfigfile`` ``FILENAME``
---------------------------------------------------

This lets you specify a custom build **recipes** config file. By default,
``config_build_recipes.yaml`` is used. See :ref:`config-build-recipes` for what
this config file does.


``--hwdbconfigfile`` ``FILENAME``
--------------------------------------------

This lets you specify a custom **hardware database** config file. By default,
``config_hwdb.yaml`` is used. See :ref:`config-hwdb` for what this config file does.


``--overrideconfigdata`` ``SECTION`` ``PARAMETER`` ``VALUE``
------------------------------------------------------------------

This lets you override a single value from the **runtime** config file. For
example, if you want to use a link latency of 3003 cycles for a particular run
(and your ``config_runtime.yaml`` file specifies differently), you can pass
``--overrideconfigdata target_config link_latency 6405`` to the manager. This
can be used with any task that uses the runtime config.


``--launchtime`` ``TIMESTAMP``
---------------------------------------------------

Specifies the "Y-m-d--H-M-S" timestamp to be used as the prefix in
``results-build`` directories.  Useful when wanting to run ``tar2afi`` after an
aborted ``buildbitstream`` was manually fixed.


``TASK``
-------------

This is the only required/positional command line argument to the manager. It
tells the manager what it should be doing. See the next section for a list of
tasks and what they do. Some tasks also take other command line arguments,
which are specified with those tasks.

