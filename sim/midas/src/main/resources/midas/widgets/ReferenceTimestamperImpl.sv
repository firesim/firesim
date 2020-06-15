`timescale 1ps/1ps
// Annotates a "real" reference signal with the times it transitions
// (simulated time in the reference = the native simulation time in VCS,
// essentially the host). The resulting (data, time) pairs are presented as
// a decoupled source that can be compared against a model token stream (whose
// simulated time is decoupled from VCS's native time).
module ReferenceTimestamperImpl #(
    parameter DATA_WIDTH) (
    input logic [DATA_WIDTH-1:0] value,
    input logic clock,
    input logic reset,
    output logic timestamped_valid,
    input logic  timestamped_ready,
    output logic [DATA_WIDTH-1:0] timestamped_bits_data,
    output logic [63:0] timestamped_bits_time);

    // The reference signal can't be backpressured (it's not host decoupled),
    // so use a large memory to capture signal transitions.
    reg [63:0] time_mem [0:65536];
    reg [DATA_WIDTH-1:0] data_mem [0:65536];
    reg [15:0] w_idx, w_idx_sync, r_idx;

    // At time 0 many initialization events occur; avoid capturing these
    // spurious transitions by registering only the last one for time
    // 0 which should be created by the reference models using an
    // additional "REFERENCE_INIT_DELAY".
    initial begin
        #0 w_idx = 16'b0;
    end

    always @(value) begin
        time_mem[w_idx] <= $time;
        data_mem[w_idx] <= value;
        w_idx <= w_idx + 1'b1;
    end

    assign timestamped_valid = w_idx_sync != r_idx;
    assign timestamped_bits_data = data_mem[r_idx];
    assign timestamped_bits_time = time_mem[r_idx];

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
            if (w_idx_sync + 1'b1 == r_idx) begin
                $fatal("Timestamper buffer overflowed. Cannot backpressure");
            end
        end
    end
endmodule

