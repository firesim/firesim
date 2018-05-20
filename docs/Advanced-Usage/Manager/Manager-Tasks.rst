Manager Tasks
========================

This page outlines all of the tasks that the FireSim manager supports.

.. _firesim-managerinit:

``firesim managerinit``
----------------------------

This is a setup command that does the following:

* Run ``aws configure``, prompt for credentials
* Replace the default config files (``config_runtime.ini``, ``config_build.ini``, ``config_build_recipes.ini``, and ``config_hwdb.ini``) with clean example versions.
* Prompt the user for email address and subscribe them to notifications for their own builds.

You can re-run this whenever you want to get clean configuration files -- you
can just hit enter when prompted for aws configure credentials and your email
address, and both will keep your previously specified values.

If you run this command by accident and didn't mean to overwrite your
configuration files, you'll find backed-up versions in
``firesim/deploy/sample-backup-configs/backup*``.


.. _firesim-buildafi:

``firesim buildafi``
----------------------


.. _firesim-launchrunfarm:

``firesim launchrunfarm``
---------------------------




.. _firesim-infrasetup:

``firesim infrasetup``
-------------------------


.. _firesim-boot:

``firesim boot``
-------------------


.. _firesim-kill:

``firesim kill``
-------------------




.. _firesim-terminaterunfarm:

``firesim terminaterunfarm``
-----------------------------



.. _firesim-runworkload:

``firesim runworkload``
--------------------------


.. _firesim-shareagfi:

``firesim shareagfi``
----------------------


.. _firesim-runcheck:

``firesim runcheck``
----------------------



