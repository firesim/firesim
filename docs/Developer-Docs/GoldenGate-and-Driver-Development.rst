Compiler & Driver Development
=======================================================

.. _Scala Integration Tests:

Integration Tests
+++++++++++++++++

These are ``ScalaTests`` that call out to FireSim's Makefiles. These
constitute the bulk of FireSim's tests for Target, Compiler, and Driver side
features. Each of these tests proceeds as follows:

#. Elaborate a small Chisel target design that exercises a single feature (e.g., printf synthesis)
#. Compile the design with GoldenGate
#. Compile metasimulator using a target-specific driver and the Golden Gate-generated collateral
#. Run metasimulation with provided arguments (possibly multiple times)
#. Post-process metasimulation outputs in Scala

Single tests may be run directly out of :gh-file-ref:`sim/` as follows::

   # Run all Chipyard-based tests (uses Rocket + BOOM)
   make test

   # Run all integration tests (very long running, not recommended)
   make test TARGET_PROJECT=midasexamples

   # Run a specific integration test (desired)
   make testOnly TARGET_PROJECT=midasexamples SCALA_TEST=firesim.midasexamples.GCDF1Test

These tests may be run from the SBT console continuously, and SBT will rerun
them on Scala changes (but not driver changes). Out of :gh-file-ref:`sim/`::

   # Launch the SBT console into the firesim subproject
   # NB: omitting TARGET_PROJECT will put you in the FireChip subproject instead
   make TARGET_PROJECT=midasexamples sbt

   # Compile the Scala test sources (optional, to enable tab completion)
   sbt:firesim> Test / compile

   # Run a specific test once
   sbt:firesim> testOnly firesim.midasexamples.GCDF1Test

   # Continuously rerun the test on Scala changes
   sbt:firesim> ~testOnly firesim.midasexamples.GCDF1Test


Key Files & Locations
---------------------
- :gh-file-ref:`sim/firesim-lib/src/test/scala/TestSuiteCommon.scala`
   Base ScalaTest class for all tests that use FireSim's make build system
- :gh-file-ref:`sim/src/test/scala/midasexamples/TutorialSuite.scala`
   Extension of TestSuiteCommon for most integration tests + concrete subclasses
- :gh-file-ref:`sim/src/main/cc/midasexamples/`
   C++ sources for target-specific drivers
- :gh-file-ref:`sim/src/main/cc/midasexamples/TestHarness.h`
   A common driver to extend for simple tests
- :gh-file-ref:`sim/src/main/scala/midasexamples/`
   Where top-level Chisel modules (targets) are defined

Defining a New Test
--------------------

#. Define a new target module (if applicable) under ``sim/src/main/scala/midasexamples``.
#. Define a driver by extending ``simif_t`` or another child class under ``src/main/cc/midasexamples``. Tests
   sequenced with the Peek Poke bridge may extend ``simif_peek_poke_t``.

#. Create a test in ``src/main/cc/midasexamples``. Register bridges and add override the ``run`` method.

#. Define a ScalaTest class for your design by extending ``TutorialSuite``. Parameters will
   define define the tuple (``DESIGN``, ``TARGET_CONFIG``, ``PLATFORM_CONFIG``), and call
   out additional plusArgs to pass to the metasimulator.  See the ScalaDoc for
   more info. Post-processing of metasimulator outputs (e.g., checking output file contents) can be implemented in
   the body of your test class.


Synthesizable Unit Tests
++++++++++++++++++++++++

These are derived from Rocket-Chip's synthesizable unit test library and are
used to test smaller, stand-alone Chisel modules.

Synthesizable unit tests may be run out of :gh-file-ref:`sim/` as follows::

   # Run default tests without waves
   $ make run-midas-unittests

   # Run default suite with waves
   $ make run-midas-unittests-debug

   # Run default suite under Verilator
   $ make run-midas-unittests  EMUL=verilator

   # Run a different suite (registered under class name TimeOutCheck)
   $ make run-midas-unittests  CONFIG=TimeOutCheck

Setting the make variable ``CONFIG`` to different scala class names will select
between different sets of unittests.  All synthesizable unittests registered
under ``WithAllUnitTests`` class are run from ScalaTest and in CI.

Key Files & Locations
---------------------

- :gh-file-ref:`sim/midas/src/main/scala/midas/SynthUnitTests.scala`
   Synthesizable unit test modules are registered here.
- :gh-file-ref:`sim/midas/src/main/cc/unittest/Makefrag`
   Make recipes for building and running the tests.
- :gh-file-ref:`sim/firesim-lib/src/test/scala/TestSuiteCommon.scala`
   ScalaTest wrappers for running synthesizable unittests

Defining a New Test
--------------------
#. Define a new Chisel module that extends ``freechips.rocketchip.unittest.UnitTest``
#. Register your modules in a ``Config`` using the ``UnitTests`` key. See ``SynthUnitTests.scala`` for examples.

Scala Unit Testing
++++++++++++++++++

We also use ScalaTest to test individual transforms, classes, and target-side Chisel
features (in ``targetutils`` package). These can be found in
``<subproject>/src/test/scala`` as is customary of Scala projects.  ScalaTests in ``targetUtils``
generally ensure that target-side annotators behave correctly when deployed in a
generator (they elaborate correctly or they give the desired error message.)
ScalaTests in ``midas`` are mostly tailored to testing FIRRTL transforms, and
have copied FIRRTL testing utilities into the source tree to make that process easier.

``targetUtils`` scala tests can be run out of :gh-file-ref:`sim/` as follows::

   # Pull open the SBT console in the firesim subproject
   $ make TARGET_PROJECT=midasexamples sbt

   # Switch to the targetutils package
   sbt:firesim> project targetutils

   # Run all scala tests under the ``targetutils`` subproject
   sbt:midas-targetutils> test

Golden Gate (formerly midas) scala tests can be run by setting the scala project
to ``midas``, as in step 2 above.

Key Files & Locations
---------------------

- :gh-file-ref:`sim/midas/src/test/scala/midas`
   Location of GoldenGate ScalaTests
- :gh-file-ref:`sim/midas/targetutils/src/test/scala`
   Location of targetutils ScalaTests

Defining A New Test
---------------------

Extend the appropriate ScalaTest spec or base class, and
place the file under the correct ``src/test/scala`` directory. They will be
automatically enumerated by ScalaTest and will run in CI by default.

C/C++ guidelines
++++++++++++++++

The C++ sources are formatted using ``clang-format`` and all submitted pull-requests
must be formatted prior to being accepted and merged. The sources follow the coding
style defined `here <https://github.com/firesim/firesim/blob/main/.clang-format>`_.
Additionally, ``clang-tidy`` is also run on CI to lint and validate C++ sources.
The tool follows the guidelines and configuration of LLVM.

``git clang-format`` can be used before committing to ensure that files are properly formatted.
``make -C sim clang-tidy`` can be used to run ``clang-tidy``. `make -C sim clang-tidy-fix`
automatically applies most fixes, but some errors and warnings require user intervention.

Scala guidelines
++++++++++++++++

The Scala sources are formatted using both ``Scalafmt`` and ``Scalafix``. All submitted
pull-requests must be formatted prior to being accepted and merged. The configuration files
are found here: `Scalafmt config <https://github.com/firesim/firesim/blob/main/sim/.scalafix.conf>`_, 
`Scalafix config <https://github.com/firesim/firesim/blob/main/sim/.scalafmt.conf>`_. Run 
``make -C sim scala-lint-check`` to check your code for compliance. Run ``make -C sim scala-lint`` to
automatically apply fixes.