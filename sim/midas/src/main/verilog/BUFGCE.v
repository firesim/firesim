module BUFGCE(
  input      I,
  input      CE,
  output reg O
);
   reg       enable_latch;
   always @(posedge I)
     enable_latch <= CE;
`ifdef VERILATOR
   // Note: Verilator doesn't like procedural clock gates
   // They cause combinational loop errors and UNOPT_FLAT
   assign O = (I & enable_latch);
`else
   // Note: VCS doesn't like the Verilator clock gate
   // Delays clock edge too much when CE is a register
   // Blocking assignment makes behavior deterministic
   always @(posedge I or negedge I) begin
     if (CE)
       O = I;
     else
       O = 1'h0;
   end
`endif
endmodule
