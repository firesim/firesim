connect_bd_intf_net -intf_net axi_clock_converter_0_M_AXI [get_bd_intf_pins axi_clock_converter_0/M_AXI] [get_bd_intf_pins firesim_wrapper_0/S_AXI_DMA]
connect_bd_intf_net -intf_net axi_clock_converter_1_M_AXI [get_bd_intf_pins axi_clock_converter_1/M_AXI] [get_bd_intf_pins firesim_wrapper_0/S_AXI_CTRL]
connect_bd_intf_net -intf_net axi_dwidth_converter_0_M_AXI [get_bd_intf_pins axi_dwidth_converter_0/M_AXI] [get_bd_intf_pins ddr4_0/C0_DDR4_S_AXI]
connect_bd_intf_net -intf_net axi_dwidth_converter_1_M_AXI [get_bd_intf_pins axi_dwidth_converter_1/M_AXI] [get_bd_intf_pins ddr4_1/C0_DDR4_S_AXI]
connect_bd_intf_net -intf_net axi_dwidth_converter_2_M_AXI [get_bd_intf_pins axi_dwidth_converter_2/M_AXI] [get_bd_intf_pins ddr4_2/C0_DDR4_S_AXI]
connect_bd_intf_net -intf_net axi_dwidth_converter_3_M_AXI [get_bd_intf_pins axi_dwidth_converter_3/M_AXI] [get_bd_intf_pins ddr4_3/C0_DDR4_S_AXI]
connect_bd_intf_net -intf_net axi_tieoff_master_0_TIEOFF_M_AXI_CTRL_0 \
   [get_bd_intf_pins axi_tieoff_master_0/TIEOFF_M_AXI_CTRL_0] \
   [get_bd_intf_pins ddr4_0/C0_DDR4_S_AXI_CTRL]
connect_bd_intf_net -intf_net axi_tieoff_master_1_TIEOFF_M_AXI_CTRL_0 \
   [get_bd_intf_pins axi_tieoff_master_1/TIEOFF_M_AXI_CTRL_0] \
   [get_bd_intf_pins ddr4_1/C0_DDR4_S_AXI_CTRL]
connect_bd_intf_net -intf_net axi_tieoff_master_2_TIEOFF_M_AXI_CTRL_0 \
   [get_bd_intf_pins axi_tieoff_master_2/TIEOFF_M_AXI_CTRL_0] \
   [get_bd_intf_pins ddr4_2/C0_DDR4_S_AXI_CTRL]
connect_bd_intf_net -intf_net axi_tieoff_master_3_TIEOFF_M_AXI_CTRL_0 \
   [get_bd_intf_pins axi_tieoff_master_3/TIEOFF_M_AXI_CTRL_0] \
   [get_bd_intf_pins ddr4_3/C0_DDR4_S_AXI_CTRL]
connect_bd_intf_net -intf_net ddr4_0_C0_DDR4 [get_bd_intf_ports ddr4_sdram_c0] [get_bd_intf_pins ddr4_0/C0_DDR4]
connect_bd_intf_net -intf_net ddr4_1_C0_DDR4 [get_bd_intf_ports ddr4_sdram_c1] [get_bd_intf_pins ddr4_1/C0_DDR4]
connect_bd_intf_net -intf_net ddr4_2_C0_DDR4 [get_bd_intf_ports ddr4_sdram_c2] [get_bd_intf_pins ddr4_2/C0_DDR4]
connect_bd_intf_net -intf_net ddr4_3_C0_DDR4 [get_bd_intf_ports ddr4_sdram_c3] [get_bd_intf_pins ddr4_3/C0_DDR4]
connect_bd_intf_net -intf_net default_300mhz_clk0_1 \
   [get_bd_intf_ports default_300mhz_clk0] \
   [get_bd_intf_pins ddr4_0/C0_SYS_CLK]
connect_bd_intf_net -intf_net default_300mhz_clk1_1 \
   [get_bd_intf_ports default_300mhz_clk1] \
   [get_bd_intf_pins ddr4_1/C0_SYS_CLK]
connect_bd_intf_net -intf_net default_300mhz_clk2_1 \
   [get_bd_intf_ports default_300mhz_clk2] \
   [get_bd_intf_pins ddr4_2/C0_SYS_CLK]
connect_bd_intf_net -intf_net default_300mhz_clk3_1 \
   [get_bd_intf_ports default_300mhz_clk3] \
   [get_bd_intf_pins ddr4_3/C0_SYS_CLK]
connect_bd_intf_net -intf_net firesim_wrapper_0_M_AXI_DDR0 [get_bd_intf_pins axi_dwidth_converter_0/S_AXI] [get_bd_intf_pins firesim_wrapper_0/M_AXI_DDR0]
connect_bd_intf_net -intf_net firesim_wrapper_0_M_AXI_DDR1 [get_bd_intf_pins axi_dwidth_converter_1/S_AXI] [get_bd_intf_pins firesim_wrapper_0/M_AXI_DDR1]
connect_bd_intf_net -intf_net firesim_wrapper_0_M_AXI_DDR2 [get_bd_intf_pins axi_dwidth_converter_2/S_AXI] [get_bd_intf_pins firesim_wrapper_0/M_AXI_DDR2]
connect_bd_intf_net -intf_net firesim_wrapper_0_M_AXI_DDR3 [get_bd_intf_pins axi_dwidth_converter_3/S_AXI] [get_bd_intf_pins firesim_wrapper_0/M_AXI_DDR3]
connect_bd_intf_net -intf_net pcie_refclk_1 [get_bd_intf_ports pcie_refclk] [get_bd_intf_pins util_ds_buf/CLK_IN_D]
connect_bd_intf_net -intf_net xdma_0_M_AXI [get_bd_intf_pins axi_clock_converter_0/S_AXI] [get_bd_intf_pins xdma_0/M_AXI]
connect_bd_intf_net -intf_net xdma_0_M_AXI_LITE [get_bd_intf_pins axi_clock_converter_1/S_AXI] [get_bd_intf_pins xdma_0/M_AXI_LITE]
connect_bd_intf_net -intf_net xdma_0_pcie_mgt [get_bd_intf_ports pci_express_x16] [get_bd_intf_pins xdma_0/pcie_mgt]
