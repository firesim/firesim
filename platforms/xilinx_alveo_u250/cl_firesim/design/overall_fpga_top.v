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

    F1Shim firesim_top(
        .clock(sys_clk),
        .reset(!sys_reset_n),

        .io_master_aw_ready(PCIE_M_AXI_LITE_awready),
        .io_master_aw_valid(PCIE_M_AXI_LITE_awvalid),
        .io_master_aw_bits_addr(PCIE_M_AXI_LITE_awaddr[24:0]),
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

        .io_master_w_ready(PCIE_M_AXI_LITE_wready),
        .io_master_w_valid(PCIE_M_AXI_LITE_wvalid),
        .io_master_w_bits_data(PCIE_M_AXI_LITE_wdata),
        .io_master_w_bits_last(1'h1),
        .io_master_w_bits_id(12'h0),
        .io_master_w_bits_strb(PCIE_M_AXI_LITE_wstrb), //OR 8'hff
        .io_master_w_bits_user(1'h0),

        .io_master_b_ready(PCIE_M_AXI_LITE_bready),
        .io_master_b_valid(PCIE_M_AXI_LITE_bvalid),
        .io_master_b_bits_resp(PCIE_M_AXI_LITE_bresp),
        .io_master_b_bits_id(),      // UNUSED at top level
        .io_master_b_bits_user(),    // UNUSED at top level

        .io_master_ar_ready(PCIE_M_AXI_LITE_arready),
        .io_master_ar_valid(PCIE_M_AXI_LITE_arvalid),
        .io_master_ar_bits_addr(PCIE_M_AXI_LITE_araddr[24:0]),
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

        .io_master_r_ready(PCIE_M_AXI_LITE_rready),
        .io_master_r_valid(PCIE_M_AXI_LITE_rvalid),
        .io_master_r_bits_resp(PCIE_M_AXI_LITE_rresp),
        .io_master_r_bits_data(PCIE_M_AXI_LITE_rdata),
        .io_master_r_bits_last(), //UNUSED at top level
        .io_master_r_bits_id(),      // UNUSED at top level
        .io_master_r_bits_user(),    // UNUSED at top level

        // special NIC master interface
        .io_dma_aw_ready(PCIE_M_AXI_awready),
        .io_dma_aw_valid(PCIE_M_AXI_awvalid),
        .io_dma_aw_bits_addr(PCIE_M_AXI_awaddr),
        .io_dma_aw_bits_len(PCIE_M_AXI_awlen),
        .io_dma_aw_bits_size(PCIE_M_AXI_awsize),
        .io_dma_aw_bits_burst(2'h1), // PCIE_M_AXI_awburst
        .io_dma_aw_bits_lock(1'h0), // PCIE_M_AXI_awlock
        .io_dma_aw_bits_cache(4'h0), // PCIE_M_AXI_awcache
        .io_dma_aw_bits_prot(3'h0), //unused? (could connect?) PCIE_M_AXI_awprot
        .io_dma_aw_bits_qos(4'h0), // PCIE_M_AXI_awqos
        .io_dma_aw_bits_region(4'h0), // PCIE_M_AXI_awregion
        .io_dma_aw_bits_id(PCIE_M_AXI_awid),
        .io_dma_aw_bits_user(1'h0),

        .io_dma_w_ready(PCIE_M_AXI_wready),
        .io_dma_w_valid(PCIE_M_AXI_wvalid),
        .io_dma_w_bits_data(PCIE_M_AXI_wdata),
        .io_dma_w_bits_last(PCIE_M_AXI_wlast),
        .io_dma_w_bits_id(4'h0),
        .io_dma_w_bits_strb(PCIE_M_AXI_wstrb),
        .io_dma_w_bits_user(1'h0),

        .io_dma_b_ready(PCIE_M_AXI_bready),
        .io_dma_b_valid(PCIE_M_AXI_bvalid),
        .io_dma_b_bits_resp(PCIE_M_AXI_bresp),
        .io_dma_b_bits_id(PCIE_M_AXI_bid),
        .io_dma_b_bits_user(),    // UNUSED at top level

        .io_dma_ar_ready(PCIE_M_AXI_arready),
        .io_dma_ar_valid(PCIE_M_AXI_arvalid),
        .io_dma_ar_bits_addr(PCIE_M_AXI_araddr),
        .io_dma_ar_bits_len(PCIE_M_AXI_arlen),
        .io_dma_ar_bits_size(PCIE_M_AXI_arsize),
        .io_dma_ar_bits_burst(2'h1), // PCIE_M_AXI_arburst
        .io_dma_ar_bits_lock(1'h0), // PCIE_M_AXI_arlock
        .io_dma_ar_bits_cache(4'h0), // PCIE_M_AXI_arcache
        .io_dma_ar_bits_prot(3'h0), // PCIE_M_AXI_arprot
        .io_dma_ar_bits_qos(4'h0), // PCIE_M_AXI_arqos
        .io_dma_ar_bits_region(4'h0), // PCIE_M_AXI_arregion
        .io_dma_ar_bits_id(PCIE_M_AXI_arid),
        .io_dma_ar_bits_user(1'h0),

        .io_dma_r_ready(PCIE_M_AXI_rready),
        .io_dma_r_valid(PCIE_M_AXI_rvalid),
        .io_dma_r_bits_resp(PCIE_M_AXI_rresp),
        .io_dma_r_bits_data(PCIE_M_AXI_rdata),
        .io_dma_r_bits_last(PCIE_M_AXI_rlast),
        .io_dma_r_bits_id(PCIE_M_AXI_rid),
        .io_dma_r_bits_user(),    // UNUSED at top level

        // `include "firesim_ila_insert_ports.v"

        .io_slave_0_aw_ready(DDR4_0_S_AXI_awready),
        .io_slave_0_aw_valid(DDR4_0_S_AXI_awvalid),
        .io_slave_0_aw_bits_addr(DDR4_0_S_AXI_awaddr),
        .io_slave_0_aw_bits_len(DDR4_0_S_AXI_awlen),
        .io_slave_0_aw_bits_size(DDR4_0_S_AXI_awsize),
        .io_slave_0_aw_bits_burst(DDR4_0_S_AXI_awburst), // not available on DDR IF
        .io_slave_0_aw_bits_lock(DDR4_0_S_AXI_awlock), // not available on DDR IF
        .io_slave_0_aw_bits_cache(DDR4_0_S_AXI_awcache), // not available on DDR IF
        .io_slave_0_aw_bits_prot(DDR4_0_S_AXI_awprot), // not available on DDR IF
        .io_slave_0_aw_bits_qos(DDR4_0_S_AXI_awqos), // not available on DDR IF
        .io_slave_0_aw_bits_id(DDR4_0_S_AXI_awid),

        .io_slave_0_w_ready(DDR4_0_S_AXI_wready),
        .io_slave_0_w_valid(DDR4_0_S_AXI_wvalid),
        .io_slave_0_w_bits_data(DDR4_0_S_AXI_wdata),
        .io_slave_0_w_bits_last(DDR4_0_S_AXI_wlast),
        .io_slave_0_w_bits_strb(DDR4_0_S_AXI_wstrb),

        .io_slave_0_b_ready(DDR4_0_S_AXI_bready),
        .io_slave_0_b_valid(DDR4_0_S_AXI_bvalid),
        .io_slave_0_b_bits_resp(DDR4_0_S_AXI_bresp),
        .io_slave_0_b_bits_id(DDR4_0_S_AXI_bid),

        .io_slave_0_ar_ready(DDR4_0_S_AXI_arready),
        .io_slave_0_ar_valid(DDR4_0_S_AXI_arvalid),
        .io_slave_0_ar_bits_addr(DDR4_0_S_AXI_araddr),
        .io_slave_0_ar_bits_len(DDR4_0_S_AXI_arlen),
        .io_slave_0_ar_bits_size(DDR4_0_S_AXI_arsize),
        .io_slave_0_ar_bits_burst(DDR4_0_S_AXI_arburst), // not available on DDR IF
        .io_slave_0_ar_bits_lock(DDR4_0_S_AXI_arlock), // not available on DDR IF
        .io_slave_0_ar_bits_cache(DDR4_0_S_AXI_arcache), // not available on DDR IF
        .io_slave_0_ar_bits_prot(DDR4_0_S_AXI_arprot), // not available on DDR IF
        .io_slave_0_ar_bits_qos(DDR4_0_S_AXI_arqos), // not available on DDR IF
        .io_slave_0_ar_bits_id(DDR4_0_S_AXI_arid), // not available on DDR IF

        .io_slave_0_r_ready(DDR4_0_S_AXI_rready),
        .io_slave_0_r_valid(DDR4_0_S_AXI_rvalid),
        .io_slave_0_r_bits_resp(DDR4_0_S_AXI_rresp),
        .io_slave_0_r_bits_data(DDR4_0_S_AXI_rdata),
        .io_slave_0_r_bits_last(DDR4_0_S_AXI_rlast),
        .io_slave_0_r_bits_id(DDR4_0_S_AXI_rid),

        .io_slave_1_aw_ready(DDR4_1_S_AXI_awready),
        .io_slave_1_aw_valid(DDR4_1_S_AXI_awvalid),
        .io_slave_1_aw_bits_addr(DDR4_1_S_AXI_awaddr),
        .io_slave_1_aw_bits_len(DDR4_1_S_AXI_awlen),
        .io_slave_1_aw_bits_size(DDR4_1_S_AXI_awsize),
        .io_slave_1_aw_bits_burst(DDR4_1_S_AXI_awburst), // not available on DDR IF
        .io_slave_1_aw_bits_lock(DDR4_1_S_AXI_awlock), // not available on DDR IF
        .io_slave_1_aw_bits_cache(DDR4_1_S_AXI_awcache), // not available on DDR IF
        .io_slave_1_aw_bits_prot(DDR4_1_S_AXI_awprot), // not available on DDR IF
        .io_slave_1_aw_bits_qos(DDR4_1_S_AXI_awqos), // not available on DDR IF
        .io_slave_1_aw_bits_id(DDR4_1_S_AXI_awid),

        .io_slave_1_w_ready(DDR4_1_S_AXI_wready),
        .io_slave_1_w_valid(DDR4_1_S_AXI_wvalid),
        .io_slave_1_w_bits_data(DDR4_1_S_AXI_wdata),
        .io_slave_1_w_bits_last(DDR4_1_S_AXI_wlast),
        .io_slave_1_w_bits_strb(DDR4_1_S_AXI_wstrb),

        .io_slave_1_b_ready(DDR4_1_S_AXI_bready),
        .io_slave_1_b_valid(DDR4_1_S_AXI_bvalid),
        .io_slave_1_b_bits_resp(DDR4_1_S_AXI_bresp),
        .io_slave_1_b_bits_id(DDR4_1_S_AXI_bid),

        .io_slave_1_ar_ready(DDR4_1_S_AXI_arready),
        .io_slave_1_ar_valid(DDR4_1_S_AXI_arvalid),
        .io_slave_1_ar_bits_addr(DDR4_1_S_AXI_araddr),
        .io_slave_1_ar_bits_len(DDR4_1_S_AXI_arlen),
        .io_slave_1_ar_bits_size(DDR4_1_S_AXI_arsize),
        .io_slave_1_ar_bits_burst(DDR4_1_S_AXI_arburst), // not available on DDR IF
        .io_slave_1_ar_bits_lock(DDR4_1_S_AXI_arlock), // not available on DDR IF
        .io_slave_1_ar_bits_cache(DDR4_1_S_AXI_arcache), // not available on DDR IF
        .io_slave_1_ar_bits_prot(DDR4_1_S_AXI_arprot), // not available on DDR IF
        .io_slave_1_ar_bits_qos(DDR4_1_S_AXI_arqos), // not available on DDR IF
        .io_slave_1_ar_bits_id(DDR4_1_S_AXI_arid), // not available on DDR IF

        .io_slave_1_r_ready(DDR4_1_S_AXI_rready),
        .io_slave_1_r_valid(DDR4_1_S_AXI_rvalid),
        .io_slave_1_r_bits_resp(DDR4_1_S_AXI_rresp),
        .io_slave_1_r_bits_data(DDR4_1_S_AXI_rdata),
        .io_slave_1_r_bits_last(DDR4_1_S_AXI_rlast),
        .io_slave_1_r_bits_id(DDR4_1_S_AXI_rid),

        .io_slave_2_aw_ready(DDR4_2_S_AXI_awready),
        .io_slave_2_aw_valid(DDR4_2_S_AXI_awvalid),
        .io_slave_2_aw_bits_addr(DDR4_2_S_AXI_awaddr),
        .io_slave_2_aw_bits_len(DDR4_2_S_AXI_awlen),
        .io_slave_2_aw_bits_size(DDR4_2_S_AXI_awsize),
        .io_slave_2_aw_bits_burst(DDR4_2_S_AXI_awburst), // not available on DDR IF
        .io_slave_2_aw_bits_lock(DDR4_2_S_AXI_awlock), // not available on DDR IF
        .io_slave_2_aw_bits_cache(DDR4_2_S_AXI_awcache), // not available on DDR IF
        .io_slave_2_aw_bits_prot(DDR4_2_S_AXI_awprot), // not available on DDR IF
        .io_slave_2_aw_bits_qos(DDR4_2_S_AXI_awqos), // not available on DDR IF
        .io_slave_2_aw_bits_id(DDR4_2_S_AXI_awid),

        .io_slave_2_w_ready(DDR4_2_S_AXI_wready),
        .io_slave_2_w_valid(DDR4_2_S_AXI_wvalid),
        .io_slave_2_w_bits_data(DDR4_2_S_AXI_wdata),
        .io_slave_2_w_bits_last(DDR4_2_S_AXI_wlast),
        .io_slave_2_w_bits_strb(DDR4_2_S_AXI_wstrb),

        .io_slave_2_b_ready(DDR4_2_S_AXI_bready),
        .io_slave_2_b_valid(DDR4_2_S_AXI_bvalid),
        .io_slave_2_b_bits_resp(DDR4_2_S_AXI_bresp),
        .io_slave_2_b_bits_id(DDR4_2_S_AXI_bid),

        .io_slave_2_ar_ready(DDR4_2_S_AXI_arready),
        .io_slave_2_ar_valid(DDR4_2_S_AXI_arvalid),
        .io_slave_2_ar_bits_addr(DDR4_2_S_AXI_araddr),
        .io_slave_2_ar_bits_len(DDR4_2_S_AXI_arlen),
        .io_slave_2_ar_bits_size(DDR4_2_S_AXI_arsize),
        .io_slave_2_ar_bits_burst(DDR4_2_S_AXI_arburst), // not available on DDR IF
        .io_slave_2_ar_bits_lock(DDR4_2_S_AXI_arlock), // not available on DDR IF
        .io_slave_2_ar_bits_cache(DDR4_2_S_AXI_arcache), // not available on DDR IF
        .io_slave_2_ar_bits_prot(DDR4_2_S_AXI_arprot), // not available on DDR IF
        .io_slave_2_ar_bits_qos(DDR4_2_S_AXI_arqos), // not available on DDR IF
        .io_slave_2_ar_bits_id(DDR4_2_S_AXI_arid), // not available on DDR IF

        .io_slave_2_r_ready(DDR4_2_S_AXI_rready),
        .io_slave_2_r_valid(DDR4_2_S_AXI_rvalid),
        .io_slave_2_r_bits_resp(DDR4_2_S_AXI_rresp),
        .io_slave_2_r_bits_data(DDR4_2_S_AXI_rdata),
        .io_slave_2_r_bits_last(DDR4_2_S_AXI_rlast),
        .io_slave_2_r_bits_id(DDR4_2_S_AXI_rid),

        .io_slave_3_aw_ready(DDR4_3_S_AXI_awready),
        .io_slave_3_aw_valid(DDR4_3_S_AXI_awvalid),
        .io_slave_3_aw_bits_addr(DDR4_3_S_AXI_awaddr),
        .io_slave_3_aw_bits_len(DDR4_3_S_AXI_awlen),
        .io_slave_3_aw_bits_size(DDR4_3_S_AXI_awsize),
        .io_slave_3_aw_bits_burst(DDR4_3_S_AXI_awburst), // not available on DDR IF
        .io_slave_3_aw_bits_lock(DDR4_3_S_AXI_awlock), // not available on DDR IF
        .io_slave_3_aw_bits_cache(DDR4_3_S_AXI_awcache), // not available on DDR IF
        .io_slave_3_aw_bits_prot(DDR4_3_S_AXI_awprot), // not available on DDR IF
        .io_slave_3_aw_bits_qos(DDR4_3_S_AXI_awqos), // not available on DDR IF
        .io_slave_3_aw_bits_id(DDR4_3_S_AXI_awid),

        .io_slave_3_w_ready(DDR4_3_S_AXI_wready),
        .io_slave_3_w_valid(DDR4_3_S_AXI_wvalid),
        .io_slave_3_w_bits_data(DDR4_3_S_AXI_wdata),
        .io_slave_3_w_bits_last(DDR4_3_S_AXI_wlast),
        .io_slave_3_w_bits_strb(DDR4_3_S_AXI_wstrb),

        .io_slave_3_b_ready(DDR4_3_S_AXI_bready),
        .io_slave_3_b_valid(DDR4_3_S_AXI_bvalid),
        .io_slave_3_b_bits_resp(DDR4_3_S_AXI_bresp),
        .io_slave_3_b_bits_id(DDR4_3_S_AXI_bid),

        .io_slave_3_ar_ready(DDR4_3_S_AXI_arready),
        .io_slave_3_ar_valid(DDR4_3_S_AXI_arvalid),
        .io_slave_3_ar_bits_addr(DDR4_3_S_AXI_araddr),
        .io_slave_3_ar_bits_len(DDR4_3_S_AXI_arlen),
        .io_slave_3_ar_bits_size(DDR4_3_S_AXI_arsize),
        .io_slave_3_ar_bits_burst(DDR4_3_S_AXI_arburst), // not available on DDR IF
        .io_slave_3_ar_bits_lock(DDR4_3_S_AXI_arlock), // not available on DDR IF
        .io_slave_3_ar_bits_cache(DDR4_3_S_AXI_arcache), // not available on DDR IF
        .io_slave_3_ar_bits_prot(DDR4_3_S_AXI_arprot), // not available on DDR IF
        .io_slave_3_ar_bits_qos(DDR4_3_S_AXI_arqos), // not available on DDR IF
        .io_slave_3_ar_bits_id(DDR4_3_S_AXI_arid), // not available on DDR IF

        .io_slave_3_r_ready(DDR4_3_S_AXI_rready),
        .io_slave_3_r_valid(DDR4_3_S_AXI_rvalid),
        .io_slave_3_r_bits_resp(DDR4_3_S_AXI_rresp),
        .io_slave_3_r_bits_data(DDR4_3_S_AXI_rdata),
        .io_slave_3_r_bits_last(DDR4_3_S_AXI_rlast),
        .io_slave_3_r_bits_id(DDR4_3_S_AXI_rid)
    );

endmodule
