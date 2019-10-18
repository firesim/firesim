module BUFGCE(
  input      I,
  input      CE,
  output reg O
);
  /* verilator lint_off COMBDLY */
  reg enable;
  always @(posedge I)
    enable <= CE;
  assign O = (I & enable);
  /* verilator lint_on COMBDLY */
endmodule
