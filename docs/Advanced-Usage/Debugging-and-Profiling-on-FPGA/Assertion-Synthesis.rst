Assertion Synthesis: Catching RTL Assertions on the FPGA
========================================================================

Golden Gate can synthesize assertions present in FIRRTL (implemented as ``stop``
statements) that would otherwise be lost in the FPGA synthesis flow. Rocket
and BOOM include hundreds of such assertions which, when synthesized, can
provide great insight into why the target may be failing.

Enabling Assertion Synthesis
----------------------------

To enable assertion synthesis prepend ``WithSynthAsserts`` config to your
PLATFORM_CONFIG.  During compilation, Golden Gate will print the number of
assertions it's synthesized.  In the generated header, you will find the
definitions of all synthesized assertions. The ``synthesized_assertions_t``
bridge driver will be automatically instantiated.


Runtime Behavior
----------------

If an assertion is caught during simulation, the driver will print the
assertion cause, the path to module instance in which it fired, a source
locator, and the cycle on which the assertion fired. Simulation will then
terminate.

An example of an assertion caught in a dual-core instance of BOOM is given
below:

::

    id: 1190, module: IssueSlot_4, path: FireSimNoNIC.tile_1.core.issue_units_0.slots_3]
    Assertion failed
        at issue_slot.scala:214 assert (!slot_p1_poisoned)
        at cycle: 2142042185



Just as in a software-hosted RTL simulation using verilator or VCS, the
reported cycle is the number of target cycles that have elapsed in the clock
domain in which the assertion was instantiated (in Chisel specifically this is
the implicit clock at the time you called ``assert``). If you rerun a FireSim
simulation with identical inputs, the same assertion should fire
deterministically at the same cycle.

Related Publications
--------------------

Assertion synthesis was first presented in our FPL2018 paper, `DESSERT
<https://people.eecs.berkeley.edu/~biancolin/papers/dessert-fpl18.pdf>`_.
