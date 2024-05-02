connect_bd_intf_net -intf_net axi_dwidth_converter_0_M_AXI [get_bd_intf_pins axi_dwidth_converter_0/M_AXI] [get_bd_intf_pins ddr4_0/C0_DDR4_S_AXI]
#connect_bd_intf_net -intf_net axi_dwidth_converter_1_M_AXI [get_bd_intf_pins axi_dwidth_converter_1/M_AXI] [get_bd_intf_pins ddr4_1/C0_DDR4_S_AXI]
#connect_bd_intf_net -intf_net axi_dwidth_converter_2_M_AXI [get_bd_intf_pins axi_dwidth_converter_2/M_AXI] [get_bd_intf_pins ddr4_2/C0_DDR4_S_AXI]
#connect_bd_intf_net -intf_net axi_dwidth_converter_3_M_AXI [get_bd_intf_pins axi_dwidth_converter_3/M_AXI] [get_bd_intf_pins ddr4_3/C0_DDR4_S_AXI]

connect_bd_intf_net -intf_net axi_tieoff_master_0_TIEOFF_M_AXI_CTRL_0 \
   [get_bd_intf_pins axi_tieoff_master_0/TIEOFF_M_AXI_CTRL_0] \
   [get_bd_intf_pins ddr4_0/C0_DDR4_S_AXI_CTRL]
#connect_bd_intf_net -intf_net axi_tieoff_master_1_TIEOFF_M_AXI_CTRL_0 \
#   [get_bd_intf_pins axi_tieoff_master_1/TIEOFF_M_AXI_CTRL_0] \
#   [get_bd_intf_pins ddr4_1/C0_DDR4_S_AXI_CTRL]
#connect_bd_intf_net -intf_net axi_tieoff_master_2_TIEOFF_M_AXI_CTRL_0 \
#   [get_bd_intf_pins axi_tieoff_master_2/TIEOFF_M_AXI_CTRL_0] \
#   [get_bd_intf_pins ddr4_2/C0_DDR4_S_AXI_CTRL]
#connect_bd_intf_net -intf_net axi_tieoff_master_3_TIEOFF_M_AXI_CTRL_0 \
#   [get_bd_intf_pins axi_tieoff_master_3/TIEOFF_M_AXI_CTRL_0] \
#   [get_bd_intf_pins ddr4_3/C0_DDR4_S_AXI_CTRL]

connect_bd_intf_net -intf_net xdma_0_M_AXI [get_bd_intf_pins axi_clock_converter_0/S_AXI] [get_bd_intf_pins xdma_0/M_AXI]
connect_bd_intf_net -intf_net xdma_0_M_AXI_LITE [get_bd_intf_pins axi_clock_converter_1/S_AXI] [get_bd_intf_pins xdma_0/M_AXI_LITE]

# clock for system (clk_wiz_0/clk_in1) comes from DDR0
connect_bd_net -net ddr4_0_c0_ddr4_ui_clk \
   [get_bd_pins clk_wiz_0/clk_in1] \
   [get_bd_pins proc_sys_reset_ddr_0/slowest_sync_clk] \
   [get_bd_pins axi_dwidth_converter_0/m_axi_aclk] \
   [get_bd_pins ddr4_0/c0_ddr4_ui_clk]

#connect_bd_net -net ddr4_1_c0_ddr4_ui_clk \
#   [get_bd_pins proc_sys_reset_ddr_1/slowest_sync_clk] \
#   [get_bd_pins axi_dwidth_converter_1/m_axi_aclk] \
#   [get_bd_pins ddr4_1/c0_ddr4_ui_clk]
#connect_bd_net -net ddr4_2_c0_ddr4_ui_clk \
#   [get_bd_pins proc_sys_reset_ddr_2/slowest_sync_clk] \
#   [get_bd_pins axi_dwidth_converter_2/m_axi_aclk] \
#   [get_bd_pins ddr4_2/c0_ddr4_ui_clk]
#connect_bd_net -net ddr4_3_c0_ddr4_ui_clk \
#   [get_bd_pins proc_sys_reset_ddr_3/slowest_sync_clk] \
#   [get_bd_pins axi_dwidth_converter_3/m_axi_aclk] \
#   [get_bd_pins ddr4_3/c0_ddr4_ui_clk]

connect_bd_net -net resetn_inv_0_Res \
   [get_bd_pins clk_wiz_0/reset] \
   [get_bd_pins ddr4_0/sys_rst] \
   [get_bd_pins clk_wiz_aurora_0/reset] \
   [get_bd_pins clk_wiz_aurora_1/reset] \
   [get_bd_pins resetn_inv_0/Res]
   #[get_bd_pins ddr4_2/sys_rst] \
   #[get_bd_pins ddr4_1/sys_rst] \
   #[get_bd_pins ddr4_3/sys_rst] \

connect_bd_net -net rst_ddr4_0_300M_interconnect_aresetn \
   [get_bd_pins axi_dwidth_converter_0/m_axi_aresetn] \
   [get_bd_pins ddr4_0/c0_ddr4_aresetn] \
   [get_bd_pins proc_sys_reset_ddr_0/interconnect_aresetn]
#connect_bd_net -net rst_ddr4_1_300M_interconnect_aresetn \
#   [get_bd_pins axi_dwidth_converter_1/m_axi_aresetn] \
#   [get_bd_pins ddr4_1/c0_ddr4_aresetn] \
#   [get_bd_pins proc_sys_reset_ddr_1/interconnect_aresetn]
#connect_bd_net -net rst_ddr4_2_300M_interconnect_aresetn \
#   [get_bd_pins axi_dwidth_converter_2/m_axi_aresetn] \
#   [get_bd_pins ddr4_2/c0_ddr4_aresetn] \
#   [get_bd_pins proc_sys_reset_ddr_2/interconnect_aresetn]
#connect_bd_net -net rst_ddr4_3_300M_interconnect_aresetn \
#   [get_bd_pins axi_dwidth_converter_3/m_axi_aresetn] \
#   [get_bd_pins ddr4_3/c0_ddr4_aresetn] \
#   [get_bd_pins proc_sys_reset_ddr_3/interconnect_aresetn]

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
