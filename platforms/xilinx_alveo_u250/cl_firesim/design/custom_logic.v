`timescale 1 ps / 1 ps

`include "helpers.vh"
`include "axi.vh"

module custom_logic(
    `DDR4_PDEF(ddr4_sdram_c0),

    `DIFF_CLK_PDEF(default_300mhz_clk0)

    , input wire resetn

    `define AMBA_AXI4
    `define AMBA_AXI_CACHE
    `define AMBA_AXI_PROT
    `define AMBA_AXI_ID
    `AMBA_AXI_SLAVE_PORT(PCIE_M_AXI, 4, 64, 512)
    `undef AMBA_AXI4
    `undef AMBA_AXI_CACHE
    `undef AMBA_AXI_PROT
    `undef AMBA_AXI_ID

    `define AMBA_AXI_PROT
    `AMBA_AXI_SLAVE_PORT(PCIE_M_AXI_LITE, unused, 32, 32)
    `undef AMBA_AXI_PROT

    , input wire xdma_axi_aclk
    , input wire xdma_axi_aresetn
);

    `define AMBA_AXI4
    `define AMBA_AXI_CACHE
    `define AMBA_AXI_PROT
    `define AMBA_AXI_QOS
    `define AMBA_AXI_REGION
    `define AMBA_AXI_ID
    `AMBA_AXI_WIRE(axi_clock_converter_0_M_AXI, 4, 64, 512)
    `undef AMBA_AXI4
    `undef AMBA_AXI_CACHE
    `undef AMBA_AXI_PROT
    `undef AMBA_AXI_QOS
    `undef AMBA_AXI_REGION
    `undef AMBA_AXI_ID

    `define AMBA_AXI_PROT
    `AMBA_AXI_WIRE(axi_clock_converter_1_M_AXI_LITE, unused, 32, 32)
    `undef AMBA_AXI_PROT

    // no *id (put as 1) since coming into dram
    `define AMBA_AXI4
    `define AMBA_AXI_CACHE
    `define AMBA_AXI_PROT
    `define AMBA_AXI_QOS
    `AMBA_AXI_WIRE(axi_dwidth_converter_0_M_AXI, 1, 34, 512)
    `undef AMBA_AXI4
    `undef AMBA_AXI_CACHE
    `undef AMBA_AXI_PROT
    `undef AMBA_AXI_QOS

    `define AMBA_AXI4
    `define AMBA_AXI_CACHE
    `define AMBA_AXI_PROT
    `define AMBA_AXI_QOS
    `define AMBA_AXI_REGION
    `define AMBA_AXI_ID
    `AMBA_AXI_WIRE(firesim_wrapper_0_M_AXI_DDR0, 16, 34, 64)
    `undef AMBA_AXI4
    `undef AMBA_AXI_CACHE
    `undef AMBA_AXI_PROT
    `undef AMBA_AXI_QOS
    `undef AMBA_AXI_REGION
    `undef AMBA_AXI_ID

    wire sys_clk;
    wire sys_resetn;

    wire ddr4_ui_clk;

    wire ddr4_resetn;

    wire drck;
    wire shift;
    wire tdi;
    wire update;
    wire sel;
    wire tdo;
    wire tms;
    wire tck;
    wire runtest;
    wire capture;
    wire reset;
    wire bscanid_en;

    // Instantiate DDR IP in the CL

    ddr4_0 ddr4_0_i(
          .c0_ddr4_aresetn(ddr4_resetn)

        // connect using parity
        `DDR4_CONNECT(c0_ddr4, ddr4_sdram_c0)

        `define AMBA_AXI4
        `define AMBA_AXI_CACHE
        `define AMBA_AXI_PROT
        `define AMBA_AXI_QOS
        `AMBA_AXI_PORT_CONNECTION(c0_ddr4_s_axi, axi_dwidth_converter_0_M_AXI)
        `undef AMBA_AXI4
        `undef AMBA_AXI_CACHE
        `undef AMBA_AXI_PROT
        `undef AMBA_AXI_QOS

        , .c0_ddr4_s_axi_arid(4'h0)
        , .c0_ddr4_s_axi_awid(4'h0)

        // tie-off axi-lite port
        , .c0_ddr4_s_axi_ctrl_araddr(32'h0)
        , .c0_ddr4_s_axi_ctrl_arready()
        , .c0_ddr4_s_axi_ctrl_arvalid(1'h0)
        , .c0_ddr4_s_axi_ctrl_awaddr(32'h0)
        , .c0_ddr4_s_axi_ctrl_awready()
        , .c0_ddr4_s_axi_ctrl_awvalid(1'h0)
        , .c0_ddr4_s_axi_ctrl_bready(1'h0)
        , .c0_ddr4_s_axi_ctrl_bresp()
        , .c0_ddr4_s_axi_ctrl_bvalid()
        , .c0_ddr4_s_axi_ctrl_rdata()
        , .c0_ddr4_s_axi_ctrl_rready(1'h0)
        , .c0_ddr4_s_axi_ctrl_rresp()
        , .c0_ddr4_s_axi_ctrl_rvalid()
        , .c0_ddr4_s_axi_ctrl_wdata(32'h0)
        , .c0_ddr4_s_axi_ctrl_wready()
        , .c0_ddr4_s_axi_ctrl_wvalid(1'h0)

        , .c0_ddr4_ui_clk(ddr4_ui_clk)

        `DIFF_CLK_CONNECT(c0_sys, default_300mhz_clk0)

        , .sys_rst(~resetn)
    );

    proc_sys_reset_1 proc_sys_reset_1_i(
        .aux_reset_in(1'b1),
        .dcm_locked(1'b1),
        .ext_reset_in(resetn),
        .interconnect_aresetn(ddr4_resetn),
        .mb_debug_sys_rst(1'h0),
        .slowest_sync_clk(ddr4_ui_clk)
    );

    // Instantiate FireSim clock/reset

    clk_wiz_0 clk_wiz_0_i(
        .clk_in1(ddr4_ui_clk),
        .clk_out1(sys_clk),
        .reset(~resetn)
    );

    proc_sys_reset_0 proc_sys_reset_0_i(
        .aux_reset_in(1'b1),
        .dcm_locked(1'b1),
        .ext_reset_in(resetn),
        .interconnect_aresetn(sys_resetn),
        .mb_debug_sys_rst(1'h0),
        .slowest_sync_clk(sys_clk)
    );

    // Convert PCIe M_AXI/M_AXI_LITE and DDR to FireSim clock domain

    axi_clock_converter_0 axi_clock_converter_0_i(
          .m_axi_aclk(sys_clk)
        , .m_axi_aresetn(sys_resetn)

        `define AMBA_AXI4
        `define AMBA_AXI_CACHE
        `define AMBA_AXI_PROT
        `define AMBA_AXI_QOS
        `define AMBA_AXI_REGION
        `define AMBA_AXI_ID
        `AMBA_AXI_PORT_CONNECTION(m_axi, axi_clock_converter_0_M_AXI)
        `undef AMBA_AXI4
        `undef AMBA_AXI_CACHE
        `undef AMBA_AXI_PROT
        `undef AMBA_AXI_QOS
        `undef AMBA_AXI_REGION
        `undef AMBA_AXI_ID

        , .s_axi_aclk(xdma_axi_aclk)
        , .s_axi_aresetn(xdma_axi_aresetn)

        `define AMBA_AXI4
        `define AMBA_AXI_CACHE
        `define AMBA_AXI_PROT
        `define AMBA_AXI_ID
        `AMBA_AXI_PORT_CONNECTION(s_axi, PCIE_M_AXI)
        `undef AMBA_AXI4
        `undef AMBA_AXI_CACHE
        `undef AMBA_AXI_PROT
        `undef AMBA_AXI_ID
        , .s_axi_arqos(4'h0)
        , .s_axi_awqos(4'h0)
        , .s_axi_arregion(4'h0)
        , .s_axi_awregion(4'h0)
    );

    axi_clock_converter_1 axi_clock_converter_1_i(
          .m_axi_aclk(sys_clk)
        , .m_axi_aresetn(sys_resetn)

        `define AMBA_AXI_PROT
        `AMBA_AXI_PORT_CONNECTION(m_axi, axi_clock_converter_1_M_AXI_LITE)
        `undef AMBA_AXI_PROT

        , .s_axi_aclk(xdma_axi_aclk)
        , .s_axi_aresetn(xdma_axi_aresetn)

        `define AMBA_AXI_PROT
        `AMBA_AXI_PORT_CONNECTION(s_axi, PCIE_M_AXI_LITE)
        `undef AMBA_AXI_PROT
    );

    axi_dwidth_converter_0 axi_dwidth_converter_0_i(
          .m_axi_aclk(ddr4_ui_clk)
        , .m_axi_aresetn(ddr4_resetn)

        `define AMBA_AXI4
        `define AMBA_AXI_CACHE
        `define AMBA_AXI_PROT
        `define AMBA_AXI_QOS
        `AMBA_AXI_PORT_CONNECTION(m_axi, axi_dwidth_converter_0_M_AXI)
        `undef AMBA_AXI4
        `undef AMBA_AXI_CACHE
        `undef AMBA_AXI_PROT
        `undef AMBA_AXI_QOS

        , .s_axi_aclk(sys_clk)
        , .s_axi_aresetn(sys_resetn)

        `define AMBA_AXI4
        `define AMBA_AXI_CACHE
        `define AMBA_AXI_PROT
        `define AMBA_AXI_QOS
        `define AMBA_AXI_REGION
        `define AMBA_AXI_ID
        `AMBA_AXI_PORT_CONNECTION(s_axi, firesim_wrapper_0_M_AXI_DDR0)
        `undef AMBA_AXI4
        `undef AMBA_AXI_CACHE
        `undef AMBA_AXI_PROT
        `undef AMBA_AXI_QOS
        `undef AMBA_AXI_REGION
        `undef AMBA_AXI_ID
    );

    debug_bridge_0 debug_bridge_0_i(
        .clk(sys_clk),
        .S_BSCAN_drck(drck),
        .S_BSCAN_shift(shift),
        .S_BSCAN_tdi(tdi),
        .S_BSCAN_update(update),
        .S_BSCAN_sel(sel),
        .S_BSCAN_tdo(tdo),
        .S_BSCAN_tms(tms),
        .S_BSCAN_tck(tck),
        .S_BSCAN_runtest(runtest),
        .S_BSCAN_reset(reset),
        .S_BSCAN_capture(capture),
        .S_BSCAN_bscanid_en(bscanid_en)
    );

    // Instantiate FireSim top

    F1Shim firesim_top(
        .clock(sys_clk),
        .reset(!sys_resetn),

        .io_master_aw_ready(axi_clock_converter_1_M_AXI_LITE_awready),
        .io_master_aw_valid(axi_clock_converter_1_M_AXI_LITE_awvalid),
        .io_master_aw_bits_addr(axi_clock_converter_1_M_AXI_LITE_awaddr[24:0]),
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

        .io_master_w_ready(axi_clock_converter_1_M_AXI_LITE_wready),
        .io_master_w_valid(axi_clock_converter_1_M_AXI_LITE_wvalid),
        .io_master_w_bits_data(axi_clock_converter_1_M_AXI_LITE_wdata),
        .io_master_w_bits_last(1'h1),
        .io_master_w_bits_id(12'h0),
        .io_master_w_bits_strb(axi_clock_converter_1_M_AXI_LITE_wstrb), //OR 8'HFF
        .io_master_w_bits_user(1'h0),

        .io_master_b_ready(axi_clock_converter_1_M_AXI_LITE_bready),
        .io_master_b_valid(axi_clock_converter_1_M_AXI_LITE_bvalid),
        .io_master_b_bits_resp(axi_clock_converter_1_M_AXI_LITE_bresp),
        .io_master_b_bits_id(),      // UNUSED at top level
        .io_master_b_bits_user(),    // UNUSED at top level

        .io_master_ar_ready(axi_clock_converter_1_M_AXI_LITE_arready),
        .io_master_ar_valid(axi_clock_converter_1_M_AXI_LITE_arvalid),
        .io_master_ar_bits_addr(axi_clock_converter_1_M_AXI_LITE_araddr[24:0]),
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

        .io_master_r_ready(axi_clock_converter_1_M_AXI_LITE_rready),
        .io_master_r_valid(axi_clock_converter_1_M_AXI_LITE_rvalid),
        .io_master_r_bits_resp(axi_clock_converter_1_M_AXI_LITE_rresp),
        .io_master_r_bits_data(axi_clock_converter_1_M_AXI_LITE_rdata),
        .io_master_r_bits_last(), //UNUSED at top level
        .io_master_r_bits_id(),      // UNUSED at top level
        .io_master_r_bits_user(),    // UNUSED at top level

        // special NIC master interface
        .io_dma_aw_ready(axi_clock_converter_0_M_AXI_awready),
        .io_dma_aw_valid(axi_clock_converter_0_M_AXI_awvalid),
        .io_dma_aw_bits_addr(axi_clock_converter_0_M_AXI_awaddr),
        .io_dma_aw_bits_len(axi_clock_converter_0_M_AXI_awlen),
        .io_dma_aw_bits_size(axi_clock_converter_0_M_AXI_awsize),
        .io_dma_aw_bits_burst(2'h1), // axi_clock_converter_0_M_AXI_awburst
        .io_dma_aw_bits_lock(1'h0), // axi_clock_converter_0_M_AXI_awlock
        .io_dma_aw_bits_cache(4'h0), // axi_clock_converter_0_M_AXI_awcache
        .io_dma_aw_bits_prot(3'h0), //unused? (could connect?) axi_clock_converter_0_M_AXI_awprot
        .io_dma_aw_bits_qos(4'h0), // axi_clock_converter_0_M_AXI_awqos
        .io_dma_aw_bits_region(4'h0), // axi_clock_converter_0_M_AXI_awregion
        .io_dma_aw_bits_id(axi_clock_converter_0_M_AXI_awid),
        .io_dma_aw_bits_user(1'h0),

        .io_dma_w_ready(axi_clock_converter_0_M_AXI_wready),
        .io_dma_w_valid(axi_clock_converter_0_M_AXI_wvalid),
        .io_dma_w_bits_data(axi_clock_converter_0_M_AXI_wdata),
        .io_dma_w_bits_last(axi_clock_converter_0_M_AXI_wlast),
        .io_dma_w_bits_id(4'h0),
        .io_dma_w_bits_strb(axi_clock_converter_0_M_AXI_wstrb),
        .io_dma_w_bits_user(1'h0),

        .io_dma_b_ready(axi_clock_converter_0_M_AXI_bready),
        .io_dma_b_valid(axi_clock_converter_0_M_AXI_bvalid),
        .io_dma_b_bits_resp(axi_clock_converter_0_M_AXI_bresp),
        .io_dma_b_bits_id({2'h0, axi_clock_converter_0_M_AXI_bid}),
        .io_dma_b_bits_user(),    // UNUSED at top level

        .io_dma_ar_ready(axi_clock_converter_0_M_AXI_arready),
        .io_dma_ar_valid(axi_clock_converter_0_M_AXI_arvalid),
        .io_dma_ar_bits_addr(axi_clock_converter_0_M_AXI_araddr),
        .io_dma_ar_bits_len(axi_clock_converter_0_M_AXI_arlen),
        .io_dma_ar_bits_size(axi_clock_converter_0_M_AXI_arsize),
        .io_dma_ar_bits_burst(2'h1), // axi_clock_converter_0_M_AXI_arburst
        .io_dma_ar_bits_lock(1'h0), // axi_clock_converter_0_M_AXI_arlock
        .io_dma_ar_bits_cache(4'h0), // axi_clock_converter_0_M_AXI_arcache
        .io_dma_ar_bits_prot(3'h0), // axi_clock_converter_0_M_AXI_arprot
        .io_dma_ar_bits_qos(4'h0), // axi_clock_converter_0_M_AXI_arqos
        .io_dma_ar_bits_region(4'h0), // axi_clock_converter_0_M_AXI_arregion
        .io_dma_ar_bits_id({2'h0, axi_clock_converter_0_M_AXI_arid}),
        .io_dma_ar_bits_user(1'h0),

        .io_dma_r_ready(axi_clock_converter_0_M_AXI_rready),
        .io_dma_r_valid(axi_clock_converter_0_M_AXI_rvalid),
        .io_dma_r_bits_resp(axi_clock_converter_0_M_AXI_rresp),
        .io_dma_r_bits_data(axi_clock_converter_0_M_AXI_rdata),
        .io_dma_r_bits_last(axi_clock_converter_0_M_AXI_rlast),
        .io_dma_r_bits_id(axi_clock_converter_0_M_AXI_rid),
        .io_dma_r_bits_user(),    // UNUSED at top level

        // `include "firesim_ila_insert_ports.v"

        .io_slave_0_aw_ready(firesim_wrapper_0_M_AXI_DDR0_awready),
        .io_slave_0_aw_valid(firesim_wrapper_0_M_AXI_DDR0_awvalid),
        .io_slave_0_aw_bits_addr(firesim_wrapper_0_M_AXI_DDR0_awaddr),
        .io_slave_0_aw_bits_len(firesim_wrapper_0_M_AXI_DDR0_awlen),
        .io_slave_0_aw_bits_size(firesim_wrapper_0_M_AXI_DDR0_awsize),
        .io_slave_0_aw_bits_burst(firesim_wrapper_0_M_AXI_DDR0_awburst), // not available on ddr if
        .io_slave_0_aw_bits_lock(firesim_wrapper_0_M_AXI_DDR0_awlock), // not available on ddr if
        .io_slave_0_aw_bits_cache(firesim_wrapper_0_M_AXI_DDR0_awcache), // not available on ddr if
        .io_slave_0_aw_bits_prot(firesim_wrapper_0_M_AXI_DDR0_awprot), // not available on ddr if
        .io_slave_0_aw_bits_qos(firesim_wrapper_0_M_AXI_DDR0_awqos), // not available on ddr if
        .io_slave_0_aw_bits_id(firesim_wrapper_0_M_AXI_DDR0_awid),

        .io_slave_0_w_ready(firesim_wrapper_0_M_AXI_DDR0_wready),
        .io_slave_0_w_valid(firesim_wrapper_0_M_AXI_DDR0_wvalid),
        .io_slave_0_w_bits_data(firesim_wrapper_0_M_AXI_DDR0_wdata),
        .io_slave_0_w_bits_last(firesim_wrapper_0_M_AXI_DDR0_wlast),
        .io_slave_0_w_bits_strb(firesim_wrapper_0_M_AXI_DDR0_wstrb),

        .io_slave_0_b_ready(firesim_wrapper_0_M_AXI_DDR0_bready),
        .io_slave_0_b_valid(firesim_wrapper_0_M_AXI_DDR0_bvalid),
        .io_slave_0_b_bits_resp(firesim_wrapper_0_M_AXI_DDR0_bresp),
        .io_slave_0_b_bits_id(firesim_wrapper_0_M_AXI_DDR0_bid),

        .io_slave_0_ar_ready(firesim_wrapper_0_M_AXI_DDR0_arready),
        .io_slave_0_ar_valid(firesim_wrapper_0_M_AXI_DDR0_arvalid),
        .io_slave_0_ar_bits_addr(firesim_wrapper_0_M_AXI_DDR0_araddr),
        .io_slave_0_ar_bits_len(firesim_wrapper_0_M_AXI_DDR0_arlen),
        .io_slave_0_ar_bits_size(firesim_wrapper_0_M_AXI_DDR0_arsize),
        .io_slave_0_ar_bits_burst(firesim_wrapper_0_M_AXI_DDR0_arburst), // not available on ddr if
        .io_slave_0_ar_bits_lock(firesim_wrapper_0_M_AXI_DDR0_arlock), // not available on ddr if
        .io_slave_0_ar_bits_cache(firesim_wrapper_0_M_AXI_DDR0_arcache), // not available on ddr if
        .io_slave_0_ar_bits_prot(firesim_wrapper_0_M_AXI_DDR0_arprot), // not available on ddr if
        .io_slave_0_ar_bits_qos(firesim_wrapper_0_M_AXI_DDR0_arqos), // not available on ddr if
        .io_slave_0_ar_bits_id(firesim_wrapper_0_M_AXI_DDR0_arid), // not available on ddr if

        .io_slave_0_r_ready(firesim_wrapper_0_M_AXI_DDR0_rready),
        .io_slave_0_r_valid(firesim_wrapper_0_M_AXI_DDR0_rvalid),
        .io_slave_0_r_bits_resp(firesim_wrapper_0_M_AXI_DDR0_rresp),
        .io_slave_0_r_bits_data(firesim_wrapper_0_M_AXI_DDR0_rdata),
        .io_slave_0_r_bits_last(firesim_wrapper_0_M_AXI_DDR0_rlast),
        .io_slave_0_r_bits_id(firesim_wrapper_0_M_AXI_DDR0_rid)
    );

endmodule
