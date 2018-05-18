Debugging & Testing with RTL Simulation
=======================================

Simulation of a single FireSim node using software RTL simulators like
Verilator, Synoposys VCS, or XSIM, is the most productive way to catch bugs
before generating an AGFI.

FireSim provides flows to do RTL simulation at three different levels of
the design/abstraction heirarchy. Ordered from least to most detailed, they are:

- **Target-Level**: This simulates just the RTL of the target-design (Rocket
  Chip). There are no host-level features being simulated. Supported
  simulators: VCS, Verilator.
- **MIDAS-Level**: This simulates the target-design after it's been tranformed
  by MIDAS.  The target- and host-clock are decoupled. FPGA-hosted simulation
  models are present.  Abstract models for host-FPGA provided services, like
  DRAM, memory-mapped IO, and PCIS are used here. Supported simulators: VCS,
  Verilator.
- **FPGA-Level**: This is a complete simulation of the design that will passed
  to the FPGA tools, including clock-domain crossings, width adapters, PLLS,
  FPGA-periphery blocks like DRAM and PCI-E controllers. This leverages the
  simulation flow provided by AWS. Supported simulators: VCS, Vivado XSIM.


Generally, MIDAS-level simulations are only slightly slower than simulating at
target-RTL. Moving to FPGA-Level is very expensive. This illustrated in the
chart below.

====== ===== =======  ========= ======
Level  Waves VCS      Verilator XSIM
====== ===== =======  ========= ======
Target Off   4.8 kHz  6.2 kHz   N/A
Target On    0.8 kHz  4.8 kHz   N/A
MIDAS  Off   3.8 kHz  2.0 kHz   N/A
MIDAS  On    2.9 kHz  1.0 kHz   N/A
FPGA   On    TODO     N/A       TODO
====== ===== =======  ========= ======

Notes: Default configurations of a single-core Rocket Chip instance running
rv64ui-v-add.  Frequencies are given in target-Hz. Presently, the default
compiler flags passed to verilator and VCS differ from level to level. Hence,
these numbers are only intended to ballpark simulation speeds with FireSim's
out-of-the-box settings, not provide a scientific comparison between
simulators.
