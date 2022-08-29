# Golden Gate (MIDAS II)

Golden Gate is an _optimizing_ FIRRTL compiler for generating FPGA-accelerated simulators
automatically from Chisel-based RTL design, and is the basis for simulator
compilation in [FireSim](https://fires.im).

Golden Gate is the successor to MIDAS, which was originally based off the
[Strober](http://dl.acm.org/citation.cfm?id=3001151) sample-based energy
simulation framework. Golden Gate differs from prior work in that it is, to our knowledge, the first compiler
to support automatic _multi-model composition_: it can break apart a
block of RTL into a graph of models.  Golden Gate uses this feature
to identify and replace FPGA-hostile blocks with multi-host-cycle models that
consume fewer FPGA resources while still exactly representing the behavior of
the source RTL. In [our ICCAD 2019 paper](http://davidbiancolin.github.io/papers/goldengate-iccad19.pdf), we leverage this feature optimize
multi-ported RAMs in order to fit an extra two BOOM cores (6 up from 4) on a
Xilinx VU9P.

## Changes From MIDAS

Golden Gate inherits nearly all of the features of MIDAS, including, FASED memory timing models, assertion synthesis, and printf synthesis, but there are some notable changes:

### 1. Support for Resource Optimizations

As mentioned above, Golden Gate can identify and optimize FPGA-hostile
structures in the target RTL. This is described at length in [our ICCAD2019
paper](http://davidbiancolin.github.io/papers/goldengate-iccad19.pdf).
Currently Golden Gate only supports optimizing multi-ported memories,
but other resource-reducing optimizations are under development.

### 2. Different Inputs and Invocation Model (FIRRTL Stage).

Golden Gate is not invoked in the same process as the target generator.
instead it's invoked as a separate process and provided with three inputs:
1) FIRRTL for the target-design
2) Associated FIRRTL annotations for that design
3) A compiler parameterization (derived from Rocket Chip's Config system).
annotations. This permits decoupling the target Generator from the compiler,
and enables the resuse of the same FIRRTL between multiple simulation or EDA
backends. midas.Compiler will be removed in the next release.

### 3. Endpoints Have Been Replaced With Target-to-Host Bridges.

Unlike Endpoints, which were instantiated by matching on a Chisel I/O type,
target-to-host bridges (or bridges, for short) are instantiated directly in the
target's RTL (i.e., in Chisel).  Unlike endpoints, bridges can be instantiated
anywhere in the module heirachy, and can more effectively capture
module-hierarchy-dependent parameterization information from the target. This
makes it easier to have multiple instances of the same bridge with difference
parameterizations.

### 4. The Input Target Design Must Be Closed

The FIRRTL passed to Golden Gate must expose no dangling I/O (with the exception of one input
clock): instead the target should be wrapped in a module that instantiates the
appropriate bridges. This wrapper module is directly analogous to a test
harness used in software-based RTL simulation.  How these bridges are
instantiated is left to the user, but multiple different examples can be found in
FireSim. One benefit of this "closed-world" approach is that the topology of the
simulator (as a network of simulation models) is guaranteed to match the topology
of the input design.

### 5. Different Underlying Dataflow Network Formalism

Golden Gate uses the [_Latency-Insensitive Bounded-Dataflow Network_](https://dl.acm.org/citation.cfm?id=1715781) (LI-BDN)
target formalism.  This makes it possible to model combinational paths that
span multiple models, and to prove that properties about target-cycle exactness
and deadlock freedom in the resulting simulator.

## Documentation

Golden Gate's documentation is hosted in [FireSim's Read-The-Docs](https://docs.fires.im)

## Related Publications

* Albert Magyar, David T. Biancolin, Jack Koenig, Sanjit Seshia, Jonathan Bachrach, Krste Asanović, **Golden Gate: Bridging The Resource-Efficiency Gap Between ASICs and FPGA Prototypes**, To appear at ICCAD '19.([Paper PDF](http://davidbiancolin.github.io/papers/goldengate-iccad19.pdf))
* David Biancolin, Sagar Karandikar, Donggyu Kim, Jack Koenig, Andrew Waterman, Jonathan Bachrach, Krste Asanović, **“FASED: FPGA-Accelerated Simulation and Evaluation of DRAM”**, In proceedings of the 27th ACM/SIGDA International Symposium on Field-Programmable Gate Arrays, Seaside, CA, February 2019. ([Paper PDF](https://people.eecs.berkeley.edu/~biancolin/papers/fased-fpga19.pdf))
* Donggyu Kim, Christopher Celio, Sagar Karandikar, David Biancolin, Jonathan Bachrach, and Krste Asanović, **“DESSERT: Debugging RTL Effectively with State Snapshotting for Error Replays across Trillions of cycles”**, In proceedings of the 28th International Conference on Field Programmable Logic & Applications (FPL 2018), Dublin, Ireland, August 2018. ([IEEE Xplore](https://ieeexplore.ieee.org/abstract/document/8533471))
* Sagar Karandikar, Howard Mao, Donggyu Kim, David Biancolin, Alon Amid, Dayeol Lee, Nathan Pemberton, Emmanuel Amaro, Colin Schmidt, Aditya Chopra, Qijing Huang, Kyle Kovacs, Borivoje Nikolić, Randy Katz, Jonathan Bachrach, and Krste Asanović, **“FireSim: FPGA-Accelerated Cycle-Exact Scale-Out System Simulation in the Public Cloud”**, In proceedings of the 45th ACM/IEEE International Symposium on Computer Architecture (ISCA 2018), Los Angeles, June 2018. ([Paper PDF](https://sagark.org/assets/pubs/firesim-isca2018.pdf), [IEEE Xplore](https://ieeexplore.ieee.org/document/8416816)) **Selected as one of IEEE Micro’s “Top Picks from Computer Architecture Conferences, 2018”.** 
* Donggyu Kim, Christopher Celio, David Biancolin, Jonathan Bachrach, and Krste Asanović, **"Evaluation of RISC-V RTL with FPGA-Accelerated Simulation"**, The First Workshop on Computer Architecture Research with RISC-V (CARRV 2017), Boston, MA, USA, Oct 2017. ([Paper PDF](doc/papers/carrv-2017.pdf))
* Donggyu Kim, Adam Izraelevitz, Christopher Celio, Hokeun Kim, Brian Zimmer, Yunsup Lee, Jonathan Bachrach, and Krste Asanović, **"Strober: Fast and Accurate Sample-Based Energy Simulation for Arbitrary RTL"**, International Symposium on Computer Architecture (ISCA-2016), Seoul, Korea, June 2016. ([ACM DL](https://dl.acm.org/citation.cfm?id=3001151), [Slides](http://isca2016.eecs.umich.edu/wp-content/uploads/2016/07/2B-2.pdf))

## Dependencies

This repository depends on the following projects:
* [Chisel](https://github.com/freechipsproject/chisel3): Target-RTL that MIDAS transformed must be written in Chisel RTL in the current version. Additionally, MIDAS RTL libraries are all written in Chisel.
* [FIRRTL](https://github.com/freechipsproject/firrtl): Transformations of target-RTL are performed using FIRRTL compiler passes.
* [RocketChip](https://github.com/freechipsproject/rocket-chip): Rocket Chip is not only a chip generator, but also a collection of useful libraries for various hardware designs.
* [barstools](https://github.com/ucb-bar/barstools): Some additional technology-dependent custom transforms(e.g. macro compiler) are required when Strober energy modelling is enabled.

