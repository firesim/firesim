.. _gap-benchmark-suite:

GAP Benchmark Suite
---------------------
You can run the reference implementation of the GAP (Graph Algorithm Performance) Benchmark Suite. We provide scripts that cross-compile the graph kernels for RISCV.

Some notes:

-  Benchmark uses ``ref`` inputs by default. ``test`` iniput size has 2^10 vertices and ``graph500`` input size has 2^20 vertices. These can be used by specifying an argument into make: ``make gapbs input=graph500``
-  The ``test`` and ``graph500`` inputs only include the synthetic input graphs (Kron and Urand). The ``ref`` input additionally includes 3 real-world graphs.
-  Since ``test/graph500`` and ``ref`` have different json and ini files, the Makefile copies them over each time you build the binaries. If you don't want your custom json or ini to be overwritten you can change this in the Makefile. 

For more information about the benchmark itself, please refer to the site: 
http://gap.cs.berkeley.edu/benchmark.html

By default, the gapbs workload definition runs the benchmark multithreaded with number of threads equal to the number of cores. To change the number of threads, you need to edit ``firesim/deploy/workloads/runscripts/gapbs-scripts/gapbs.sh``. Additionally, the workload does not verify the output of the benchmark by default. To change this, add a ``--verify`` parameter to the json.

To Build Binaries and RootFSes:

.. code-block:: bash

    cd firesim/deploy/workloads/
    make gapbs

``test/graph500`` Run Resource Requirements:

.. include:: /../deploy/workloads/runscripts/gapbs-scripts/non-ref-gapbs.ini
   :start-line: 3
   :end-line: 6
   :code: ini

``ref`` Run Resource Requirements:

.. include:: /../deploy/workloads/gapbs.ini
   :start-line: 3
   :end-line: 6
   :code: ini


To Run:

.. code-block:: bash

    firesim launchrunfarm    -c workloads/gapbs.ini
    firesim infrasetup       -c workloads/gapbs.ini
    firesim runworkload      -c workloads/gapbs.ini
    firesim terminaterunfarm -c workloads/gapbs.ini


Simulation times are host and target dependent. For reference, on a
four-core rocket-based SoC with a DDR3 + 1 MiB LLC model, with a 90
MHz host clock, ``test`` and ``graph500`` input sizes finish in a few minutes.
