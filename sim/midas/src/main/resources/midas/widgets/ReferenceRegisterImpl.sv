`timescale 1ps/1ps
module ReferenceRegisterImpl #(
    parameter DATA_WIDTH = 1,
    string EDGE_SENSE = "POSEDGE",
    parameter INIT_VALUE = 0) (
    input logic clock,
    input logic reset,
    input logic [DATA_WIDTH-1:0] d,
    output reg  [DATA_WIDTH-1:0] q);

    initial begin
        // Create a fake transition on Q at time zero so that the reference
        // timestamper captures the correct initialization value
        q = ~INIT_VALUE[DATA_WIDTH - 1:0];
        // This must be greater > 0 but < 1
        #`REFERENCE_INIT_DELAY q = INIT_VALUE[DATA_WIDTH - 1:0];
    end

    // Avoid a race condition where d simultaenously changes with the clock edge
    // Adding this #0 delay allows the RHS of the non-blocking assignments to
    // resolve deterministically to the old value of d before the new value is
    // propogated to d_delay
    //
    // Alternatively this could be changed so that the new value is observed,
    // but that would require also changing the timestamped model
    logic [DATA_WIDTH-1:0] d_delay;
    initial begin
        // Provide an defined initial value since it will only take on a real
        // one after #0
        d_delay = INIT_VALUE[DATA_WIDTH - 1:0];
    end
    always @(d) begin
        #0 d_delay = d;
    end

    generate
    if (EDGE_SENSE == "POSEDGE")
        always @(posedge clock) begin
            q <= d_delay;
        end
    else
        always @(negedge clock) begin
            q <= d_delay;
        end
    endgenerate
endmodule
