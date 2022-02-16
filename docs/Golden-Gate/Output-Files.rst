Output Files
============

Golden Gate generates many output files, we describe them here.  Note, the GG
CML-argument ``--output-filename-base=<BASE>`` defines defines a common prefix
for all output files.


Core Files
-------------------------------------
These are used in nearly all flows.

* **<BASE>.sv**: The verilog implementation of the simulator which will be synthesized onto the FPGA. The top-level is the Shim module specified in the ``PLATFORM_CONFIG``.
* **<BASE>.const.h**: A target-specific header containing all necessary metadata to instantiate bridge drivers. This is linked into the simulator driver and meta-simulators (FPGA-level / MIDAS-level). Often referred to as "the header".
* **<BASE>.runtime.conf**: Default plus args for generated FASED memory timing models. Most other bridges have their defaults baked into the driver.

FPGA Synthesis Files
-------------------------------------
These are additional files passed to the FPGA build directory.

* **<BASE>.defines.vh**: Verilog macro definitions for FPGA synthesis.
* **<BASE>.env.tcl**: Used a means to inject arbitrary TCL into the start of the build flow. Controls synthesis and implementation strategies, and sets the host_clock frequency before the clock generator (MCMM) is synthesized.
* **<BASE>.ila_insert_vivado.tcl**: Synthesizes an ILA for the design. See :ref:`auto-ila` for more details about using ILAs in FireSim.
* **<BASE>.ila_insert_{inst, ports, wires}.v**: Instantiated in the FPGA project via ```include`` directives to instantiate the generated ILA.

Meta-simulation Files
-------------------------------------
These are additional sources used only in MIDAS-level simulators

* **<BASE>.const.vh**: Verilog macros to define variable width fields.
