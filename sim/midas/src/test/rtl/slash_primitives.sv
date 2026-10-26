// See LICENSE for license details.
// Behavioral test doubles only. They do not qualify vendor primitive timing.
module BUFG_FABRIC(input I, output O);
  assign O = I;
endmodule
module xpm_cdc_sync_rst #(parameter DEST_SYNC_FF=4, INIT=1, INIT_SYNC_FF=1)
  (input dest_clk, input src_rst, output dest_rst);
  reg [DEST_SYNC_FF-1:0] stages = {DEST_SYNC_FF{INIT[0]}};
  always @(posedge dest_clk) stages <= {stages[DEST_SYNC_FF-2:0], src_rst};
  assign dest_rst = stages[DEST_SYNC_FF-1];
endmodule
