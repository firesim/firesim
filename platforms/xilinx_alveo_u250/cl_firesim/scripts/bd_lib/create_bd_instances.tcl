proc create_axi_clock_converter { name } {
   return [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 $name ]
}
set axi_clock_converter_0 [ create_axi_clock_converter axi_clock_converter_0 ]
set axi_clock_converter_1 [ create_axi_clock_converter axi_clock_converter_1 ]

proc create_axi_dwidth_converter { name } {
   set axi_dwidth_props [list \
      CONFIG.MI_DATA_WIDTH.VALUE_SRC USER \
      CONFIG.ACLK_ASYNC {1} \
      CONFIG.FIFO_MODE {2} \
      CONFIG.MI_DATA_WIDTH {512} \
      CONFIG.SI_DATA_WIDTH {64} \
      CONFIG.SI_ID_WIDTH {16} \
   ]
   set i [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 $name ]
   set_property -dict $axi_dwidth_props $i
   return $i
}
set axi_dwidth_converter_0 [ create_axi_dwidth_converter axi_dwidth_converter_0 ]

proc create_axi_tieoff_master { name } {
   set block_name axi_tieoff_master
   set block_cell_name $name
   if { [catch {set i [create_bd_cell -type module -reference $block_name $block_cell_name] } errmsg] } {
      catch {common::send_gid_msg -ssname BD::TCL -id 2095 -severity "ERROR" "Unable to add referenced block <$block_name>. Please add the files for ${block_name}'s definition into the project."}
      exit 1
   } elseif { $i eq "" } {
      catch {common::send_gid_msg -ssname BD::TCL -id 2096 -severity "ERROR" "Unable to referenced block <$block_name>. Please add the files for ${block_name}'s definition into the project."}
      exit 1
   }
   return $i
}
set axi_tieoff_master_0 [ create_axi_tieoff_master axi_tieoff_master_0 ]

set clk_wiz_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:clk_wiz:6.0 clk_wiz_0 ]
set_property -dict [list \
   CONFIG.CLKOUT1_REQUESTED_OUT_FREQ $firesim_freq_mhz \
   CONFIG.USE_LOCKED {false} \
] $clk_wiz_0

proc create_ddr { name clk_intf ddr_intf } {
   set i [ create_bd_cell -type ip -vlnv xilinx.com:ip:ddr4:2.2 $name ]
   set_property -dict [list \
      CONFIG.C0_CLOCK_BOARD_INTERFACE $clk_intf \
      CONFIG.C0_DDR4_BOARD_INTERFACE $ddr_intf \
      CONFIG.ADDN_UI_CLKOUT1_FREQ_HZ {100} \
      CONFIG.C0.DDR4_AUTO_AP_COL_A3 {true} \
      CONFIG.C0.DDR4_AxiAddressWidth {34} \
      CONFIG.C0.DDR4_EN_PARITY {true} \
      CONFIG.C0.DDR4_MCS_ECC {false} \
      CONFIG.C0.DDR4_Mem_Add_Map {ROW_COLUMN_BANK_INTLV} \
      CONFIG.Debug_Signal {Disable} \
      CONFIG.RESET_BOARD_INTERFACE {resetn} \
   ] $i
   return $i
}
set ddr4_0 [ create_ddr ddr4_0 default_300mhz_clk0 ddr4_sdram_c0 ]

set proc_sys_reset_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 proc_sys_reset_0 ]

proc create_proc_sys_reset_ddr { name } {
   return [ create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 $name ]
}
set proc_sys_reset_ddr_0 [ create_proc_sys_reset_ddr proc_sys_reset_ddr_0 ]

set resetn_inv_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:util_vector_logic:2.0 resetn_inv_0 ]
set_property -dict [list \
   CONFIG.C_OPERATION {not} \
   CONFIG.C_SIZE {1} \
] $resetn_inv_0

set util_ds_buf [ create_bd_cell -type ip -vlnv xilinx.com:ip:util_ds_buf:2.2 util_ds_buf ]
set_property -dict [list \
   CONFIG.C_BUF_TYPE {IBUFDSGTE} \
   CONFIG.DIFF_CLK_IN_BOARD_INTERFACE {pcie_refclk} \
   CONFIG.USE_BOARD_FLOW {true} \
] $util_ds_buf

set xdma_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:xdma:4.1 xdma_0 ]
set_property -dict [list \
   CONFIG.PCIE_BOARD_INTERFACE {pci_express_x16} \
   CONFIG.SYS_RST_N_BOARD_INTERFACE {pcie_perstn} \
   CONFIG.axilite_master_en {true} \
   CONFIG.axilite_master_size {32} \
   CONFIG.xdma_axi_intf_mm {AXI_Memory_Mapped} \
   CONFIG.xdma_rnum_chnl {4} \
   CONFIG.xdma_wnum_chnl {4} \
   CONFIG.pciebar2axibar_axist_bypass {0x0000000000000000} \
   CONFIG.pf0_msix_cap_pba_bir {BAR_1} \
   CONFIG.pf0_msix_cap_table_bir {BAR_1} \
   CONFIG.xdma_axi_intf_mm {AXI_Memory_Mapped} \
] $xdma_0

set xlconstant_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:xlconstant:1.1 xlconstant_0 ]
set_property -dict [ list \
   CONFIG.CONST_VAL {0} \
] $xlconstant_0

proc create_generic_aurora_64b66b { name } {
   set i [ create_bd_cell -type ip -vlnv xilinx.com:ip:aurora_64b66b:12.0 $name ]
   set_property -dict [ list \
      CONFIG.C_AURORA_LANES {4} \
      CONFIG.C_GT_LOC_2 {2} \
      CONFIG.C_GT_LOC_3 {3} \
      CONFIG.C_GT_LOC_4 {4} \
      CONFIG.C_LINE_RATE {15} \
      CONFIG.SupportLevel {1} \
      CONFIG.drp_mode {Disabled} \
      CONFIG.interface_mode {Streaming} \
   ] $i
   return $i
}
# X1YN where N=4,5,6,7,10,11 (channel enable is N*4 to N*4+3)
set aurora_64b66b_0 [ create_generic_aurora_64b66b aurora_64b66b_0 ]
set_property -dict [ list \
   CONFIG.CHANNEL_ENABLE {X1Y44 X1Y45 X1Y46 X1Y47} \
   CONFIG.C_REFCLK_SOURCE {MGTREFCLK0_of_Quad_X1Y11} \
   CONFIG.C_START_LANE {X1Y44} \
   CONFIG.C_START_QUAD {Quad_X1Y11} \
] $aurora_64b66b_0
set aurora_64b66b_1 [ create_generic_aurora_64b66b aurora_64b66b_1 ]
set_property -dict [ list \
   CONFIG.CHANNEL_ENABLE {X1Y40 X1Y41 X1Y42 X1Y43} \
   CONFIG.C_REFCLK_SOURCE {MGTREFCLK0_of_Quad_X1Y10} \
   CONFIG.C_START_LANE {X1Y40} \
   CONFIG.C_START_QUAD {Quad_X1Y10} \
] $aurora_64b66b_1

proc create_aurora_driver { name } {
   set block_name aurora_64b66b_0_driver
   set block_cell_name $name
   if { [catch {set i [create_bd_cell -type module -reference $block_name $block_cell_name] } errmsg] } {
      catch {common::send_gid_msg -ssname BD::TCL -id 2095 -severity "ERROR" "Unable to add referenced block <$block_name>. Please add the files for ${block_name}'s definition into the project."}
      exit 1
   } elseif { $i eq "" } {
      catch {common::send_gid_msg -ssname BD::TCL -id 2096 -severity "ERROR" "Unable to referenced block <$block_name>. Please add the files for ${block_name}'s definition into the project."}
      exit 1
   }
   set_property -dict [ list \
      CONFIG.POLARITY {ACTIVE_HIGH} \
   ] [get_bd_pins /$name/reset_pb]
   return $i
}
set aurora_64b66b_0_driver [ create_aurora_driver aurora_64b66b_0_driver ]
set aurora_64b66b_1_driver [ create_aurora_driver aurora_64b66b_1_driver ]

proc create_aurora_gt_wrapper { name } {
   set block_name aurora_gt_wrapper
   set block_cell_name $name
   if { [catch {set i [create_bd_cell -type module -reference $block_name $block_cell_name] } errmsg] } {
      catch {common::send_gid_msg -ssname BD::TCL -id 2095 -severity "ERROR" "Unable to add referenced block <$block_name>. Please add the files for ${block_name}'s definition into the project."}
      exit 1
   } elseif { $i eq "" } {
      catch {common::send_gid_msg -ssname BD::TCL -id 2096 -severity "ERROR" "Unable to referenced block <$block_name>. Please add the files for ${block_name}'s definition into the project."}
      exit 1
   }
   return $i
}
set aurora_gt_wrapper_0 [ create_aurora_gt_wrapper aurora_gt_wrapper_0 ]
set aurora_gt_wrapper_1 [ create_aurora_gt_wrapper aurora_gt_wrapper_1 ]

proc create_axis_clock_converter { name } {
   set i [ create_bd_cell -type ip -vlnv xilinx.com:ip:axis_clock_converter:1.1 $name ]
   set_property -dict [ list \
      CONFIG.SYNCHRONIZATION_STAGES {3} \
      CONFIG.TDATA_NUM_BYTES {32} \
   ] $i
   return $i
}
set axis_clock_converter_0 [ create_axis_clock_converter axis_clock_converter_0 ]
set axis_clock_converter_1 [ create_axis_clock_converter axis_clock_converter_1 ]
set axis_clock_converter_2 [ create_axis_clock_converter axis_clock_converter_2 ]
set axis_clock_converter_3 [ create_axis_clock_converter axis_clock_converter_3 ]

proc create_axis_data_fifo { name } {
   set i [ create_bd_cell -type ip -vlnv xilinx.com:ip:axis_data_fifo:2.0 $name ]
   set_property -dict [ list \
      CONFIG.FIFO_DEPTH {2048} \
      CONFIG.TDATA_NUM_BYTES {32} \
   ] $i
   return $i
}
set axis_data_fifo_0 [ create_axis_data_fifo axis_data_fifo_0 ]
set axis_data_fifo_1 [ create_axis_data_fifo axis_data_fifo_1 ]
set axis_data_fifo_2 [ create_axis_data_fifo axis_data_fifo_2 ]
set axis_data_fifo_3 [ create_axis_data_fifo axis_data_fifo_3 ]

proc create_aurora_clk_wiz { name } {
   set i [ create_bd_cell -type ip -vlnv xilinx.com:ip:clk_wiz:6.0 $name ]
   set_property -dict [ list \
      CONFIG.CLKIN1_JITTER_PS {33.330000000000005} \
      CONFIG.CLKOUT1_JITTER {101.475} \
      CONFIG.CLKOUT1_PHASE_ERROR {77.836} \
      CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {100} \
      CONFIG.MMCM_CLKFBOUT_MULT_F {4.000} \
      CONFIG.MMCM_CLKIN1_PERIOD {3.333} \
      CONFIG.MMCM_CLKIN2_PERIOD {10.0} \
      CONFIG.MMCM_CLKOUT0_DIVIDE_F {12.000} \
      CONFIG.MMCM_DIVCLK_DIVIDE {1} \
      CONFIG.PRIM_IN_FREQ {300.000} \
      CONFIG.PRIM_SOURCE {Differential_clock_capable_pin} \
   ] $i
   return $i
}
set clk_wiz_aurora_0 [ create_aurora_clk_wiz clk_wiz_aurora_0 ]
set clk_wiz_aurora_1 [ create_aurora_clk_wiz clk_wiz_aurora_1 ]

proc create_util_vector_logic { name } {
   set i [ create_bd_cell -type ip -vlnv xilinx.com:ip:util_vector_logic:2.0 $name ]
   set_property -dict [ list \
      CONFIG.C_OPERATION {not} \
      CONFIG.C_SIZE {1} \
      CONFIG.LOGO_FILE {data/sym_notgate.png} \
   ] $i
   return $i
}
set util_vector_logic_0 [ create_util_vector_logic util_vector_logic_0 ]
set util_vector_logic_1 [ create_util_vector_logic util_vector_logic_1 ]
