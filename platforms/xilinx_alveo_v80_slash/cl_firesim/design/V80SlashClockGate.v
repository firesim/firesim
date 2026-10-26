// See LICENSE for license details.
// The host falling edge captures CE before the next complete target pulse.
// The targetFire equation and target-cycle state remain compiler controlled.
(* dont_touch = "yes" *) module V80SlashClockGate(input I, input CE, output O);
  (* dont_touch = "yes" *) reg clken_l;
  (* dont_touch = "yes" *) wire gated;
  always @(negedge I) clken_l <= CE;
  assign gated = I & clken_l;
  (* dont_touch = "yes" *) BUFG_FABRIC u_bufg (.I(gated), .O(O));
endmodule
