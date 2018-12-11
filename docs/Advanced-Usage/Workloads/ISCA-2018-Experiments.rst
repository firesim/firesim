ISCA 2018 Experiments
================================

This page contains descriptions of the experiments in our `ISCA 2018 paper
<https://sagark.org/assets/pubs/firesim-isca2018.pdf>`__ and instructions for
reproducing them on your own simulations.

One important difference between the configuration used in the ISCA
2018 paper and the open-source release of FireSim is that the ISCA
paper used a proprietary L2 cache design that is not open-source.
Instead, the open-source FireSim uses an LLC model that models the
behavior of having an L2 cache as part of the memory model. Even with
the LLC model, you should be able to see the same trends in these
experiments, but exact numbers may vary.

Each section below describes the resources necessary to run the experiment.
Some of these experiments require a large number of instances -- you should
make sure you understand the resource requirements before you run one of the
scripts.

Compatiblity: These were last tested with commit
``bba9dea4811a2445f22809ef226cf00971674758`` of FireSim.


Prerequisites
-------------------------

These guides assume that you have previously followed the
single-node/cluster-scale experiment guides in the FireSim documentation. Note
that these are **advanced** experiments, not introductory tutorials.


Building Benchmark Binaries/Rootfses
--------------------------------------

We include scripts to automatically build all of the benchmark rootfs images
that will be used below. To build them, make sure you have already run
``./sw-manager.py -c br-disk.json build`` in ``firesim/sw/firesim-software``, then run:

.. code-block:: bash

    cd firesim/deploy/workloads/
    make allpaper


Figure 5: Ping Latency vs. Configured Link Latency
-----------------------------------------------------

Resource requirements:

.. include:: /../deploy/workloads/ping-latency-config.ini
   :start-line: 3
   :end-line: 6
   :code: ini


To Run:

.. code-block:: bash

    cd firesim/deploy/workloads/
    ./run-ping-latency.sh withlaunch



Figure 6: Network Bandwidth Saturation
-----------------------------------------------

Resource requirements:

.. include:: /../deploy/workloads/bw-test-config.ini
   :start-line: 3
   :end-line: 6
   :code: ini


To Run:

.. code-block:: bash

    cd firesim/deploy/workloads/
    ./run-bw-test.sh withlaunch


Figure 7: Memcached QoS / Thread Imbalance
-----------------------------------------------

Resource requirements:

.. include:: /../deploy/workloads/memcached-thread-imbalance-config.ini
   :start-line: 3
   :end-line: 6
   :code: ini

To Run:

.. code-block:: bash

    cd firesim/deploy/workloads/
    ./run-memcached-thread-imbalance.sh withlaunch


Figure 8: Simulation Rate vs. Scale
----------------------------------------

Resource requirements:

.. include:: /../deploy/workloads/simperf-test-scale-config.ini
   :start-line: 3
   :end-line: 6
   :code: ini


To Run:

.. code-block:: bash

    cd firesim/deploy/workloads/
    ./run-simperf-test-scale.sh withlaunch


Notes: Excludes supernode since it is still in beta and not merged on master.

Figure 9: Simulation Rate vs. Link Latency
---------------------------------------------

Resource requirements:

.. include:: /../deploy/workloads/simperf-test-latency-config.ini
   :start-line: 3
   :end-line: 6
   :code: ini


To Run:

.. code-block:: bash

    cd firesim/deploy/workloads/
    ./run-simperf-test-latency.sh withlaunch


Notes: Excludes supernode since it is still in beta and not merged on master.


Running all experiments at once
-----------------------------------

This script simply executes all of the above scripts in parallel. One caveat
is that the bw-test script currently cannot run in parallel with the others,
since it requires patching the switches. This will be resolved in a future
release.

.. code-block:: bash

    cd firesim/deploy/workloads/
    ./run-all.sh

