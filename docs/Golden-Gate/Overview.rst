Overview & Philosophy
=====================

Underpinning FireSim is Golden Gate (MIDAS II), a FIRRTL-based compiler and C++ library, which
is used to transform Chisel-generated RTL into a deterministic FPGA-accelerated
simulator.

Golden Gate vs FPGA Prototyping
-------------------------------

Key to understanding the design of Golden Gate, is understanding that Golden Gate-generated
simulators are not FPGA prototypes. Unlike in a prototype, Golden Gate-generated simulators decouple the
target-design clocks from all FPGA-host clocks (we say it is *host-decoupled*): one cycle in the target machine is
simulated over a dynamically variable number FPGA clock cycles. In constrast, a
conventional FPGA-prototype "emulates" the SoC by implementing the target
directly in FPGA logic, with each FPGA-clock edge executing a clock edge of the
SoC.

Why Use Golden Gate & FireSim
-------------------------------

The host decoupling by Golden Gate-generated simulators enables:

#. **Deterministic simulation**
   Golden Gate creates a closed simulation environment such that bugs in the target can be reproduced
   despite timing-differences (eg. DRAM refresh, PCI-E transport latency) in the underlying host.
   The simulators for the same target can be generated for different host-FPGAs but will maintain
   the same target behavior.

#. **FPGA-host optimizations**
   Structures in ASIC RTL that map poorly to FPGA logic can be replaced with models
   that preserve the target RTL's behavior, but take more host cycles to save resources.
   eg. A 5R, 3W-ported register file with a dual-ported BRAM over 4 cycles.

#. **Distributed simulation & software co-simulation**
   Since models are decoupled from host time, it becomes much easier to host
   components of the simulator on multiple FPGAs, and on a host-CPU, while still
   preserving simulation determinism. This feature serves as the basis for building
   cycle-accurate scale-out systems with FireSim.

#. **FPGA-hosted, timing-faithful models of I/O devices**
   Most simple FPGA-prototypes use FPGA-attached DRAM to model the target's
   DRAM memory system. If the available memory system does not match that of
   the target, the target's simulated performance will be artificially
   fast or slow. Host-decoupling permits writing detailed timing models that
   provide host-independent, deterministic timing of the target's memory system,
   while still use FPGA-host resources like DRAM as a functional store.


Why Not Golden Gate
-----------------------------------

Ultimately, Golden Gate-generated simulators introduce overheads not present in an
FPGA-prototype that *may* increase FPGA resource use, decrease fmax, and
decrease overall simulation throughput [#]_.  Those looking to develop
soft-cores or develop a complete FPGA-based platform with their own boards and
I/O devices would be best served by implementing their design directly on an FPGA. For
those looking to building a system around Rocket-Chip, we'd suggest looking at
`SiFive's Freedom platform <https://github.com/sifive/freedom>`_ to start.

How is Host-Decoupling Implemented?
-----------------------------------
Host-decoupling in Golden Gate-generated simulators is implemented by decomposing the
target machine into a dataflow graph of latency-insensitive models. As a user
of FireSim, understanding this dataflow abstraction is essential for debugging
your system and for developing your own software models and bridges. We
describe it in the next section.

.. [#] These overheads varying depending on the features implemented and optimizations applied. Certain optimizations, currently in development, may increase fmax or decrease resource utilization over the equivalent prototype.

