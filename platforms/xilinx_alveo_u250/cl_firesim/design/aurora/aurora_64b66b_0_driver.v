module aurora_64b66b_0_driver (
// clk wiz input
input                INIT_CLK_IN,
output               INIT_CLK_i,
input                locked,
// aurora input
input                channel_up_i,
input                system_reset_i,


output reg           reset_pb,
output               gt_rxcdrovrden_i,
output    [2:0]      loopback_i,
output               power_down_i,
output               gt_reset_i,

input               user_clk_i
);

    assign gt_rxcdrovrden_i = 1'b0;
    assign loopback_i = 3'b000;
    assign power_down_i      =   1'b0;

    wire RESET;
    assign RESET = ~locked;

    reg [127:0]        pma_init_stage = {128{1'b1}};
    reg [23:0]         pma_init_pulse_width_cnt = 24'h0;
    reg pma_init_assertion = 1'b0;
    reg pma_init_assertion_r;
    reg gt_reset_i_delayed_r1;
    reg gt_reset_i_delayed_r2;
    wire gt_reset_i_delayed;

    reg PMA_INIT;
    // PMA_INIT is RESET delayed by 1 INIT_CLK cycles
    always @(posedge INIT_CLK_i)
    begin
        PMA_INIT <= RESET;
    end

    wire               gt_reset_i_tmp;
    wire               gt_reset_i_tmp2;

    wire reset_i;

    assign  gt_reset_i_tmp = PMA_INIT;
    assign  reset_i  =   RESET | gt_reset_i_tmp2;

    always @(posedge INIT_CLK_i)
    begin
        pma_init_stage[127:0] <= {pma_init_stage[126:0], gt_reset_i_tmp};
    end

    assign gt_reset_i_delayed = pma_init_stage[127];

    always @(posedge INIT_CLK_i)
    begin
        gt_reset_i_delayed_r1     <=  gt_reset_i_delayed;
        gt_reset_i_delayed_r2     <=  gt_reset_i_delayed_r1;
        pma_init_assertion_r  <= pma_init_assertion;
        if(~gt_reset_i_delayed_r2 & gt_reset_i_delayed_r1 & ~pma_init_assertion & (pma_init_pulse_width_cnt != 24'hFFFFFF))
            pma_init_assertion <= 1'b1;
        else if (pma_init_assertion & pma_init_pulse_width_cnt == 24'hFFFFFF)
            pma_init_assertion <= 1'b0;

        if(pma_init_assertion)
            pma_init_pulse_width_cnt <= pma_init_pulse_width_cnt + 24'h1;
    end

    assign gt_reset_i = pma_init_assertion ? 1'b1 : gt_reset_i_delayed;

    aurora_64b66b_0_rst_sync_exdes   u_rst_sync_gtrsttmpi
     (
       .prmry_in     (gt_reset_i_tmp),
       .scndry_aclk  (user_clk_i),
       .scndry_out   (gt_reset_i_tmp2)
      );

    // assign gt_reset_i_eff = pma_init_assertion ? 1'b1 : gt_reset_i_delayed;

    BUFG initclk_bufg_i
   (
      .I  (INIT_CLK_IN),
      .O  (INIT_CLK_i)
   );

 //*********************************Main Body of Code**********************************

 

     always @(posedge user_clk_i)
         reset_pb <= `DLY reset_i;

endmodule
