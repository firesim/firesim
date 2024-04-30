connect_bd_intf_net -intf_net pcie_refclk_net [get_bd_intf_ports pcie_refclk] [get_bd_intf_pins util_ds_buf/CLK_IN_D]
connect_bd_intf_net -intf_net xdma_0_pcie_mgt_net [get_bd_intf_ports pci_express_x16] [get_bd_intf_pins xdma_0/pcie_mgt]
connect_bd_net -net pcie_perstn_net [get_bd_ports pcie_perstn] [get_bd_pins xdma_0/sys_rst_n]
connect_bd_net -net xdma_0_axi_aclk [get_bd_ports axi_aclk_0] [get_bd_pins xdma_0/axi_aclk]
connect_bd_net -net xdma_0_axi_aresetn [get_bd_ports axi_aresetn_0]  [get_bd_pins xdma_0/axi_aresetn]

connect_bd_intf_net -intf_net PCIE_M_AXI [get_bd_intf_ports xdma_0_M_AXI_0] [get_bd_intf_pins xdma_0/M_AXI]
connect_bd_intf_net -intf_net PCIE_M_AXI_LITE [get_bd_intf_ports xdma_0_M_AXI_LITE_0] [get_bd_intf_pins xdma_0/M_AXI_LITE]

connect_bd_net -net util_ds_buf_IBUF_DS_ODIV2 [get_bd_pins util_ds_buf/IBUF_DS_ODIV2] [get_bd_pins xdma_0/sys_clk]

connect_bd_net -net util_ds_buf_IBUF_OUT [get_bd_pins util_ds_buf/IBUF_OUT] [get_bd_pins xdma_0/sys_clk_gt]

connect_bd_net -net xlconstant_0_dout [get_bd_pins xdma_0/usr_irq_req] [get_bd_pins xlconstant_0/dout]
