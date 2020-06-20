/*

Rocket Chip Clock Configuration


Rocket Chip             1000              RC_CLK_MULT
 Clockrate     =   ---------------   X   -------------
  (in MHz)         ZYNQ_CLK_PERIOD       RC_CLK_DIVIDE


This sets the parameters used by midas_wrapper.v to
generate its own clock.

Most uses should only change RC_CLK_MULT & RC_CLK_DIVIDE.
ZYNQ_CLK_PERIOD should only be changed to match the input
clock period set in the Vivado GUI and 
hw/src/constrs/pin_constraints.xdc

*/


`ifndef _clocking_vh_
`define _clocking_vh_


`define ZYNQ_CLK_PERIOD 10.0

`define RC_CLK_MULT     10.0

`define RC_CLK_DIVIDE   40.0


`endif // _clocking_vh_
