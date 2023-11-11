# clock for system (clk_wiz_0/clk_in1) comes from DDR0
connect_bd_net -net ddr4_0_c0_ddr4_ui_clk \
   [get_bd_pins clk_wiz_0/clk_in1] \
   [get_bd_pins proc_sys_reset_ddr_0/slowest_sync_clk] \
   [get_bd_pins axi_dwidth_converter_0/m_axi_aclk] \
   [get_bd_pins ddr4_0/c0_ddr4_ui_clk]

connect_bd_net -net ddr4_1_c0_ddr4_ui_clk \
   [get_bd_pins proc_sys_reset_ddr_1/slowest_sync_clk] \
   [get_bd_pins axi_dwidth_converter_1/m_axi_aclk] \
   [get_bd_pins ddr4_1/c0_ddr4_ui_clk]

connect_bd_net -net ddr4_2_c0_ddr4_ui_clk \
   [get_bd_pins proc_sys_reset_ddr_2/slowest_sync_clk] \
   [get_bd_pins axi_dwidth_converter_2/m_axi_aclk] \
   [get_bd_pins ddr4_2/c0_ddr4_ui_clk]

connect_bd_net -net ddr4_3_c0_ddr4_ui_clk \
   [get_bd_pins proc_sys_reset_ddr_3/slowest_sync_clk] \
   [get_bd_pins axi_dwidth_converter_3/m_axi_aclk] \
   [get_bd_pins ddr4_3/c0_ddr4_ui_clk]

connect_bd_net -net pcie_perstn_1 [get_bd_ports pcie_perstn] [get_bd_pins xdma_0/sys_rst_n]
connect_bd_net -net proc_sys_reset_0_interconnect_aresetn \
   [get_bd_pins axi_clock_converter_0/m_axi_aresetn] \
   [get_bd_pins axi_clock_converter_1/m_axi_aresetn] \
   [get_bd_pins axi_dwidth_converter_0/s_axi_aresetn] \
   [get_bd_pins axi_dwidth_converter_1/s_axi_aresetn] \
   [get_bd_pins axi_dwidth_converter_2/s_axi_aresetn] \
   [get_bd_pins axi_dwidth_converter_3/s_axi_aresetn] \
   [get_bd_pins firesim_wrapper_0/sys_reset_n] \
   [get_bd_pins proc_sys_reset_0/interconnect_aresetn]

connect_bd_net -net resetn_1 \
   [get_bd_ports resetn] \
   [get_bd_pins proc_sys_reset_0/ext_reset_in] \
   [get_bd_pins proc_sys_reset_ddr_0/ext_reset_in] \
   [get_bd_pins proc_sys_reset_ddr_1/ext_reset_in] \
   [get_bd_pins proc_sys_reset_ddr_2/ext_reset_in] \
   [get_bd_pins proc_sys_reset_ddr_3/ext_reset_in] \
   [get_bd_pins resetn_inv_0/Op1]

connect_bd_net -net resetn_inv_0_Res \
   [get_bd_pins clk_wiz_0/reset] \
   [get_bd_pins ddr4_0/sys_rst] \
   [get_bd_pins ddr4_1/sys_rst] \
   [get_bd_pins ddr4_2/sys_rst] \
   [get_bd_pins ddr4_3/sys_rst] \
   [get_bd_pins resetn_inv_0/Res]

connect_bd_net -net rst_ddr4_0_300M_interconnect_aresetn \
   [get_bd_pins axi_dwidth_converter_0/m_axi_aresetn] \
   [get_bd_pins ddr4_0/c0_ddr4_aresetn] \
   [get_bd_pins proc_sys_reset_ddr_0/interconnect_aresetn]

connect_bd_net -net rst_ddr4_1_300M_interconnect_aresetn \
   [get_bd_pins axi_dwidth_converter_1/m_axi_aresetn] \
   [get_bd_pins ddr4_1/c0_ddr4_aresetn] \
   [get_bd_pins proc_sys_reset_ddr_1/interconnect_aresetn]

connect_bd_net -net rst_ddr4_2_300M_interconnect_aresetn \
   [get_bd_pins axi_dwidth_converter_2/m_axi_aresetn] \
   [get_bd_pins ddr4_2/c0_ddr4_aresetn] \
   [get_bd_pins proc_sys_reset_ddr_2/interconnect_aresetn]

connect_bd_net -net rst_ddr4_3_300M_interconnect_aresetn \
   [get_bd_pins axi_dwidth_converter_3/m_axi_aresetn] \
   [get_bd_pins ddr4_3/c0_ddr4_aresetn] \
   [get_bd_pins proc_sys_reset_ddr_3/interconnect_aresetn]

connect_bd_net -net sys_clk_30 \
   [get_bd_pins axi_clock_converter_0/m_axi_aclk] \
   [get_bd_pins axi_clock_converter_1/m_axi_aclk] \
   [get_bd_pins axi_dwidth_converter_0/s_axi_aclk] \
   [get_bd_pins axi_dwidth_converter_1/s_axi_aclk] \
   [get_bd_pins axi_dwidth_converter_2/s_axi_aclk] \
   [get_bd_pins axi_dwidth_converter_3/s_axi_aclk] \
   [get_bd_pins clk_wiz_0/clk_out1] \
   [get_bd_pins firesim_wrapper_0/sys_clk_30] \
   [get_bd_pins proc_sys_reset_0/slowest_sync_clk]

connect_bd_net -net util_ds_buf_IBUF_DS_ODIV2 [get_bd_pins util_ds_buf/IBUF_DS_ODIV2] [get_bd_pins xdma_0/sys_clk]
connect_bd_net -net util_ds_buf_IBUF_OUT [get_bd_pins util_ds_buf/IBUF_OUT] [get_bd_pins xdma_0/sys_clk_gt]
connect_bd_net -net xdma_0_axi_aclk [get_bd_pins axi_clock_converter_0/s_axi_aclk] [get_bd_pins axi_clock_converter_1/s_axi_aclk] [get_bd_pins xdma_0/axi_aclk]
connect_bd_net -net xdma_0_axi_aresetn [get_bd_pins axi_clock_converter_0/s_axi_aresetn] [get_bd_pins axi_clock_converter_1/s_axi_aresetn] [get_bd_pins xdma_0/axi_aresetn]
connect_bd_net -net xlconstant_0_dout [get_bd_pins xdma_0/usr_irq_req] [get_bd_pins xlconstant_0/dout]
