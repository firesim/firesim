// Most of the verilog affected by these `defines should be removed
// by the verilog preprocesor when in synthesis. I'm adding the extra guard
// here to be explicit.
`ifndef SYNTHESIS

// Setting these an avoids X-prop issues for uninitialized state in
// Chisel-emitted verilog
`define RANDOMIZE_MEM_INIT
`define RANDOMIZE_REG_INIT
`define RANDOMIZE_GARBAGE_ASSIGN
`define RANDOMIZE_INVALID_ASSIGN

// This populates anything that would be randomized with assignment to 0
// Note: Calls to $random appear to break vitis hardware emulation, this both
// works around that, and provides a somewhat better model of the FPGA.
`define RANDOM 1'b0

// TODO: determine sensible values for these. Need a path through the linked
// design hierarchy to reset.
`define PRINTF_COND 1'b1
`define STOP_COND 1'b1

`endif
