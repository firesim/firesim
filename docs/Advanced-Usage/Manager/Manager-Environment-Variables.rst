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

.. _buildfarm-prefix:

``FIRESIM_BUILDFARM_PREFIX``
----------------------------

This environment variable is used to prefix all Build Farm tags with some prefix in the AWS EC2 case.
This is mainly for CI use only.
