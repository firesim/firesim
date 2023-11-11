`timescale 1ns/1ps
//////////////////////////////////////////////////////////////////////////////////
// Company:
// Engineer:
//
// Create Date: 09/10/2021 07:33:50 PM
// Design Name:
// Module Name: firesim_wrapper
// Project Name:
// Target Devices:
// Tool Versions:
// Description:
//
// Dependencies:
//
// Revision:
// Revision 0.01 - File Created
// Additional Comments:
//
//////////////////////////////////////////////////////////////////////////////////


module firesim_wrapper(
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
    sys_clk_30,
    sys_reset_n
);

    (* X_INTERFACE_PARAMETER = "XIL_INTERFACENAME S_AXI_CTRL, ADDR_WIDTH 32, ARUSER_WIDTH 0, AWUSER_WIDTH 0, BUSER_WIDTH 0, CLK_DOMAIN design_1_clk_wiz_0_0_clk_out1, DATA_WIDTH 32, HAS_BRESP 1, HAS_BURST 0, HAS_CACHE 0, HAS_LOCK 0, HAS_PROT 1, HAS_QOS 0, HAS_REGION 0, HAS_RRESP 1, HAS_WSTRB 1, ID_WIDTH 0, INSERT_VIP 0, MAX_BURST_LENGTH 1, NUM_READ_OUTSTANDING 1, NUM_READ_THREADS 1, NUM_WRITE_OUTSTANDING 1, NUM_WRITE_THREADS 1, PHASE 0, PROTOCOL AXI4LITE, READ_WRITE_MODE READ_WRITE, RUSER_BITS_PER_BYTE 0, RUSER_WIDTH 0, SUPPORTS_NARROW_BURST 0, WUSER_BITS_PER_BYTE 0, WUSER_WIDTH 0" *)
        (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_CTRL ARADDR" *) input[31:0] S_AXI_CTRL_araddr;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_CTRL ARPROT" *) input[2:0] S_AXI_CTRL_arprot;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_CTRL ARREADY" *) output S_AXI_CTRL_arready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_CTRL ARVALID" *) input S_AXI_CTRL_arvalid;

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_CTRL AWADDR" *) input[31:0] S_AXI_CTRL_awaddr;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_CTRL AWPROT" *) input[2:0] S_AXI_CTRL_awprot;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_CTRL AWREADY" *) output S_AXI_CTRL_awready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_CTRL AWVALID" *) input S_AXI_CTRL_awvalid;

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_CTRL BREADY" *) input S_AXI_CTRL_bready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_CTRL BRESP" *) output[1:0] S_AXI_CTRL_bresp;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_CTRL BVALID" *) output S_AXI_CTRL_bvalid;

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_CTRL RDATA" *) output[31:0] S_AXI_CTRL_rdata;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_CTRL RREADY" *) input S_AXI_CTRL_rready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_CTRL RRESP" *) output[1:0] S_AXI_CTRL_rresp;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_CTRL RVALID" *) output S_AXI_CTRL_rvalid;

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_CTRL WDATA" *) input[31:0] S_AXI_CTRL_wdata;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_CTRL WREADY" *) output S_AXI_CTRL_wready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_CTRL WSTRB" *) input[3:0] S_AXI_CTRL_wstrb;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_CTRL WVALID" *) input S_AXI_CTRL_wvalid;

    (* X_INTERFACE_PARAMETER = "XIL_INTERFACENAME S_AXI_DMA, ADDR_WIDTH 64, ARUSER_WIDTH 0, AWUSER_WIDTH 0, BUSER_WIDTH 0, CLK_DOMAIN design_1_clk_wiz_0_0_clk_out1, DATA_WIDTH 512, HAS_BRESP 1, HAS_BURST 0, HAS_CACHE 1, HAS_LOCK 1, HAS_PROT 1, HAS_QOS 0, HAS_REGION 0, HAS_RRESP 1, HAS_WSTRB 1, ID_WIDTH 4, INSERT_VIP 0, MAX_BURST_LENGTH 256, NUM_READ_OUTSTANDING 32, NUM_READ_THREADS 4, NUM_WRITE_OUTSTANDING 16, NUM_WRITE_THREADS 4, PHASE 0, PROTOCOL AXI4, READ_WRITE_MODE READ_WRITE, RUSER_BITS_PER_BYTE 0, RUSER_WIDTH 0, SUPPORTS_NARROW_BURST 0, WUSER_BITS_PER_BYTE 0, WUSER_WIDTH 0" *)
        (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA ARADDR" *) input[63:0] S_AXI_DMA_araddr;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA ARBURST" *) input[1:0] S_AXI_DMA_arburst;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA ARCACHE" *) input[3:0] S_AXI_DMA_arcache;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA ARID" *) input[3:0] S_AXI_DMA_arid;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA ARLEN" *) input[7:0] S_AXI_DMA_arlen;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA ARLOCK" *) input[0:0] S_AXI_DMA_arlock;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA ARPROT" *) input[2:0] S_AXI_DMA_arprot;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA ARQOS" *) input[3:0] S_AXI_DMA_arqos;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA ARREADY" *) output S_AXI_DMA_arready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA ARREGION" *) input[3:0] S_AXI_DMA_arregion;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA ARSIZE" *) input[2:0] S_AXI_DMA_arsize;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA ARVALID" *) input S_AXI_DMA_arvalid;

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA AWADDR" *) input[63:0] S_AXI_DMA_awaddr;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA AWBURST" *) input[1:0] S_AXI_DMA_awburst;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA AWCACHE" *) input[3:0] S_AXI_DMA_awcache;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA AWID" *) input[3:0] S_AXI_DMA_awid;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA AWLEN" *) input[7:0] S_AXI_DMA_awlen;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA AWLOCK" *) input[0:0] S_AXI_DMA_awlock;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA AWPROT" *) input[2:0] S_AXI_DMA_awprot;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA AWQOS" *) input[3:0] S_AXI_DMA_awqos;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA AWREADY" *) output S_AXI_DMA_awready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA AWREGION" *) input[3:0] S_AXI_DMA_awregion;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA AWSIZE" *) input[2:0] S_AXI_DMA_awsize;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA AWVALID" *) input S_AXI_DMA_awvalid;

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA BID" *) output[3:0] S_AXI_DMA_bid;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA BREADY" *) input S_AXI_DMA_bready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA BRESP" *) output[1:0] S_AXI_DMA_bresp;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA BVALID" *) output S_AXI_DMA_bvalid;

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA RDATA" *) output[511:0] S_AXI_DMA_rdata;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA RID" *) output[3:0] S_AXI_DMA_rid;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA RLAST" *) output S_AXI_DMA_rlast;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA RREADY" *) input S_AXI_DMA_rready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA RRESP" *) output[1:0] S_AXI_DMA_rresp;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA RVALID" *) output S_AXI_DMA_rvalid;

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA WDATA" *) input[511:0] S_AXI_DMA_wdata;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA WLAST" *) input S_AXI_DMA_wlast;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA WREADY" *) output S_AXI_DMA_wready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA WSTRB" *) input[63:0] S_AXI_DMA_wstrb;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 S_AXI_DMA WVALID" *) input S_AXI_DMA_wvalid;

    (* X_INTERFACE_PARAMETER = "XIL_INTERFACENAME M_AXI_DDR0, ADDR_WIDTH 34, ARUSER_WIDTH 0, AWUSER_WIDTH 0, BUSER_WIDTH 0, CLK_DOMAIN design_1_clk_wiz_0_0_clk_out1, DATA_WIDTH 64, HAS_BRESP 1, HAS_BURST 1, HAS_CACHE 1, HAS_LOCK 1, HAS_PROT 1, HAS_QOS 1, HAS_REGION 1, HAS_RRESP 1, HAS_WSTRB 1, ID_WIDTH 16, INSERT_VIP 0, MAX_BURST_LENGTH 256, NUM_READ_OUTSTANDING 2, NUM_READ_THREADS 1, NUM_WRITE_OUTSTANDING 2, NUM_WRITE_THREADS 1, PHASE 0, PROTOCOL AXI4, READ_WRITE_MODE READ_WRITE, RUSER_BITS_PER_BYTE 0, RUSER_WIDTH 0, SUPPORTS_NARROW_BURST 1, WUSER_BITS_PER_BYTE 0, WUSER_WIDTH 0" *)
        (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 ARADDR" *) output[33:0] M_AXI_DDR0_araddr;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 ARBURST" *) output[1:0] M_AXI_DDR0_arburst;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 ARCACHE" *) output[3:0] M_AXI_DDR0_arcache;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 ARID" *) output[15:0] M_AXI_DDR0_arid;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 ARLEN" *) output[7:0] M_AXI_DDR0_arlen;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 ARLOCK" *) output[0:0] M_AXI_DDR0_arlock;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 ARPROT" *) output[2:0] M_AXI_DDR0_arprot;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 ARQOS" *) output[3:0] M_AXI_DDR0_arqos;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 ARREADY" *) input M_AXI_DDR0_arready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 ARREGION" *) output[3:0] M_AXI_DDR0_arregion; // TODO: connect
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 ARSIZE" *) output[2:0] M_AXI_DDR0_arsize;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 ARVALID" *) output M_AXI_DDR0_arvalid;

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 AWADDR" *) output[33:0] M_AXI_DDR0_awaddr;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 AWBURST" *) output[1:0] M_AXI_DDR0_awburst;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 AWCACHE" *) output[3:0] M_AXI_DDR0_awcache;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 AWID" *) output[15:0] M_AXI_DDR0_awid;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 AWLEN" *) output[7:0] M_AXI_DDR0_awlen;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 AWLOCK" *) output[0:0] M_AXI_DDR0_awlock;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 AWPROT" *) output[2:0] M_AXI_DDR0_awprot;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 AWQOS" *) output[3:0] M_AXI_DDR0_awqos;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 AWREADY" *) input M_AXI_DDR0_awready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 AWREGION" *) output[3:0] M_AXI_DDR0_awregion; // TODO: connect
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 AWSIZE" *) output[2:0] M_AXI_DDR0_awsize;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 AWVALID" *) output M_AXI_DDR0_awvalid;

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 BID" *) input[15:0] M_AXI_DDR0_bid;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 BREADY" *) output M_AXI_DDR0_bready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 BRESP" *) input[1:0] M_AXI_DDR0_bresp;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 BVALID" *) input M_AXI_DDR0_bvalid;

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 RDATA" *) input[63:0] M_AXI_DDR0_rdata;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 RID" *) input[15:0] M_AXI_DDR0_rid;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 RLAST" *) input M_AXI_DDR0_rlast;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 RREADY" *) output M_AXI_DDR0_rready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 RRESP" *) input[1:0] M_AXI_DDR0_rresp;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 RVALID" *) input M_AXI_DDR0_rvalid;

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 WDATA" *) output[63:0] M_AXI_DDR0_wdata;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 WLAST" *) output M_AXI_DDR0_wlast;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 WREADY" *) input M_AXI_DDR0_wready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 WSTRB" *) output[7:0] M_AXI_DDR0_wstrb;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR0 WVALID" *) output M_AXI_DDR0_wvalid;

    (* X_INTERFACE_PARAMETER = "XIL_INTERFACENAME M_AXI_DDR1, ADDR_WIDTH 34, ARUSER_WIDTH 0, AWUSER_WIDTH 0, BUSER_WIDTH 0, CLK_DOMAIN design_1_clk_wiz_0_0_clk_out1, DATA_WIDTH 64, HAS_BRESP 1, HAS_BURST 1, HAS_CACHE 1, HAS_LOCK 1, HAS_PROT 1, HAS_QOS 1, HAS_REGION 1, HAS_RRESP 1, HAS_WSTRB 1, ID_WIDTH 16, INSERT_VIP 0, MAX_BURST_LENGTH 256, NUM_READ_OUTSTANDING 2, NUM_READ_THREADS 1, NUM_WRITE_OUTSTANDING 2, NUM_WRITE_THREADS 1, PHASE 0, PROTOCOL AXI4, READ_WRITE_MODE READ_WRITE, RUSER_BITS_PER_BYTE 0, RUSER_WIDTH 0, SUPPORTS_NARROW_BURST 1, WUSER_BITS_PER_BYTE 0, WUSER_WIDTH 0" *)
        (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 ARADDR" *) output[33:0] M_AXI_DDR1_araddr;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 ARBURST" *) output[1:0] M_AXI_DDR1_arburst;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 ARCACHE" *) output[3:0] M_AXI_DDR1_arcache;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 ARID" *) output[15:0] M_AXI_DDR1_arid;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 ARLEN" *) output[7:0] M_AXI_DDR1_arlen;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 ARLOCK" *) output[0:0] M_AXI_DDR1_arlock;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 ARPROT" *) output[2:0] M_AXI_DDR1_arprot;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 ARQOS" *) output[3:0] M_AXI_DDR1_arqos;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 ARREADY" *) input M_AXI_DDR1_arready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 ARREGION" *) output[3:0] M_AXI_DDR1_arregion; // TODO: connect
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 ARSIZE" *) output[2:0] M_AXI_DDR1_arsize;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 ARVALID" *) output M_AXI_DDR1_arvalid;

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 AWADDR" *) output[33:0] M_AXI_DDR1_awaddr;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 AWBURST" *) output[1:0] M_AXI_DDR1_awburst;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 AWCACHE" *) output[3:0] M_AXI_DDR1_awcache;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 AWID" *) output[15:0] M_AXI_DDR1_awid;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 AWLEN" *) output[7:0] M_AXI_DDR1_awlen;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 AWLOCK" *) output[0:0] M_AXI_DDR1_awlock;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 AWPROT" *) output[2:0] M_AXI_DDR1_awprot;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 AWQOS" *) output[3:0] M_AXI_DDR1_awqos;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 AWREADY" *) input M_AXI_DDR1_awready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 AWREGION" *) output[3:0] M_AXI_DDR1_awregion; // TODO: connect
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 AWSIZE" *) output[2:0] M_AXI_DDR1_awsize;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 AWVALID" *) output M_AXI_DDR1_awvalid;

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 BID" *) input[15:0] M_AXI_DDR1_bid;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 BREADY" *) output M_AXI_DDR1_bready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 BRESP" *) input[1:0] M_AXI_DDR1_bresp;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 BVALID" *) input M_AXI_DDR1_bvalid;

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 RDATA" *) input[63:0] M_AXI_DDR1_rdata;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 RID" *) input[15:0] M_AXI_DDR1_rid;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 RLAST" *) input M_AXI_DDR1_rlast;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 RREADY" *) output M_AXI_DDR1_rready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 RRESP" *) input[1:0] M_AXI_DDR1_rresp;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 RVALID" *) input M_AXI_DDR1_rvalid;

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 WDATA" *) output[63:0] M_AXI_DDR1_wdata;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 WLAST" *) output M_AXI_DDR1_wlast;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 WREADY" *) input M_AXI_DDR1_wready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 WSTRB" *) output[7:0] M_AXI_DDR1_wstrb;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR1 WVALID" *) output M_AXI_DDR1_wvalid;

    (* X_INTERFACE_PARAMETER = "XIL_INTERFACENAME M_AXI_DDR2, ADDR_WIDTH 34, ARUSER_WIDTH 0, AWUSER_WIDTH 0, BUSER_WIDTH 0, CLK_DOMAIN design_1_clk_wiz_0_0_clk_out1, DATA_WIDTH 64, HAS_BRESP 1, HAS_BURST 1, HAS_CACHE 1, HAS_LOCK 1, HAS_PROT 1, HAS_QOS 1, HAS_REGION 1, HAS_RRESP 1, HAS_WSTRB 1, ID_WIDTH 16, INSERT_VIP 0, MAX_BURST_LENGTH 256, NUM_READ_OUTSTANDING 2, NUM_READ_THREADS 1, NUM_WRITE_OUTSTANDING 2, NUM_WRITE_THREADS 1, PHASE 0, PROTOCOL AXI4, READ_WRITE_MODE READ_WRITE, RUSER_BITS_PER_BYTE 0, RUSER_WIDTH 0, SUPPORTS_NARROW_BURST 1, WUSER_BITS_PER_BYTE 0, WUSER_WIDTH 0" *)
        (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 ARADDR" *) output[33:0] M_AXI_DDR2_araddr;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 ARBURST" *) output[1:0] M_AXI_DDR2_arburst;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 ARCACHE" *) output[3:0] M_AXI_DDR2_arcache;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 ARID" *) output[15:0] M_AXI_DDR2_arid;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 ARLEN" *) output[7:0] M_AXI_DDR2_arlen;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 ARLOCK" *) output[0:0] M_AXI_DDR2_arlock;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 ARPROT" *) output[2:0] M_AXI_DDR2_arprot;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 ARQOS" *) output[3:0] M_AXI_DDR2_arqos;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 ARREADY" *) input M_AXI_DDR2_arready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 ARREGION" *) output[3:0] M_AXI_DDR2_arregion; // TODO: connect
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 ARSIZE" *) output[2:0] M_AXI_DDR2_arsize;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 ARVALID" *) output M_AXI_DDR2_arvalid;

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 AWADDR" *) output[33:0] M_AXI_DDR2_awaddr;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 AWBURST" *) output[1:0] M_AXI_DDR2_awburst;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 AWCACHE" *) output[3:0] M_AXI_DDR2_awcache;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 AWID" *) output[15:0] M_AXI_DDR2_awid;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 AWLEN" *) output[7:0] M_AXI_DDR2_awlen;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 AWLOCK" *) output[0:0] M_AXI_DDR2_awlock;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 AWPROT" *) output[2:0] M_AXI_DDR2_awprot;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 AWQOS" *) output[3:0] M_AXI_DDR2_awqos;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 AWREADY" *) input M_AXI_DDR2_awready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 AWREGION" *) output[3:0] M_AXI_DDR2_awregion; // TODO: connect
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 AWSIZE" *) output[2:0] M_AXI_DDR2_awsize;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 AWVALID" *) output M_AXI_DDR2_awvalid;

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 BID" *) input[15:0] M_AXI_DDR2_bid;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 BREADY" *) output M_AXI_DDR2_bready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 BRESP" *) input[1:0] M_AXI_DDR2_bresp;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 BVALID" *) input M_AXI_DDR2_bvalid;

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 RDATA" *) input[63:0] M_AXI_DDR2_rdata;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 RID" *) input[15:0] M_AXI_DDR2_rid;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 RLAST" *) input M_AXI_DDR2_rlast;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 RREADY" *) output M_AXI_DDR2_rready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 RRESP" *) input[1:0] M_AXI_DDR2_rresp;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 RVALID" *) input M_AXI_DDR2_rvalid;

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 WDATA" *) output[63:0] M_AXI_DDR2_wdata;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 WLAST" *) output M_AXI_DDR2_wlast;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 WREADY" *) input M_AXI_DDR2_wready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 WSTRB" *) output[7:0] M_AXI_DDR2_wstrb;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR2 WVALID" *) output M_AXI_DDR2_wvalid;

    (* X_INTERFACE_PARAMETER = "XIL_INTERFACENAME M_AXI_DDR3, ADDR_WIDTH 34, ARUSER_WIDTH 0, AWUSER_WIDTH 0, BUSER_WIDTH 0, CLK_DOMAIN design_1_clk_wiz_0_0_clk_out1, DATA_WIDTH 64, HAS_BRESP 1, HAS_BURST 1, HAS_CACHE 1, HAS_LOCK 1, HAS_PROT 1, HAS_QOS 1, HAS_REGION 1, HAS_RRESP 1, HAS_WSTRB 1, ID_WIDTH 16, INSERT_VIP 0, MAX_BURST_LENGTH 256, NUM_READ_OUTSTANDING 2, NUM_READ_THREADS 1, NUM_WRITE_OUTSTANDING 2, NUM_WRITE_THREADS 1, PHASE 0, PROTOCOL AXI4, READ_WRITE_MODE READ_WRITE, RUSER_BITS_PER_BYTE 0, RUSER_WIDTH 0, SUPPORTS_NARROW_BURST 1, WUSER_BITS_PER_BYTE 0, WUSER_WIDTH 0" *)
        (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 ARADDR" *) output[33:0] M_AXI_DDR3_araddr;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 ARBURST" *) output[1:0] M_AXI_DDR3_arburst;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 ARCACHE" *) output[3:0] M_AXI_DDR3_arcache;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 ARID" *) output[15:0] M_AXI_DDR3_arid;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 ARLEN" *) output[7:0] M_AXI_DDR3_arlen;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 ARLOCK" *) output[0:0] M_AXI_DDR3_arlock;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 ARPROT" *) output[2:0] M_AXI_DDR3_arprot;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 ARQOS" *) output[3:0] M_AXI_DDR3_arqos;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 ARREADY" *) input M_AXI_DDR3_arready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 ARREGION" *) output[3:0] M_AXI_DDR3_arregion; // TODO: connect
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 ARSIZE" *) output[2:0] M_AXI_DDR3_arsize;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 ARVALID" *) output M_AXI_DDR3_arvalid;

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 AWADDR" *) output[33:0] M_AXI_DDR3_awaddr;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 AWBURST" *) output[1:0] M_AXI_DDR3_awburst;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 AWCACHE" *) output[3:0] M_AXI_DDR3_awcache;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 AWID" *) output[15:0] M_AXI_DDR3_awid;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 AWLEN" *) output[7:0] M_AXI_DDR3_awlen;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 AWLOCK" *) output[0:0] M_AXI_DDR3_awlock;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 AWPROT" *) output[2:0] M_AXI_DDR3_awprot;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 AWQOS" *) output[3:0] M_AXI_DDR3_awqos;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 AWREADY" *) input M_AXI_DDR3_awready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 AWREGION" *) output[3:0] M_AXI_DDR3_awregion; // TODO: connect
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 AWSIZE" *) output[2:0] M_AXI_DDR3_awsize;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 AWVALID" *) output M_AXI_DDR3_awvalid;

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 BID" *) input[15:0] M_AXI_DDR3_bid;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 BREADY" *) output M_AXI_DDR3_bready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 BRESP" *) input[1:0] M_AXI_DDR3_bresp;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 BVALID" *) input M_AXI_DDR3_bvalid;

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 RDATA" *) input[63:0] M_AXI_DDR3_rdata;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 RID" *) input[15:0] M_AXI_DDR3_rid;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 RLAST" *) input M_AXI_DDR3_rlast;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 RREADY" *) output M_AXI_DDR3_rready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 RRESP" *) input[1:0] M_AXI_DDR3_rresp;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 RVALID" *) input M_AXI_DDR3_rvalid;

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 WDATA" *) output[63:0] M_AXI_DDR3_wdata;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 WLAST" *) output M_AXI_DDR3_wlast;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 WREADY" *) input M_AXI_DDR3_wready;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 WSTRB" *) output[7:0] M_AXI_DDR3_wstrb;
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm_rtl:1.0 M_AXI_DDR3 WVALID" *) output M_AXI_DDR3_wvalid;

    (* X_INTERFACE_PARAMETER = "XIL_INTERFACENAME CLK.SYS_CLK_30, ASSOCIATED_BUSIF S_AXI_CTRL:S_AXI_DMA:M_AXI_DDR0:M_AXI_DDR1:M_AXI_DDR2:M_AXI_DDR3, CLK_DOMAIN design_1_clk_wiz_0_0_clk_out1, INSERT_VIP 0, PHASE 0" *)
        (* X_INTERFACE_INFO = "xilinx.com:signal:clock:1.0 CLK.SYS_CLK_30 CLK" *) input sys_clk_30;

    (* X_INTERFACE_PARAMETER = "XIL_INTERFACENAME RST.SYS_RESET_N, INSERT_VIP 0, POLARITY ACTIVE_LOW" *)
        (* X_INTERFACE_INFO = "xilinx.com:signal:reset:1.0 RST.SYS_RESET_N RST" *) input[0:0] sys_reset_n;

    F1Shim firesim_top(
        .clock(sys_clk_30),
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
