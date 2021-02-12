`timescale 1ps/100fs
// Samples a "real" reference signal every 1ps
// (simulated time in the reference = the native simulation time in VCS,
// essentially the host). The resulting samples, essentially (data, time) pairs, are presented as
// a decoupled source that can be compared against a model token stream (whose
// simulated time is decoupled from VCS's native time).
module ReferenceTimestamperImpl #(
    parameter DATA_WIDTH,
    parameter LOG2_NUM_SAMPLES = 16
) (
    input logic [DATA_WIDTH-1:0] value,
    input logic clock,
    input logic reset,
    output logic timestamped_valid,
    input logic  timestamped_ready,
    output logic [DATA_WIDTH-1:0] timestamped_bits_data,
    output logic [63:0] timestamped_bits_time);

    localparam NUM_SAMPLES = 2**LOG2_NUM_SAMPLES;

    // The reference signal can't be backpressured (it's not host decoupled),
    // so use a large memory to capture signal transitions.
    reg [DATA_WIDTH-1:0] data_mem [0:NUM_SAMPLES-1];
    reg [LOG2_NUM_SAMPLES-1:0] w_idx, w_idx_sync, r_idx;

    // Sample the signal every timestep
    initial begin
        w_idx = '0;
        forever begin
            // Use a fractional delay that still rounds down to 0 to produce
            // the right timestamp
            #0.1;
            if (w_idx < (NUM_SAMPLES - 1)) begin
                data_mem[w_idx] <= value;
                w_idx <= w_idx + 1'b1;
            end
            #0.9;
        end
    end

    assign timestamped_valid = w_idx_sync != r_idx;
    assign timestamped_bits_data = data_mem[r_idx];
    assign timestamped_bits_time = r_idx;

    always @(posedge clock) begin
        if (reset) begin
            w_idx_sync <= 16'b0;
            r_idx <= 16'b0;
        end
        else begin
            w_idx_sync <= w_idx;
            if (timestamped_valid && timestamped_ready) begin
                r_idx <= r_idx + 1'b1;
            end
        end
    end
endmodule

