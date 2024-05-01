`timescale 1 ps / 1 ps

`include "helpers.vh"
`include "axi.vh"

module overall_fpga_top(
    `DDR4_PDEF(ddr4_sdram_c0),

    `DIFF_CLK_PDEF(default_300mhz_clk0),

    input wire [15:0] pci_express_x16_rxn,
    input wire [15:0] pci_express_x16_rxp,
    output wire [15:0] pci_express_x16_txn,
    output wire [15:0] pci_express_x16_txp,
    input wire pcie_perstn,
    `DIFF_CLK_PDEF(pcie_refclk),

    input wire resetn
);

    wire sys_clk;
    wire sys_reset_n;

    `define AMBA_AXI4
    `define AMBA_AXI_CACHE
    `define AMBA_AXI_PROT
    `define AMBA_AXI_ID
    `AMBA_AXI_WIRE(PCIE_M_AXI, 4, 64, 512)
    `undef AMBA_AXI4
    `undef AMBA_AXI_CACHE
    `undef AMBA_AXI_PROT
    `undef AMBA_AXI_ID

    `define AMBA_AXI_PROT
    `AMBA_AXI_WIRE(PCIE_M_AXI_LITE, unused, 32, 32)
    `undef AMBA_AXI_PROT

    `define AMBA_AXI4
    `define AMBA_AXI_CACHE
    `define AMBA_AXI_PROT
    `define AMBA_AXI_QOS
    `define AMBA_AXI_REGION
    `define AMBA_AXI_ID
    `AMBA_AXI_WIRE(DDR4_0_S_AXI, 16, 34, 64)
    `undef AMBA_AXI4
    `undef AMBA_AXI_CACHE
    `undef AMBA_AXI_PROT
    `undef AMBA_AXI_QOS
    `undef AMBA_AXI_REGION
    `undef AMBA_AXI_ID

    design_1 design_1_i (
          .resetn(resetn)

	`define DDR4_PAR
	`DDR4_CONNECT(ddr4_sdram_c0, ddr4_sdram_c0)
	`undef DDR4_PAR

	`DIFF_CLK_CONNECT(default_300mhz_clk0, default_300mhz_clk0)

	`define AMBA_AXI4
	`define AMBA_AXI_CACHE
	`define AMBA_AXI_PROT
	`define AMBA_AXI_QOS
	`define AMBA_AXI_REGION
	`define AMBA_AXI_ID
	`AMBA_AXI_PORT_CONNECTION(DDR4_0_S_AXI, DDR4_0_S_AXI)
	`undef AMBA_AXI4
	`undef AMBA_AXI_CACHE
	`undef AMBA_AXI_PROT
	`undef AMBA_AXI_QOS
	`undef AMBA_AXI_REGION
	`undef AMBA_AXI_ID

	`define AMBA_AXI4
	`define AMBA_AXI_CACHE
	`define AMBA_AXI_PROT
	`define AMBA_AXI_ID
	`AMBA_AXI_PORT_CONNECTION(PCIE_M_AXI, PCIE_M_AXI)
	`undef AMBA_AXI4
	`undef AMBA_AXI_CACHE
	`undef AMBA_AXI_PROT
	`undef AMBA_AXI_ID

	`define AMBA_AXI_PROT
	`AMBA_AXI_PORT_CONNECTION(PCIE_M_AXI_LITE, PCIE_M_AXI_LITE)
	`undef AMBA_AXI_PROT

        , .pci_express_x16_rxn(pci_express_x16_rxn)
        , .pci_express_x16_rxp(pci_express_x16_rxp)
        , .pci_express_x16_txn(pci_express_x16_txn)
        , .pci_express_x16_txp(pci_express_x16_txp)
        , .pcie_perstn(pcie_perstn)
	`DIFF_CLK_CONNECT(pcie_refclk, pcie_refclk)

        , .sys_clk(sys_clk)
        , .sys_reset_n(sys_reset_n)
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
        .io_slave_0_r_bits_id(DDR4_0_S_AXI_rid)
    );

endmodule
