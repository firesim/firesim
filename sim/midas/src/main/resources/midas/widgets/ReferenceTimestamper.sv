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
    // so use a large memory to capture signal transitions. In general the
    // models will simulate time faster since they don't have to wait for host
    // time to pass to model the delay, and the host clock transitions on
    // every simulator timestep..
    reg [63:0] time_mem [0:255];
    reg [DATA_WIDTH-1:0] data_mem [0:255];
    reg [7:0] w_idx, w_idx_sync, r_idx;
    initial begin
        w_idx = 8'b0;
        w_idx_sync = 8'b0;
        r_idx = 8'b0;
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
            w_idx_sync <= 8'b0;
            r_idx <= 8'b0;
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

