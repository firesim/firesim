Parallel Jobs
======================
``test/jobs.yaml``

FireMarshal is capable of running multiple jobs in parallel. To understand how this feature works, let's use the ``jobs.yaml`` workload as an example.

.. include:: ../../../test/jobs.yaml
  :code: json

Based on ``br-base.json``, this simple workload defines a root workload and two jobs - ``j0`` and ``j1``. Each of these is set up to run an ``echo`` command to identify whether they have been run. The testing directory searches for these strings in the stripped output to the terminal.

Note that within a single invocation of the ``marshal`` command, the names of the root workloads must be unique. Similarly, within a single workload, jobs must have unique names.

We can build and launch this workload:

::

  ./marshal build test/jobs.yaml
  ./marshal launch test/jobs.yaml

FireMarshal uses separate screen sessions to run workload in parallel. One can attach to a workload to interact with or observe it using standard screen syntax and the screen session identifiers listed in the output of the ``launch`` command. 

For instance, to attach to job ``j0``, use:

::

  screen -r jobs-j0

It also logs ``stdout`` and ``stderr`` to a ``uartlog`` file for each workload. These files can be found inside the corresponding workload output directory inside ``runOutputs``. The path to this directory will be output by the ``launch`` command. For instance, a workload output directory for the launched ``jobs`` workload would be called ``jobs-launch-YYYY-MM-DD--HH-MM-SS-<HASH>``.  

.. todo:: Add (or link to) more detailed examples of bare-metal and rocc workloads.
