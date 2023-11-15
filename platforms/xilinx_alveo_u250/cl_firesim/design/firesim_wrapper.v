`timescale 1ns/1ps

module firesim_wrapper (
    S_AXI_CTRL_araddr,
    S_AXI_CTRL_arprot,
    S_AXI_CTRL_arready,
    S_AXI_CTRL_arvalid,
    S_AXI_CTRL_awaddr,
    S_AXI_CTRL_awprot,
    S_AXI_CTRL_awready,
    S_AXI_CTRL_awvalid,
    S_AXI_CTRL_bready,
    S_AXI_CTRL_bresp,
    S_AXI_CTRL_bvalid,
    S_AXI_CTRL_rdata,
    S_AXI_CTRL_rready,
    S_AXI_CTRL_rresp,
    S_AXI_CTRL_rvalid,
    S_AXI_CTRL_wdata,
    S_AXI_CTRL_wready,
    S_AXI_CTRL_wstrb,
    S_AXI_CTRL_wvalid,

    S_AXI_DMA_araddr,
    S_AXI_DMA_arburst,
    S_AXI_DMA_arcache,
    S_AXI_DMA_arid,
    S_AXI_DMA_arlen,
    S_AXI_DMA_arlock,
    S_AXI_DMA_arprot,
    S_AXI_DMA_arqos,
    S_AXI_DMA_arready,
    S_AXI_DMA_arregion,
    S_AXI_DMA_arsize,
    S_AXI_DMA_arvalid,
    S_AXI_DMA_awaddr,
    S_AXI_DMA_awburst,
    S_AXI_DMA_awcache,
    S_AXI_DMA_awid,
    S_AXI_DMA_awlen,
    S_AXI_DMA_awlock,
    S_AXI_DMA_awprot,
    S_AXI_DMA_awqos,
    S_AXI_DMA_awready,
    S_AXI_DMA_awregion,
    S_AXI_DMA_awsize,
    S_AXI_DMA_awvalid,
    S_AXI_DMA_bid,
    S_AXI_DMA_bready,
    S_AXI_DMA_bresp,
    S_AXI_DMA_bvalid,
    S_AXI_DMA_rdata,
    S_AXI_DMA_rid,
    S_AXI_DMA_rlast,
    S_AXI_DMA_rready,
    S_AXI_DMA_rresp,
    S_AXI_DMA_rvalid,
    S_AXI_DMA_wdata,
    S_AXI_DMA_wlast,
    S_AXI_DMA_wready,
    S_AXI_DMA_wstrb,
    S_AXI_DMA_wvalid,

    M_AXI_DDR0_araddr,
    M_AXI_DDR0_arburst,
    M_AXI_DDR0_arcache,
    M_AXI_DDR0_arid,
    M_AXI_DDR0_arlen,
    M_AXI_DDR0_arlock,
    M_AXI_DDR0_arprot,
    M_AXI_DDR0_arqos,
    M_AXI_DDR0_arready,
    M_AXI_DDR0_arregion,
    M_AXI_DDR0_arsize,
    M_AXI_DDR0_arvalid,
    M_AXI_DDR0_awaddr,
    M_AXI_DDR0_awburst,
    M_AXI_DDR0_awcache,
    M_AXI_DDR0_awid,
    M_AXI_DDR0_awlen,
    M_AXI_DDR0_awlock,
    M_AXI_DDR0_awprot,
    M_AXI_DDR0_awqos,
    M_AXI_DDR0_awready,
    M_AXI_DDR0_awregion,
    M_AXI_DDR0_awsize,
    M_AXI_DDR0_awvalid,
    M_AXI_DDR0_bready,
    M_AXI_DDR0_bid,
    M_AXI_DDR0_bresp,
    M_AXI_DDR0_bvalid,
    M_AXI_DDR0_rdata,
    M_AXI_DDR0_rid,
    M_AXI_DDR0_rlast,
    M_AXI_DDR0_rready,
    M_AXI_DDR0_rresp,
    M_AXI_DDR0_rvalid,
    M_AXI_DDR0_wdata,
    M_AXI_DDR0_wlast,
    M_AXI_DDR0_wready,
    M_AXI_DDR0_wstrb,
    M_AXI_DDR0_wvalid,

    M_AXI_DDR1_araddr,
    M_AXI_DDR1_arburst,
    M_AXI_DDR1_arcache,
    M_AXI_DDR1_arid,
    M_AXI_DDR1_arlen,
    M_AXI_DDR1_arlock,
    M_AXI_DDR1_arprot,
    M_AXI_DDR1_arqos,
    M_AXI_DDR1_arready,
    M_AXI_DDR1_arregion,
    M_AXI_DDR1_arsize,
    M_AXI_DDR1_arvalid,
    M_AXI_DDR1_awaddr,
    M_AXI_DDR1_awburst,
    M_AXI_DDR1_awcache,
    M_AXI_DDR1_awid,
    M_AXI_DDR1_awlen,
    M_AXI_DDR1_awlock,
    M_AXI_DDR1_awprot,
    M_AXI_DDR1_awqos,
    M_AXI_DDR1_awready,
    M_AXI_DDR1_awregion,
    M_AXI_DDR1_awsize,
    M_AXI_DDR1_awvalid,
    M_AXI_DDR1_bready,
    M_AXI_DDR1_bid,
    M_AXI_DDR1_bresp,
    M_AXI_DDR1_bvalid,
    M_AXI_DDR1_rdata,
    M_AXI_DDR1_rid,
    M_AXI_DDR1_rlast,
    M_AXI_DDR1_rready,
    M_AXI_DDR1_rresp,
    M_AXI_DDR1_rvalid,
    M_AXI_DDR1_wdata,
    M_AXI_DDR1_wlast,
    M_AXI_DDR1_wready,
    M_AXI_DDR1_wstrb,
    M_AXI_DDR1_wvalid,

    M_AXI_DDR2_araddr,
    M_AXI_DDR2_arburst,
    M_AXI_DDR2_arcache,
    M_AXI_DDR2_arid,
    M_AXI_DDR2_arlen,
    M_AXI_DDR2_arlock,
    M_AXI_DDR2_arprot,
    M_AXI_DDR2_arqos,
    M_AXI_DDR2_arready,
    M_AXI_DDR2_arregion,
    M_AXI_DDR2_arsize,
    M_AXI_DDR2_arvalid,
    M_AXI_DDR2_awaddr,
    M_AXI_DDR2_awburst,
    M_AXI_DDR2_awcache,
    M_AXI_DDR2_awid,
    M_AXI_DDR2_awlen,
    M_AXI_DDR2_awlock,
    M_AXI_DDR2_awprot,
    M_AXI_DDR2_awqos,
    M_AXI_DDR2_awready,
    M_AXI_DDR2_awregion,
    M_AXI_DDR2_awsize,
    M_AXI_DDR2_awvalid,
    M_AXI_DDR2_bready,
    M_AXI_DDR2_bid,
    M_AXI_DDR2_bresp,
    M_AXI_DDR2_bvalid,
    M_AXI_DDR2_rdata,
    M_AXI_DDR2_rid,
    M_AXI_DDR2_rlast,
    M_AXI_DDR2_rready,
    M_AXI_DDR2_rresp,
    M_AXI_DDR2_rvalid,
    M_AXI_DDR2_wdata,
    M_AXI_DDR2_wlast,
    M_AXI_DDR2_wready,
    M_AXI_DDR2_wstrb,
    M_AXI_DDR2_wvalid,

    M_AXI_DDR3_araddr,
    M_AXI_DDR3_arburst,
    M_AXI_DDR3_arcache,
    M_AXI_DDR3_arid,
    M_AXI_DDR3_arlen,
    M_AXI_DDR3_arlock,
    M_AXI_DDR3_arprot,
    M_AXI_DDR3_arqos,
    M_AXI_DDR3_arready,
    M_AXI_DDR3_arregion,
    M_AXI_DDR3_arsize,
    M_AXI_DDR3_arvalid,
    M_AXI_DDR3_awaddr,
    M_AXI_DDR3_awburst,
    M_AXI_DDR3_awcache,
    M_AXI_DDR3_awid,
    M_AXI_DDR3_awlen,
    M_AXI_DDR3_awlock,
    M_AXI_DDR3_awprot,
    M_AXI_DDR3_awqos,
    M_AXI_DDR3_awready,
    M_AXI_DDR3_awregion,
    M_AXI_DDR3_awsize,
    M_AXI_DDR3_awvalid,
    M_AXI_DDR3_bready,
    M_AXI_DDR3_bid,
    M_AXI_DDR3_bresp,
    M_AXI_DDR3_bvalid,
    M_AXI_DDR3_rdata,
    M_AXI_DDR3_rid,
    M_AXI_DDR3_rlast,
    M_AXI_DDR3_rready,
    M_AXI_DDR3_rresp,
    M_AXI_DDR3_rvalid,
    M_AXI_DDR3_wdata,
    M_AXI_DDR3_wlast,
    M_AXI_DDR3_wready,
    M_AXI_DDR3_wstrb,
    M_AXI_DDR3_wvalid,

    sys_clk,
    sys_reset_n
);

    input[31:0] S_AXI_CTRL_araddr;
    input[2:0] S_AXI_CTRL_arprot;
    output S_AXI_CTRL_arready;
    input S_AXI_CTRL_arvalid;
    input[31:0] S_AXI_CTRL_awaddr;
    input[2:0] S_AXI_CTRL_awprot;
    output S_AXI_CTRL_awready;
    input S_AXI_CTRL_awvalid;
    input S_AXI_CTRL_bready;
    output[1:0] S_AXI_CTRL_bresp;
    output S_AXI_CTRL_bvalid;
    output[31:0] S_AXI_CTRL_rdata;
    input S_AXI_CTRL_rready;
    output[1:0] S_AXI_CTRL_rresp;
    output S_AXI_CTRL_rvalid;
    input[31:0] S_AXI_CTRL_wdata;
    output S_AXI_CTRL_wready;
    input[3:0] S_AXI_CTRL_wstrb;
    input S_AXI_CTRL_wvalid;
    input[63:0] S_AXI_DMA_araddr;
    input[1:0] S_AXI_DMA_arburst;
    input[3:0] S_AXI_DMA_arcache;
    input[3:0] S_AXI_DMA_arid;
    input[7:0] S_AXI_DMA_arlen;
    input[0:0] S_AXI_DMA_arlock;
    input[2:0] S_AXI_DMA_arprot;
    input[3:0] S_AXI_DMA_arqos;
    output S_AXI_DMA_arready;
    input[3:0] S_AXI_DMA_arregion;
    input[2:0] S_AXI_DMA_arsize;
    input S_AXI_DMA_arvalid;
    input[63:0] S_AXI_DMA_awaddr;
    input[1:0] S_AXI_DMA_awburst;
    input[3:0] S_AXI_DMA_awcache;
    input[3:0] S_AXI_DMA_awid;
    input[7:0] S_AXI_DMA_awlen;
    input[0:0] S_AXI_DMA_awlock;
    input[2:0] S_AXI_DMA_awprot;
    input[3:0] S_AXI_DMA_awqos;
    output S_AXI_DMA_awready;
    input[3:0] S_AXI_DMA_awregion;
    input[2:0] S_AXI_DMA_awsize;
    input S_AXI_DMA_awvalid;
    output[3:0] S_AXI_DMA_bid;
    input S_AXI_DMA_bready;
    output[1:0] S_AXI_DMA_bresp;
    output S_AXI_DMA_bvalid;
    output[511:0] S_AXI_DMA_rdata;
    output[3:0] S_AXI_DMA_rid;
    output S_AXI_DMA_rlast;
    input S_AXI_DMA_rready;
    output[1:0] S_AXI_DMA_rresp;
    output S_AXI_DMA_rvalid;
    input[511:0] S_AXI_DMA_wdata;
    input S_AXI_DMA_wlast;
    output S_AXI_DMA_wready;
    input[63:0] S_AXI_DMA_wstrb;
    input S_AXI_DMA_wvalid;
    output[33:0] M_AXI_DDR0_araddr;
    output[1:0] M_AXI_DDR0_arburst;
    output[3:0] M_AXI_DDR0_arcache;
    output[15:0] M_AXI_DDR0_arid;
    output[7:0] M_AXI_DDR0_arlen;
    output[0:0] M_AXI_DDR0_arlock;
    output[2:0] M_AXI_DDR0_arprot;
    output[3:0] M_AXI_DDR0_arqos;
    input M_AXI_DDR0_arready;
    output[3:0] M_AXI_DDR0_arregion; // TODO: connect
    output[2:0] M_AXI_DDR0_arsize;
    output M_AXI_DDR0_arvalid;
    output[33:0] M_AXI_DDR0_awaddr;
    output[1:0] M_AXI_DDR0_awburst;
    output[3:0] M_AXI_DDR0_awcache;
    output[15:0] M_AXI_DDR0_awid;
    output[7:0] M_AXI_DDR0_awlen;
    output[0:0] M_AXI_DDR0_awlock;
    output[2:0] M_AXI_DDR0_awprot;
    output[3:0] M_AXI_DDR0_awqos;
    input M_AXI_DDR0_awready;
    output[3:0] M_AXI_DDR0_awregion; // TODO: connect
    output[2:0] M_AXI_DDR0_awsize;
    output M_AXI_DDR0_awvalid;
    input[15:0] M_AXI_DDR0_bid;
    output M_AXI_DDR0_bready;
    input[1:0] M_AXI_DDR0_bresp;
    input M_AXI_DDR0_bvalid;
    input[63:0] M_AXI_DDR0_rdata;
    input[15:0] M_AXI_DDR0_rid;
    input M_AXI_DDR0_rlast;
    output M_AXI_DDR0_rready;
    input[1:0] M_AXI_DDR0_rresp;
    input M_AXI_DDR0_rvalid;
    output[63:0] M_AXI_DDR0_wdata;
    output M_AXI_DDR0_wlast;
    input M_AXI_DDR0_wready;
    output[7:0] M_AXI_DDR0_wstrb;
    output M_AXI_DDR0_wvalid;
    output[33:0] M_AXI_DDR1_araddr;
    output[1:0] M_AXI_DDR1_arburst;
    output[3:0] M_AXI_DDR1_arcache;
    output[15:0] M_AXI_DDR1_arid;
    output[7:0] M_AXI_DDR1_arlen;
    output[0:0] M_AXI_DDR1_arlock;
    output[2:0] M_AXI_DDR1_arprot;
    output[3:0] M_AXI_DDR1_arqos;
    input M_AXI_DDR1_arready;
    output[3:0] M_AXI_DDR1_arregion; // TODO: connect
    output[2:0] M_AXI_DDR1_arsize;
    output M_AXI_DDR1_arvalid;
    output[33:0] M_AXI_DDR1_awaddr;
    output[1:0] M_AXI_DDR1_awburst;
    output[3:0] M_AXI_DDR1_awcache;
    output[15:0] M_AXI_DDR1_awid;
    output[7:0] M_AXI_DDR1_awlen;
    output[0:0] M_AXI_DDR1_awlock;
    output[2:0] M_AXI_DDR1_awprot;
    output[3:0] M_AXI_DDR1_awqos;
    input M_AXI_DDR1_awready;
    output[3:0] M_AXI_DDR1_awregion; // TODO: connect
    output[2:0] M_AXI_DDR1_awsize;
    output M_AXI_DDR1_awvalid;
    input[15:0] M_AXI_DDR1_bid;
    output M_AXI_DDR1_bready;
    input[1:0] M_AXI_DDR1_bresp;
    input M_AXI_DDR1_bvalid;
    input[63:0] M_AXI_DDR1_rdata;
    input[15:0] M_AXI_DDR1_rid;
    input M_AXI_DDR1_rlast;
    output M_AXI_DDR1_rready;
    input[1:0] M_AXI_DDR1_rresp;
    input M_AXI_DDR1_rvalid;
    output[63:0] M_AXI_DDR1_wdata;
    output M_AXI_DDR1_wlast;
    input M_AXI_DDR1_wready;
    output[7:0] M_AXI_DDR1_wstrb;
    output M_AXI_DDR1_wvalid;
    output[33:0] M_AXI_DDR2_araddr;
    output[1:0] M_AXI_DDR2_arburst;
    output[3:0] M_AXI_DDR2_arcache;
    output[15:0] M_AXI_DDR2_arid;
    output[7:0] M_AXI_DDR2_arlen;
    output[0:0] M_AXI_DDR2_arlock;
    output[2:0] M_AXI_DDR2_arprot;
    output[3:0] M_AXI_DDR2_arqos;
    input M_AXI_DDR2_arready;
    output[3:0] M_AXI_DDR2_arregion; // TODO: connect
    output[2:0] M_AXI_DDR2_arsize;
    output M_AXI_DDR2_arvalid;
    output[33:0] M_AXI_DDR2_awaddr;
    output[1:0] M_AXI_DDR2_awburst;
    output[3:0] M_AXI_DDR2_awcache;
    output[15:0] M_AXI_DDR2_awid;
    output[7:0] M_AXI_DDR2_awlen;
    output[0:0] M_AXI_DDR2_awlock;
    output[2:0] M_AXI_DDR2_awprot;
    output[3:0] M_AXI_DDR2_awqos;
    input M_AXI_DDR2_awready;
    output[3:0] M_AXI_DDR2_awregion; // TODO: connect
    output[2:0] M_AXI_DDR2_awsize;
    output M_AXI_DDR2_awvalid;
    input[15:0] M_AXI_DDR2_bid;
    output M_AXI_DDR2_bready;
    input[1:0] M_AXI_DDR2_bresp;
    input M_AXI_DDR2_bvalid;
    input[63:0] M_AXI_DDR2_rdata;
    input[15:0] M_AXI_DDR2_rid;
    input M_AXI_DDR2_rlast;
    output M_AXI_DDR2_rready;
    input[1:0] M_AXI_DDR2_rresp;
    input M_AXI_DDR2_rvalid;
    output[63:0] M_AXI_DDR2_wdata;
    output M_AXI_DDR2_wlast;
    input M_AXI_DDR2_wready;
    output[7:0] M_AXI_DDR2_wstrb;
    output M_AXI_DDR2_wvalid;
    output[33:0] M_AXI_DDR3_araddr;
    output[1:0] M_AXI_DDR3_arburst;
    output[3:0] M_AXI_DDR3_arcache;
    output[15:0] M_AXI_DDR3_arid;
    output[7:0] M_AXI_DDR3_arlen;
    output[0:0] M_AXI_DDR3_arlock;
    output[2:0] M_AXI_DDR3_arprot;
    output[3:0] M_AXI_DDR3_arqos;
    input M_AXI_DDR3_arready;
    output[3:0] M_AXI_DDR3_arregion; // TODO: connect
    output[2:0] M_AXI_DDR3_arsize;
    output M_AXI_DDR3_arvalid;
    output[33:0] M_AXI_DDR3_awaddr;
    output[1:0] M_AXI_DDR3_awburst;
    output[3:0] M_AXI_DDR3_awcache;
    output[15:0] M_AXI_DDR3_awid;
    output[7:0] M_AXI_DDR3_awlen;
    output[0:0] M_AXI_DDR3_awlock;
    output[2:0] M_AXI_DDR3_awprot;
    output[3:0] M_AXI_DDR3_awqos;
    input M_AXI_DDR3_awready;
    output[3:0] M_AXI_DDR3_awregion; // TODO: connect
    output[2:0] M_AXI_DDR3_awsize;
    output M_AXI_DDR3_awvalid;
    input[15:0] M_AXI_DDR3_bid;
    output M_AXI_DDR3_bready;
    input[1:0] M_AXI_DDR3_bresp;
    input M_AXI_DDR3_bvalid;
    input[63:0] M_AXI_DDR3_rdata;
    input[15:0] M_AXI_DDR3_rid;
    input M_AXI_DDR3_rlast;
    output M_AXI_DDR3_rready;
    input[1:0] M_AXI_DDR3_rresp;
    input M_AXI_DDR3_rvalid;
    output[63:0] M_AXI_DDR3_wdata;
    output M_AXI_DDR3_wlast;
    input M_AXI_DDR3_wready;
    output[7:0] M_AXI_DDR3_wstrb;
    output M_AXI_DDR3_wvalid;

    input sys_clk;

    input[0:0] sys_reset_n;

    F1Shim firesim_top(
        .clock(sys_clk),
        .reset(!sys_reset_n),

        .io_master_aw_ready(S_AXI_CTRL_awready),
        .io_master_aw_valid(S_AXI_CTRL_awvalid),
        .io_master_aw_bits_addr(S_AXI_CTRL_awaddr[24:0]),
        .io_master_aw_bits_len(8'h0),
        .io_master_aw_bits_size(3'h2),
        .io_master_aw_bits_burst(2'h1),
        .io_master_aw_bits_lock(1'h0),
        .io_master_aw_bits_cache(4'h0),
        .io_master_aw_bits_prot(3'h0), //unused? (could connect?) S_AXI_CTRL_awprot
        .io_master_aw_bits_qos(4'h0),
        .io_master_aw_bits_region(4'h0),
        .io_master_aw_bits_id(12'h0),
        .io_master_aw_bits_user(1'h0),

        .io_master_w_ready(S_AXI_CTRL_wready),
        .io_master_w_valid(S_AXI_CTRL_wvalid),
        .io_master_w_bits_data(S_AXI_CTRL_wdata),
        .io_master_w_bits_last(1'h1),
        .io_master_w_bits_id(12'h0),
        .io_master_w_bits_strb(S_AXI_CTRL_wstrb), //OR 8'hff
        .io_master_w_bits_user(1'h0),

        .io_master_b_ready(S_AXI_CTRL_bready),
        .io_master_b_valid(S_AXI_CTRL_bvalid),
        .io_master_b_bits_resp(S_AXI_CTRL_bresp),
        .io_master_b_bits_id(),      // UNUSED at top level
        .io_master_b_bits_user(),    // UNUSED at top level

        .io_master_ar_ready(S_AXI_CTRL_arready),
        .io_master_ar_valid(S_AXI_CTRL_arvalid),
        .io_master_ar_bits_addr(S_AXI_CTRL_araddr[24:0]),
        .io_master_ar_bits_len(8'h0),
        .io_master_ar_bits_size(3'h2),
        .io_master_ar_bits_burst(2'h1),
        .io_master_ar_bits_lock(1'h0),
        .io_master_ar_bits_cache(4'h0),
        .io_master_ar_bits_prot(3'h0), // S_AXI_CTRL_arprot
        .io_master_ar_bits_qos(4'h0),
        .io_master_ar_bits_region(4'h0),
        .io_master_ar_bits_id(12'h0),
        .io_master_ar_bits_user(1'h0),

        .io_master_r_ready(S_AXI_CTRL_rready),
        .io_master_r_valid(S_AXI_CTRL_rvalid),
        .io_master_r_bits_resp(S_AXI_CTRL_rresp),
        .io_master_r_bits_data(S_AXI_CTRL_rdata),
        .io_master_r_bits_last(), //UNUSED at top level
        .io_master_r_bits_id(),      // UNUSED at top level
        .io_master_r_bits_user(),    // UNUSED at top level

        // special NIC master interface
        .io_dma_aw_ready(S_AXI_DMA_awready),
        .io_dma_aw_valid(S_AXI_DMA_awvalid),
        .io_dma_aw_bits_addr(S_AXI_DMA_awaddr),
        .io_dma_aw_bits_len(S_AXI_DMA_awlen),
        .io_dma_aw_bits_size(S_AXI_DMA_awsize),
        .io_dma_aw_bits_burst(2'h1), // S_AXI_DMA_awburst
        .io_dma_aw_bits_lock(1'h0), // S_AXI_DMA_awlock
        .io_dma_aw_bits_cache(4'h0), // S_AXI_DMA_awcache
        .io_dma_aw_bits_prot(3'h0), //unused? (could connect?) S_AXI_DMA_awprot
        .io_dma_aw_bits_qos(4'h0), // S_AXI_DMA_awqos
        .io_dma_aw_bits_region(4'h0), // S_AXI_DMA_awregion
        .io_dma_aw_bits_id(S_AXI_DMA_awid),
        .io_dma_aw_bits_user(1'h0),

        .io_dma_w_ready(S_AXI_DMA_wready),
        .io_dma_w_valid(S_AXI_DMA_wvalid),
        .io_dma_w_bits_data(S_AXI_DMA_wdata),
        .io_dma_w_bits_last(S_AXI_DMA_wlast),
        .io_dma_w_bits_id(4'h0),
        .io_dma_w_bits_strb(S_AXI_DMA_wstrb),
        .io_dma_w_bits_user(1'h0),

        .io_dma_b_ready(S_AXI_DMA_bready),
        .io_dma_b_valid(S_AXI_DMA_bvalid),
        .io_dma_b_bits_resp(S_AXI_DMA_bresp),
        .io_dma_b_bits_id(S_AXI_DMA_bid),
        .io_dma_b_bits_user(),    // UNUSED at top level

        .io_dma_ar_ready(S_AXI_DMA_arready),
        .io_dma_ar_valid(S_AXI_DMA_arvalid),
        .io_dma_ar_bits_addr(S_AXI_DMA_araddr),
        .io_dma_ar_bits_len(S_AXI_DMA_arlen),
        .io_dma_ar_bits_size(S_AXI_DMA_arsize),
        .io_dma_ar_bits_burst(2'h1), // S_AXI_DMA_arburst
        .io_dma_ar_bits_lock(1'h0), // S_AXI_DMA_arlock
        .io_dma_ar_bits_cache(4'h0), // S_AXI_DMA_arcache
        .io_dma_ar_bits_prot(3'h0), // S_AXI_DMA_arprot
        .io_dma_ar_bits_qos(4'h0), // S_AXI_DMA_arqos
        .io_dma_ar_bits_region(4'h0), // S_AXI_DMA_arregion
        .io_dma_ar_bits_id(S_AXI_DMA_arid),
        .io_dma_ar_bits_user(1'h0),

        .io_dma_r_ready(S_AXI_DMA_rready),
        .io_dma_r_valid(S_AXI_DMA_rvalid),
        .io_dma_r_bits_resp(S_AXI_DMA_rresp),
        .io_dma_r_bits_data(S_AXI_DMA_rdata),
        .io_dma_r_bits_last(S_AXI_DMA_rlast),
        .io_dma_r_bits_id(S_AXI_DMA_rid),
        .io_dma_r_bits_user(),    // UNUSED at top level

        // `include "firesim_ila_insert_ports.v"

        .io_slave_0_aw_ready(M_AXI_DDR0_awready),
        .io_slave_0_aw_valid(M_AXI_DDR0_awvalid),
        .io_slave_0_aw_bits_addr(M_AXI_DDR0_awaddr),
        .io_slave_0_aw_bits_len(M_AXI_DDR0_awlen),
        .io_slave_0_aw_bits_size(M_AXI_DDR0_awsize),
        .io_slave_0_aw_bits_burst(M_AXI_DDR0_awburst), // not available on DDR IF
        .io_slave_0_aw_bits_lock(M_AXI_DDR0_awlock), // not available on DDR IF
        .io_slave_0_aw_bits_cache(M_AXI_DDR0_awcache), // not available on DDR IF
        .io_slave_0_aw_bits_prot(M_AXI_DDR0_awprot), // not available on DDR IF
        .io_slave_0_aw_bits_qos(M_AXI_DDR0_awqos), // not available on DDR IF
        .io_slave_0_aw_bits_id(M_AXI_DDR0_awid),

        .io_slave_0_w_ready(M_AXI_DDR0_wready),
        .io_slave_0_w_valid(M_AXI_DDR0_wvalid),
        .io_slave_0_w_bits_data(M_AXI_DDR0_wdata),
        .io_slave_0_w_bits_last(M_AXI_DDR0_wlast),
        .io_slave_0_w_bits_strb(M_AXI_DDR0_wstrb),

        .io_slave_0_b_ready(M_AXI_DDR0_bready),
        .io_slave_0_b_valid(M_AXI_DDR0_bvalid),
        .io_slave_0_b_bits_resp(M_AXI_DDR0_bresp),
        .io_slave_0_b_bits_id(M_AXI_DDR0_bid),

        .io_slave_0_ar_ready(M_AXI_DDR0_arready),
        .io_slave_0_ar_valid(M_AXI_DDR0_arvalid),
        .io_slave_0_ar_bits_addr(M_AXI_DDR0_araddr),
        .io_slave_0_ar_bits_len(M_AXI_DDR0_arlen),
        .io_slave_0_ar_bits_size(M_AXI_DDR0_arsize),
        .io_slave_0_ar_bits_burst(M_AXI_DDR0_arburst), // not available on DDR IF
        .io_slave_0_ar_bits_lock(M_AXI_DDR0_arlock), // not available on DDR IF
        .io_slave_0_ar_bits_cache(M_AXI_DDR0_arcache), // not available on DDR IF
        .io_slave_0_ar_bits_prot(M_AXI_DDR0_arprot), // not available on DDR IF
        .io_slave_0_ar_bits_qos(M_AXI_DDR0_arqos), // not available on DDR IF
        .io_slave_0_ar_bits_id(M_AXI_DDR0_arid), // not available on DDR IF

        .io_slave_0_r_ready(M_AXI_DDR0_rready),
        .io_slave_0_r_valid(M_AXI_DDR0_rvalid),
        .io_slave_0_r_bits_resp(M_AXI_DDR0_rresp),
        .io_slave_0_r_bits_data(M_AXI_DDR0_rdata),
        .io_slave_0_r_bits_last(M_AXI_DDR0_rlast),
        .io_slave_0_r_bits_id(M_AXI_DDR0_rid),

        .io_slave_1_aw_ready(M_AXI_DDR1_awready),
        .io_slave_1_aw_valid(M_AXI_DDR1_awvalid),
        .io_slave_1_aw_bits_addr(M_AXI_DDR1_awaddr),
        .io_slave_1_aw_bits_len(M_AXI_DDR1_awlen),
        .io_slave_1_aw_bits_size(M_AXI_DDR1_awsize),
        .io_slave_1_aw_bits_burst(M_AXI_DDR1_awburst), // not available on DDR IF
        .io_slave_1_aw_bits_lock(M_AXI_DDR1_awlock), // not available on DDR IF
        .io_slave_1_aw_bits_cache(M_AXI_DDR1_awcache), // not available on DDR IF
        .io_slave_1_aw_bits_prot(M_AXI_DDR1_awprot), // not available on DDR IF
        .io_slave_1_aw_bits_qos(M_AXI_DDR1_awqos), // not available on DDR IF
        .io_slave_1_aw_bits_id(M_AXI_DDR1_awid),

        .io_slave_1_w_ready(M_AXI_DDR1_wready),
        .io_slave_1_w_valid(M_AXI_DDR1_wvalid),
        .io_slave_1_w_bits_data(M_AXI_DDR1_wdata),
        .io_slave_1_w_bits_last(M_AXI_DDR1_wlast),
        .io_slave_1_w_bits_strb(M_AXI_DDR1_wstrb),

        .io_slave_1_b_ready(M_AXI_DDR1_bready),
        .io_slave_1_b_valid(M_AXI_DDR1_bvalid),
        .io_slave_1_b_bits_resp(M_AXI_DDR1_bresp),
        .io_slave_1_b_bits_id(M_AXI_DDR1_bid),

        .io_slave_1_ar_ready(M_AXI_DDR1_arready),
        .io_slave_1_ar_valid(M_AXI_DDR1_arvalid),
        .io_slave_1_ar_bits_addr(M_AXI_DDR1_araddr),
        .io_slave_1_ar_bits_len(M_AXI_DDR1_arlen),
        .io_slave_1_ar_bits_size(M_AXI_DDR1_arsize),
        .io_slave_1_ar_bits_burst(M_AXI_DDR1_arburst), // not available on DDR IF
        .io_slave_1_ar_bits_lock(M_AXI_DDR1_arlock), // not available on DDR IF
        .io_slave_1_ar_bits_cache(M_AXI_DDR1_arcache), // not available on DDR IF
        .io_slave_1_ar_bits_prot(M_AXI_DDR1_arprot), // not available on DDR IF
        .io_slave_1_ar_bits_qos(M_AXI_DDR1_arqos), // not available on DDR IF
        .io_slave_1_ar_bits_id(M_AXI_DDR1_arid), // not available on DDR IF

        .io_slave_1_r_ready(M_AXI_DDR1_rready),
        .io_slave_1_r_valid(M_AXI_DDR1_rvalid),
        .io_slave_1_r_bits_resp(M_AXI_DDR1_rresp),
        .io_slave_1_r_bits_data(M_AXI_DDR1_rdata),
        .io_slave_1_r_bits_last(M_AXI_DDR1_rlast),
        .io_slave_1_r_bits_id(M_AXI_DDR1_rid),

        .io_slave_2_aw_ready(M_AXI_DDR2_awready),
        .io_slave_2_aw_valid(M_AXI_DDR2_awvalid),
        .io_slave_2_aw_bits_addr(M_AXI_DDR2_awaddr),
        .io_slave_2_aw_bits_len(M_AXI_DDR2_awlen),
        .io_slave_2_aw_bits_size(M_AXI_DDR2_awsize),
        .io_slave_2_aw_bits_burst(M_AXI_DDR2_awburst), // not available on DDR IF
        .io_slave_2_aw_bits_lock(M_AXI_DDR2_awlock), // not available on DDR IF
        .io_slave_2_aw_bits_cache(M_AXI_DDR2_awcache), // not available on DDR IF
        .io_slave_2_aw_bits_prot(M_AXI_DDR2_awprot), // not available on DDR IF
        .io_slave_2_aw_bits_qos(M_AXI_DDR2_awqos), // not available on DDR IF
        .io_slave_2_aw_bits_id(M_AXI_DDR2_awid),

        .io_slave_2_w_ready(M_AXI_DDR2_wready),
        .io_slave_2_w_valid(M_AXI_DDR2_wvalid),
        .io_slave_2_w_bits_data(M_AXI_DDR2_wdata),
        .io_slave_2_w_bits_last(M_AXI_DDR2_wlast),
        .io_slave_2_w_bits_strb(M_AXI_DDR2_wstrb),

        .io_slave_2_b_ready(M_AXI_DDR2_bready),
        .io_slave_2_b_valid(M_AXI_DDR2_bvalid),
        .io_slave_2_b_bits_resp(M_AXI_DDR2_bresp),
        .io_slave_2_b_bits_id(M_AXI_DDR2_bid),

        .io_slave_2_ar_ready(M_AXI_DDR2_arready),
        .io_slave_2_ar_valid(M_AXI_DDR2_arvalid),
        .io_slave_2_ar_bits_addr(M_AXI_DDR2_araddr),
        .io_slave_2_ar_bits_len(M_AXI_DDR2_arlen),
        .io_slave_2_ar_bits_size(M_AXI_DDR2_arsize),
        .io_slave_2_ar_bits_burst(M_AXI_DDR2_arburst), // not available on DDR IF
        .io_slave_2_ar_bits_lock(M_AXI_DDR2_arlock), // not available on DDR IF
        .io_slave_2_ar_bits_cache(M_AXI_DDR2_arcache), // not available on DDR IF
        .io_slave_2_ar_bits_prot(M_AXI_DDR2_arprot), // not available on DDR IF
        .io_slave_2_ar_bits_qos(M_AXI_DDR2_arqos), // not available on DDR IF
        .io_slave_2_ar_bits_id(M_AXI_DDR2_arid), // not available on DDR IF

        .io_slave_2_r_ready(M_AXI_DDR2_rready),
        .io_slave_2_r_valid(M_AXI_DDR2_rvalid),
        .io_slave_2_r_bits_resp(M_AXI_DDR2_rresp),
        .io_slave_2_r_bits_data(M_AXI_DDR2_rdata),
        .io_slave_2_r_bits_last(M_AXI_DDR2_rlast),
        .io_slave_2_r_bits_id(M_AXI_DDR2_rid),

        .io_slave_3_aw_ready(M_AXI_DDR3_awready),
        .io_slave_3_aw_valid(M_AXI_DDR3_awvalid),
        .io_slave_3_aw_bits_addr(M_AXI_DDR3_awaddr),
        .io_slave_3_aw_bits_len(M_AXI_DDR3_awlen),
        .io_slave_3_aw_bits_size(M_AXI_DDR3_awsize),
        .io_slave_3_aw_bits_burst(M_AXI_DDR3_awburst), // not available on DDR IF
        .io_slave_3_aw_bits_lock(M_AXI_DDR3_awlock), // not available on DDR IF
        .io_slave_3_aw_bits_cache(M_AXI_DDR3_awcache), // not available on DDR IF
        .io_slave_3_aw_bits_prot(M_AXI_DDR3_awprot), // not available on DDR IF
        .io_slave_3_aw_bits_qos(M_AXI_DDR3_awqos), // not available on DDR IF
        .io_slave_3_aw_bits_id(M_AXI_DDR3_awid),

        .io_slave_3_w_ready(M_AXI_DDR3_wready),
        .io_slave_3_w_valid(M_AXI_DDR3_wvalid),
        .io_slave_3_w_bits_data(M_AXI_DDR3_wdata),
        .io_slave_3_w_bits_last(M_AXI_DDR3_wlast),
        .io_slave_3_w_bits_strb(M_AXI_DDR3_wstrb),

        .io_slave_3_b_ready(M_AXI_DDR3_bready),
        .io_slave_3_b_valid(M_AXI_DDR3_bvalid),
        .io_slave_3_b_bits_resp(M_AXI_DDR3_bresp),
        .io_slave_3_b_bits_id(M_AXI_DDR3_bid),

        .io_slave_3_ar_ready(M_AXI_DDR3_arready),
        .io_slave_3_ar_valid(M_AXI_DDR3_arvalid),
        .io_slave_3_ar_bits_addr(M_AXI_DDR3_araddr),
        .io_slave_3_ar_bits_len(M_AXI_DDR3_arlen),
        .io_slave_3_ar_bits_size(M_AXI_DDR3_arsize),
        .io_slave_3_ar_bits_burst(M_AXI_DDR3_arburst), // not available on DDR IF
        .io_slave_3_ar_bits_lock(M_AXI_DDR3_arlock), // not available on DDR IF
        .io_slave_3_ar_bits_cache(M_AXI_DDR3_arcache), // not available on DDR IF
        .io_slave_3_ar_bits_prot(M_AXI_DDR3_arprot), // not available on DDR IF
        .io_slave_3_ar_bits_qos(M_AXI_DDR3_arqos), // not available on DDR IF
        .io_slave_3_ar_bits_id(M_AXI_DDR3_arid), // not available on DDR IF

        .io_slave_3_r_ready(M_AXI_DDR3_rready),
        .io_slave_3_r_valid(M_AXI_DDR3_rvalid),
        .io_slave_3_r_bits_resp(M_AXI_DDR3_rresp),
        .io_slave_3_r_bits_data(M_AXI_DDR3_rdata),
        .io_slave_3_r_bits_last(M_AXI_DDR3_rlast),
        .io_slave_3_r_bits_id(M_AXI_DDR3_rid)
    );
endmodule : firesim_wrapper
