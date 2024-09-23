Using FireSim without Chipyard
==============================

FireSim is now standalone allowing (1) FireSim developers to test the repository without
Chipyard and (2) allowing non-Chipyard top-level projects to integrate FireSim as a
library. We will discuss option (2) in this section.

A non-Chipyard top-level serves as the target which FireSim will simulate. It
must provide a few items:

- A Chisel top-level "harness" to connect FireSim bridges to drive things like the clock
  and reset.
- A C++ top-level simulation driver to indicate how to progress in the simulation.
- A series of Make fragments to configure the FireSim build system (otherwise called MIDAS or Golden Gate).

We name this set of sources a "project".

Simple Counter Example Project
------------------------------

By default, FireSim provides a simple example on how to add your own RTL can create a simulator with
just a clock and reset. This serves as the starting point for users to add future bridges or more
complicated designs. Throughout this section you will see the name ``examples`` at the end of paths, this
is the FireSim "project" that we are using.

Top-Level Harness
~~~~~~~~~~~~~~~~~

:gh-file-ref:`sim/src/main/scala/examples/SimpleCounter.scala` holds the simple top-level harness which
wraps around a simple counter that increments to 1000.

Looking at the counter module, it outputs a ``done`` signal when the counter reaches 1000.

.. literalinclude:: ../../sim/src/main/scala/examples/SimpleCounter.scala
   :language: scala
   :start-after: DOC include start: Counter
   :end-before: DOC include end: Counter

To simulate this module, we need to wrap it in test harness that will source/sink it's IOs and will also
drive the reset and clock of the module. This is shown below:

.. literalinclude:: ../../sim/src/main/scala/examples/SimpleCounter.scala
   :language: scala
   :start-after: DOC include start: TestHarness
   :end-before: DOC include end: TestHarness

First, we create a top-level ``clock`` and ``reset`` wire that is used for the simple counter module.
This is shown here:

.. literalinclude:: ../../sim/src/main/scala/examples/SimpleCounter.scala
   :language: scala
   :start-after: DOC include start: ClockResetWire
   :end-before: DOC include end: ClockResetWire

Next, we connect those ``clock`` and ``reset`` wires to two corresponding bridges that can drive the ``clock`` and ``reset`` for the simulation.
In this case, we use the ``RationalClockBridge`` and the ``ResetPulseBridge`` which run the simulation on one clock domain and reset the simulation.
In more complex cases, these bridges can be used to drive multi-clock simulations or drive a reset pulse for a longer period of time.
This is shown here:

.. literalinclude:: ../../sim/src/main/scala/examples/SimpleCounter.scala
   :language: scala
   :start-after: DOC include start: Bridges
   :end-before: DOC include end: Bridges

Finally, we need to instantiate our simple counter and wire up it's IOs.
This is done here:

.. literalinclude:: ../../sim/src/main/scala/examples/SimpleCounter.scala
   :language: scala
   :start-after: DOC include start: CL
   :end-before: DOC include end: CL

Since we are creating logic within a Chisel ``RawModule`` we need to indicate that the
``SimpleCounter`` and registers we are using have a default ``clock`` and ``reset``.
This is done with the ``withClockAndReset`` scope.
Also note that this RTL just prints, we will use the C++ top-level to terminate the
simulation by timing out in the next section.

C++ Driver Top
~~~~~~~~~~~~~~

:gh-file-ref:`sim/src/main/cc/examples/simple_counter_top.cc` defines the C++ top-level simulation driver called ``simple_counter_top_t`` for the simulation.
It is in charge of adding any extra widgets/bridges, determining how to step the simulation, and terminating.
Most of this file is boilerplate code (i.e. code that can be copied from the example), but two sections are highlighted here.

First, we need to define a core simulation loop. This loop is in charge of managing the simulation and indicating when bridges
need to run their logic. This is shown here:

.. literalinclude:: ../../sim/src/main/cc/examples/simple_counter_top.cc
   :language: scala
   :start-after: DOC include start: Loop
   :end-before: DOC include end: Loop

You can see things like ``peek_poke.step`` being called to "step" forward in the simulation, ``bridge->tick()`` to run bridge logic and more.
This loop is terminated after N cycles which is given adding ``+max-cycles=N`` to the simulator binary (this is defined in the ``systematic_scheduler_t`` class).

Second, we need to register the ``simple_counter_top_t`` class as the main simulation driver class in the default FireSim main function.
This is done here:

.. literalinclude:: ../../sim/src/main/cc/examples/simple_counter_top.cc
   :language: scala
   :start-after: DOC include start: RegisterWithMain
   :end-before: DOC include end: RegisterWithMain

This code simply creates a unique pointer to the simulation class you want (in this case ``simple_counter_top_t``) in a function that is called in FireSim's main function.

Make fragments
~~~~~~~~~~~~~~

Next, you need to provide a series of Make fragments to configure the FireSim build
system to generate the RTL to run with Golden Gate. This is located in
:gh-file-ref:`sim/src/main/makefrag/examples`.

This area consists of four Make fragments that indicate how to build/run/configure the project:

* :gh-file-ref:`sim/src/main/makefrag/examples/build.mk` - Target-RTL generation
* :gh-file-ref:`sim/src/main/makefrag/examples/config.mk` - Variable defaults
* :gh-file-ref:`sim/src/main/makefrag/examples/driver.mk` - MIDAS/Golden-Gate sources
* :gh-file-ref:`sim/src/main/makefrag/examples/metasim.mk` - Top-level meta-simulator targets

Starting with the :gh-file-ref:`sim/src/main/makefrag/examples/build.mk`, this file specifies a rule to build the ``FIRRTL_FILE``
and ``ANNO_FILE`` files needed for downstream FireSim Make rules. This FIRRTL file needs
to be a Chisel 3.6 (i.e. Scala FIRRTL Compiler) compatible FIRRTL file.
In this case, we reuse the Chisel generator binary (i.e. ``midas.chiselstage.Generator``) for RTL generation since all the Scala sources
are defined in the FireSim top-level :gh-file-ref:`sim/build.sbt`

Next is :gh-file-ref:`sim/src/main/makefrag/examples/config.mk`. This file provides capitalized variables used throughout the
FireSim Make system. This is set to sensible defaults but each of these variables can be overridden on the make command
line (i.e. ``make TARGET_CONFIG=... ...``).
In this case, we point to our simulation top-level module ``SimpleCounterHarness``.

Next is :gh-file-ref:`sim/src/main/makefrag/examples/driver.mk` . This file provides the ``DRIVER_H``, ``DRIVER_CC``,
``TARGET_CXX_FLAGS``, and ``TARGET_LD_FLAGS`` needed for the FireSim Make system to
build a C++ driver for the simulation.
In this case, we point to our ``simple_counter_top.cc`` file that we created earlier.

Finally, the :gh-file-ref:`sim/src/main/makefrag/examples/metasim.mk` file provides Make targets for running metasimulations
through the FireSim MakeFile. You can use this to add targets to run your target with
any simulator, or whatever else.
In this case, we define simulation targets for Verilator and VCS that indicate to the simulation that it should timeout after 10000 cycles.

Running meta-simulations and more
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Once these Make fragments are added, you can then run the FireSim MakeFile or build
FireSim recipes by invoking the Make system with ``TARGET_PROJECT_MAKEFRAG`` pointing to
the Make fragments (i.e. ``make TARGET_PROJECT_MAKEFRAG=<PATH/TO/MAKEFRAG/FOLDER> ...``).
In this case, since the files reside within FireSim, the ``TARGET_PROJECT_MAKEFRAG`` will be properly set to
``sim/src/main/makefrag/<TARGET_PROJECT>`` so we just need to define the ``TARGET_PROJECT`` to be ``examples``.

The following code runs a metasimulation with VCS for our example RTL test harness, C++ code, and make fragments.

.. code-block:: bash

   cd $FS_DIR
   source sourceme-manager.sh --skip-ssh-setup
   make TARGET_PROJECT=examples run-vcs

Chipyard Example
----------------

For the remainder of this section we will use Chipyard as an example of how to integrate
FireSim into a top-level project. In the future, we will provide a simplified example
non-Chipyard top-level setup that users can reference.

Top-Level Harness
~~~~~~~~~~~~~~~~~

An example of a FireSim top-level harness is in
:cy-gh-file-ref:`generators/firechip/chip/src/main/scala/FireSim.scala`. While there are
alot of extra Chipyard specific features, the main focus should be on adding a
``ResetPulseBridge`` to drive the top-level reset of the system, and adding a
``RationalClockBridge`` to drive system clocks. Then the harness can choose to
instantiate any other target-specific bridges (i.e. the FASED DRAM model or a UART
bridge for example).

C++ Driver Top
~~~~~~~~~~~~~~

Next, you need to provide a top-level C++ driver such as
:cy-gh-file-ref:`generators/firechip/chip/src/main/cc/firesim/firesim_top.cc`. This
indicates how the bridges should be run, and when.

Make fragments
~~~~~~~~~~~~~~

Next, you need to provide a series of Make fragments to configure the FireSim build
system to generate the RTL to run with Golden Gate. Chipyard's example is here:
:cy-gh-file-ref:`generators/firechip/chip/src/main/makefrag/firesim`.

Starting with the ``build.mk``, this file specifies a rule to build the ``FIRRTL_FILE``
and ``ANNO_FILE`` files needed for downstream FireSim Make rules. This FIRRTL file needs
to be a Chisel 3.6 (i.e. Scala FIRRTL Compiler) compatible FIRRTL file. In Chipyard's
case, the Chipyard MakeFile is invoked always (due to the dependency on a ``.PHONY``
target) to create the two files. However, Chipyard's MakeFile doesn't update the files
if nothing has changed preventing downstream FireSim Make rules from re-running.
Additionally, the file has a variable ``TARGET_COPY_TO_MIDAS_SCALA_DIRS`` this allows
the ``firesim_target_symlink_hook`` target to symlink target-specific bridge directories
into MIDAS to compile (in this case, ``bridgeinterfaces`` and
``golgengateimplementations``). If you are using the default bridges (i.e.
``ResetPulseBridge`` and ``RationalClockBridge``) then this variable and target
shouldn't be needed.

Next is ``config.mk``. This file provides capitalized variables used throughout the
FireSim Make system. This also provides Make variables that can be visible from the
other ``*.mk`` files in this directory (like ``chipyard_dir``).

Next is ``driver.mk``. This file provides the ``DRIVER_H``, ``DRIVER_CC``,
``TARGET_CXX_FLAGS``, and ``TARGET_LD_FLAGS`` needed for the FireSim Make system to
build a C++ driver for the simulation. When using default bridges, you should just need
to add your ``firesim_top.cc`` file to the ``DRIVER_CC``. Additionally, you might need
to add to ``TARGET_CXX_FLAGS`` an include path to the generated directory (i.e.
``-I$(GENERATED_DIR)``).

Finally, the ``metasim.mk`` file provides Make targets for running metasimulations
through the FireSim MakeFile. You can use this to add targets to run your target with
any simulator, or whatever else.

Once these Make fragments are added, you can then run the FireSim MakeFile or build
FireSim recipes by invoking the Make system with ``TARGET_PROJECT_MAKEFRAG`` pointing to
the Make fragments (like
:cy-gh-file-ref:`generators/firechip/chip/src/main/makefrag/firesim`).
