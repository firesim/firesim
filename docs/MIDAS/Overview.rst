Overview & Philosophy
=====================

Underpinning FireSim is MIDAS, a FIRRTL-based compiler and C++ library, which
is used to transform Chisel-generated RTL into a deterministic FPGA-accelerated
simulator.


MIDAS vs FireSim
----------------

MIDAS was designed to be used as library in any Chisel-based project. That
said, *FireSim is the canonical example of how to employ MIDAS*, and provides
many features that would be required in most projects that would use MIDAS
(FPGA host-platform projects (AWS FPGA), automation utilities (the manager), a
standalone build system, as well the most common Chisel-based RISC-V target
designs in Rocket and BOOM).  To this end, we expect that most users should
either fork FireSim, or submodule FireSim into their larger chip project,
instead of using MIDAS directly.

FPGA-Accelerated Simulation vs FPGA Prototyping
-----------------------------------------------

Key to understanding the design of MIDAS, is understanding that MIDAS-generated
simulators, like FireSim, are not FPGA prototypes. MIDAS-generated simulators,
like all FPGA-accelerated simulators before it (see RAMP), decouple the
target-clock from the FPGA-host clock. Thus one cycle in the target-machine is
simulated over a one-or-more FPGA-host clock cycles. In constrast, a
conventional FPGA-prototype "emulates" the SoC by implementing the target
directly in FPGA logic, with each FPGA-clock edge executing a clock edge of the
SoC.

Why FPGA-Accelerated Simulation
-------------------------------

Decoupling the host and target clock enables:

#. **Providing simulation determinism.**
   Debugging on an FPGA is instrinsically difficult. MIDAS creates a closed simulation
   deterministic simulation environment such that bugs in the target can be reproduced
   despite timing-differences (eg. DRAM refresh, PCI-E transport latency) in the underlying host.


#. **FPGA-host optimizations.**
   Structures in ASIC RTL, that map poorly to FPGA logic can be replaced with models
   that maintain the same target-RTL behavior, but take more host-cycle to save resources.
   eg. Simulating a multi-ported register file with a dual-ported BRAM.


#. **Distributed simulation & SW co-simulation.**
   Since models are decoupled from host-time, it becomes much easier to host
   components of the simulator on multiple FPGAs, and on host-CPU, while still
   preserving simulation determinism.


#. **FPGA-hosted timing-faithful models of I/O devices.**
   Most simple FPGA-prototypes use FPGA-attached DRAM to model the target's
   DRAM memory system. If the available memory system does not match that of
   the target, the target's simulated performance will be uncharacteristically
   fast or slow. Host-decoupling permits writing detailed timing models that
   provide host-independent, deterministic timing of the target's memory system,
   while still use FPGA-host resources like DRAM as a functional store.


Why Not FPGA-Accelerated Simulation
-----------------------------------

Ultimately, MIDAS-generated simulators introduce overheads not present in an
FPGA-prototype that *may* increase FPGA resource use, decrease fmax, and
decrease overall simulation throughput [#]_.  Those looking to develop
soft-cores or develop a complete FPGA-based platform with their own boards and
I/O devices would be best served by implementing their design on an FPGA. For
those looking to building a system around Rocket-Chip, we'd suggest looking at
`SiFive's Freedom platform <https://github.com/sifive/freedom>`_ to start.


.. [#] These overheads varying depending on the features implemented and optimizations applied. Certain optimizations, currently in development, may increase fmax or decrease resource utilization over the equivalent prototype.













