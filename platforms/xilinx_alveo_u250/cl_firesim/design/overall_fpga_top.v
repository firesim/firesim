`timescale 1 ps / 1 ps

module overall_fpga_top (
    ddr4_sdram_c0_act_n,
    ddr4_sdram_c0_adr,
    ddr4_sdram_c0_ba,
    ddr4_sdram_c0_bg,
    ddr4_sdram_c0_ck_c,
    ddr4_sdram_c0_ck_t,
    ddr4_sdram_c0_cke,
    ddr4_sdram_c0_cs_n,
    ddr4_sdram_c0_dq,
    ddr4_sdram_c0_dqs_c,
    ddr4_sdram_c0_dqs_t,
    ddr4_sdram_c0_odt,
    ddr4_sdram_c0_par,
    ddr4_sdram_c0_reset_n,
    ddr4_sdram_c1_act_n,
    ddr4_sdram_c1_adr,
    ddr4_sdram_c1_ba,
    ddr4_sdram_c1_bg,
    ddr4_sdram_c1_ck_c,
    ddr4_sdram_c1_ck_t,
    ddr4_sdram_c1_cke,
    ddr4_sdram_c1_cs_n,
    ddr4_sdram_c1_dq,
    ddr4_sdram_c1_dqs_c,
    ddr4_sdram_c1_dqs_t,
    ddr4_sdram_c1_odt,
    ddr4_sdram_c1_par,
    ddr4_sdram_c1_reset_n,
    ddr4_sdram_c2_act_n,
    ddr4_sdram_c2_adr,
    ddr4_sdram_c2_ba,
    ddr4_sdram_c2_bg,
    ddr4_sdram_c2_ck_c,
    ddr4_sdram_c2_ck_t,
    ddr4_sdram_c2_cke,
    ddr4_sdram_c2_cs_n,
    ddr4_sdram_c2_dq,
    ddr4_sdram_c2_dqs_c,
    ddr4_sdram_c2_dqs_t,
    ddr4_sdram_c2_odt,
    ddr4_sdram_c2_par,
    ddr4_sdram_c2_reset_n,
    ddr4_sdram_c3_act_n,
    ddr4_sdram_c3_adr,
    ddr4_sdram_c3_ba,
    ddr4_sdram_c3_bg,
    ddr4_sdram_c3_ck_c,
    ddr4_sdram_c3_ck_t,
    ddr4_sdram_c3_cke,
    ddr4_sdram_c3_cs_n,
    ddr4_sdram_c3_dq,
    ddr4_sdram_c3_dqs_c,
    ddr4_sdram_c3_dqs_t,
    ddr4_sdram_c3_odt,
    ddr4_sdram_c3_par,
    ddr4_sdram_c3_reset_n,
    default_300mhz_clk0_clk_n,
    default_300mhz_clk0_clk_p,
    default_300mhz_clk1_clk_n,
    default_300mhz_clk1_clk_p,
    default_300mhz_clk2_clk_n,
    default_300mhz_clk2_clk_p,
    default_300mhz_clk3_clk_n,
    default_300mhz_clk3_clk_p,
    pci_express_x16_rxn,
    pci_express_x16_rxp,
    pci_express_x16_txn,
    pci_express_x16_txp,
    pcie_perstn,
    pcie_refclk_clk_n,
    pcie_refclk_clk_p,
    resetn
);

    output ddr4_sdram_c0_act_n;
    output [16:0]ddr4_sdram_c0_adr;
    output [1:0]ddr4_sdram_c0_ba;
    output [1:0]ddr4_sdram_c0_bg;
    output ddr4_sdram_c0_ck_c;
    output ddr4_sdram_c0_ck_t;
    output ddr4_sdram_c0_cke;
    output ddr4_sdram_c0_cs_n;
    inout [71:0]ddr4_sdram_c0_dq;
    inout [17:0]ddr4_sdram_c0_dqs_c;
    inout [17:0]ddr4_sdram_c0_dqs_t;
    output ddr4_sdram_c0_odt;
    output ddr4_sdram_c0_par;
    output ddr4_sdram_c0_reset_n;
    output ddr4_sdram_c1_act_n;
    output [16:0]ddr4_sdram_c1_adr;
    output [1:0]ddr4_sdram_c1_ba;
    output [1:0]ddr4_sdram_c1_bg;
    output ddr4_sdram_c1_ck_c;
    output ddr4_sdram_c1_ck_t;
    output ddr4_sdram_c1_cke;
    output ddr4_sdram_c1_cs_n;
    inout [71:0]ddr4_sdram_c1_dq;
    inout [17:0]ddr4_sdram_c1_dqs_c;
    inout [17:0]ddr4_sdram_c1_dqs_t;
    output ddr4_sdram_c1_odt;
    output ddr4_sdram_c1_par;
    output ddr4_sdram_c1_reset_n;
    output ddr4_sdram_c2_act_n;
    output [16:0]ddr4_sdram_c2_adr;
    output [1:0]ddr4_sdram_c2_ba;
    output [1:0]ddr4_sdram_c2_bg;
    output ddr4_sdram_c2_ck_c;
    output ddr4_sdram_c2_ck_t;
    output ddr4_sdram_c2_cke;
    output ddr4_sdram_c2_cs_n;
    inout [71:0]ddr4_sdram_c2_dq;
    inout [17:0]ddr4_sdram_c2_dqs_c;
    inout [17:0]ddr4_sdram_c2_dqs_t;
    output ddr4_sdram_c2_odt;
    output ddr4_sdram_c2_par;
    output ddr4_sdram_c2_reset_n;
    output ddr4_sdram_c3_act_n;
    output [16:0]ddr4_sdram_c3_adr;
    output [1:0]ddr4_sdram_c3_ba;
    output [1:0]ddr4_sdram_c3_bg;
    output ddr4_sdram_c3_ck_c;
    output ddr4_sdram_c3_ck_t;
    output ddr4_sdram_c3_cke;
    output ddr4_sdram_c3_cs_n;
    inout [71:0]ddr4_sdram_c3_dq;
    inout [17:0]ddr4_sdram_c3_dqs_c;
    inout [17:0]ddr4_sdram_c3_dqs_t;
    output ddr4_sdram_c3_odt;
    output ddr4_sdram_c3_par;
    output ddr4_sdram_c3_reset_n;
    input default_300mhz_clk0_clk_n;
    input default_300mhz_clk0_clk_p;
    input default_300mhz_clk1_clk_n;
    input default_300mhz_clk1_clk_p;
    input default_300mhz_clk2_clk_n;
    input default_300mhz_clk2_clk_p;
    input default_300mhz_clk3_clk_n;
    input default_300mhz_clk3_clk_p;
    input [15:0]pci_express_x16_rxn;
    input [15:0]pci_express_x16_rxp;
    output [15:0]pci_express_x16_txn;
    output [15:0]pci_express_x16_txp;
    input pcie_perstn;
    input pcie_refclk_clk_n;
    input pcie_refclk_clk_p;
    input resetn;

    wire ddr4_sdram_c0_act_n;
    wire [16:0]ddr4_sdram_c0_adr;
    wire [1:0]ddr4_sdram_c0_ba;
    wire [1:0]ddr4_sdram_c0_bg;
    wire ddr4_sdram_c0_ck_c;
    wire ddr4_sdram_c0_ck_t;
    wire ddr4_sdram_c0_cke;
    wire ddr4_sdram_c0_cs_n;
    wire [71:0]ddr4_sdram_c0_dq;
    wire [17:0]ddr4_sdram_c0_dqs_c;
    wire [17:0]ddr4_sdram_c0_dqs_t;
    wire ddr4_sdram_c0_odt;
    wire ddr4_sdram_c0_par;
    wire ddr4_sdram_c0_reset_n;
    wire ddr4_sdram_c1_act_n;
    wire [16:0]ddr4_sdram_c1_adr;
    wire [1:0]ddr4_sdram_c1_ba;
    wire [1:0]ddr4_sdram_c1_bg;
    wire ddr4_sdram_c1_ck_c;
    wire ddr4_sdram_c1_ck_t;
    wire ddr4_sdram_c1_cke;
    wire ddr4_sdram_c1_cs_n;
    wire [71:0]ddr4_sdram_c1_dq;
    wire [17:0]ddr4_sdram_c1_dqs_c;
    wire [17:0]ddr4_sdram_c1_dqs_t;
    wire ddr4_sdram_c1_odt;
    wire ddr4_sdram_c1_par;
    wire ddr4_sdram_c1_reset_n;
    wire ddr4_sdram_c2_act_n;
    wire [16:0]ddr4_sdram_c2_adr;
    wire [1:0]ddr4_sdram_c2_ba;
    wire [1:0]ddr4_sdram_c2_bg;
    wire ddr4_sdram_c2_ck_c;
    wire ddr4_sdram_c2_ck_t;
    wire ddr4_sdram_c2_cke;
    wire ddr4_sdram_c2_cs_n;
    wire [71:0]ddr4_sdram_c2_dq;
    wire [17:0]ddr4_sdram_c2_dqs_c;
    wire [17:0]ddr4_sdram_c2_dqs_t;
    wire ddr4_sdram_c2_odt;
    wire ddr4_sdram_c2_par;
    wire ddr4_sdram_c2_reset_n;
    wire ddr4_sdram_c3_act_n;
    wire [16:0]ddr4_sdram_c3_adr;
    wire [1:0]ddr4_sdram_c3_ba;
    wire [1:0]ddr4_sdram_c3_bg;
    wire ddr4_sdram_c3_ck_c;
    wire ddr4_sdram_c3_ck_t;
    wire ddr4_sdram_c3_cke;
    wire ddr4_sdram_c3_cs_n;
    wire [71:0]ddr4_sdram_c3_dq;
    wire [17:0]ddr4_sdram_c3_dqs_c;
    wire [17:0]ddr4_sdram_c3_dqs_t;
    wire ddr4_sdram_c3_odt;
    wire ddr4_sdram_c3_par;
    wire ddr4_sdram_c3_reset_n;
    wire default_300mhz_clk0_clk_n;
    wire default_300mhz_clk0_clk_p;
    wire default_300mhz_clk1_clk_n;
    wire default_300mhz_clk1_clk_p;
    wire default_300mhz_clk2_clk_n;
    wire default_300mhz_clk2_clk_p;
    wire default_300mhz_clk3_clk_n;
    wire default_300mhz_clk3_clk_p;
    wire [15:0]pci_express_x16_rxn;
    wire [15:0]pci_express_x16_rxp;
    wire [15:0]pci_express_x16_txn;
    wire [15:0]pci_express_x16_txp;
    wire pcie_perstn;
    wire pcie_refclk_clk_n;
    wire pcie_refclk_clk_p;
    wire resetn;

    wire sys_clk;
    wire [0:0]sys_reset_n;
    wire [33:0]DDR4_0_S_AXI_araddr;
    wire [1:0]DDR4_0_S_AXI_arburst;
    wire [3:0]DDR4_0_S_AXI_arcache;
    wire [15:0]DDR4_0_S_AXI_arid;
    wire [7:0]DDR4_0_S_AXI_arlen;
    wire [0:0]DDR4_0_S_AXI_arlock;
    wire [2:0]DDR4_0_S_AXI_arprot;
    wire [3:0]DDR4_0_S_AXI_arqos;
    wire DDR4_0_S_AXI_arready;
    wire [3:0]DDR4_0_S_AXI_arregion;
    wire [2:0]DDR4_0_S_AXI_arsize;
    wire DDR4_0_S_AXI_arvalid;
    wire [33:0]DDR4_0_S_AXI_awaddr;
    wire [1:0]DDR4_0_S_AXI_awburst;
    wire [3:0]DDR4_0_S_AXI_awcache;
    wire [15:0]DDR4_0_S_AXI_awid;
    wire [7:0]DDR4_0_S_AXI_awlen;
    wire [0:0]DDR4_0_S_AXI_awlock;
    wire [2:0]DDR4_0_S_AXI_awprot;
    wire [3:0]DDR4_0_S_AXI_awqos;
    wire DDR4_0_S_AXI_awready;
    wire [3:0]DDR4_0_S_AXI_awregion;
    wire [2:0]DDR4_0_S_AXI_awsize;
    wire DDR4_0_S_AXI_awvalid;
    wire [15:0]DDR4_0_S_AXI_bid;
    wire DDR4_0_S_AXI_bready;
    wire [1:0]DDR4_0_S_AXI_bresp;
    wire DDR4_0_S_AXI_bvalid;
    wire [63:0]DDR4_0_S_AXI_rdata;
    wire [15:0]DDR4_0_S_AXI_rid;
    wire DDR4_0_S_AXI_rlast;
    wire DDR4_0_S_AXI_rready;
    wire [1:0]DDR4_0_S_AXI_rresp;
    wire DDR4_0_S_AXI_rvalid;
    wire [63:0]DDR4_0_S_AXI_wdata;
    wire DDR4_0_S_AXI_wlast;
    wire DDR4_0_S_AXI_wready;
    wire [7:0]DDR4_0_S_AXI_wstrb;
    wire DDR4_0_S_AXI_wvalid;
    wire [33:0]DDR4_1_S_AXI_araddr;
    wire [1:0]DDR4_1_S_AXI_arburst;
    wire [3:0]DDR4_1_S_AXI_arcache;
    wire [15:0]DDR4_1_S_AXI_arid;
    wire [7:0]DDR4_1_S_AXI_arlen;
    wire [0:0]DDR4_1_S_AXI_arlock;
    wire [2:0]DDR4_1_S_AXI_arprot;
    wire [3:0]DDR4_1_S_AXI_arqos;
    wire DDR4_1_S_AXI_arready;
    wire [3:0]DDR4_1_S_AXI_arregion;
    wire [2:0]DDR4_1_S_AXI_arsize;
    wire DDR4_1_S_AXI_arvalid;
    wire [33:0]DDR4_1_S_AXI_awaddr;
    wire [1:0]DDR4_1_S_AXI_awburst;
    wire [3:0]DDR4_1_S_AXI_awcache;
    wire [15:0]DDR4_1_S_AXI_awid;
    wire [7:0]DDR4_1_S_AXI_awlen;
    wire [0:0]DDR4_1_S_AXI_awlock;
    wire [2:0]DDR4_1_S_AXI_awprot;
    wire [3:0]DDR4_1_S_AXI_awqos;
    wire DDR4_1_S_AXI_awready;
    wire [3:0]DDR4_1_S_AXI_awregion;
    wire [2:0]DDR4_1_S_AXI_awsize;
    wire DDR4_1_S_AXI_awvalid;
    wire [15:0]DDR4_1_S_AXI_bid;
    wire DDR4_1_S_AXI_bready;
    wire [1:0]DDR4_1_S_AXI_bresp;
    wire DDR4_1_S_AXI_bvalid;
    wire [63:0]DDR4_1_S_AXI_rdata;
    wire [15:0]DDR4_1_S_AXI_rid;
    wire DDR4_1_S_AXI_rlast;
    wire DDR4_1_S_AXI_rready;
    wire [1:0]DDR4_1_S_AXI_rresp;
    wire DDR4_1_S_AXI_rvalid;
    wire [63:0]DDR4_1_S_AXI_wdata;
    wire DDR4_1_S_AXI_wlast;
    wire DDR4_1_S_AXI_wready;
    wire [7:0]DDR4_1_S_AXI_wstrb;
    wire DDR4_1_S_AXI_wvalid;
    wire [33:0]DDR4_2_S_AXI_araddr;
    wire [1:0]DDR4_2_S_AXI_arburst;
    wire [3:0]DDR4_2_S_AXI_arcache;
    wire [15:0]DDR4_2_S_AXI_arid;
    wire [7:0]DDR4_2_S_AXI_arlen;
    wire [0:0]DDR4_2_S_AXI_arlock;
    wire [2:0]DDR4_2_S_AXI_arprot;
    wire [3:0]DDR4_2_S_AXI_arqos;
    wire DDR4_2_S_AXI_arready;
    wire [3:0]DDR4_2_S_AXI_arregion;
    wire [2:0]DDR4_2_S_AXI_arsize;
    wire DDR4_2_S_AXI_arvalid;
    wire [33:0]DDR4_2_S_AXI_awaddr;
    wire [1:0]DDR4_2_S_AXI_awburst;
    wire [3:0]DDR4_2_S_AXI_awcache;
    wire [15:0]DDR4_2_S_AXI_awid;
    wire [7:0]DDR4_2_S_AXI_awlen;
    wire [0:0]DDR4_2_S_AXI_awlock;
    wire [2:0]DDR4_2_S_AXI_awprot;
    wire [3:0]DDR4_2_S_AXI_awqos;
    wire DDR4_2_S_AXI_awready;
    wire [3:0]DDR4_2_S_AXI_awregion;
    wire [2:0]DDR4_2_S_AXI_awsize;
    wire DDR4_2_S_AXI_awvalid;
    wire [15:0]DDR4_2_S_AXI_bid;
    wire DDR4_2_S_AXI_bready;
    wire [1:0]DDR4_2_S_AXI_bresp;
    wire DDR4_2_S_AXI_bvalid;
    wire [63:0]DDR4_2_S_AXI_rdata;
    wire [15:0]DDR4_2_S_AXI_rid;
    wire DDR4_2_S_AXI_rlast;
    wire DDR4_2_S_AXI_rready;
    wire [1:0]DDR4_2_S_AXI_rresp;
    wire DDR4_2_S_AXI_rvalid;
    wire [63:0]DDR4_2_S_AXI_wdata;
    wire DDR4_2_S_AXI_wlast;
    wire DDR4_2_S_AXI_wready;
    wire [7:0]DDR4_2_S_AXI_wstrb;
    wire DDR4_2_S_AXI_wvalid;
    wire [33:0]DDR4_3_S_AXI_araddr;
    wire [1:0]DDR4_3_S_AXI_arburst;
    wire [3:0]DDR4_3_S_AXI_arcache;
    wire [15:0]DDR4_3_S_AXI_arid;
    wire [7:0]DDR4_3_S_AXI_arlen;
    wire [0:0]DDR4_3_S_AXI_arlock;
    wire [2:0]DDR4_3_S_AXI_arprot;
    wire [3:0]DDR4_3_S_AXI_arqos;
    wire DDR4_3_S_AXI_arready;
    wire [3:0]DDR4_3_S_AXI_arregion;
    wire [2:0]DDR4_3_S_AXI_arsize;
    wire DDR4_3_S_AXI_arvalid;
    wire [33:0]DDR4_3_S_AXI_awaddr;
    wire [1:0]DDR4_3_S_AXI_awburst;
    wire [3:0]DDR4_3_S_AXI_awcache;
    wire [15:0]DDR4_3_S_AXI_awid;
    wire [7:0]DDR4_3_S_AXI_awlen;
    wire [0:0]DDR4_3_S_AXI_awlock;
    wire [2:0]DDR4_3_S_AXI_awprot;
    wire [3:0]DDR4_3_S_AXI_awqos;
    wire DDR4_3_S_AXI_awready;
    wire [3:0]DDR4_3_S_AXI_awregion;
    wire [2:0]DDR4_3_S_AXI_awsize;
    wire DDR4_3_S_AXI_awvalid;
    wire [15:0]DDR4_3_S_AXI_bid;
    wire DDR4_3_S_AXI_bready;
    wire [1:0]DDR4_3_S_AXI_bresp;
    wire DDR4_3_S_AXI_bvalid;
    wire [63:0]DDR4_3_S_AXI_rdata;
    wire [15:0]DDR4_3_S_AXI_rid;
    wire DDR4_3_S_AXI_rlast;
    wire DDR4_3_S_AXI_rready;
    wire [1:0]DDR4_3_S_AXI_rresp;
    wire DDR4_3_S_AXI_rvalid;
    wire [63:0]DDR4_3_S_AXI_wdata;
    wire DDR4_3_S_AXI_wlast;
    wire DDR4_3_S_AXI_wready;
    wire [7:0]DDR4_3_S_AXI_wstrb;
    wire DDR4_3_S_AXI_wvalid;
    wire [31:0]PCIE_M_AXI_LITE_araddr;
    wire [2:0]PCIE_M_AXI_LITE_arprot;
    wire PCIE_M_AXI_LITE_arready;
    wire PCIE_M_AXI_LITE_arvalid;
    wire [31:0]PCIE_M_AXI_LITE_awaddr;
    wire [2:0]PCIE_M_AXI_LITE_awprot;
    wire PCIE_M_AXI_LITE_awready;
    wire PCIE_M_AXI_LITE_awvalid;
    wire PCIE_M_AXI_LITE_bready;
    wire [1:0]PCIE_M_AXI_LITE_bresp;
    wire PCIE_M_AXI_LITE_bvalid;
    wire [31:0]PCIE_M_AXI_LITE_rdata;
    wire PCIE_M_AXI_LITE_rready;
    wire [1:0]PCIE_M_AXI_LITE_rresp;
    wire PCIE_M_AXI_LITE_rvalid;
    wire [31:0]PCIE_M_AXI_LITE_wdata;
    wire PCIE_M_AXI_LITE_wready;
    wire [3:0]PCIE_M_AXI_LITE_wstrb;
    wire PCIE_M_AXI_LITE_wvalid;
    wire [63:0]PCIE_M_AXI_araddr;
    wire [1:0]PCIE_M_AXI_arburst;
    wire [3:0]PCIE_M_AXI_arcache;
    wire [3:0]PCIE_M_AXI_arid;
    wire [7:0]PCIE_M_AXI_arlen;
    wire [0:0]PCIE_M_AXI_arlock;
    wire [2:0]PCIE_M_AXI_arprot;
    wire [3:0]PCIE_M_AXI_arqos;
    wire PCIE_M_AXI_arready;
    wire [3:0]PCIE_M_AXI_arregion;
    wire [2:0]PCIE_M_AXI_arsize;
    wire PCIE_M_AXI_arvalid;
    wire [63:0]PCIE_M_AXI_awaddr;
    wire [1:0]PCIE_M_AXI_awburst;
    wire [3:0]PCIE_M_AXI_awcache;
    wire [3:0]PCIE_M_AXI_awid;
    wire [7:0]PCIE_M_AXI_awlen;
    wire [0:0]PCIE_M_AXI_awlock;
    wire [2:0]PCIE_M_AXI_awprot;
    wire [3:0]PCIE_M_AXI_awqos;
    wire PCIE_M_AXI_awready;
    wire [3:0]PCIE_M_AXI_awregion;
    wire [2:0]PCIE_M_AXI_awsize;
    wire PCIE_M_AXI_awvalid;
    wire [3:0]PCIE_M_AXI_bid;
    wire PCIE_M_AXI_bready;
    wire [1:0]PCIE_M_AXI_bresp;
    wire PCIE_M_AXI_bvalid;
    wire [511:0]PCIE_M_AXI_rdata;
    wire [3:0]PCIE_M_AXI_rid;
    wire PCIE_M_AXI_rlast;
    wire PCIE_M_AXI_rready;
    wire [1:0]PCIE_M_AXI_rresp;
    wire PCIE_M_AXI_rvalid;
    wire [511:0]PCIE_M_AXI_wdata;
    wire PCIE_M_AXI_wlast;
    wire PCIE_M_AXI_wready;
    wire [63:0]PCIE_M_AXI_wstrb;
    wire PCIE_M_AXI_wvalid;

    design_1 design_1_i (
        .ddr4_sdram_c0_act_n(ddr4_sdram_c0_act_n),
        .ddr4_sdram_c0_adr(ddr4_sdram_c0_adr),
        .ddr4_sdram_c0_ba(ddr4_sdram_c0_ba),
        .ddr4_sdram_c0_bg(ddr4_sdram_c0_bg),
        .ddr4_sdram_c0_ck_c(ddr4_sdram_c0_ck_c),
        .ddr4_sdram_c0_ck_t(ddr4_sdram_c0_ck_t),
        .ddr4_sdram_c0_cke(ddr4_sdram_c0_cke),
        .ddr4_sdram_c0_cs_n(ddr4_sdram_c0_cs_n),
        .ddr4_sdram_c0_dq(ddr4_sdram_c0_dq),
        .ddr4_sdram_c0_dqs_c(ddr4_sdram_c0_dqs_c),
        .ddr4_sdram_c0_dqs_t(ddr4_sdram_c0_dqs_t),
        .ddr4_sdram_c0_odt(ddr4_sdram_c0_odt),
        .ddr4_sdram_c0_par(ddr4_sdram_c0_par),
        .ddr4_sdram_c0_reset_n(ddr4_sdram_c0_reset_n),
        .ddr4_sdram_c1_act_n(ddr4_sdram_c1_act_n),
        .ddr4_sdram_c1_adr(ddr4_sdram_c1_adr),
        .ddr4_sdram_c1_ba(ddr4_sdram_c1_ba),
        .ddr4_sdram_c1_bg(ddr4_sdram_c1_bg),
        .ddr4_sdram_c1_ck_c(ddr4_sdram_c1_ck_c),
        .ddr4_sdram_c1_ck_t(ddr4_sdram_c1_ck_t),
        .ddr4_sdram_c1_cke(ddr4_sdram_c1_cke),
        .ddr4_sdram_c1_cs_n(ddr4_sdram_c1_cs_n),
        .ddr4_sdram_c1_dq(ddr4_sdram_c1_dq),
        .ddr4_sdram_c1_dqs_c(ddr4_sdram_c1_dqs_c),
        .ddr4_sdram_c1_dqs_t(ddr4_sdram_c1_dqs_t),
        .ddr4_sdram_c1_odt(ddr4_sdram_c1_odt),
        .ddr4_sdram_c1_par(ddr4_sdram_c1_par),
        .ddr4_sdram_c1_reset_n(ddr4_sdram_c1_reset_n),
        .ddr4_sdram_c2_act_n(ddr4_sdram_c2_act_n),
        .ddr4_sdram_c2_adr(ddr4_sdram_c2_adr),
        .ddr4_sdram_c2_ba(ddr4_sdram_c2_ba),
        .ddr4_sdram_c2_bg(ddr4_sdram_c2_bg),
        .ddr4_sdram_c2_ck_c(ddr4_sdram_c2_ck_c),
        .ddr4_sdram_c2_ck_t(ddr4_sdram_c2_ck_t),
        .ddr4_sdram_c2_cke(ddr4_sdram_c2_cke),
        .ddr4_sdram_c2_cs_n(ddr4_sdram_c2_cs_n),
        .ddr4_sdram_c2_dq(ddr4_sdram_c2_dq),
        .ddr4_sdram_c2_dqs_c(ddr4_sdram_c2_dqs_c),
        .ddr4_sdram_c2_dqs_t(ddr4_sdram_c2_dqs_t),
        .ddr4_sdram_c2_odt(ddr4_sdram_c2_odt),
        .ddr4_sdram_c2_par(ddr4_sdram_c2_par),
        .ddr4_sdram_c2_reset_n(ddr4_sdram_c2_reset_n),
        .ddr4_sdram_c3_act_n(ddr4_sdram_c3_act_n),
        .ddr4_sdram_c3_adr(ddr4_sdram_c3_adr),
        .ddr4_sdram_c3_ba(ddr4_sdram_c3_ba),
        .ddr4_sdram_c3_bg(ddr4_sdram_c3_bg),
        .ddr4_sdram_c3_ck_c(ddr4_sdram_c3_ck_c),
        .ddr4_sdram_c3_ck_t(ddr4_sdram_c3_ck_t),
        .ddr4_sdram_c3_cke(ddr4_sdram_c3_cke),
        .ddr4_sdram_c3_cs_n(ddr4_sdram_c3_cs_n),
        .ddr4_sdram_c3_dq(ddr4_sdram_c3_dq),
        .ddr4_sdram_c3_dqs_c(ddr4_sdram_c3_dqs_c),
        .ddr4_sdram_c3_dqs_t(ddr4_sdram_c3_dqs_t),
        .ddr4_sdram_c3_odt(ddr4_sdram_c3_odt),
        .ddr4_sdram_c3_par(ddr4_sdram_c3_par),
        .ddr4_sdram_c3_reset_n(ddr4_sdram_c3_reset_n),
        .default_300mhz_clk0_clk_n(default_300mhz_clk0_clk_n),
        .default_300mhz_clk0_clk_p(default_300mhz_clk0_clk_p),
        .default_300mhz_clk1_clk_n(default_300mhz_clk1_clk_n),
        .default_300mhz_clk1_clk_p(default_300mhz_clk1_clk_p),
        .default_300mhz_clk2_clk_n(default_300mhz_clk2_clk_n),
        .default_300mhz_clk2_clk_p(default_300mhz_clk2_clk_p),
        .default_300mhz_clk3_clk_n(default_300mhz_clk3_clk_n),
        .default_300mhz_clk3_clk_p(default_300mhz_clk3_clk_p),
        .pci_express_x16_rxn(pci_express_x16_rxn),
        .pci_express_x16_rxp(pci_express_x16_rxp),
        .pci_express_x16_txn(pci_express_x16_txn),
        .pci_express_x16_txp(pci_express_x16_txp),
        .pcie_perstn(pcie_perstn),
        .pcie_refclk_clk_n(pcie_refclk_clk_n),
        .pcie_refclk_clk_p(pcie_refclk_clk_p),
        .resetn(resetn),

        .sys_clk(sys_clk),
        .sys_reset_n(sys_reset_n),

        .DDR4_0_S_AXI_araddr(DDR4_0_S_AXI_araddr),
        .DDR4_0_S_AXI_arburst(DDR4_0_S_AXI_arburst),
        .DDR4_0_S_AXI_arcache(DDR4_0_S_AXI_arcache),
        .DDR4_0_S_AXI_arid(DDR4_0_S_AXI_arid),
        .DDR4_0_S_AXI_arlen(DDR4_0_S_AXI_arlen),
        .DDR4_0_S_AXI_arlock(DDR4_0_S_AXI_arlock),
        .DDR4_0_S_AXI_arprot(DDR4_0_S_AXI_arprot),
        .DDR4_0_S_AXI_arqos(DDR4_0_S_AXI_arqos),
        .DDR4_0_S_AXI_arready(DDR4_0_S_AXI_arready),
        .DDR4_0_S_AXI_arregion(DDR4_0_S_AXI_arregion),
        .DDR4_0_S_AXI_arsize(DDR4_0_S_AXI_arsize),
        .DDR4_0_S_AXI_arvalid(DDR4_0_S_AXI_arvalid),
        .DDR4_0_S_AXI_awaddr(DDR4_0_S_AXI_awaddr),
        .DDR4_0_S_AXI_awburst(DDR4_0_S_AXI_awburst),
        .DDR4_0_S_AXI_awcache(DDR4_0_S_AXI_awcache),
        .DDR4_0_S_AXI_awid(DDR4_0_S_AXI_awid),
        .DDR4_0_S_AXI_awlen(DDR4_0_S_AXI_awlen),
        .DDR4_0_S_AXI_awlock(DDR4_0_S_AXI_awlock),
        .DDR4_0_S_AXI_awprot(DDR4_0_S_AXI_awprot),
        .DDR4_0_S_AXI_awqos(DDR4_0_S_AXI_awqos),
        .DDR4_0_S_AXI_awready(DDR4_0_S_AXI_awready),
        .DDR4_0_S_AXI_awregion(DDR4_0_S_AXI_awregion),
        .DDR4_0_S_AXI_awsize(DDR4_0_S_AXI_awsize),
        .DDR4_0_S_AXI_awvalid(DDR4_0_S_AXI_awvalid),
        .DDR4_0_S_AXI_bid(DDR4_0_S_AXI_bid),
        .DDR4_0_S_AXI_bready(DDR4_0_S_AXI_bready),
        .DDR4_0_S_AXI_bresp(DDR4_0_S_AXI_bresp),
        .DDR4_0_S_AXI_bvalid(DDR4_0_S_AXI_bvalid),
        .DDR4_0_S_AXI_rdata(DDR4_0_S_AXI_rdata),
        .DDR4_0_S_AXI_rid(DDR4_0_S_AXI_rid),
        .DDR4_0_S_AXI_rlast(DDR4_0_S_AXI_rlast),
        .DDR4_0_S_AXI_rready(DDR4_0_S_AXI_rready),
        .DDR4_0_S_AXI_rresp(DDR4_0_S_AXI_rresp),
        .DDR4_0_S_AXI_rvalid(DDR4_0_S_AXI_rvalid),
        .DDR4_0_S_AXI_wdata(DDR4_0_S_AXI_wdata),
        .DDR4_0_S_AXI_wlast(DDR4_0_S_AXI_wlast),
        .DDR4_0_S_AXI_wready(DDR4_0_S_AXI_wready),
        .DDR4_0_S_AXI_wstrb(DDR4_0_S_AXI_wstrb),
        .DDR4_0_S_AXI_wvalid(DDR4_0_S_AXI_wvalid),

        .DDR4_1_S_AXI_araddr(DDR4_1_S_AXI_araddr),
        .DDR4_1_S_AXI_arburst(DDR4_1_S_AXI_arburst),
        .DDR4_1_S_AXI_arcache(DDR4_1_S_AXI_arcache),
        .DDR4_1_S_AXI_arid(DDR4_1_S_AXI_arid),
        .DDR4_1_S_AXI_arlen(DDR4_1_S_AXI_arlen),
        .DDR4_1_S_AXI_arlock(DDR4_1_S_AXI_arlock),
        .DDR4_1_S_AXI_arprot(DDR4_1_S_AXI_arprot),
        .DDR4_1_S_AXI_arqos(DDR4_1_S_AXI_arqos),
        .DDR4_1_S_AXI_arready(DDR4_1_S_AXI_arready),
        .DDR4_1_S_AXI_arregion(DDR4_1_S_AXI_arregion),
        .DDR4_1_S_AXI_arsize(DDR4_1_S_AXI_arsize),
        .DDR4_1_S_AXI_arvalid(DDR4_1_S_AXI_arvalid),
        .DDR4_1_S_AXI_awaddr(DDR4_1_S_AXI_awaddr),
        .DDR4_1_S_AXI_awburst(DDR4_1_S_AXI_awburst),
        .DDR4_1_S_AXI_awcache(DDR4_1_S_AXI_awcache),
        .DDR4_1_S_AXI_awid(DDR4_1_S_AXI_awid),
        .DDR4_1_S_AXI_awlen(DDR4_1_S_AXI_awlen),
        .DDR4_1_S_AXI_awlock(DDR4_1_S_AXI_awlock),
        .DDR4_1_S_AXI_awprot(DDR4_1_S_AXI_awprot),
        .DDR4_1_S_AXI_awqos(DDR4_1_S_AXI_awqos),
        .DDR4_1_S_AXI_awready(DDR4_1_S_AXI_awready),
        .DDR4_1_S_AXI_awregion(DDR4_1_S_AXI_awregion),
        .DDR4_1_S_AXI_awsize(DDR4_1_S_AXI_awsize),
        .DDR4_1_S_AXI_awvalid(DDR4_1_S_AXI_awvalid),
        .DDR4_1_S_AXI_bid(DDR4_1_S_AXI_bid),
        .DDR4_1_S_AXI_bready(DDR4_1_S_AXI_bready),
        .DDR4_1_S_AXI_bresp(DDR4_1_S_AXI_bresp),
        .DDR4_1_S_AXI_bvalid(DDR4_1_S_AXI_bvalid),
        .DDR4_1_S_AXI_rdata(DDR4_1_S_AXI_rdata),
        .DDR4_1_S_AXI_rid(DDR4_1_S_AXI_rid),
        .DDR4_1_S_AXI_rlast(DDR4_1_S_AXI_rlast),
        .DDR4_1_S_AXI_rready(DDR4_1_S_AXI_rready),
        .DDR4_1_S_AXI_rresp(DDR4_1_S_AXI_rresp),
        .DDR4_1_S_AXI_rvalid(DDR4_1_S_AXI_rvalid),
        .DDR4_1_S_AXI_wdata(DDR4_1_S_AXI_wdata),
        .DDR4_1_S_AXI_wlast(DDR4_1_S_AXI_wlast),
        .DDR4_1_S_AXI_wready(DDR4_1_S_AXI_wready),
        .DDR4_1_S_AXI_wstrb(DDR4_1_S_AXI_wstrb),
        .DDR4_1_S_AXI_wvalid(DDR4_1_S_AXI_wvalid),

        .DDR4_2_S_AXI_araddr(DDR4_2_S_AXI_araddr),
        .DDR4_2_S_AXI_arburst(DDR4_2_S_AXI_arburst),
        .DDR4_2_S_AXI_arcache(DDR4_2_S_AXI_arcache),
        .DDR4_2_S_AXI_arid(DDR4_2_S_AXI_arid),
        .DDR4_2_S_AXI_arlen(DDR4_2_S_AXI_arlen),
        .DDR4_2_S_AXI_arlock(DDR4_2_S_AXI_arlock),
        .DDR4_2_S_AXI_arprot(DDR4_2_S_AXI_arprot),
        .DDR4_2_S_AXI_arqos(DDR4_2_S_AXI_arqos),
        .DDR4_2_S_AXI_arready(DDR4_2_S_AXI_arready),
        .DDR4_2_S_AXI_arregion(DDR4_2_S_AXI_arregion),
        .DDR4_2_S_AXI_arsize(DDR4_2_S_AXI_arsize),
        .DDR4_2_S_AXI_arvalid(DDR4_2_S_AXI_arvalid),
        .DDR4_2_S_AXI_awaddr(DDR4_2_S_AXI_awaddr),
        .DDR4_2_S_AXI_awburst(DDR4_2_S_AXI_awburst),
        .DDR4_2_S_AXI_awcache(DDR4_2_S_AXI_awcache),
        .DDR4_2_S_AXI_awid(DDR4_2_S_AXI_awid),
        .DDR4_2_S_AXI_awlen(DDR4_2_S_AXI_awlen),
        .DDR4_2_S_AXI_awlock(DDR4_2_S_AXI_awlock),
        .DDR4_2_S_AXI_awprot(DDR4_2_S_AXI_awprot),
        .DDR4_2_S_AXI_awqos(DDR4_2_S_AXI_awqos),
        .DDR4_2_S_AXI_awready(DDR4_2_S_AXI_awready),
        .DDR4_2_S_AXI_awregion(DDR4_2_S_AXI_awregion),
        .DDR4_2_S_AXI_awsize(DDR4_2_S_AXI_awsize),
        .DDR4_2_S_AXI_awvalid(DDR4_2_S_AXI_awvalid),
        .DDR4_2_S_AXI_bid(DDR4_2_S_AXI_bid),
        .DDR4_2_S_AXI_bready(DDR4_2_S_AXI_bready),
        .DDR4_2_S_AXI_bresp(DDR4_2_S_AXI_bresp),
        .DDR4_2_S_AXI_bvalid(DDR4_2_S_AXI_bvalid),
        .DDR4_2_S_AXI_rdata(DDR4_2_S_AXI_rdata),
        .DDR4_2_S_AXI_rid(DDR4_2_S_AXI_rid),
        .DDR4_2_S_AXI_rlast(DDR4_2_S_AXI_rlast),
        .DDR4_2_S_AXI_rready(DDR4_2_S_AXI_rready),
        .DDR4_2_S_AXI_rresp(DDR4_2_S_AXI_rresp),
        .DDR4_2_S_AXI_rvalid(DDR4_2_S_AXI_rvalid),
        .DDR4_2_S_AXI_wdata(DDR4_2_S_AXI_wdata),
        .DDR4_2_S_AXI_wlast(DDR4_2_S_AXI_wlast),
        .DDR4_2_S_AXI_wready(DDR4_2_S_AXI_wready),
        .DDR4_2_S_AXI_wstrb(DDR4_2_S_AXI_wstrb),
        .DDR4_2_S_AXI_wvalid(DDR4_2_S_AXI_wvalid),

        .DDR4_3_S_AXI_araddr(DDR4_3_S_AXI_araddr),
        .DDR4_3_S_AXI_arburst(DDR4_3_S_AXI_arburst),
        .DDR4_3_S_AXI_arcache(DDR4_3_S_AXI_arcache),
        .DDR4_3_S_AXI_arid(DDR4_3_S_AXI_arid),
        .DDR4_3_S_AXI_arlen(DDR4_3_S_AXI_arlen),
        .DDR4_3_S_AXI_arlock(DDR4_3_S_AXI_arlock),
        .DDR4_3_S_AXI_arprot(DDR4_3_S_AXI_arprot),
        .DDR4_3_S_AXI_arqos(DDR4_3_S_AXI_arqos),
        .DDR4_3_S_AXI_arready(DDR4_3_S_AXI_arready),
        .DDR4_3_S_AXI_arregion(DDR4_3_S_AXI_arregion),
        .DDR4_3_S_AXI_arsize(DDR4_3_S_AXI_arsize),
        .DDR4_3_S_AXI_arvalid(DDR4_3_S_AXI_arvalid),
        .DDR4_3_S_AXI_awaddr(DDR4_3_S_AXI_awaddr),
        .DDR4_3_S_AXI_awburst(DDR4_3_S_AXI_awburst),
        .DDR4_3_S_AXI_awcache(DDR4_3_S_AXI_awcache),
        .DDR4_3_S_AXI_awid(DDR4_3_S_AXI_awid),
        .DDR4_3_S_AXI_awlen(DDR4_3_S_AXI_awlen),
        .DDR4_3_S_AXI_awlock(DDR4_3_S_AXI_awlock),
        .DDR4_3_S_AXI_awprot(DDR4_3_S_AXI_awprot),
        .DDR4_3_S_AXI_awqos(DDR4_3_S_AXI_awqos),
        .DDR4_3_S_AXI_awready(DDR4_3_S_AXI_awready),
        .DDR4_3_S_AXI_awregion(DDR4_3_S_AXI_awregion),
        .DDR4_3_S_AXI_awsize(DDR4_3_S_AXI_awsize),
        .DDR4_3_S_AXI_awvalid(DDR4_3_S_AXI_awvalid),
        .DDR4_3_S_AXI_bid(DDR4_3_S_AXI_bid),
        .DDR4_3_S_AXI_bready(DDR4_3_S_AXI_bready),
        .DDR4_3_S_AXI_bresp(DDR4_3_S_AXI_bresp),
        .DDR4_3_S_AXI_bvalid(DDR4_3_S_AXI_bvalid),
        .DDR4_3_S_AXI_rdata(DDR4_3_S_AXI_rdata),
        .DDR4_3_S_AXI_rid(DDR4_3_S_AXI_rid),
        .DDR4_3_S_AXI_rlast(DDR4_3_S_AXI_rlast),
        .DDR4_3_S_AXI_rready(DDR4_3_S_AXI_rready),
        .DDR4_3_S_AXI_rresp(DDR4_3_S_AXI_rresp),
        .DDR4_3_S_AXI_rvalid(DDR4_3_S_AXI_rvalid),
        .DDR4_3_S_AXI_wdata(DDR4_3_S_AXI_wdata),
        .DDR4_3_S_AXI_wlast(DDR4_3_S_AXI_wlast),
        .DDR4_3_S_AXI_wready(DDR4_3_S_AXI_wready),
        .DDR4_3_S_AXI_wstrb(DDR4_3_S_AXI_wstrb),
        .DDR4_3_S_AXI_wvalid(DDR4_3_S_AXI_wvalid),

        .PCIE_M_AXI_LITE_araddr(PCIE_M_AXI_LITE_araddr),
        .PCIE_M_AXI_LITE_arprot(PCIE_M_AXI_LITE_arprot),
        .PCIE_M_AXI_LITE_arready(PCIE_M_AXI_LITE_arready),
        .PCIE_M_AXI_LITE_arvalid(PCIE_M_AXI_LITE_arvalid),
        .PCIE_M_AXI_LITE_awaddr(PCIE_M_AXI_LITE_awaddr),
        .PCIE_M_AXI_LITE_awprot(PCIE_M_AXI_LITE_awprot),
        .PCIE_M_AXI_LITE_awready(PCIE_M_AXI_LITE_awready),
        .PCIE_M_AXI_LITE_awvalid(PCIE_M_AXI_LITE_awvalid),
        .PCIE_M_AXI_LITE_bready(PCIE_M_AXI_LITE_bready),
        .PCIE_M_AXI_LITE_bresp(PCIE_M_AXI_LITE_bresp),
        .PCIE_M_AXI_LITE_bvalid(PCIE_M_AXI_LITE_bvalid),
        .PCIE_M_AXI_LITE_rdata(PCIE_M_AXI_LITE_rdata),
        .PCIE_M_AXI_LITE_rready(PCIE_M_AXI_LITE_rready),
        .PCIE_M_AXI_LITE_rresp(PCIE_M_AXI_LITE_rresp),
        .PCIE_M_AXI_LITE_rvalid(PCIE_M_AXI_LITE_rvalid),
        .PCIE_M_AXI_LITE_wdata(PCIE_M_AXI_LITE_wdata),
        .PCIE_M_AXI_LITE_wready(PCIE_M_AXI_LITE_wready),
        .PCIE_M_AXI_LITE_wstrb(PCIE_M_AXI_LITE_wstrb),
        .PCIE_M_AXI_LITE_wvalid(PCIE_M_AXI_LITE_wvalid),

        .PCIE_M_AXI_araddr(PCIE_M_AXI_araddr),
        .PCIE_M_AXI_arburst(PCIE_M_AXI_arburst),
        .PCIE_M_AXI_arcache(PCIE_M_AXI_arcache),
        .PCIE_M_AXI_arid(PCIE_M_AXI_arid),
        .PCIE_M_AXI_arlen(PCIE_M_AXI_arlen),
        .PCIE_M_AXI_arlock(PCIE_M_AXI_arlock),
        .PCIE_M_AXI_arprot(PCIE_M_AXI_arprot),
        .PCIE_M_AXI_arqos(PCIE_M_AXI_arqos),
        .PCIE_M_AXI_arready(PCIE_M_AXI_arready),
        .PCIE_M_AXI_arregion(PCIE_M_AXI_arregion),
        .PCIE_M_AXI_arsize(PCIE_M_AXI_arsize),
        .PCIE_M_AXI_arvalid(PCIE_M_AXI_arvalid),
        .PCIE_M_AXI_awaddr(PCIE_M_AXI_awaddr),
        .PCIE_M_AXI_awburst(PCIE_M_AXI_awburst),
        .PCIE_M_AXI_awcache(PCIE_M_AXI_awcache),
        .PCIE_M_AXI_awid(PCIE_M_AXI_awid),
        .PCIE_M_AXI_awlen(PCIE_M_AXI_awlen),
        .PCIE_M_AXI_awlock(PCIE_M_AXI_awlock),
        .PCIE_M_AXI_awprot(PCIE_M_AXI_awprot),
        .PCIE_M_AXI_awqos(PCIE_M_AXI_awqos),
        .PCIE_M_AXI_awready(PCIE_M_AXI_awready),
        .PCIE_M_AXI_awregion(PCIE_M_AXI_awregion),
        .PCIE_M_AXI_awsize(PCIE_M_AXI_awsize),
        .PCIE_M_AXI_awvalid(PCIE_M_AXI_awvalid),
        .PCIE_M_AXI_bid(PCIE_M_AXI_bid),
        .PCIE_M_AXI_bready(PCIE_M_AXI_bready),
        .PCIE_M_AXI_bresp(PCIE_M_AXI_bresp),
        .PCIE_M_AXI_bvalid(PCIE_M_AXI_bvalid),
        .PCIE_M_AXI_rdata(PCIE_M_AXI_rdata),
        .PCIE_M_AXI_rid(PCIE_M_AXI_rid),
        .PCIE_M_AXI_rlast(PCIE_M_AXI_rlast),
        .PCIE_M_AXI_rready(PCIE_M_AXI_rready),
        .PCIE_M_AXI_rresp(PCIE_M_AXI_rresp),
        .PCIE_M_AXI_rvalid(PCIE_M_AXI_rvalid),
        .PCIE_M_AXI_wdata(PCIE_M_AXI_wdata),
        .PCIE_M_AXI_wlast(PCIE_M_AXI_wlast),
        .PCIE_M_AXI_wready(PCIE_M_AXI_wready),
        .PCIE_M_AXI_wstrb(PCIE_M_AXI_wstrb),
        .PCIE_M_AXI_wvalid(PCIE_M_AXI_wvalid)
    );

    firesim_wrapper firesim_wrapper_i (
        .M_AXI_DDR0_araddr(DDR4_0_S_AXI_araddr),
        .M_AXI_DDR0_arburst(DDR4_0_S_AXI_arburst),
        .M_AXI_DDR0_arcache(DDR4_0_S_AXI_arcache),
        .M_AXI_DDR0_arid(DDR4_0_S_AXI_arid),
        .M_AXI_DDR0_arlen(DDR4_0_S_AXI_arlen),
        .M_AXI_DDR0_arlock(DDR4_0_S_AXI_arlock),
        .M_AXI_DDR0_arprot(DDR4_0_S_AXI_arprot),
        .M_AXI_DDR0_arqos(DDR4_0_S_AXI_arqos),
        .M_AXI_DDR0_arready(DDR4_0_S_AXI_arready),
        .M_AXI_DDR0_arregion(DDR4_0_S_AXI_arregion),
        .M_AXI_DDR0_arsize(DDR4_0_S_AXI_arsize),
        .M_AXI_DDR0_arvalid(DDR4_0_S_AXI_arvalid),
        .M_AXI_DDR0_awaddr(DDR4_0_S_AXI_awaddr),
        .M_AXI_DDR0_awburst(DDR4_0_S_AXI_awburst),
        .M_AXI_DDR0_awcache(DDR4_0_S_AXI_awcache),
        .M_AXI_DDR0_awid(DDR4_0_S_AXI_awid),
        .M_AXI_DDR0_awlen(DDR4_0_S_AXI_awlen),
        .M_AXI_DDR0_awlock(DDR4_0_S_AXI_awlock),
        .M_AXI_DDR0_awprot(DDR4_0_S_AXI_awprot),
        .M_AXI_DDR0_awqos(DDR4_0_S_AXI_awqos),
        .M_AXI_DDR0_awready(DDR4_0_S_AXI_awready),
        .M_AXI_DDR0_awregion(DDR4_0_S_AXI_awregion),
        .M_AXI_DDR0_awsize(DDR4_0_S_AXI_awsize),
        .M_AXI_DDR0_awvalid(DDR4_0_S_AXI_awvalid),
        .M_AXI_DDR0_bready(DDR4_0_S_AXI_bid),
        .M_AXI_DDR0_bid(DDR4_0_S_AXI_bready),
        .M_AXI_DDR0_bresp(DDR4_0_S_AXI_bresp),
        .M_AXI_DDR0_bvalid(DDR4_0_S_AXI_bvalid),
        .M_AXI_DDR0_rdata(DDR4_0_S_AXI_rdata),
        .M_AXI_DDR0_rid(DDR4_0_S_AXI_rid),
        .M_AXI_DDR0_rlast(DDR4_0_S_AXI_rlast),
        .M_AXI_DDR0_rready(DDR4_0_S_AXI_rready),
        .M_AXI_DDR0_rresp(DDR4_0_S_AXI_rresp),
        .M_AXI_DDR0_rvalid(DDR4_0_S_AXI_rvalid),
        .M_AXI_DDR0_wdata(DDR4_0_S_AXI_wdata),
        .M_AXI_DDR0_wlast(DDR4_0_S_AXI_wlast),
        .M_AXI_DDR0_wready(DDR4_0_S_AXI_wready),
        .M_AXI_DDR0_wstrb(DDR4_0_S_AXI_wstrb),
        .M_AXI_DDR0_wvalid(DDR4_0_S_AXI_wvalid),

        .M_AXI_DDR1_araddr(DDR4_1_S_AXI_araddr),
        .M_AXI_DDR1_arburst(DDR4_1_S_AXI_arburst),
        .M_AXI_DDR1_arcache(DDR4_1_S_AXI_arcache),
        .M_AXI_DDR1_arid(DDR4_1_S_AXI_arid),
        .M_AXI_DDR1_arlen(DDR4_1_S_AXI_arlen),
        .M_AXI_DDR1_arlock(DDR4_1_S_AXI_arlock),
        .M_AXI_DDR1_arprot(DDR4_1_S_AXI_arprot),
        .M_AXI_DDR1_arqos(DDR4_1_S_AXI_arqos),
        .M_AXI_DDR1_arready(DDR4_1_S_AXI_arready),
        .M_AXI_DDR1_arregion(DDR4_1_S_AXI_arregion),
        .M_AXI_DDR1_arsize(DDR4_1_S_AXI_arsize),
        .M_AXI_DDR1_arvalid(DDR4_1_S_AXI_arvalid),
        .M_AXI_DDR1_awaddr(DDR4_1_S_AXI_awaddr),
        .M_AXI_DDR1_awburst(DDR4_1_S_AXI_awburst),
        .M_AXI_DDR1_awcache(DDR4_1_S_AXI_awcache),
        .M_AXI_DDR1_awid(DDR4_1_S_AXI_awid),
        .M_AXI_DDR1_awlen(DDR4_1_S_AXI_awlen),
        .M_AXI_DDR1_awlock(DDR4_1_S_AXI_awlock),
        .M_AXI_DDR1_awprot(DDR4_1_S_AXI_awprot),
        .M_AXI_DDR1_awqos(DDR4_1_S_AXI_awqos),
        .M_AXI_DDR1_awready(DDR4_1_S_AXI_awready),
        .M_AXI_DDR1_awregion(DDR4_1_S_AXI_awregion),
        .M_AXI_DDR1_awsize(DDR4_1_S_AXI_awsize),
        .M_AXI_DDR1_awvalid(DDR4_1_S_AXI_awvalid),
        .M_AXI_DDR1_bready(DDR4_1_S_AXI_bid),
        .M_AXI_DDR1_bid(DDR4_1_S_AXI_bready),
        .M_AXI_DDR1_bresp(DDR4_1_S_AXI_bresp),
        .M_AXI_DDR1_bvalid(DDR4_1_S_AXI_bvalid),
        .M_AXI_DDR1_rdata(DDR4_1_S_AXI_rdata),
        .M_AXI_DDR1_rid(DDR4_1_S_AXI_rid),
        .M_AXI_DDR1_rlast(DDR4_1_S_AXI_rlast),
        .M_AXI_DDR1_rready(DDR4_1_S_AXI_rready),
        .M_AXI_DDR1_rresp(DDR4_1_S_AXI_rresp),
        .M_AXI_DDR1_rvalid(DDR4_1_S_AXI_rvalid),
        .M_AXI_DDR1_wdata(DDR4_1_S_AXI_wdata),
        .M_AXI_DDR1_wlast(DDR4_1_S_AXI_wlast),
        .M_AXI_DDR1_wready(DDR4_1_S_AXI_wready),
        .M_AXI_DDR1_wstrb(DDR4_1_S_AXI_wstrb),
        .M_AXI_DDR1_wvalid(DDR4_1_S_AXI_wvalid),

        .M_AXI_DDR2_araddr(DDR4_2_S_AXI_araddr),
        .M_AXI_DDR2_arburst(DDR4_2_S_AXI_arburst),
        .M_AXI_DDR2_arcache(DDR4_2_S_AXI_arcache),
        .M_AXI_DDR2_arid(DDR4_2_S_AXI_arid),
        .M_AXI_DDR2_arlen(DDR4_2_S_AXI_arlen),
        .M_AXI_DDR2_arlock(DDR4_2_S_AXI_arlock),
        .M_AXI_DDR2_arprot(DDR4_2_S_AXI_arprot),
        .M_AXI_DDR2_arqos(DDR4_2_S_AXI_arqos),
        .M_AXI_DDR2_arready(DDR4_2_S_AXI_arready),
        .M_AXI_DDR2_arregion(DDR4_2_S_AXI_arregion),
        .M_AXI_DDR2_arsize(DDR4_2_S_AXI_arsize),
        .M_AXI_DDR2_arvalid(DDR4_2_S_AXI_arvalid),
        .M_AXI_DDR2_awaddr(DDR4_2_S_AXI_awaddr),
        .M_AXI_DDR2_awburst(DDR4_2_S_AXI_awburst),
        .M_AXI_DDR2_awcache(DDR4_2_S_AXI_awcache),
        .M_AXI_DDR2_awid(DDR4_2_S_AXI_awid),
        .M_AXI_DDR2_awlen(DDR4_2_S_AXI_awlen),
        .M_AXI_DDR2_awlock(DDR4_2_S_AXI_awlock),
        .M_AXI_DDR2_awprot(DDR4_2_S_AXI_awprot),
        .M_AXI_DDR2_awqos(DDR4_2_S_AXI_awqos),
        .M_AXI_DDR2_awready(DDR4_2_S_AXI_awready),
        .M_AXI_DDR2_awregion(DDR4_2_S_AXI_awregion),
        .M_AXI_DDR2_awsize(DDR4_2_S_AXI_awsize),
        .M_AXI_DDR2_awvalid(DDR4_2_S_AXI_awvalid),
        .M_AXI_DDR2_bready(DDR4_2_S_AXI_bid),
        .M_AXI_DDR2_bid(DDR4_2_S_AXI_bready),
        .M_AXI_DDR2_bresp(DDR4_2_S_AXI_bresp),
        .M_AXI_DDR2_bvalid(DDR4_2_S_AXI_bvalid),
        .M_AXI_DDR2_rdata(DDR4_2_S_AXI_rdata),
        .M_AXI_DDR2_rid(DDR4_2_S_AXI_rid),
        .M_AXI_DDR2_rlast(DDR4_2_S_AXI_rlast),
        .M_AXI_DDR2_rready(DDR4_2_S_AXI_rready),
        .M_AXI_DDR2_rresp(DDR4_2_S_AXI_rresp),
        .M_AXI_DDR2_rvalid(DDR4_2_S_AXI_rvalid),
        .M_AXI_DDR2_wdata(DDR4_2_S_AXI_wdata),
        .M_AXI_DDR2_wlast(DDR4_2_S_AXI_wlast),
        .M_AXI_DDR2_wready(DDR4_2_S_AXI_wready),
        .M_AXI_DDR2_wstrb(DDR4_2_S_AXI_wstrb),
        .M_AXI_DDR2_wvalid(DDR4_2_S_AXI_wvalid),

        .M_AXI_DDR3_araddr(DDR4_3_S_AXI_araddr),
        .M_AXI_DDR3_arburst(DDR4_3_S_AXI_arburst),
        .M_AXI_DDR3_arcache(DDR4_3_S_AXI_arcache),
        .M_AXI_DDR3_arid(DDR4_3_S_AXI_arid),
        .M_AXI_DDR3_arlen(DDR4_3_S_AXI_arlen),
        .M_AXI_DDR3_arlock(DDR4_3_S_AXI_arlock),
        .M_AXI_DDR3_arprot(DDR4_3_S_AXI_arprot),
        .M_AXI_DDR3_arqos(DDR4_3_S_AXI_arqos),
        .M_AXI_DDR3_arready(DDR4_3_S_AXI_arready),
        .M_AXI_DDR3_arregion(DDR4_3_S_AXI_arregion),
        .M_AXI_DDR3_arsize(DDR4_3_S_AXI_arsize),
        .M_AXI_DDR3_arvalid(DDR4_3_S_AXI_arvalid),
        .M_AXI_DDR3_awaddr(DDR4_3_S_AXI_awaddr),
        .M_AXI_DDR3_awburst(DDR4_3_S_AXI_awburst),
        .M_AXI_DDR3_awcache(DDR4_3_S_AXI_awcache),
        .M_AXI_DDR3_awid(DDR4_3_S_AXI_awid),
        .M_AXI_DDR3_awlen(DDR4_3_S_AXI_awlen),
        .M_AXI_DDR3_awlock(DDR4_3_S_AXI_awlock),
        .M_AXI_DDR3_awprot(DDR4_3_S_AXI_awprot),
        .M_AXI_DDR3_awqos(DDR4_3_S_AXI_awqos),
        .M_AXI_DDR3_awready(DDR4_3_S_AXI_awready),
        .M_AXI_DDR3_awregion(DDR4_3_S_AXI_awregion),
        .M_AXI_DDR3_awsize(DDR4_3_S_AXI_awsize),
        .M_AXI_DDR3_awvalid(DDR4_3_S_AXI_awvalid),
        .M_AXI_DDR3_bready(DDR4_3_S_AXI_bid),
        .M_AXI_DDR3_bid(DDR4_3_S_AXI_bready),
        .M_AXI_DDR3_bresp(DDR4_3_S_AXI_bresp),
        .M_AXI_DDR3_bvalid(DDR4_3_S_AXI_bvalid),
        .M_AXI_DDR3_rdata(DDR4_3_S_AXI_rdata),
        .M_AXI_DDR3_rid(DDR4_3_S_AXI_rid),
        .M_AXI_DDR3_rlast(DDR4_3_S_AXI_rlast),
        .M_AXI_DDR3_rready(DDR4_3_S_AXI_rready),
        .M_AXI_DDR3_rresp(DDR4_3_S_AXI_rresp),
        .M_AXI_DDR3_rvalid(DDR4_3_S_AXI_rvalid),
        .M_AXI_DDR3_wdata(DDR4_3_S_AXI_wdata),
        .M_AXI_DDR3_wlast(DDR4_3_S_AXI_wlast),
        .M_AXI_DDR3_wready(DDR4_3_S_AXI_wready),
        .M_AXI_DDR3_wstrb(DDR4_3_S_AXI_wstrb),
        .M_AXI_DDR3_wvalid(DDR4_3_S_AXI_wvalid),

        .S_AXI_CTRL_araddr(PCIE_M_AXI_LITE_araddr),
        .S_AXI_CTRL_arprot(PCIE_M_AXI_LITE_arprot),
        .S_AXI_CTRL_arready(PCIE_M_AXI_LITE_arready),
        .S_AXI_CTRL_arvalid(PCIE_M_AXI_LITE_arvalid),
        .S_AXI_CTRL_awaddr(PCIE_M_AXI_LITE_awaddr),
        .S_AXI_CTRL_awprot(PCIE_M_AXI_LITE_awprot),
        .S_AXI_CTRL_awready(PCIE_M_AXI_LITE_awready),
        .S_AXI_CTRL_awvalid(PCIE_M_AXI_LITE_awvalid),
        .S_AXI_CTRL_bready(PCIE_M_AXI_LITE_bready),
        .S_AXI_CTRL_bresp(PCIE_M_AXI_LITE_bresp),
        .S_AXI_CTRL_bvalid(PCIE_M_AXI_LITE_bvalid),
        .S_AXI_CTRL_rdata(PCIE_M_AXI_LITE_rdata),
        .S_AXI_CTRL_rready(PCIE_M_AXI_LITE_rready),
        .S_AXI_CTRL_rresp(PCIE_M_AXI_LITE_rresp),
        .S_AXI_CTRL_rvalid(PCIE_M_AXI_LITE_rvalid),
        .S_AXI_CTRL_wdata(PCIE_M_AXI_LITE_wdata),
        .S_AXI_CTRL_wready(PCIE_M_AXI_LITE_wready),
        .S_AXI_CTRL_wstrb(PCIE_M_AXI_LITE_wstrb),
        .S_AXI_CTRL_wvalid(PCIE_M_AXI_LITE_wvalid),

        .S_AXI_DMA_araddr(PCIE_M_AXI_araddr),
        .S_AXI_DMA_arburst(PCIE_M_AXI_arburst),
        .S_AXI_DMA_arcache(PCIE_M_AXI_arcache),
        .S_AXI_DMA_arid(PCIE_M_AXI_arid),
        .S_AXI_DMA_arlen(PCIE_M_AXI_arlen),
        .S_AXI_DMA_arlock(PCIE_M_AXI_arlock),
        .S_AXI_DMA_arprot(PCIE_M_AXI_arprot),
        .S_AXI_DMA_arqos(PCIE_M_AXI_arqos),
        .S_AXI_DMA_arready(PCIE_M_AXI_arready),
        .S_AXI_DMA_arregion(PCIE_M_AXI_arregion),
        .S_AXI_DMA_arsize(PCIE_M_AXI_arsize),
        .S_AXI_DMA_arvalid(PCIE_M_AXI_arvalid),
        .S_AXI_DMA_awaddr(PCIE_M_AXI_awaddr),
        .S_AXI_DMA_awburst(PCIE_M_AXI_awburst),
        .S_AXI_DMA_awcache(PCIE_M_AXI_awcache),
        .S_AXI_DMA_awid(PCIE_M_AXI_awid),
        .S_AXI_DMA_awlen(PCIE_M_AXI_awlen),
        .S_AXI_DMA_awlock(PCIE_M_AXI_awlock),
        .S_AXI_DMA_awprot(PCIE_M_AXI_awprot),
        .S_AXI_DMA_awqos(PCIE_M_AXI_awqos),
        .S_AXI_DMA_awready(PCIE_M_AXI_awready),
        .S_AXI_DMA_awregion(PCIE_M_AXI_awregion),
        .S_AXI_DMA_awsize(PCIE_M_AXI_awsize),
        .S_AXI_DMA_awvalid(PCIE_M_AXI_awvalid),
        .S_AXI_DMA_bid(PCIE_M_AXI_bid),
        .S_AXI_DMA_bready(PCIE_M_AXI_bready),
        .S_AXI_DMA_bresp(PCIE_M_AXI_bresp),
        .S_AXI_DMA_bvalid(PCIE_M_AXI_bvalid),
        .S_AXI_DMA_rdata(PCIE_M_AXI_rdata),
        .S_AXI_DMA_rid(PCIE_M_AXI_rid),
        .S_AXI_DMA_rlast(PCIE_M_AXI_rlast),
        .S_AXI_DMA_rready(PCIE_M_AXI_rready),
        .S_AXI_DMA_rresp(PCIE_M_AXI_rresp),
        .S_AXI_DMA_rvalid(PCIE_M_AXI_rvalid),
        .S_AXI_DMA_wdata(PCIE_M_AXI_wdata),
        .S_AXI_DMA_wlast(PCIE_M_AXI_wlast),
        .S_AXI_DMA_wready(PCIE_M_AXI_wready),
        .S_AXI_DMA_wstrb(PCIE_M_AXI_wstrb),
        .S_AXI_DMA_wvalid(PCIE_M_AXI_wvalid),

        .sys_clk(sys_clk),
        .sys_reset_n(sys_reset_n)
    );

endmodule
