`default_nettype none
`timescale 1 ps / 1 ps

module XRTShim #(
    parameter integer C_S_AXI_CONTROL_DATA_WIDTH = 32,
    parameter integer C_S_AXI_CONTROL_ADDR_WIDTH = 25
)(
    // main clock
    input wire         ap_clk,
    input wire         ap_rst_n,

    output wire         s_axi_lite_awready,
    input wire         s_axi_lite_awvalid,
    input wire [C_S_AXI_CONTROL_ADDR_WIDTH-1:0]  s_axi_lite_awaddr,
    input wire [7:0]   s_axi_lite_awlen,
    input wire [2:0]   s_axi_lite_awsize,
    input wire [1:0]   s_axi_lite_awburst,
    input wire         s_axi_lite_awlock,
    input wire [3:0]   s_axi_lite_awcache,
    input wire [2:0]   s_axi_lite_awprot,
    input wire [3:0]   s_axi_lite_awqos,
    input wire [3:0]   s_axi_lite_awregion,
    input wire [11:0]  s_axi_lite_awid,
    input wire         s_axi_lite_awuser,
    output wire         s_axi_lite_wready,
    input wire         s_axi_lite_wvalid,
    input wire [C_S_AXI_CONTROL_DATA_WIDTH-1:0]  s_axi_lite_wdata,
    input wire         s_axi_lite_wlast,
    input wire [11:0]  s_axi_lite_wid,
    input wire [C_S_AXI_CONTROL_DATA_WIDTH/8-1:0]   s_axi_lite_wstrb,
    input wire         s_axi_lite_wuser,
    input wire         s_axi_lite_bready,
    output wire         s_axi_lite_bvalid,
    output wire [1:0]   s_axi_lite_bresp,
    output wire [11:0]  s_axi_lite_bid,
    output wire         s_axi_lite_buser,
    output wire         s_axi_lite_arready,
    input wire         s_axi_lite_arvalid,
    input wire [C_S_AXI_CONTROL_ADDR_WIDTH-1:0]  s_axi_lite_araddr,
    input wire [7:0]   s_axi_lite_arlen,
    input wire [2:0]   s_axi_lite_arsize,
    input wire [1:0]   s_axi_lite_arburst,
    input wire         s_axi_lite_arlock,
    input wire [3:0]   s_axi_lite_arcache,
    input wire [2:0]   s_axi_lite_arprot,
    input wire [3:0]   s_axi_lite_arqos,
    input wire [3:0]   s_axi_lite_arregion,
    input wire [11:0]  s_axi_lite_arid,
    input wire         s_axi_lite_aruser,
    input wire         s_axi_lite_rready,
    output wire         s_axi_lite_rvalid,
    output wire [1:0]   s_axi_lite_rresp,
    output wire [C_S_AXI_CONTROL_DATA_WIDTH-1:0]  s_axi_lite_rdata,
    output wire         s_axi_lite_rlast,
    output wire [11:0]  s_axi_lite_rid,
    output wire         s_axi_lite_ruser
);

   reg pre_sync_rst_n_firesim;
   reg sync_rst_n_firesim;
   always @(negedge ap_rst_n or posedge ap_clk) begin
       if (!ap_rst_n)
       begin
           pre_sync_rst_n_firesim  <= 1'b0;
           sync_rst_n_firesim <= 1'b0;
       end
       else begin
           pre_sync_rst_n_firesim  <= 1'b1;
           sync_rst_n_firesim <= pre_sync_rst_n_firesim;
       end
    end

    (* dont_touch = "true" *) wire [C_S_AXI_CONTROL_ADDR_WIDTH-1:0]  s_axi_lite_awaddr_trunc;
    (* dont_touch = "true" *) wire [C_S_AXI_CONTROL_ADDR_WIDTH-1:0]  s_axi_lite_araddr_trunc;
    assign s_axi_lite_awaddr_trunc = s_axi_lite_awaddr[15:0];
    assign s_axi_lite_araddr_trunc = s_axi_lite_araddr[15:0];

    VitisShim vitisShim (
        .clock(ap_clk),
        .reset(!sync_rst_n_firesim),
        .io_master_aw_ready(s_axi_lite_awready),
        .io_master_aw_valid(s_axi_lite_awvalid),
        .io_master_aw_bits_addr(s_axi_lite_awaddr_trunc),
        .io_master_aw_bits_len(s_axi_lite_awlen),
        .io_master_aw_bits_size(s_axi_lite_awsize),
        .io_master_aw_bits_burst(s_axi_lite_awburst),
        .io_master_aw_bits_lock(s_axi_lite_awlock),
        .io_master_aw_bits_cache(s_axi_lite_awcache),
        .io_master_aw_bits_prot(s_axi_lite_awprot),
        .io_master_aw_bits_qos(s_axi_lite_awqos),
        .io_master_aw_bits_region(s_axi_lite_awregion),
        .io_master_aw_bits_id(s_axi_lite_awid),
        .io_master_aw_bits_user(s_axi_lite_awuser),
        .io_master_w_ready(s_axi_lite_wready),
        .io_master_w_valid(s_axi_lite_wvalid),
        .io_master_w_bits_data(s_axi_lite_wdata),
        .io_master_w_bits_last(s_axi_lite_wlast),
        .io_master_w_bits_id(s_axi_lite_wid),
        .io_master_w_bits_strb(s_axi_lite_wstrb),
        .io_master_w_bits_user(s_axi_lite_wuser),
        .io_master_b_ready(s_axi_lite_bready),
        .io_master_b_valid(s_axi_lite_bvalid),
        .io_master_b_bits_resp(s_axi_lite_bresp),
        .io_master_b_bits_id(s_axi_lite_bid),
        .io_master_b_bits_user(s_axi_lite_buser),
        .io_master_ar_ready(s_axi_lite_arready),
        .io_master_ar_valid(s_axi_lite_arvalid),
        .io_master_ar_bits_addr(s_axi_lite_araddr_trunc),
        .io_master_ar_bits_len(s_axi_lite_arlen),
        .io_master_ar_bits_size(s_axi_lite_arsize),
        .io_master_ar_bits_burst(s_axi_lite_arburst),
        .io_master_ar_bits_lock(s_axi_lite_arlock),
        .io_master_ar_bits_cache(s_axi_lite_arcache),
        .io_master_ar_bits_prot(s_axi_lite_arprot),
        .io_master_ar_bits_qos(s_axi_lite_arqos),
        .io_master_ar_bits_region(s_axi_lite_arregion),
        .io_master_ar_bits_id(s_axi_lite_arid),
        .io_master_ar_bits_user(s_axi_lite_aruser),
        .io_master_r_ready(s_axi_lite_rready),
        .io_master_r_valid(s_axi_lite_rvalid),
        .io_master_r_bits_resp(s_axi_lite_rresp),
        .io_master_r_bits_data(s_axi_lite_rdata),
        .io_master_r_bits_last(s_axi_lite_rlast),
        .io_master_r_bits_id(s_axi_lite_rid),
        .io_master_r_bits_user(s_axi_lite_ruser)
    );

endmodule : XRTShim

`default_nettype wire
