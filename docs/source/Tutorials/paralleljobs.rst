.. _tutorial-paralleljobs:

Parallel Jobs
======================
``example-workloads/parallelJobs.yaml``

FireMarshal is capable of running multiple jobs in parallel. To understand how this feature works, let's use the ``parallelJobs.yaml`` workload as an example.

.. include:: ../../../example-workloads/parallelJobs.yaml
  :code: json

Based on ``br-base.json``, this simple workload defines a root workload (implicitly) and two jobs - ``j0`` and ``j1``.

Note that within a single invocation of the ``marshal`` command, the names of the root workloads must be unique. Similarly, within a single workload, jobs must have unique names.

We can build this workload and launch its root as follows:

::

  ./marshal build example-workloads/parallelJobs.yaml
  ./marshal launch example-workloads/parallelJobs.yaml

FireMarshal uses screen sessions to run workloads. Notice how the root workload, running buildroot emulated using QEMU, launches directly in your terminal. When a single workload is launched, FireMarshal attaches to its screen session by default. 

The default login details for all workloads are:

``buildroot login:`` ``root``

``Password:`` ``firesim``

Once logged in, you can run commands in the terminal. To exit, use:

::

  poweroff 

We can launch a single job ``j0`` as follows:

::

  ./marshal launch --job j0 example-workloads/parallelJobs.yaml


The same login details and exit procedure apply.

Note that both the root and the job workloads can't be launched at once. 

Let us now launch both jobs in this workload using:

::

  ./marshal launch --all example-workloads/parallelJobs.yaml

FireMarshal uses separate screen sessions to run workloads in parallel. It exits only after all launched workloads have exited. 

One can attach to a workload to interact with or observe it by opening a new terminal (with tmux for instance), using standard screen syntax, and the screen session identifiers listed in the output of the ``launch`` command. 

For instance, attach to job ``j0``, using:

::

  screen -r parallelJobs-j0


To detach from inside the screen session, use ``ctrl-a`` then ``ctrl-d``.


Similarly, attach to job ``j1``, using:

::

  screen -r parallelJobs-j1


Login (with the details provided above) and then use the ``poweroff`` command to shut job ``j1`` down.


Now re-attach to job ``j0``, using:

::

  screen -r parallelJobs-j0


Login (with the details provided above) and then use the ``poweroff`` command to shut job ``j0`` down.

FireMarshal also logs ``stdout`` and ``stderr`` to a ``uartlog`` file for each workload. These files can be found inside the corresponding workload output directory inside ``runOutputs``. The path to this directory will be output by the ``launch`` command. For instance, a workload output directory for the launched ``parallelJobs`` workload would be called ``parallelJobs-launch-YYYY-MM-DD--HH-MM-SS-<HASH>``.  

