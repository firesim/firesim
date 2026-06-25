connect_bd_intf_net -intf_net pcie_refclk_net [get_bd_intf_ports pcie_refclk] [get_bd_intf_pins util_ds_buf/CLK_IN_D]
connect_bd_intf_net -intf_net xdma_0_pcie_mgt_net [get_bd_intf_ports pci_express_x16] [get_bd_intf_pins xdma_0/pcie_mgt]
connect_bd_net -net pcie_perstn_net [get_bd_ports pcie_perstn] [get_bd_pins xdma_0/sys_rst_n]

connect_bd_intf_net -intf_net ddr4_0_C0_DDR4_net [get_bd_intf_ports ddr4_sdram_c0] [get_bd_intf_pins ddr4_0/C0_DDR4]

connect_bd_net -net resetn_net \
   [get_bd_ports resetn] \
   [get_bd_pins proc_sys_reset_0/ext_reset_in] \
   [get_bd_pins proc_sys_reset_ddr_0/ext_reset_in] \
   [get_bd_pins resetn_inv_0/Op1]

connect_bd_intf_net -intf_net default_300mhz_clk0_net [get_bd_intf_ports default_300mhz_clk0] [get_bd_intf_pins ddr4_0/C0_SYS_CLK]

connect_bd_intf_net -intf_net M_AXI_DDR0_net [get_bd_intf_pins axi_dwidth_converter_0/S_AXI] [get_bd_intf_ports DDR4_0_S_AXI]

connect_bd_intf_net -intf_net axi_clock_converter_0_M_AXI_net [get_bd_intf_pins axi_clock_converter_0/M_AXI] [get_bd_intf_ports PCIE_M_AXI]
connect_bd_intf_net -intf_net axi_clock_converter_1_M_AXI_net [get_bd_intf_pins axi_clock_converter_1/M_AXI] [get_bd_intf_ports PCIE_M_AXI_LITE]

connect_bd_net -net proc_sys_reset_0_interconnect_aresetn \
   [get_bd_ports sys_reset_n] \
   [get_bd_pins axi_clock_converter_0/m_axi_aresetn] \
   [get_bd_pins axi_clock_converter_1/m_axi_aresetn] \
   [get_bd_pins axi_dwidth_converter_0/s_axi_aresetn] \
   [get_bd_pins axis_clock_converter_0/m_axis_aresetn] \
   [get_bd_pins axis_clock_converter_1/s_axis_aresetn] \
   [get_bd_pins axis_clock_converter_2/m_axis_aresetn] \
   [get_bd_pins axis_clock_converter_3/s_axis_aresetn] \
   [get_bd_pins proc_sys_reset_0/interconnect_aresetn]

connect_bd_net -net sys_clk_net \
   [get_bd_ports sys_clk] \
   [get_bd_pins axi_clock_converter_0/m_axi_aclk] \
   [get_bd_pins axi_clock_converter_1/m_axi_aclk] \
   [get_bd_pins axi_dwidth_converter_0/s_axi_aclk] \
   [get_bd_pins axis_clock_converter_0/m_axis_aclk] \
   [get_bd_pins axis_clock_converter_1/s_axis_aclk] \
   [get_bd_pins axis_clock_converter_2/m_axis_aclk] \
   [get_bd_pins axis_clock_converter_3/s_axis_aclk] \
   [get_bd_pins clk_wiz_0/clk_out1] \
   [get_bd_pins proc_sys_reset_0/slowest_sync_clk]

connect_bd_intf_net -intf_net aurora_64b66b_0_USER_DATA_M_AXIS_RX [get_bd_intf_pins aurora_64b66b_0/USER_DATA_M_AXIS_RX] [get_bd_intf_pins axis_data_fifo_0/S_AXIS]
connect_bd_intf_net -intf_net aurora_64b66b_1_USER_DATA_M_AXIS_RX [get_bd_intf_pins aurora_64b66b_1/USER_DATA_M_AXIS_RX] [get_bd_intf_pins axis_data_fifo_2/S_AXIS]
connect_bd_intf_net -intf_net aurora_out_0 [get_bd_intf_ports qsfp0_4x] [get_bd_intf_pins aurora_gt_wrapper_0/QSFP_GT]
connect_bd_intf_net -intf_net aurora_out_1 [get_bd_intf_ports qsfp1_4x] [get_bd_intf_pins aurora_gt_wrapper_1/QSFP_GT]
connect_bd_intf_net -intf_net qsfp0_156mhz_1 [get_bd_intf_ports qsfp0_156mhz] [get_bd_intf_pins aurora_64b66b_0/GT_DIFF_REFCLK1]
connect_bd_intf_net -intf_net qsfp1_156mhz_1 [get_bd_intf_ports qsfp1_156mhz] [get_bd_intf_pins aurora_64b66b_1/GT_DIFF_REFCLK1]
connect_bd_intf_net -intf_net axis_clock_converter_1_M_AXIS [get_bd_intf_pins axis_clock_converter_1/M_AXIS] [get_bd_intf_pins axis_data_fifo_3/S_AXIS]
connect_bd_intf_net -intf_net axis_clock_converter_3_M_AXIS [get_bd_intf_pins axis_clock_converter_3/M_AXIS] [get_bd_intf_pins axis_data_fifo_1/S_AXIS]
connect_bd_intf_net -intf_net axis_data_fifo_0_M_AXIS [get_bd_intf_pins axis_clock_converter_2/S_AXIS] [get_bd_intf_pins axis_data_fifo_0/M_AXIS]
connect_bd_intf_net -intf_net axis_data_fifo_1_M_AXIS [get_bd_intf_pins aurora_64b66b_0/USER_DATA_S_AXIS_TX] [get_bd_intf_pins axis_data_fifo_1/M_AXIS]
connect_bd_intf_net -intf_net axis_data_fifo_2_M_AXIS [get_bd_intf_pins axis_clock_converter_0/S_AXIS] [get_bd_intf_pins axis_data_fifo_2/M_AXIS]
connect_bd_intf_net -intf_net axis_data_fifo_3_M_AXIS [get_bd_intf_pins aurora_64b66b_1/USER_DATA_S_AXIS_TX] [get_bd_intf_pins axis_data_fifo_3/M_AXIS]
connect_bd_intf_net -intf_net default_300mhz_clk1_net [get_bd_intf_ports default_300mhz_clk1] [get_bd_intf_pins clk_wiz_aurora_0/CLK_IN1_D]
connect_bd_intf_net -intf_net default_300mhz_clk2_net [get_bd_intf_ports default_300mhz_clk2] [get_bd_intf_pins clk_wiz_aurora_1/CLK_IN1_D]

connect_bd_intf_net -intf_net axi_dwidth_converter_0_M_AXI [get_bd_intf_pins axi_dwidth_converter_0/M_AXI] [get_bd_intf_pins ddr4_0/C0_DDR4_S_AXI]

connect_bd_intf_net -intf_net axi_tieoff_master_0_TIEOFF_M_AXI_CTRL_0 \
   [get_bd_intf_pins axi_tieoff_master_0/TIEOFF_M_AXI_CTRL_0] \
   [get_bd_intf_pins ddr4_0/C0_DDR4_S_AXI_CTRL]

connect_bd_intf_net -intf_net xdma_0_M_AXI [get_bd_intf_pins axi_clock_converter_0/S_AXI] [get_bd_intf_pins xdma_0/M_AXI]
connect_bd_intf_net -intf_net xdma_0_M_AXI_LITE [get_bd_intf_pins axi_clock_converter_1/S_AXI] [get_bd_intf_pins xdma_0/M_AXI_LITE]

# clock for system (clk_wiz_0/clk_in1) comes from DDR0
connect_bd_net -net ddr4_0_c0_ddr4_ui_clk \
   [get_bd_pins clk_wiz_0/clk_in1] \
   [get_bd_pins proc_sys_reset_ddr_0/slowest_sync_clk] \
   [get_bd_pins axi_dwidth_converter_0/m_axi_aclk] \
   [get_bd_pins ddr4_0/c0_ddr4_ui_clk]

connect_bd_net -net resetn_inv_0_Res \
   [get_bd_pins clk_wiz_0/reset] \
   [get_bd_pins ddr4_0/sys_rst] \
   [get_bd_pins clk_wiz_aurora_0/reset] \
   [get_bd_pins clk_wiz_aurora_1/reset] \
   [get_bd_pins resetn_inv_0/Res]

connect_bd_net -net rst_ddr4_0_300M_interconnect_aresetn \
   [get_bd_pins axi_dwidth_converter_0/m_axi_aresetn] \
   [get_bd_pins ddr4_0/c0_ddr4_aresetn] \
   [get_bd_pins proc_sys_reset_ddr_0/interconnect_aresetn]

connect_bd_net -net util_ds_buf_IBUF_DS_ODIV2 [get_bd_pins util_ds_buf/IBUF_DS_ODIV2] [get_bd_pins xdma_0/sys_clk]

connect_bd_net -net util_ds_buf_IBUF_OUT [get_bd_pins util_ds_buf/IBUF_OUT] [get_bd_pins xdma_0/sys_clk_gt]

connect_bd_net -net xdma_0_axi_aclk [get_bd_pins axi_clock_converter_0/s_axi_aclk] [get_bd_pins axi_clock_converter_1/s_axi_aclk] [get_bd_pins xdma_0/axi_aclk]

connect_bd_net -net xdma_0_axi_aresetn [get_bd_pins axi_clock_converter_0/s_axi_aresetn] [get_bd_pins axi_clock_converter_1/s_axi_aresetn] [get_bd_pins xdma_0/axi_aresetn]

connect_bd_net -net xlconstant_0_dout [get_bd_pins xdma_0/usr_irq_req] [get_bd_pins xlconstant_0/dout]

##########

connect_bd_net -net Net [get_bd_pins aurora_64b66b_1/user_clk_out] [get_bd_pins aurora_64b66b_1_driver/user_clk_i] [get_bd_pins axis_clock_converter_0/s_axis_aclk] [get_bd_pins axis_clock_converter_1/m_axis_aclk] [get_bd_pins axis_data_fifo_2/s_axis_aclk] [get_bd_pins axis_data_fifo_3/s_axis_aclk]
connect_bd_net -net aurora_64b66b_0_channel_up [get_bd_pins aurora_64b66b_0/channel_up] [get_bd_pins aurora_64b66b_0_driver/channel_up_i] [get_bd_ports QSFP0_CHANNEL_UP]
connect_bd_net -net aurora_64b66b_0_driver_INIT_CLK_i [get_bd_pins aurora_64b66b_0/init_clk] [get_bd_pins aurora_64b66b_0_driver/INIT_CLK_i]
connect_bd_net -net aurora_64b66b_0_driver_gt_reset_i [get_bd_pins aurora_64b66b_0/pma_init] [get_bd_pins aurora_64b66b_0_driver/gt_reset_i]
connect_bd_net -net aurora_64b66b_0_driver_gt_rxcdrovrden_i [get_bd_pins aurora_64b66b_0/gt_rxcdrovrden_in] [get_bd_pins aurora_64b66b_0_driver/gt_rxcdrovrden_i]
connect_bd_net -net aurora_64b66b_0_driver_loopback_i [get_bd_pins aurora_64b66b_0/loopback] [get_bd_pins aurora_64b66b_0_driver/loopback_i]
connect_bd_net -net aurora_64b66b_0_driver_power_down_i [get_bd_pins aurora_64b66b_0/power_down] [get_bd_pins aurora_64b66b_0_driver/power_down_i]
connect_bd_net -net aurora_64b66b_0_driver_reset_pb [get_bd_pins aurora_64b66b_0/reset_pb] [get_bd_pins aurora_64b66b_0_driver/reset_pb]
connect_bd_net -net aurora_64b66b_0_sys_reset_out [get_bd_pins aurora_64b66b_0/sys_reset_out] [get_bd_pins aurora_64b66b_0_driver/system_reset_i] [get_bd_pins util_vector_logic_1/Op1]
connect_bd_net -net aurora_64b66b_0_txn [get_bd_pins aurora_64b66b_0/txn] [get_bd_pins aurora_gt_wrapper_0/TXN_in]
connect_bd_net -net aurora_64b66b_0_txp [get_bd_pins aurora_64b66b_0/txp] [get_bd_pins aurora_gt_wrapper_0/TXP_in]
connect_bd_net -net aurora_64b66b_0_user_clk_out [get_bd_pins aurora_64b66b_0/user_clk_out] [get_bd_pins aurora_64b66b_0_driver/user_clk_i] [get_bd_pins axis_clock_converter_2/s_axis_aclk] [get_bd_pins axis_clock_converter_3/m_axis_aclk] [get_bd_pins axis_data_fifo_0/s_axis_aclk] [get_bd_pins axis_data_fifo_1/s_axis_aclk]
connect_bd_net -net aurora_64b66b_1_channel_up [get_bd_pins aurora_64b66b_1/channel_up] [get_bd_pins aurora_64b66b_1_driver/channel_up_i] [get_bd_ports QSFP1_CHANNEL_UP]
connect_bd_net -net aurora_64b66b_1_driver_INIT_CLK_i [get_bd_pins aurora_64b66b_1/init_clk] [get_bd_pins aurora_64b66b_1_driver/INIT_CLK_i]
connect_bd_net -net aurora_64b66b_1_driver_gt_reset_i [get_bd_pins aurora_64b66b_1/pma_init] [get_bd_pins aurora_64b66b_1_driver/gt_reset_i]
connect_bd_net -net aurora_64b66b_1_driver_gt_rxcdrovrden_i [get_bd_pins aurora_64b66b_1/gt_rxcdrovrden_in] [get_bd_pins aurora_64b66b_1_driver/gt_rxcdrovrden_i]
connect_bd_net -net aurora_64b66b_1_driver_loopback_i [get_bd_pins aurora_64b66b_1/loopback] [get_bd_pins aurora_64b66b_1_driver/loopback_i]
connect_bd_net -net aurora_64b66b_1_driver_power_down_i [get_bd_pins aurora_64b66b_1/power_down] [get_bd_pins aurora_64b66b_1_driver/power_down_i]
connect_bd_net -net aurora_64b66b_1_driver_reset_pb [get_bd_pins aurora_64b66b_1/reset_pb] [get_bd_pins aurora_64b66b_1_driver/reset_pb]
connect_bd_net -net aurora_64b66b_1_sys_reset_out [get_bd_pins aurora_64b66b_1/sys_reset_out] [get_bd_pins aurora_64b66b_1_driver/system_reset_i] [get_bd_pins util_vector_logic_0/Op1]
connect_bd_net -net aurora_64b66b_1_txn [get_bd_pins aurora_64b66b_1/txn] [get_bd_pins aurora_gt_wrapper_1/TXN_in]
connect_bd_net -net aurora_64b66b_1_txp [get_bd_pins aurora_64b66b_1/txp] [get_bd_pins aurora_gt_wrapper_1/TXP_in]
connect_bd_net -net aurora_gt_wrapper_0_RXN_in [get_bd_pins aurora_64b66b_0/rxn] [get_bd_pins aurora_gt_wrapper_0/RXN_in]
connect_bd_net -net aurora_gt_wrapper_0_RXP_in [get_bd_pins aurora_64b66b_0/rxp] [get_bd_pins aurora_gt_wrapper_0/RXP_in]
connect_bd_net -net aurora_gt_wrapper_1_RXN_in [get_bd_pins aurora_64b66b_1/rxn] [get_bd_pins aurora_gt_wrapper_1/RXN_in]
connect_bd_net -net aurora_gt_wrapper_1_RXP_in [get_bd_pins aurora_64b66b_1/rxp] [get_bd_pins aurora_gt_wrapper_1/RXP_in]
connect_bd_net -net axis_clock_converter_0_m_axis_tdata [get_bd_pins axis_clock_converter_0/m_axis_tdata] [get_bd_ports FROM_QSFP1_DATA]
connect_bd_net -net axis_clock_converter_0_m_axis_tvalid [get_bd_pins axis_clock_converter_0/m_axis_tvalid] [get_bd_ports FROM_QSFP1_VALID]
connect_bd_net -net axis_clock_converter_1_s_axis_tready [get_bd_pins axis_clock_converter_1/s_axis_tready] [get_bd_ports TO_QSFP1_READY]
connect_bd_net -net axis_clock_converter_2_m_axis_tdata [get_bd_pins axis_clock_converter_2/m_axis_tdata] [get_bd_ports FROM_QSFP0_DATA]
connect_bd_net -net axis_clock_converter_2_m_axis_tvalid [get_bd_pins axis_clock_converter_2/m_axis_tvalid] [get_bd_ports FROM_QSFP0_VALID]
connect_bd_net -net axis_clock_converter_3_s_axis_tready [get_bd_pins axis_clock_converter_3/s_axis_tready] [get_bd_ports TO_QSFP0_READY]
connect_bd_net -net clk_wiz_aurora_0_clk_out1 [get_bd_pins aurora_64b66b_0_driver/INIT_CLK_IN] [get_bd_pins clk_wiz_aurora_0/clk_out1]
connect_bd_net -net clk_wiz_aurora_0_locked [get_bd_pins aurora_64b66b_0_driver/locked] [get_bd_pins clk_wiz_aurora_0/locked]
connect_bd_net -net clk_wiz_aurora_1_clk_out1 [get_bd_pins aurora_64b66b_1_driver/INIT_CLK_IN] [get_bd_pins clk_wiz_aurora_1/clk_out1]
connect_bd_net -net clk_wiz_aurora_1_locked [get_bd_pins aurora_64b66b_1_driver/locked] [get_bd_pins clk_wiz_aurora_1/locked]
connect_bd_net -net util_vector_logic_0_Res [get_bd_pins axis_clock_converter_0/s_axis_aresetn] [get_bd_pins axis_clock_converter_1/m_axis_aresetn] [get_bd_pins axis_data_fifo_2/s_axis_aresetn] [get_bd_pins axis_data_fifo_3/s_axis_aresetn] [get_bd_pins util_vector_logic_0/Res]
connect_bd_net -net util_vector_logic_1_Res [get_bd_pins axis_clock_converter_2/s_axis_aresetn] [get_bd_pins axis_clock_converter_3/m_axis_aresetn] [get_bd_pins axis_data_fifo_0/s_axis_aresetn] [get_bd_pins axis_data_fifo_1/s_axis_aresetn] [get_bd_pins util_vector_logic_1/Res]

connect_bd_net -net firesim_wrapper_0_FROM_QSFP0_READY [get_bd_pins axis_clock_converter_2/m_axis_tready] [get_bd_ports FROM_QSFP0_READY]
connect_bd_net -net firesim_wrapper_0_FROM_QSFP1_READY [get_bd_pins axis_clock_converter_0/m_axis_tready] [get_bd_ports FROM_QSFP1_READY]
connect_bd_net -net firesim_wrapper_0_TO_QSFP0_DATA [get_bd_pins axis_clock_converter_3/s_axis_tdata] [get_bd_ports TO_QSFP0_DATA]
connect_bd_net -net firesim_wrapper_0_TO_QSFP0_VALID [get_bd_pins axis_clock_converter_3/s_axis_tvalid] [get_bd_ports TO_QSFP0_VALID]
connect_bd_net -net firesim_wrapper_0_TO_QSFP1_DATA [get_bd_pins axis_clock_converter_1/s_axis_tdata] [get_bd_ports TO_QSFP1_DATA]
connect_bd_net -net firesim_wrapper_0_TO_QSFP1_VALID [get_bd_pins axis_clock_converter_1/s_axis_tvalid] [get_bd_ports TO_QSFP1_VALID]
