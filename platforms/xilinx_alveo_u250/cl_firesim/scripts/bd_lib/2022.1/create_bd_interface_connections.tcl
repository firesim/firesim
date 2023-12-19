connect_bd_intf_net -intf_net pcie_refclk_net [get_bd_intf_ports pcie_refclk] [get_bd_intf_pins util_ds_buf/CLK_IN_D]
connect_bd_intf_net -intf_net xdma_0_pcie_mgt_net [get_bd_intf_ports pci_express_x16] [get_bd_intf_pins xdma_0/pcie_mgt]
connect_bd_net -net pcie_perstn_net [get_bd_ports pcie_perstn] [get_bd_pins xdma_0/sys_rst_n]

connect_bd_intf_net -intf_net ddr4_0_C0_DDR4_net [get_bd_intf_ports ddr4_sdram_c0] [get_bd_intf_pins ddr4_0/C0_DDR4]
connect_bd_intf_net -intf_net ddr4_1_C0_DDR4_net [get_bd_intf_ports ddr4_sdram_c1] [get_bd_intf_pins ddr4_1/C0_DDR4]
connect_bd_intf_net -intf_net ddr4_2_C0_DDR4_net [get_bd_intf_ports ddr4_sdram_c2] [get_bd_intf_pins ddr4_2/C0_DDR4]
connect_bd_intf_net -intf_net ddr4_3_C0_DDR4_net [get_bd_intf_ports ddr4_sdram_c3] [get_bd_intf_pins ddr4_3/C0_DDR4]

connect_bd_net -net resetn_net \
   [get_bd_ports resetn] \
   [get_bd_pins proc_sys_reset_0/ext_reset_in] \
   [get_bd_pins proc_sys_reset_ddr_0/ext_reset_in] \
   [get_bd_pins proc_sys_reset_ddr_1/ext_reset_in] \
   [get_bd_pins proc_sys_reset_ddr_2/ext_reset_in] \
   [get_bd_pins proc_sys_reset_ddr_3/ext_reset_in] \
   [get_bd_pins resetn_inv_0/Op1]

connect_bd_intf_net -intf_net default_300mhz_clk0_net [get_bd_intf_ports default_300mhz_clk0] [get_bd_intf_pins ddr4_0/C0_SYS_CLK]
connect_bd_intf_net -intf_net default_300mhz_clk1_net [get_bd_intf_ports default_300mhz_clk1] [get_bd_intf_pins ddr4_1/C0_SYS_CLK]
connect_bd_intf_net -intf_net default_300mhz_clk2_net [get_bd_intf_ports default_300mhz_clk2] [get_bd_intf_pins ddr4_2/C0_SYS_CLK]
connect_bd_intf_net -intf_net default_300mhz_clk3_net [get_bd_intf_ports default_300mhz_clk3] [get_bd_intf_pins ddr4_3/C0_SYS_CLK]

connect_bd_intf_net -intf_net M_AXI_DDR0_net [get_bd_intf_pins axi_dwidth_converter_0/S_AXI] [get_bd_intf_ports DDR4_0_S_AXI]
connect_bd_intf_net -intf_net M_AXI_DDR1_net [get_bd_intf_pins axi_dwidth_converter_1/S_AXI] [get_bd_intf_ports DDR4_1_S_AXI]
connect_bd_intf_net -intf_net M_AXI_DDR2_net [get_bd_intf_pins axi_dwidth_converter_2/S_AXI] [get_bd_intf_ports DDR4_2_S_AXI]
connect_bd_intf_net -intf_net M_AXI_DDR3_net [get_bd_intf_pins axi_dwidth_converter_3/S_AXI] [get_bd_intf_ports DDR4_3_S_AXI]

connect_bd_intf_net -intf_net axi_clock_converter_0_M_AXI_net [get_bd_intf_pins axi_clock_converter_0/M_AXI] [get_bd_intf_ports PCIE_M_AXI]
connect_bd_intf_net -intf_net axi_clock_converter_1_M_AXI_net [get_bd_intf_pins axi_clock_converter_1/M_AXI] [get_bd_intf_ports PCIE_M_AXI_LITE]

connect_bd_net -net proc_sys_reset_0_interconnect_aresetn \
   [get_bd_ports sys_reset_n] \
   [get_bd_pins axi_clock_converter_0/m_axi_aresetn] \
   [get_bd_pins axi_clock_converter_1/m_axi_aresetn] \
   [get_bd_pins axi_dwidth_converter_0/s_axi_aresetn] \
   [get_bd_pins axi_dwidth_converter_1/s_axi_aresetn] \
   [get_bd_pins axi_dwidth_converter_2/s_axi_aresetn] \
   [get_bd_pins axi_dwidth_converter_3/s_axi_aresetn] \
   [get_bd_pins proc_sys_reset_0/interconnect_aresetn]

connect_bd_net -net sys_clk_net \
   [get_bd_ports sys_clk] \
   [get_bd_pins axi_clock_converter_0/m_axi_aclk] \
   [get_bd_pins axi_clock_converter_1/m_axi_aclk] \
   [get_bd_pins axi_dwidth_converter_0/s_axi_aclk] \
   [get_bd_pins axi_dwidth_converter_1/s_axi_aclk] \
   [get_bd_pins axi_dwidth_converter_2/s_axi_aclk] \
   [get_bd_pins axi_dwidth_converter_3/s_axi_aclk] \
   [get_bd_pins clk_wiz_0/clk_out1] \
   [get_bd_pins proc_sys_reset_0/slowest_sync_clk]
