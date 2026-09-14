# Block-design net connectivity, mirrored from /scratch/raghavgupta/project_xb10/xb10.tcl
# lines 451-503. All Aurora/QSFP routing from the u250 template is dropped.

# --- Interface connections ---
connect_bd_intf_net -intf_net ddr4_0_C0_DDR4_net        [get_bd_intf_ports ddr4_sdram_c0]         [get_bd_intf_pins ddr4_0/C0_DDR4]
connect_bd_intf_net -intf_net default_250mhz_clk0_net   [get_bd_intf_ports default_250mhz_clk0]   [get_bd_intf_pins ddr4_0/C0_SYS_CLK]

connect_bd_intf_net -intf_net pcie_refclk_net           [get_bd_intf_ports pcie_refclk]           [get_bd_intf_pins util_ds_buf/CLK_IN_D]
connect_bd_intf_net -intf_net xdma_0_pcie_mgt_net       [get_bd_intf_ports pci_express_x16]       [get_bd_intf_pins xdma_0/pcie_mgt]

connect_bd_intf_net -intf_net xdma_0_M_AXI              [get_bd_intf_pins axi_clock_converter_0/S_AXI]  [get_bd_intf_pins xdma_0/M_AXI]
connect_bd_intf_net -intf_net xdma_0_M_AXI_LITE         [get_bd_intf_pins axi_clock_converter_1/S_AXI]  [get_bd_intf_pins xdma_0/M_AXI_LITE]
connect_bd_intf_net -intf_net axi_clock_converter_0_M_AXI_net  [get_bd_intf_pins axi_clock_converter_0/M_AXI]  [get_bd_intf_ports PCIE_M_AXI]
connect_bd_intf_net -intf_net axi_clock_converter_1_M_AXI_net  [get_bd_intf_pins axi_clock_converter_1/M_AXI]  [get_bd_intf_ports PCIE_M_AXI_LITE]

connect_bd_intf_net -intf_net M_AXI_DDR0_net            [get_bd_intf_pins axi_dwidth_converter_0/S_AXI]   [get_bd_intf_ports DDR4_0_S_AXI]
connect_bd_intf_net -intf_net axi_dwidth_converter_0_M_AXI  [get_bd_intf_pins axi_dwidth_converter_0/M_AXI]  [get_bd_intf_pins ddr4_0/C0_DDR4_S_AXI]

# --- Reset fanout ---
connect_bd_net -net resetn_net \
   [get_bd_ports resetn] \
   [get_bd_pins proc_sys_reset_0/ext_reset_in] \
   [get_bd_pins proc_sys_reset_ddr_0/ext_reset_in] \
   [get_bd_pins resetn_inv_0/Op1]

connect_bd_net -net pcie_perstn_net \
   [get_bd_ports pcie_perstn] \
   [get_bd_pins xdma_0/sys_rst_n]

connect_bd_net -net resetn_inv_0_Res \
   [get_bd_pins resetn_inv_0/Res] \
   [get_bd_pins clk_wiz_0/reset] \
   [get_bd_pins ddr4_0/sys_rst]

connect_bd_net -net proc_sys_reset_0_interconnect_aresetn \
   [get_bd_pins proc_sys_reset_0/interconnect_aresetn] \
   [get_bd_ports sys_reset_n] \
   [get_bd_pins axi_clock_converter_0/m_axi_aresetn] \
   [get_bd_pins axi_clock_converter_1/m_axi_aresetn] \
   [get_bd_pins axi_dwidth_converter_0/s_axi_aresetn]

connect_bd_net -net rst_ddr4_0_interconnect_aresetn \
   [get_bd_pins proc_sys_reset_ddr_0/interconnect_aresetn] \
   [get_bd_pins axi_dwidth_converter_0/m_axi_aresetn] \
   [get_bd_pins ddr4_0/c0_ddr4_aresetn]

connect_bd_net -net xdma_0_axi_aresetn \
   [get_bd_pins xdma_0/axi_aresetn] \
   [get_bd_pins axi_clock_converter_0/s_axi_aresetn] \
   [get_bd_pins axi_clock_converter_1/s_axi_aresetn]

# --- Clock fanout ---
# DDR UI clock (250 MHz) feeds the system clk_wiz input, the DDR side of the
# width converter, and the DDR-side reset.
connect_bd_net -net ddr4_0_c0_ddr4_ui_clk \
   [get_bd_pins ddr4_0/c0_ddr4_ui_clk] \
   [get_bd_pins clk_wiz_0/clk_in1] \
   [get_bd_pins axi_dwidth_converter_0/m_axi_aclk] \
   [get_bd_pins proc_sys_reset_ddr_0/slowest_sync_clk]

# FireSim system clock (clk_wiz output, $firesim_freq_mhz).
connect_bd_net -net sys_clk_net \
   [get_bd_pins clk_wiz_0/clk_out1] \
   [get_bd_ports sys_clk] \
   [get_bd_pins axi_clock_converter_0/m_axi_aclk] \
   [get_bd_pins axi_clock_converter_1/m_axi_aclk] \
   [get_bd_pins axi_dwidth_converter_0/s_axi_aclk] \
   [get_bd_pins proc_sys_reset_0/slowest_sync_clk]

# XDMA's internal AXI clock (250 MHz) to XDMA-side of clock converters.
connect_bd_net -net xdma_0_axi_aclk \
   [get_bd_pins xdma_0/axi_aclk] \
   [get_bd_pins axi_clock_converter_0/s_axi_aclk] \
   [get_bd_pins axi_clock_converter_1/s_axi_aclk]

# PCIe refclk buffering (IBUFDSGTE outputs: full GT and /2 divided).
connect_bd_net -net util_ds_buf_IBUF_OUT \
   [get_bd_pins util_ds_buf/IBUF_OUT] \
   [get_bd_pins xdma_0/sys_clk_gt]

connect_bd_net -net util_ds_buf_IBUF_DS_ODIV2 \
   [get_bd_pins util_ds_buf/IBUF_DS_ODIV2] \
   [get_bd_pins xdma_0/sys_clk]

# --- Misc ---
connect_bd_net -net xlconstant_0_dout \
   [get_bd_pins xlconstant_0/dout] \
   [get_bd_pins xdma_0/usr_irq_req]

# Address segments: DDR4 reachable from XDMA via the width converter.
assign_bd_address -target_address_space /xdma_0/M_AXI [get_bd_addr_segs DDR4_0_S_AXI/Reg] -force
