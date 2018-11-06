Assertion Synthesis
===================

MIDAS can synthesize assertions present in FIRRTL (implemented as ``stop``
statements) that would otherwise be lost in the FPGA synthesis flow. Rocket
and BOOM include hundreds of such assertions which, when synthesized, can
provide great insight into why the target may be failing.

Enabling Assertion Synthesis
----------------------------

To enable assertion synthesis add the ``WithSynthAsserts`` Config to your
PLATFORM_CONFIG in SimConfigs.scala.  During compilation, MIDAS will print the
number of assertions it's synthesized.  In the target's ``generated-src/``
directory, you'll find a ``*.asserts`` file with the definitions of all
synthesized assertions.  If assertion synthesis has been enabled, the
``synthesized_assertions_t`` endpoint driver will be automatically instantiated
the driver.


Runtime Behavior
----------------

If an assertion is caught during simulation, the driver will print the
assertion cause, the path to module instance in which it fired, a source
locator, and the cycle on which the assertion fired. Simulation will then
terminate.

An example of an assertion caught in a dual-core instance of BOOM is given
below:

::

    id: 1190, module: IssueSlot_4, path: FireBoomNoNIC.tile_1.core.issue_units_0.slots_3]
    Assertion failed
        at issue_slot.scala:214 assert (!slot_p1_poisoned)
        at cycle: 2142042185

Related Publications
--------------------

Assertion synthesis was first presented in our FPL2018 paper, `DESSERT
<https://people.eecs.berkeley.edu/~biancolin/papers/dessert-fpl18.pdf>`_.
