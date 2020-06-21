`timescale 1 ps / 1 ps
`include "clocking.vh"

module midas_wrapper
   (DDR_addr,
    DDR_ba,
    DDR_cas_n,
    DDR_ck_n,
    DDR_ck_p,
    DDR_cke,
    DDR_cs_n,
    DDR_dm,
    DDR_dq,
    DDR_dqs_n,
    DDR_dqs_p,
    DDR_odt,
    DDR_ras_n,
    DDR_reset_n,
    DDR_we_n,
    DDR3_SODIMM_addr,
    DDR3_SODIMM_ba,
    DDR3_SODIMM_cas_n,
    DDR3_SODIMM_ck_n,
    DDR3_SODIMM_ck_p,
    DDR3_SODIMM_cke,
    DDR3_SODIMM_cs_n,
    DDR3_SODIMM_dm,
    DDR3_SODIMM_dq,
    DDR3_SODIMM_dqs_n,
    DDR3_SODIMM_dqs_p,
    DDR3_SODIMM_odt,
    DDR3_SODIMM_ras_n,
    DDR3_SODIMM_reset_n,
    DDR3_SODIMM_we_n,
    FIXED_IO_ddr_vrn,
    FIXED_IO_ddr_vrp,
    FIXED_IO_mio,
    FIXED_IO_ps_clk,
    FIXED_IO_ps_porb,
    FIXED_IO_ps_srstb,
`ifndef differential_clock
    clk);
`else
    SYSCLK_P,
    SYSCLK_N);
`endif

  inout [14:0]DDR_addr;
  inout [2:0]DDR_ba;
  inout DDR_cas_n;
  inout DDR_ck_n;
  inout DDR_ck_p;
  inout DDR_cke;
  inout DDR_cs_n;
  inout [3:0]DDR_dm;
  inout [31:0]DDR_dq;
  inout [3:0]DDR_dqs_n;
  inout [3:0]DDR_dqs_p;
  inout DDR_odt;
  inout DDR_ras_n;
  inout DDR_reset_n;
  inout DDR_we_n;

  inout FIXED_IO_ddr_vrn;
  inout FIXED_IO_ddr_vrp;
  inout [53:0]FIXED_IO_mio;
  inout FIXED_IO_ps_clk;
  inout FIXED_IO_ps_porb;
  inout FIXED_IO_ps_srstb;

  output [13:0]DDR3_SODIMM_addr;
  output [2:0]DDR3_SODIMM_ba;
  output DDR3_SODIMM_cas_n;
  output [0:0]DDR3_SODIMM_ck_n;
  output [0:0]DDR3_SODIMM_ck_p;
  output [0:0]DDR3_SODIMM_cke;
  output [0:0]DDR3_SODIMM_cs_n;
  output [7:0]DDR3_SODIMM_dm;
  inout [63:0]DDR3_SODIMM_dq;
  inout [7:0]DDR3_SODIMM_dqs_n;
  inout [7:0]DDR3_SODIMM_dqs_p;
  output [0:0]DDR3_SODIMM_odt;
  output DDR3_SODIMM_ras_n;
  output DDR3_SODIMM_reset_n;
  output DDR3_SODIMM_we_n;

`ifndef differential_clock
  input clk;
`else
  input SYSCLK_P;
  input SYSCLK_N;
`endif

  wire FCLK_RESET0_N;
  
  wire [31:0]M_AXI_araddr;
  wire [1:0]M_AXI_arburst;
  wire [7:0]M_AXI_arlen;
  wire M_AXI_arready;
  wire [2:0]M_AXI_arsize;
  wire M_AXI_arvalid;
  wire [31:0]M_AXI_awaddr;
  wire [1:0]M_AXI_awburst;
  wire [7:0]M_AXI_awlen;
  wire [3:0]M_AXI_wstrb;
  wire M_AXI_awready;
  wire [2:0]M_AXI_awsize;
  wire M_AXI_awvalid;
  wire M_AXI_bready;
  wire M_AXI_bvalid;
  wire [1:0] M_AXI_bresp;
  wire [31:0]M_AXI_rdata;
  wire M_AXI_rlast;
  wire M_AXI_rready;
  wire M_AXI_rvalid;
  wire [1:0] M_AXI_rresp;
  wire [31:0]M_AXI_wdata;
  wire M_AXI_wlast;
  wire M_AXI_wready;
  wire M_AXI_wvalid;
  wire [11:0] M_AXI_arid, M_AXI_awid; // outputs from ARM core
  wire [11:0] M_AXI_bid, M_AXI_rid;   // inputs to ARM core

  wire S_AXI_arready;
  wire S_AXI_arvalid;
  wire [31:0] S_AXI_araddr;
  wire [5:0]  S_AXI_arid;
  wire [2:0]  S_AXI_arsize;
  wire [7:0]  S_AXI_arlen;
  wire [1:0]  S_AXI_arburst;
  wire S_AXI_arlock;
  wire [3:0]  S_AXI_arcache;
  wire [2:0]  S_AXI_arprot;
  wire [3:0]  S_AXI_arqos;

  wire S_AXI_awready;
  wire S_AXI_awvalid;
  wire [31:0] S_AXI_awaddr;
  wire [5:0]  S_AXI_awid;
  wire [2:0]  S_AXI_awsize;
  wire [7:0]  S_AXI_awlen;
  wire [1:0]  S_AXI_awburst;
  wire S_AXI_awlock;
  wire [3:0]  S_AXI_awcache;
  wire [2:0]  S_AXI_awprot;
  wire [3:0]  S_AXI_awqos;

  wire S_AXI_wready;
  wire S_AXI_wvalid;
  wire [7:0]  S_AXI_wstrb;
  wire [63:0] S_AXI_wdata;
  wire S_AXI_wlast;

  wire S_AXI_bready;
  wire S_AXI_bvalid;
  wire [1:0] S_AXI_bresp;
  wire [5:0] S_AXI_bid;

  wire S_AXI_rready;
  wire S_AXI_rvalid;
  wire [1:0]  S_AXI_rresp;
  wire [5:0]  S_AXI_rid;
  wire [63:0] S_AXI_rdata;
  wire S_AXI_rlast;

  wire reset, reset_cpu;
  wire host_clk;
  wire gclk_i, gclk_fbout, host_clk_i, mmcm_locked;

  system system_i
       (.DDR_addr(DDR_addr),
        .DDR_ba(DDR_ba),
        .DDR_cas_n(DDR_cas_n),
        .DDR_ck_n(DDR_ck_n),
        .DDR_ck_p(DDR_ck_p),
        .DDR_cke(DDR_cke),
        .DDR_cs_n(DDR_cs_n),
        .DDR_dm(DDR_dm),
        .DDR_dq(DDR_dq),
        .DDR_dqs_n(DDR_dqs_n),
        .DDR_dqs_p(DDR_dqs_p),
        .DDR_odt(DDR_odt),
        .DDR_ras_n(DDR_ras_n),
        .DDR_reset_n(DDR_reset_n),
        .DDR_we_n(DDR_we_n),
        .FCLK_RESET0_N(FCLK_RESET0_N),
        .FIXED_IO_ddr_vrn(FIXED_IO_ddr_vrn),
        .FIXED_IO_ddr_vrp(FIXED_IO_ddr_vrp),
        .FIXED_IO_mio(FIXED_IO_mio),
        .FIXED_IO_ps_clk(FIXED_IO_ps_clk),
        .FIXED_IO_ps_porb(FIXED_IO_ps_porb),
        .FIXED_IO_ps_srstb(FIXED_IO_ps_srstb),
        // master AXI interface (zynq = master, fpga = slave)
        .M_AXI_araddr(M_AXI_araddr),
        .M_AXI_arburst(M_AXI_arburst), // burst type
        .M_AXI_arcache(),
        .M_AXI_arid(M_AXI_arid),
        .M_AXI_arlen(M_AXI_arlen), // burst length (#transfers)
        .M_AXI_arlock(),
        .M_AXI_arprot(),
        .M_AXI_arqos(),
        .M_AXI_arready(M_AXI_arready),
        .M_AXI_arregion(),
        .M_AXI_arsize(M_AXI_arsize), // burst size (bits/transfer)
        .M_AXI_arvalid(M_AXI_arvalid),
        //
        .M_AXI_awaddr(M_AXI_awaddr),
        .M_AXI_awburst(M_AXI_awburst),
        .M_AXI_awcache(),
        .M_AXI_awid(M_AXI_awid),
        .M_AXI_awlen(M_AXI_awlen),
        .M_AXI_awlock(),
        .M_AXI_awprot(),
        .M_AXI_awqos(),
        .M_AXI_awready(M_AXI_awready),
        .M_AXI_awregion(),
        .M_AXI_awsize(M_AXI_awsize),
        .M_AXI_awvalid(M_AXI_awvalid),
        //
        .M_AXI_bid(M_AXI_bid),
        .M_AXI_bready(M_AXI_bready),
        .M_AXI_bresp(M_AXI_bresp),
        .M_AXI_bvalid(M_AXI_bvalid),
        //
        .M_AXI_rdata(M_AXI_rdata),
        .M_AXI_rid(M_AXI_rid),
        .M_AXI_rlast(M_AXI_rlast),
        .M_AXI_rready(M_AXI_rready),
        .M_AXI_rresp(M_AXI_rresp),
        .M_AXI_rvalid(M_AXI_rvalid),
        //
        .M_AXI_wdata(M_AXI_wdata),
        .M_AXI_wlast(M_AXI_wlast),
        .M_AXI_wready(M_AXI_wready),
        .M_AXI_wstrb(M_AXI_wstrb),
        .M_AXI_wvalid(M_AXI_wvalid),

        // slave AXI interface (fpga = master, zynq = slave) 
        // connected directly to DDR controller to handle test chip mem
        .S_AXI_araddr(S_AXI_araddr),
        .S_AXI_arburst(S_AXI_arburst),
        .S_AXI_arcache(S_AXI_arcache),
        .S_AXI_arid(S_AXI_arid),
        .S_AXI_arlen(S_AXI_arlen),
        .S_AXI_arlock(S_AXI_arlock),
        .S_AXI_arprot(S_AXI_arprot),
        .S_AXI_arqos(S_AXI_arqos),
        .S_AXI_arready(S_AXI_arready),
        .S_AXI_arregion(4'b0),
        .S_AXI_arsize(S_AXI_arsize),
        .S_AXI_arvalid(S_AXI_arvalid),
        //
        .S_AXI_awaddr(S_AXI_awaddr),
        .S_AXI_awburst(S_AXI_awburst),
        .S_AXI_awcache(S_AXI_awcache),
        .S_AXI_awid(S_AXI_awid),
        .S_AXI_awlen(S_AXI_awlen),
        .S_AXI_awlock(S_AXI_awlock),
        .S_AXI_awprot(S_AXI_awprot),
        .S_AXI_awqos(S_AXI_awqos),
        .S_AXI_awready(S_AXI_awready),
        .S_AXI_awregion(4'b0),
        .S_AXI_awsize(S_AXI_awsize),
        .S_AXI_awvalid(S_AXI_awvalid),
        //
        .S_AXI_bid(S_AXI_bid),
        .S_AXI_bready(S_AXI_bready),
        .S_AXI_bresp(S_AXI_bresp),
        .S_AXI_bvalid(S_AXI_bvalid),
        //
        .S_AXI_rid(S_AXI_rid),
        .S_AXI_rdata(S_AXI_rdata),
        .S_AXI_rlast(S_AXI_rlast),
        .S_AXI_rready(S_AXI_rready),
        .S_AXI_rresp(S_AXI_rresp),
        .S_AXI_rvalid(S_AXI_rvalid),
        //
        .S_AXI_wdata(S_AXI_wdata),
        .S_AXI_wlast(S_AXI_wlast),
        .S_AXI_wready(S_AXI_wready),
        .S_AXI_wstrb(S_AXI_wstrb),
        .S_AXI_wvalid(S_AXI_wvalid),

        .DDR3_SODIMM_addr(DDR3_SODIMM_addr),
        .DDR3_SODIMM_ba(DDR3_SODIMM_ba),
        .DDR3_SODIMM_cas_n(DDR3_SODIMM_cas_n),
        .DDR3_SODIMM_ck_n(DDR3_SODIMM_ck_n),
        .DDR3_SODIMM_ck_p(DDR3_SODIMM_ck_p),
        .DDR3_SODIMM_cke(DDR3_SODIMM_cke),
        .DDR3_SODIMM_cs_n(DDR3_SODIMM_cs_n),
        .DDR3_SODIMM_dm(DDR3_SODIMM_dm),
        .DDR3_SODIMM_dq(DDR3_SODIMM_dq),
        .DDR3_SODIMM_dqs_n(DDR3_SODIMM_dqs_n),
        .DDR3_SODIMM_dqs_p(DDR3_SODIMM_dqs_p),
        .DDR3_SODIMM_odt(DDR3_SODIMM_odt),
        .DDR3_SODIMM_ras_n(DDR3_SODIMM_ras_n),
        .DDR3_SODIMM_reset_n(DDR3_SODIMM_reset_n),
        .DDR3_SODIMM_we_n(DDR3_SODIMM_we_n),

        .ext_clk_in(host_clk),
        .gclk_i(gclk_i)
        );

  assign reset = !FCLK_RESET0_N || !mmcm_locked;

  ZynqShim top(
   .clock(host_clk),
   .reset(reset),

   .io_master_aw_ready (M_AXI_awready),
   .io_master_aw_valid (M_AXI_awvalid),
   .io_master_aw_bits_addr (M_AXI_awaddr),
   .io_master_aw_bits_len (M_AXI_awlen),
   .io_master_aw_bits_size (M_AXI_awsize),
   .io_master_aw_bits_burst (M_AXI_awburst),
   .io_master_aw_bits_id (M_AXI_awid),
   .io_master_aw_bits_lock (),
   .io_master_aw_bits_prot (),
   .io_master_aw_bits_qos (),
   .io_master_aw_bits_region (),

   .io_master_ar_ready (M_AXI_arready),
   .io_master_ar_valid (M_AXI_arvalid),
   .io_master_ar_bits_addr (M_AXI_araddr),
   .io_master_ar_bits_len (M_AXI_arlen),
   .io_master_ar_bits_size (M_AXI_arsize),
   .io_master_ar_bits_burst (M_AXI_arburst),
   .io_master_ar_bits_id (M_AXI_arid),
   .io_master_ar_bits_lock (),
   .io_master_ar_bits_prot (),
   .io_master_ar_bits_qos (),
   .io_master_ar_bits_region (),

   .io_master_w_valid (M_AXI_wvalid),
   .io_master_w_ready (M_AXI_wready),
   .io_master_w_bits_data (M_AXI_wdata),
   .io_master_w_bits_strb (M_AXI_wstrb),
   .io_master_w_bits_last (M_AXI_wlast),

   .io_master_r_valid (M_AXI_rvalid),
   .io_master_r_ready (M_AXI_rready),
   .io_master_r_bits_id (M_AXI_rid),
   .io_master_r_bits_resp (M_AXI_rresp),
   .io_master_r_bits_data (M_AXI_rdata),
   .io_master_r_bits_last (M_AXI_rlast),

   .io_master_b_valid (M_AXI_bvalid),
   .io_master_b_ready (M_AXI_bready),
   .io_master_b_bits_id (M_AXI_bid),
   .io_master_b_bits_resp (M_AXI_bresp),

   .io_slave_ar_valid (S_AXI_arvalid),
   .io_slave_ar_ready (S_AXI_arready),
   .io_slave_ar_bits_addr (S_AXI_araddr),
   .io_slave_ar_bits_id (S_AXI_arid),
   .io_slave_ar_bits_size (S_AXI_arsize),
   .io_slave_ar_bits_len (S_AXI_arlen),
   .io_slave_ar_bits_burst (S_AXI_arburst),
   .io_slave_ar_bits_cache (S_AXI_arcache),
   .io_slave_ar_bits_lock (S_AXI_arlock),
   .io_slave_ar_bits_prot (S_AXI_arprot),
   .io_slave_ar_bits_qos (S_AXI_arqos),
   .io_slave_aw_valid (S_AXI_awvalid),
   .io_slave_aw_ready (S_AXI_awready),
   .io_slave_aw_bits_addr (S_AXI_awaddr),
   .io_slave_aw_bits_id (S_AXI_awid),
   .io_slave_aw_bits_size (S_AXI_awsize),
   .io_slave_aw_bits_len (S_AXI_awlen),
   .io_slave_aw_bits_burst (S_AXI_awburst),
   .io_slave_aw_bits_cache (S_AXI_awcache),
   .io_slave_aw_bits_lock (S_AXI_awlock),
   .io_slave_aw_bits_prot (S_AXI_awprot),
   .io_slave_aw_bits_qos (S_AXI_awqos),
   .io_slave_w_valid (S_AXI_wvalid),
   .io_slave_w_ready (S_AXI_wready),
   .io_slave_w_bits_strb (S_AXI_wstrb),
   .io_slave_w_bits_data (S_AXI_wdata),
   .io_slave_w_bits_last (S_AXI_wlast),
   .io_slave_b_valid (S_AXI_bvalid),
   .io_slave_b_ready (S_AXI_bready),
   .io_slave_b_bits_resp (S_AXI_bresp),
   .io_slave_b_bits_id (S_AXI_bid),
   .io_slave_r_valid (S_AXI_rvalid),
   .io_slave_r_ready (S_AXI_rready),
   .io_slave_r_bits_resp (S_AXI_rresp),
   .io_slave_r_bits_id (S_AXI_rid),
   .io_slave_r_bits_data (S_AXI_rdata),
   .io_slave_r_bits_last (S_AXI_rlast)
  );
`ifndef differential_clock
  IBUFG ibufg_gclk (.I(clk), .O(gclk_i));
`else
  IBUFDS #(.DIFF_TERM("TRUE"), .IBUF_LOW_PWR("TRUE"), .IOSTANDARD("DEFAULT")) clk_ibufds (.O(gclk_i), .I(SYSCLK_P), .IB(SYSCLK_N));
`endif
  BUFG  bufg_host_clk (.I(host_clk_i), .O(host_clk));

  MMCME2_BASE #(
    .BANDWIDTH("OPTIMIZED"),
    .CLKFBOUT_MULT_F(`RC_CLK_MULT),
    .CLKFBOUT_PHASE(0.0),
    .CLKIN1_PERIOD(`ZYNQ_CLK_PERIOD),
    .CLKOUT1_DIVIDE(1),
    .CLKOUT2_DIVIDE(1),
    .CLKOUT3_DIVIDE(1),
    .CLKOUT4_DIVIDE(1),
    .CLKOUT5_DIVIDE(1),
    .CLKOUT6_DIVIDE(1),
    .CLKOUT0_DIVIDE_F(`RC_CLK_DIVIDE),
    .CLKOUT0_DUTY_CYCLE(0.5),
    .CLKOUT1_DUTY_CYCLE(0.5),
    .CLKOUT2_DUTY_CYCLE(0.5),
    .CLKOUT3_DUTY_CYCLE(0.5),
    .CLKOUT4_DUTY_CYCLE(0.5),
    .CLKOUT5_DUTY_CYCLE(0.5),
    .CLKOUT6_DUTY_CYCLE(0.5),
    .CLKOUT0_PHASE(0.0),
    .CLKOUT1_PHASE(0.0),
    .CLKOUT2_PHASE(0.0),
    .CLKOUT3_PHASE(0.0),
    .CLKOUT4_PHASE(0.0),
    .CLKOUT5_PHASE(0.0),
    .CLKOUT6_PHASE(0.0),
    .CLKOUT4_CASCADE("FALSE"),
    .DIVCLK_DIVIDE(1),
    .REF_JITTER1(0.0),
    .STARTUP_WAIT("FALSE")
  ) MMCME2_BASE_inst (
    .CLKOUT0(host_clk_i),
    .CLKOUT0B(),
    .CLKOUT1(),
    .CLKOUT1B(),
    .CLKOUT2(),
    .CLKOUT2B(),
    .CLKOUT3(),
    .CLKOUT3B(),
    .CLKOUT4(),
    .CLKOUT5(),
    .CLKOUT6(),
    .CLKFBOUT(gclk_fbout),
    .CLKFBOUTB(),
    .LOCKED(mmcm_locked),
    .CLKIN1(gclk_i),
    .PWRDWN(1'b0),
    .RST(1'b0),
    .CLKFBIN(gclk_fbout));

endmodule
