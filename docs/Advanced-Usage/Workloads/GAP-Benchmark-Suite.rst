.. _gap-benchmark-suite:

GAP Benchmark Suite
---------------------
You can run the reference implementation of the GAP (Graph Algorithm Performance)
Benchmark Suite. We provide scripts that cross-compile the graph kernels for RISCV.

For more information about the benchmark itself, please refer to the site:
http://gap.cs.berkeley.edu/benchmark.html

Some notes:

-  Only the Kron input graph is currently supported.
-  Benchmark uses ``graph500`` input graph size of 2^20 vertices by default. ``test`` input size has 2^10 vertices and can be used by specifying an argument into make: ``make gapbs input=test``
-  The reference input size with 2^27 verticies is not currently supported.

By default, the gapbs workload definition runs the benchmark multithreaded with number of threads equal to the number of cores. To change the number of threads, you need to edit ``firesim/deploy/workloads/runscripts/gapbs-scripts/gapbs.sh``. Additionally, the workload does not verify the output of the benchmark by default. To change this, add a ``--verify`` parameter to the json.

To Build Binaries and RootFSes:

.. code-block:: bash

    cd firesim/deploy/workloads/
    make gapbs

Run Resource Requirements:

.. include:: /../deploy/workloads/gapbs.ini
   :start-line: 3
   :end-line: 6
   :code: ini


To Run:

.. code-block:: bash

    ./run-workload.sh workloads/gapbs.ini --withlaunch

Simulation times are host and target dependent. For reference, on a
four-core rocket-based SoC with a DDR3 + 1 MiB LLC model, with a 90
MHz host clock, ``test`` and ``graph500`` input sizes finish in a few minutes.
