.. _manager-environment-variables:

Manager Environment Variables
===============================

This page contains a centralized reference for the environment variables used
by the manager.

.. _runfarm-prefix:

``FIRESIM_RUNFARM_PREFIX``
--------------------------

This environment variable is used to prefix all Run Farm tags with some prefix in the AWS EC2 case.
This is useful for separating run farms between multiple copies of FireSim.

This is set in ``sourceme-f1-manager.sh``, so you can change it and commit it
(e.g. if you're maintaining a branch for special runs). It can be unset or set
to the empty string.



