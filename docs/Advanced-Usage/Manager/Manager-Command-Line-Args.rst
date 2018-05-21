Manager Command Line Arguments
===================================

The manager provides built-in help output for the command line arguments it
supports if you run ``firesim --help``

.. include:: HELP_OUTPUT
   :code: ini

On this page, we will go through some of these options -- others are more
complicated, so we will give them their own section on the following pages.

``--runtimeconfigfile`` ``FILENAME``
-----------------------------------------

This lets you specify a custom runtime config file. By default, ``config_runtime.ini``
is used. See :ref:`config-runtime` for what this config file does.


``--buildconfigfile`` ``FILENAME``
------------------------------------------

This lets you specify a custom build config file. By default, ``config_build.ini``
is used. See :ref:`config-build` for what this config file does.


``--buildrecipesconfigfile`` ``FILENAME``
---------------------------------------------------

This lets you specify a custom build **recipes** config file. By default,
``config_build_recipes.ini`` is used. See :ref:`config-build-recipes` for what
this config file does.


``--hwdbconfigfile`` ``FILENAME``
--------------------------------------------

This lets you specify a custom hardware database config file. By default,
``config_hwdb.ini`` is used. See :ref:`config-hwdb` for what this config file does.


``--overrideconfigdata`` ``SECTION`` ``PARAMETER`` ``VALUE``
------------------------------------------------------------------

This lets you override a single value from the runtime config file. For
example, if you want to use a link latency of 3003 cycles for a particular run
(and your ``config_runtime.ini`` file specifies differently), you can pass
``--overrideconfigdata targetconfig linklatency 6405`` to the manager. This
can be used with any task that uses the runtime config.


``TASK``
-------------

This is the only required/positional command line argument to the manager. It
tells the manager what it should be doing. See the next section for a list of
tasks and what they do. Some tasks also take other command line arguments,
which are specified with those tasks.

