`timescale 1ps/1ps
module ReferenceRegisterImpl #(
    parameter DATA_WIDTH,
    string EDGE_SENSE,
    parameter INIT_VALUE) (
    input logic clock,
    input logic reset,
    input logic [DATA_WIDTH-1:0] d,
    output reg  [DATA_WIDTH-1:0] q);

    initial begin
        // This must be greater > 0 but < 1
        #`REFERENCE_INIT_DELAY q = INIT_VALUE[DATA_WIDTH - 1:0];
    end
    generate
    if (EDGE_SENSE == "POSEDGE")
        always @(posedge clock) begin
            q <= d;
        end
    else
        always @(negedge clock) begin
            q <= d;
        end
    endgenerate
endmodule

