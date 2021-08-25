//Copyright 1986-2020 Xilinx, Inc. All Rights Reserved.
//--------------------------------------------------------------------------------
//Tool Version: Vivado v.2020.2 (lin64) Build 3064766 Wed Nov 18 09:12:47 MST 2020
//Date        : Tue Aug 10 14:40:18 2021
//Host        : firesim1 running 64-bit Ubuntu 18.04.5 LTS
//Command     : generate_target ex_synth_wrapper.bd
//Design      : ex_synth_wrapper
//Purpose     : IP block netlist
//--------------------------------------------------------------------------------
`timescale 1 ps / 1 ps

module U250Top
   (SYSCLK0_300_clk_n,
    SYSCLK0_300_clk_p,
    c0_ddr4_act_n,
    c0_ddr4_adr,
    c0_ddr4_ba,
    c0_ddr4_bg,
    c0_ddr4_ck_c,
    c0_ddr4_ck_t,
    c0_ddr4_cke,
    c0_ddr4_cs_n,
    c0_ddr4_dq,
    c0_ddr4_dqs_c,
    c0_ddr4_dqs_t,
    c0_ddr4_odt,
    c0_ddr4_par,
    c0_ddr4_reset_n,
    pcie_7x_mgt_rxn,
    pcie_7x_mgt_rxp,
    pcie_7x_mgt_txn,
    pcie_7x_mgt_txp,
    pcie_mgt_clk_n,
    pcie_mgt_clk_p,
    pcie_perstn_rst,
    qsfp0_int_l_0,
    qsfp0_lpmode_0,
    qsfp0_modprs_l_0,
    qsfp0_modsel_l_0,
    qsfp0_reset_l_0,
    qsfp1_int_l_0,
    qsfp1_lpmode_0,
    qsfp1_modprs_l_0,
    qsfp1_modsel_l_0,
    qsfp1_reset_l_0,
    satellite_gpio,
    satellite_uart_rxd,
    satellite_uart_txd);
  input SYSCLK0_300_clk_n;
  input SYSCLK0_300_clk_p;
  output c0_ddr4_act_n;
  output [16:0]c0_ddr4_adr;
  output [1:0]c0_ddr4_ba;
  output [1:0]c0_ddr4_bg;
  output c0_ddr4_ck_c;
  output c0_ddr4_ck_t;
  output c0_ddr4_cke;
  output c0_ddr4_cs_n;
  inout [71:0]c0_ddr4_dq;
  inout [17:0]c0_ddr4_dqs_c;
  inout [17:0]c0_ddr4_dqs_t;
  output c0_ddr4_odt;
  output c0_ddr4_par;
  output c0_ddr4_reset_n;
  input [15:0]pcie_7x_mgt_rxn;
  input [15:0]pcie_7x_mgt_rxp;
  output [15:0]pcie_7x_mgt_txn;
  output [15:0]pcie_7x_mgt_txp;
  input [0:0]pcie_mgt_clk_n;
  input [0:0]pcie_mgt_clk_p;
  input pcie_perstn_rst;
  input [0:0]qsfp0_int_l_0;
  output [0:0]qsfp0_lpmode_0;
  input [0:0]qsfp0_modprs_l_0;
  output [0:0]qsfp0_modsel_l_0;
  output [0:0]qsfp0_reset_l_0;
  input [0:0]qsfp1_int_l_0;
  output [0:0]qsfp1_lpmode_0;
  input [0:0]qsfp1_modprs_l_0;
  output [0:0]qsfp1_modsel_l_0;
  output [0:0]qsfp1_reset_l_0;
  input [3:0]satellite_gpio;
  input satellite_uart_rxd;
  output satellite_uart_txd;

  wire SYSCLK0_300_clk_n;
  wire SYSCLK0_300_clk_p;
  wire c0_ddr4_act_n;
  wire [16:0]c0_ddr4_adr;
  wire [1:0]c0_ddr4_ba;
  wire [1:0]c0_ddr4_bg;
  wire c0_ddr4_ck_c;
  wire c0_ddr4_ck_t;
  wire c0_ddr4_cke;
  wire c0_ddr4_cs_n;
  wire [71:0]c0_ddr4_dq;
  wire [17:0]c0_ddr4_dqs_c;
  wire [17:0]c0_ddr4_dqs_t;
  wire c0_ddr4_odt;
  wire c0_ddr4_par;
  wire c0_ddr4_reset_n;
  wire [31:0]ctrl_araddr;
  wire [2:0]ctrl_arprot;
  wire ctrl_arready;
  wire ctrl_arvalid;
  wire [31:0]ctrl_awaddr;
  wire [2:0]ctrl_awprot;
  wire ctrl_awready;
  wire ctrl_awvalid;
  wire ctrl_bready;
  wire [1:0]ctrl_bresp;
  wire ctrl_bvalid;
  wire [31:0]ctrl_rdata;
  wire ctrl_rready;
  wire [1:0]ctrl_rresp;
  wire ctrl_rvalid;
  wire [31:0]ctrl_wdata;
  wire ctrl_wready;
  wire [3:0]ctrl_wstrb;
  wire ctrl_wvalid;
  wire [63:0]dma_araddr;
  wire [1:0]dma_arburst;
  wire [3:0]dma_arcache;
  wire [3:0]dma_arid;
  wire [7:0]dma_arlen;
  wire [0:0]dma_arlock;
  wire [2:0]dma_arprot;
  wire [3:0]dma_arqos;
  wire dma_arready;
  wire [3:0]dma_arregion;
  wire [2:0]dma_arsize;
  wire dma_arvalid;
  wire [63:0]dma_awaddr;
  wire [1:0]dma_awburst;
  wire [3:0]dma_awcache;
  wire [3:0]dma_awid;
  wire [7:0]dma_awlen;
  wire [0:0]dma_awlock;
  wire [2:0]dma_awprot;
  wire [3:0]dma_awqos;
  wire dma_awready;
  wire [3:0]dma_awregion;
  wire [2:0]dma_awsize;
  wire dma_awvalid;
  wire [3:0]dma_bid;
  wire dma_bready;
  wire [1:0]dma_bresp;
  wire dma_bvalid;
  wire [511:0]dma_rdata;
  wire [3:0]dma_rid;
  wire dma_rlast;
  wire dma_rready;
  wire [1:0]dma_rresp;
  wire dma_rvalid;
  wire [511:0]dma_wdata;
  wire dma_wlast;
  wire dma_wready;
  wire [63:0]dma_wstrb;
  wire dma_wvalid;
  wire firesim_clock;
  wire [0:0]firesim_reset;
  wire [33:0]mem_0_araddr;
  wire [1:0]mem_0_arburst;
  wire [3:0]mem_0_arcache;
  wire [3:0]mem_0_arid;
  wire [7:0]mem_0_arlen;
  wire [0:0]mem_0_arlock;
  wire [2:0]mem_0_arprot;
  wire [3:0]mem_0_arqos;
  wire mem_0_arready;
  wire [3:0]mem_0_arregion;
  wire [2:0]mem_0_arsize;
  wire mem_0_arvalid;
  wire [33:0]mem_0_awaddr;
  wire [1:0]mem_0_awburst;
  wire [3:0]mem_0_awcache;
  wire [3:0]mem_0_awid;
  wire [7:0]mem_0_awlen;
  wire [0:0]mem_0_awlock;
  wire [2:0]mem_0_awprot;
  wire [3:0]mem_0_awqos;
  wire mem_0_awready;
  wire [3:0]mem_0_awregion;
  wire [2:0]mem_0_awsize;
  wire mem_0_awvalid;
  wire [3:0]mem_0_bid;
  wire mem_0_bready;
  wire [1:0]mem_0_bresp;
  wire mem_0_bvalid;
  wire [63:0]mem_0_rdata;
  wire [3:0]mem_0_rid;
  wire mem_0_rlast;
  wire mem_0_rready;
  wire [1:0]mem_0_rresp;
  wire mem_0_rvalid;
  wire [63:0]mem_0_wdata;
  wire mem_0_wlast;
  wire mem_0_wready;
  wire [7:0]mem_0_wstrb;
  wire mem_0_wvalid;
  wire [15:0]pcie_7x_mgt_rxn;
  wire [15:0]pcie_7x_mgt_rxp;
  wire [15:0]pcie_7x_mgt_txn;
  wire [15:0]pcie_7x_mgt_txp;
  wire [0:0]pcie_mgt_clk_n;
  wire [0:0]pcie_mgt_clk_p;
  wire pcie_perstn_rst;
  wire [0:0]qsfp0_int_l_0;
  wire [0:0]qsfp0_lpmode_0;
  wire [0:0]qsfp0_modprs_l_0;
  wire [0:0]qsfp0_modsel_l_0;
  wire [0:0]qsfp0_reset_l_0;
  wire [0:0]qsfp1_int_l_0;
  wire [0:0]qsfp1_lpmode_0;
  wire [0:0]qsfp1_modprs_l_0;
  wire [0:0]qsfp1_modsel_l_0;
  wire [0:0]qsfp1_reset_l_0;
  wire [3:0]satellite_gpio;
  wire satellite_uart_rxd;
  wire satellite_uart_txd;

  ex_synth ex_synth_i
       (.SYSCLK0_300_clk_n(SYSCLK0_300_clk_n),
        .SYSCLK0_300_clk_p(SYSCLK0_300_clk_p),
        .c0_ddr4_act_n(c0_ddr4_act_n),
        .c0_ddr4_adr(c0_ddr4_adr),
        .c0_ddr4_ba(c0_ddr4_ba),
        .c0_ddr4_bg(c0_ddr4_bg),
        .c0_ddr4_ck_c(c0_ddr4_ck_c),
        .c0_ddr4_ck_t(c0_ddr4_ck_t),
        .c0_ddr4_cke(c0_ddr4_cke),
        .c0_ddr4_cs_n(c0_ddr4_cs_n),
        .c0_ddr4_dq(c0_ddr4_dq),
        .c0_ddr4_dqs_c(c0_ddr4_dqs_c),
        .c0_ddr4_dqs_t(c0_ddr4_dqs_t),
        .c0_ddr4_odt(c0_ddr4_odt),
        .c0_ddr4_par(c0_ddr4_par),
        .c0_ddr4_reset_n(c0_ddr4_reset_n),
        .ctrl_araddr(ctrl_araddr),
        .ctrl_arprot(ctrl_arprot),
        .ctrl_arready(ctrl_arready),
        .ctrl_arvalid(ctrl_arvalid),
        .ctrl_awaddr(ctrl_awaddr),
        .ctrl_awprot(ctrl_awprot),
        .ctrl_awready(ctrl_awready),
        .ctrl_awvalid(ctrl_awvalid),
        .ctrl_bready(ctrl_bready),
        .ctrl_bresp(ctrl_bresp),
        .ctrl_bvalid(ctrl_bvalid),
        .ctrl_rdata(ctrl_rdata),
        .ctrl_rready(ctrl_rready),
        .ctrl_rresp(ctrl_rresp),
        .ctrl_rvalid(ctrl_rvalid),
        .ctrl_wdata(ctrl_wdata),
        .ctrl_wready(ctrl_wready),
        .ctrl_wstrb(ctrl_wstrb),
        .ctrl_wvalid(ctrl_wvalid),
        .dma_araddr(dma_araddr),
        .dma_arburst(dma_arburst),
        .dma_arcache(dma_arcache),
        .dma_arid(dma_arid),
        .dma_arlen(dma_arlen),
        .dma_arlock(dma_arlock),
        .dma_arprot(dma_arprot),
        .dma_arqos(dma_arqos),
        .dma_arready(dma_arready),
        .dma_arregion(dma_arregion),
        .dma_arsize(dma_arsize),
        .dma_arvalid(dma_arvalid),
        .dma_awaddr(dma_awaddr),
        .dma_awburst(dma_awburst),
        .dma_awcache(dma_awcache),
        .dma_awid(dma_awid),
        .dma_awlen(dma_awlen),
        .dma_awlock(dma_awlock),
        .dma_awprot(dma_awprot),
        .dma_awqos(dma_awqos),
        .dma_awready(dma_awready),
        .dma_awregion(dma_awregion),
        .dma_awsize(dma_awsize),
        .dma_awvalid(dma_awvalid),
        .dma_bid(dma_bid),
        .dma_bready(dma_bready),
        .dma_bresp(dma_bresp),
        .dma_bvalid(dma_bvalid),
        .dma_rdata(dma_rdata),
        .dma_rid(dma_rid),
        .dma_rlast(dma_rlast),
        .dma_rready(dma_rready),
        .dma_rresp(dma_rresp),
        .dma_rvalid(dma_rvalid),
        .dma_wdata(dma_wdata),
        .dma_wlast(dma_wlast),
        .dma_wready(dma_wready),
        .dma_wstrb(dma_wstrb),
        .dma_wvalid(dma_wvalid),
        .firesim_clock(firesim_clock),
        .firesim_reset(firesim_reset),
        .mem_0_araddr(mem_0_araddr),
        .mem_0_arburst(mem_0_arburst),
        .mem_0_arcache(mem_0_arcache),
        .mem_0_arid(mem_0_arid),
        .mem_0_arlen(mem_0_arlen),
        .mem_0_arlock(mem_0_arlock),
        .mem_0_arprot(mem_0_arprot),
        .mem_0_arqos(mem_0_arqos),
        .mem_0_arready(mem_0_arready),
        .mem_0_arregion(mem_0_arregion),
        .mem_0_arsize(mem_0_arsize),
        .mem_0_arvalid(mem_0_arvalid),
        .mem_0_awaddr(mem_0_awaddr),
        .mem_0_awburst(mem_0_awburst),
        .mem_0_awcache(mem_0_awcache),
        .mem_0_awid(mem_0_awid),
        .mem_0_awlen(mem_0_awlen),
        .mem_0_awlock(mem_0_awlock),
        .mem_0_awprot(mem_0_awprot),
        .mem_0_awqos(mem_0_awqos),
        .mem_0_awready(mem_0_awready),
        .mem_0_awregion(mem_0_awregion),
        .mem_0_awsize(mem_0_awsize),
        .mem_0_awvalid(mem_0_awvalid),
        .mem_0_bid(mem_0_bid),
        .mem_0_bready(mem_0_bready),
        .mem_0_bresp(mem_0_bresp),
        .mem_0_bvalid(mem_0_bvalid),
        .mem_0_rdata(mem_0_rdata),
        .mem_0_rid(mem_0_rid),
        .mem_0_rlast(mem_0_rlast),
        .mem_0_rready(mem_0_rready),
        .mem_0_rresp(mem_0_rresp),
        .mem_0_rvalid(mem_0_rvalid),
        .mem_0_wdata(mem_0_wdata),
        .mem_0_wlast(mem_0_wlast),
        .mem_0_wready(mem_0_wready),
        .mem_0_wstrb(mem_0_wstrb),
        .mem_0_wvalid(mem_0_wvalid),
        .pcie_7x_mgt_rxn(pcie_7x_mgt_rxn),
        .pcie_7x_mgt_rxp(pcie_7x_mgt_rxp),
        .pcie_7x_mgt_txn(pcie_7x_mgt_txn),
        .pcie_7x_mgt_txp(pcie_7x_mgt_txp),
        .pcie_mgt_clk_n(pcie_mgt_clk_n),
        .pcie_mgt_clk_p(pcie_mgt_clk_p),
        .pcie_perstn_rst(pcie_perstn_rst),
        .qsfp0_int_l_0(qsfp0_int_l_0),
        .qsfp0_lpmode_0(qsfp0_lpmode_0),
        .qsfp0_modprs_l_0(qsfp0_modprs_l_0),
        .qsfp0_modsel_l_0(qsfp0_modsel_l_0),
        .qsfp0_reset_l_0(qsfp0_reset_l_0),
        .qsfp1_int_l_0(qsfp1_int_l_0),
        .qsfp1_lpmode_0(qsfp1_lpmode_0),
        .qsfp1_modprs_l_0(qsfp1_modprs_l_0),
        .qsfp1_modsel_l_0(qsfp1_modsel_l_0),
        .qsfp1_reset_l_0(qsfp1_reset_l_0),
        .satellite_gpio(satellite_gpio),
        .satellite_uart_rxd(satellite_uart_rxd),
        .satellite_uart_txd(satellite_uart_txd));

  U250Shim firesim(
      .clock(firesim_clock),
      .reset(firesim_reset),
      .ctrl_aw_ready(ctrl_awready),
      .ctrl_aw_valid(ctrl_awvalid),
      .ctrl_aw_bits_addr(ctrl_awaddr[19:0]),
      .ctrl_aw_bits_len(8'h0),
      .ctrl_aw_bits_size(3'h2),
      .ctrl_aw_bits_burst(2'h1),
      .ctrl_aw_bits_lock(1'h0),
      .ctrl_aw_bits_cache(4'h0),
      .ctrl_aw_bits_prot(3'h0),
      .ctrl_aw_bits_qos(4'h0),
      .ctrl_aw_bits_region(4'h0),
      .ctrl_aw_bits_id(1'h0),
      .ctrl_aw_bits_user(1'h0),
      .ctrl_w_ready(ctrl_wready),
      .ctrl_w_valid(ctrl_wvalid),
      .ctrl_w_bits_data(ctrl_wdata),
      .ctrl_w_bits_last(1'h1),
      .ctrl_w_bits_id(1'h0),
      .ctrl_w_bits_strb(ctrl_wstrb),
      .ctrl_w_bits_user(1'h0),
      .ctrl_b_ready(ctrl_bready),
      .ctrl_b_valid(ctrl_bvalid),
      .ctrl_b_bits_resp(ctrl_bresp),
      .ctrl_b_bits_id(),
      .ctrl_b_bits_user(),
      .ctrl_ar_ready(ctrl_arready),
      .ctrl_ar_valid(ctrl_arvalid),
      .ctrl_ar_bits_addr(ctrl_araddr[19:0]),
      .ctrl_ar_bits_len(8'h0),
      .ctrl_ar_bits_size(3'h2),
      .ctrl_ar_bits_burst(2'h1),
      .ctrl_ar_bits_lock(1'h0),
      .ctrl_ar_bits_cache(4'h0),
      .ctrl_ar_bits_prot(3'h0),
      .ctrl_ar_bits_qos(4'h0),
      .ctrl_ar_bits_region(4'h0),
      .ctrl_ar_bits_id(1'h0),
      .ctrl_ar_bits_user(1'h0),
      .ctrl_r_ready(ctrl_rready),
      .ctrl_r_valid(ctrl_rvalid),
      .ctrl_r_bits_resp(ctrl_rresp),
      .ctrl_r_bits_data(ctrl_rdata),
      .ctrl_r_bits_last(),
      .ctrl_r_bits_id(),
      .ctrl_r_bits_user(),
      .dma_aw_ready(dma_awready),
      .dma_aw_valid(dma_awvalid),
      .dma_aw_bits_addr(dma_awaddr),
      .dma_aw_bits_len(dma_awlen),
      .dma_aw_bits_size(dma_awsize),
      .dma_aw_bits_burst(dma_awburst),
      .dma_aw_bits_lock(dma_awlock),
      .dma_aw_bits_cache(dma_awcache),
      .dma_aw_bits_prot(dma_awprot),
      .dma_aw_bits_qos(dma_awqos),
      .dma_aw_bits_region(dma_awregion),
      .dma_aw_bits_id(dma_awid),
      .dma_aw_bits_user(1'h0),
      .dma_w_ready(dma_wready),
      .dma_w_valid(dma_wvalid),
      .dma_w_bits_data(dma_wdata),
      .dma_w_bits_last(dma_wlast),
      .dma_w_bits_id(dma_wid),
      .dma_w_bits_strb(dma_wstrb),
      .dma_w_bits_user(1'h0),
      .dma_b_ready(dma_bready),
      .dma_b_valid(dma_bvalid),
      .dma_b_bits_resp(dma_bresp),
      .dma_b_bits_id(dma_bid),
      .dma_b_bits_user(),
      .dma_ar_ready(dma_arready),
      .dma_ar_valid(dma_arvalid),
      .dma_ar_bits_addr(dma_araddr),
      .dma_ar_bits_len(dma_arlen),
      .dma_ar_bits_size(dma_arsize),
      .dma_ar_bits_burst(dma_arburst),
      .dma_ar_bits_lock(dma_arlock),
      .dma_ar_bits_cache(dma_arcache),
      .dma_ar_bits_prot(dma_arprot),
      .dma_ar_bits_qos(dma_arqos),
      .dma_ar_bits_region(dma_arregion),
      .dma_ar_bits_id(dma_arid),
      .dma_ar_bits_user(1'h0),
      .dma_r_ready(dma_rready),
      .dma_r_valid(dma_rvalid),
      .dma_r_bits_resp(dma_rresp),
      .dma_r_bits_data(dma_rdata),
      .dma_r_bits_last(dma_rlast),
      .dma_r_bits_id(dma_rid),
      .dma_r_bits_user(),
      .mem_0_aw_ready(mem_0_awready),
      .mem_0_aw_valid(mem_0_awvalid),
      .mem_0_aw_bits_id(mem_0_awid),
      .mem_0_aw_bits_addr(mem_0_awaddr),
      .mem_0_aw_bits_len(mem_0_awlen),
      .mem_0_aw_bits_size(mem_0_awsize),
      .mem_0_aw_bits_burst(mem_0_awburst),
      .mem_0_aw_bits_lock(mem_0_awlock),
      // BIANCOLIN TODO: Pass this out.
      .mem_0_aw_bits_cache(4'h2),
      .mem_0_aw_bits_prot(mem_0_awprot),
      .mem_0_aw_bits_qos(mem_0_awqos),
      .mem_0_w_ready(mem_0_wready),
      .mem_0_w_valid(mem_0_wvalid),
      .mem_0_w_bits_data(mem_0_wdata),
      .mem_0_w_bits_strb(mem_0_wstrb),
      .mem_0_w_bits_last(mem_0_wlast),
      .mem_0_b_ready(mem_0_bready),
      .mem_0_b_valid(mem_0_bvalid),
      .mem_0_b_bits_id(mem_0_bid),
      .mem_0_b_bits_resp(mem_0_bresp),
      .mem_0_ar_ready(mem_0_arready),
      .mem_0_ar_valid(mem_0_arvalid),
      .mem_0_ar_bits_id(mem_0_arid),
      .mem_0_ar_bits_addr(mem_0_araddr),
      .mem_0_ar_bits_len(mem_0_arlen),
      .mem_0_ar_bits_size(mem_0_arsize),
      .mem_0_ar_bits_burst(mem_0_arburst),
      .mem_0_ar_bits_lock(mem_0_arlock),
      .mem_0_ar_bits_cache(4'h2),
      .mem_0_ar_bits_prot(mem_0_arprot),
      .mem_0_ar_bits_qos(mem_0_arqos),
      .mem_0_r_ready(mem_0_rready),
      .mem_0_r_valid(mem_0_rvalid),
      .mem_0_r_bits_id(mem_0_rid),
      .mem_0_r_bits_data(mem_0_rdata),
      .mem_0_r_bits_resp(mem_0_rresp),
      .mem_0_r_bits_last(mem_0_rlast)
  );
endmodule
