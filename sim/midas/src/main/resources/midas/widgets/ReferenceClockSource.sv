`timescale 1ps/1ps
module ClockSourceReference #(
    parameter PERIOD_PS,
    parameter DUTY_CYCLE,
    parameter INIT_VALUE) (
    // Assign the initial value on declaration to avoid clock sinks from
    // seeing an edge at time 0. If the assignment was done in a initial
    // block, downstream blocks will see a transition from HiZ to INIT_VALUE[0]. This may trigger
    // additional procedural blocks that would be scheduled concurrently with initial blocks,
    // introducing non-determinism.
    output logic clockOut = INIT_VALUE[0]);

    localparam HIGH_TIME = (PERIOD_PS * DUTY_CYCLE) / 100;
    localparam LOW_TIME = PERIOD_PS - HIGH_TIME;

    initial begin
        if (INIT_VALUE == 0) #LOW_TIME clockOut = ~clockOut;

        forever begin
            #HIGH_TIME clockOut = ~clockOut;
            #LOW_TIME clockOut = ~clockOut;
        end
    end
endmodule

