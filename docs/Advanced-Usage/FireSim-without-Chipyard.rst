Using FireSim without Chipyard
==============================

FireSim is now standalone allowing (1) FireSim developers to test the repository without
Chipyard and (2) allowing non-Chipyard top-level projects to integrate FireSim as a
library. We will discuss option (2) in this section.

A non-Chipyard top-level project serves as the target which FireSim will simulate. It
must provide a few items:

- A Chisel top-level "harness" to connect FireSim bridges to drive things like the clock
  and reset.
- A series of Make fragments to configure the FireSim build system.

For the remainder of this section we will use Chipyard as an example of how to integrate
FireSim into a top-level project. In the future, we will provide a simplified example
non-Chipyard top-level setup that users can reference.

Top-Level Harness
-----------------

An example of a FireSim top-level harness is in
:cy-gh-file-ref:`generators/firechip/chip/src/main/scala/FireSim.scala`. While there are
alot of extra Chipyard specific features, the main focus should be on adding a
``ResetPulseBridge`` to drive the top-level reset of the system, and adding a
``RationalClockBridge`` to drive system clocks. Then the harness can choose to
instantiate any other target-specific bridges (i.e. the FASED DRAM model or a UART
bridge for example).

C++ Driver Top
--------------

Next, you need to provide a top-level C++ driver such as
:cy-gh-file-ref:`generators/firechip/chip/src/main/cc/firesim/firesim_top.cc`. This
indicates how the bridges should be run, and when.

Make fragments
--------------

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
