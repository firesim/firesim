module BUFGCE(
  input      I,
  input      CE,
  output reg O
);
   /* verilator lint_off BLKSEQ */
   // VCS/Verilator-v5 compatible clock gating
   // Blocking assignment makes behavior deterministic
   always @(posedge I or negedge I) begin
     if (CE)
       O = I;
     else
       O = 1'h0;
   end
   /* verilator lint_on BLKSEQ */
endmodule
