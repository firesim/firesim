A Walkthrough
===============

Defining the Target
-------------------

A target in MIDAS is modelled as a graph of three types of models:
#. Transformed. Models transformed from tapeout-ready ASIC RTL (Chisel).
#. Custom. Handwritten FPGA-hosted models not intended for tapeout.
#. Software. Handwritten CPU-hosted models.

These three models are linked together during MIDAS compilation to produce
a complete closed-world simulation environment.

Host pre-requisties
-------------------

Generated MIDAS-simulators require both a host-CPU and host-FPGA, though the CPU host
could be implemented as a soft-core. A host-platform project consists of:

#. A skeleton FPGA-project which exposes standard interfaces to DRAM and
   transport to the host-CPU, and provides the tooling to compile a bitstream.
#. A C++ transport driver, which implements the communication primitives required
   by MIDAS services and to move tokens between software and FPGA-hosted models.

This will be covered in greater detail later.

Building the FPGA-hosted component
----------------------------------

.. MIDAS Compiler
.. ##############
.. 
.. First of all, we assume the target design is written in Chisel. The RTL that describes the FPGA-accelerated simulator is generated from target RTL (an elaborated chisel module) and a configuration that are passed to `midas.MidasCompiler`:
.. ```scala
.. // mod: Module (target design)
.. // dir: File (target directory)
.. // p: config.Parameters (midas configuration)
.. midas.MidasCompiler(mod, dir)(p)
.. 
.. For example, the compiler is invoked in [midas-example](https://github.com/ucb-bar/midas-examples/blob/master/src/main/scala/Main.scala) and [midas-top](https://github.com/ucb-bar/midas-top-release/blob/master/src/main/scala/Generator.scala).
.. 
.. Parameterizations
.. #################
.. 
.. The MIDAS configuration is responsible for describing what models the Compiler should bind to the I/O of your target RTL, and for enabling simulation services that require in-FPGA support (like target-state snapshotting).  
.. 
.. The default MIDAS parameters are given in [src/main/scala/midas/Config.scala](https://github.com/ucb-bar/midas/blob/readme/src/main/scala/midas/Config.scala). To generate the RTL for a Xilinx Zynq FPGA-host, pass the `ZynqConfig` to the Compiler. To also enable Strober energy modelling, pass the `ZynqConfigWithSnapshot` to the Compiler.
.. 
.. MIDAS uses the same parameterization library as Rocket Chip, and thus MIDAS configurations can be defined and changed in the manner. For example, to include a simple last-level cache (LLC) model, override the default configuration like so:
.. ```scala
.. class WithMidasLLC(extends Config((site, here, up) => {
..   case MidasLLCKey => Some(MidasLLCParameters(nWays = 8, nSets = 4096, blockBytes = 128)) // capacity <= 4MiB
.. })
.. 
.. class ZynqConfigWithLLC(new ZynqConfig ++ new WithMidasLLC)
.. ```
.. 
.. Building the CPU-hosted component
.. ---------------------------------
.. 
.. In MIDAS, a CPU-hosted *driver* controls the execution of the simulator. The driver is written in C++ by the user. The simplest form of driver uses `peek`, `poke`, `step`, `expect` functions as in [Chisel testers](https://github.com/freechipsproject/chisel-testers.git). The first step is define a virtual class that inherets from `simif_t`. This class describes the execution of the simulator and is agnostic of the host platform. Next, this class is concretized for each host platform you wish to use, usually twice: once for your FPGA-host platform, and again, for a CPU-only host-platform in which the FPGA-hosted components are simulated using an RTL simulator like Verilator or Synopsys VCS. As an example, here is a software driver for GCD (e.g. in `GCD.h`):
.. ```c++
.. #include "simif.h"
.. 
.. class GCD_t: virtual simif_t
.. {
.. public:
..   void run() {
..     uint32_t a = 64, b = 48, z = 16; //test vectors
..     target_reset();
..     do {
..       poke(io_a, a);
..       poke(io_b, b);
..       poke(io_e, cycles() == 0 ? 1 : 0);
..       step(1);
..     } while (cycles() <= 1 || peek(io_v) == 0);
..     expect(io_z, z);
..   }
.. };
.. ```
.. 
.. The concretized class for CPU-only simulation (from in `GCD-emul.cc`). We refer to this as an emulator (as it emulates the FPGA-accelerated simulator).
.. 
.. ```c++
.. #include "simif_emul.h"
.. #include "GCD.h"
.. 
.. class GCD_emul_t:
..   public simif_emul_t,
..   public GCD_t { };
.. 
.. int main(int argc, char** argv)
.. {
..   GCD_emul_t GCD;
..   GCD.init(argc, argv, true);
..   GCD.run();
..   return GCD.finish();
.. }
.. ```
.. 
.. The concretized class for an Xilinx Zynq host platform (from `GCD-zynq.cc`).
.. 
.. ```c++
.. #include "simif_zynq.h"
.. #include "GCD.h"
.. 
.. class GCD_zynq_t:
..   public simif_zynq_t,
..   public GCD_t { };
.. 
.. int main(int argc, char** argv) 
.. {
..   GCD_zynq_t GCD;
..   GCD.init(argc, argv, true);
..   GCD.run();
..   return GCD.finish();
.. }
