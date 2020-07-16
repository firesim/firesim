`timescale 1ps/1ps
module ReferenceClockMux(
    input logic clockA,
    input logic clockB,
    input logic sel,
    output logic clockOut);

    logic regA_out;
    logic regB_out;

    ReferenceRegisterImpl #(.EDGE_SENSE("NEGEDGE")) regA(clockA, 1'b0, !regB_out && !sel, regA_out);
    ReferenceRegisterImpl #(.EDGE_SENSE("NEGEDGE")) regB(clockB, 1'b0, !regA_out && sel, regB_out);
    assign clockOut = regA_out && clockA || regB_out && clockB;

endmodule
