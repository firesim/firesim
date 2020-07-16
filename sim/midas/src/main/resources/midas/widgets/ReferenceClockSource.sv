`timescale 1ps/1ps
module ClockSourceReference #(
    parameter PERIOD_PS,
    parameter DUTY_CYCLE,
    parameter INIT_VALUE) (
    output logic clockOut);

    localparam HIGH_TIME = (PERIOD_PS * DUTY_CYCLE) / 100;
    localparam LOW_TIME = PERIOD_PS - HIGH_TIME;

    initial begin
        clockOut = INIT_VALUE[0];
        if (INIT_VALUE == 0) #LOW_TIME clockOut = ~clockOut;

        forever begin
            #HIGH_TIME clockOut = ~clockOut;
            #LOW_TIME clockOut = ~clockOut;
        end
    end
endmodule

