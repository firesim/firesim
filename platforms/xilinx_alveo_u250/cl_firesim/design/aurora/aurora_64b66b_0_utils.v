//aurora_64b66b_0_utils.v 

module aurora_tx_arb(
    input [3:0] tx_signal,

    output tx_lane_1,
    output tx_lane_2,
    output tx_lane_3,
    output tx_lane_4
);

    assign tx_lane_1 = tx_signal[0];
    assign tx_lane_2 = tx_signal[1];
    assign tx_lane_3 = tx_signal[2];
    assign tx_lane_4 = tx_signal[3];
endmodule

module aurora_rx_arb(
    input rx_lane_1,
    input rx_lane_2,
    input rx_lane_3,
    input rx_lane_4,

    output [3:0] rx_signal
);

    assign rx_signal = {rx_lane_4, rx_lane_3, rx_lane_2, rx_lane_1};
endmodule

module aurora_gt_wrapper(
    RXP_in,
    RXN_in,

    RXP_out,
    RXN_out,

    TXP_in,
    TXN_in,

    TXP_out,
    TXN_out
);

(* X_INTERFACE_PARAMETER = "XIL_INTERFACENAME QSFP_GT" *)
(* X_INTERFACE_INFO = "xilinx.com:interface:gt_rtl:1.0 QSFP_GT GTX_N" *) output[3:0] TXP_out;
(* X_INTERFACE_INFO = "xilinx.com:interface:gt_rtl:1.0 QSFP_GT GTX_P" *) output[3:0] TXN_out;
(* X_INTERFACE_INFO = "xilinx.com:interface:gt_rtl:1.0 QSFP_GT GRX_N" *) input[3:0] RXN_out;
(* X_INTERFACE_INFO = "xilinx.com:interface:gt_rtl:1.0 QSFP_GT GRX_P" *) input[3:0] RXP_out;


output [3:0] RXP_in;
output [3:0] RXN_in;

input [3:0] TXP_in;
input [3:0] TXN_in;

assign RXP_in = RXP_out;
assign RXN_in = RXN_out;

assign TXP_out = TXP_in;
assign TXN_out = TXN_in;

endmodule