# Block-design net connectivity for the Xilinx Alveo V80 (Versal).

# --- Interface connections ---
connect_bd_intf_net -intf_net DDR4_0_S_AXI_1 [get_bd_intf_ports DDR4_0_S_AXI] [get_bd_intf_pins axi_noc_1/S00_AXI]
connect_bd_intf_net -intf_net axi_noc_0_M00_AXI [get_bd_intf_ports PCIE_M_AXI] [get_bd_intf_pins axi_noc_0/M00_AXI]
connect_bd_intf_net -intf_net axi_noc_0_M01_AXI [get_bd_intf_ports PCIE_M_AXI_LITE] [get_bd_intf_pins smartconnect_0/M00_AXI]
connect_bd_intf_net -intf_net axi_noc_0_M01_AXI1 [get_bd_intf_pins axi_noc_0/M01_AXI] [get_bd_intf_pins smartconnect_0/S00_AXI]
connect_bd_intf_net -intf_net axi_noc_1_CH0_DDR4_0 [get_bd_intf_ports ddr4_sdram_c0] [get_bd_intf_pins axi_noc_1/CH0_DDR4_0]
connect_bd_intf_net -intf_net pcie_refclk_1 [get_bd_intf_ports pcie_refclk] [get_bd_intf_pins versal_cips_0/gt_refclk0]
connect_bd_intf_net -intf_net sys_clk0_1_1 [get_bd_intf_ports sys_clk0_1] [get_bd_intf_pins axi_noc_1/sys_clk0]
connect_bd_intf_net -intf_net versal_cips_0_CPM_PCIE_NOC_0 [get_bd_intf_pins versal_cips_0/CPM_PCIE_NOC_0] [get_bd_intf_pins axi_noc_0/S00_AXI]
connect_bd_intf_net -intf_net versal_cips_0_PCIE0_GT [get_bd_intf_ports pci_express_x16] [get_bd_intf_pins versal_cips_0/PCIE0_GT]

# --- Port connections ---
connect_bd_net -net proc_sys_reset_0_interconnect_aresetn  [get_bd_pins proc_sys_reset_0/interconnect_aresetn] \
  [get_bd_ports sys_reset_n] \
  [get_bd_pins versal_cips_0/dma0_intrfc_resetn] \
  [get_bd_pins smartconnect_0/aresetn]
connect_bd_net -net sys_clk_net  [get_bd_pins clk_wizard_0/clk_out1] \
  [get_bd_pins proc_sys_reset_0/slowest_sync_clk] \
  [get_bd_pins axi_noc_1/aclk0] \
  [get_bd_pins axi_noc_0/aclk1] \
  [get_bd_pins versal_cips_0/dma0_intrfc_clk] \
  [get_bd_pins smartconnect_0/aclk] \
  [get_bd_pins axi_noc_0/aclk2] \
  [get_bd_ports sys_clk]
connect_bd_net -net versal_cips_0_cpm_pcie_noc_axi0_clk  [get_bd_pins versal_cips_0/cpm_pcie_noc_axi0_clk] \
  [get_bd_pins axi_noc_0/aclk0]
connect_bd_net -net versal_cips_0_pl0_ref_clk  [get_bd_pins versal_cips_0/pl0_ref_clk] \
  [get_bd_pins clk_wizard_0/clk_in1]
connect_bd_net -net versal_cips_0_pl0_resetn  [get_bd_pins versal_cips_0/pl0_resetn] \
  [get_bd_pins proc_sys_reset_0/ext_reset_in]

# --- Address segments ---
assign_bd_address -offset 0x020100000000 -range 0x02000000 -target_address_space [get_bd_addr_spaces versal_cips_0/CPM_PCIE_NOC_0] [get_bd_addr_segs PCIE_M_AXI_LITE/Reg] -force
assign_bd_address -offset 0x020300000000 -range 0x000100000000 -target_address_space [get_bd_addr_spaces versal_cips_0/CPM_PCIE_NOC_0] [get_bd_addr_segs PCIE_M_AXI/Reg] -force
assign_bd_address -offset 0x060000000000 -range 0x000800000000 -target_address_space [get_bd_addr_spaces DDR4_0_S_AXI] [get_bd_addr_segs axi_noc_1/S00_AXI/C0_DDR_CH2] -force
