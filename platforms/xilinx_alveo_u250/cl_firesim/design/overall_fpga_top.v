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

    wire xdma_axi_aclk;
    wire xdma_axi_aresetn;

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

    shell shell_i (
          .pci_express_x16_rxn(pci_express_x16_rxn)
        , .pci_express_x16_rxp(pci_express_x16_rxp)
        , .pci_express_x16_txn(pci_express_x16_txn)
        , .pci_express_x16_txp(pci_express_x16_txp)
        , .pcie_perstn(pcie_perstn)
        `DIFF_CLK_CONNECT(pcie_refclk, pcie_refclk)

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

        , .axi_aclk_0(xdma_axi_aclk)
        , .axi_aresetn_0(xdma_axi_aresetn)
    );

    custom_logic custom_logic_i (
          .resetn(resetn)

        `define DDR4_PAR
        `DDR4_CONNECT(ddr4_sdram_c0, ddr4_sdram_c0)
        `define DDR4_PAR

        `DIFF_CLK_CONNECT(default_300mhz_clk0, default_300mhz_clk0)

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

        , .xdma_axi_aclk(xdma_axi_aclk)
        , .xdma_axi_aresetn(xdma_axi_aresetn)
    );

endmodule
