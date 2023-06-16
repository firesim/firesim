
# **Setting up your FireSim Machine(s)**: Bringing-up a FireSim Simulation
Host Cluster (or a single Desktop) from scratch.

#. **Single-node simulation guide**: This guide guides you through the
   process of running one simulation locally consisting of a single
   |fpga_name|, using our pre-built public FireSim |bit_type| bitstream.

#. **Building your own hardware designs guide (RTL Design to Simulator FPGA Image)**:
   This guide guides you through the full process of taking Rocket Chip RTL
   and any custom RTL plugged into Rocket Chip and producing a FireSim bitstream
   to plug into your simulations. This automatically runs Chisel elaboration,
   FAME-1 Transformation, and the |build_type| FPGA flow.

Generally speaking, you only need to follow Step 5 if you're modifying Chisel
RTL or changing non-runtime configurable hardware parameters.
