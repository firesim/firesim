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
set axi_dwidth_converter_1 [ create_axi_dwidth_converter axi_dwidth_converter_1 ]
set axi_dwidth_converter_2 [ create_axi_dwidth_converter axi_dwidth_converter_2 ]
set axi_dwidth_converter_3 [ create_axi_dwidth_converter axi_dwidth_converter_3 ]

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
set axi_tieoff_master_1 [ create_axi_tieoff_master axi_tieoff_master_1 ]
set axi_tieoff_master_2 [ create_axi_tieoff_master axi_tieoff_master_2 ]
set axi_tieoff_master_3 [ create_axi_tieoff_master axi_tieoff_master_3 ]

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
set ddr4_1 [ create_ddr ddr4_1 default_300mhz_clk1 ddr4_sdram_c1 ]
set ddr4_2 [ create_ddr ddr4_2 default_300mhz_clk2 ddr4_sdram_c2 ]
set ddr4_3 [ create_ddr ddr4_3 default_300mhz_clk3 ddr4_sdram_c3 ]

set proc_sys_reset_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 proc_sys_reset_0 ]

proc create_proc_sys_reset_ddr { name } {
   return [ create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 $name ]
}
set proc_sys_reset_ddr_0 [ create_proc_sys_reset_ddr proc_sys_reset_ddr_0 ]
set proc_sys_reset_ddr_1 [ create_proc_sys_reset_ddr proc_sys_reset_ddr_1 ]
set proc_sys_reset_ddr_2 [ create_proc_sys_reset_ddr proc_sys_reset_ddr_2 ]
set proc_sys_reset_ddr_3 [ create_proc_sys_reset_ddr proc_sys_reset_ddr_3 ]

set resetn_inv_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:util_vector_logic:2.0 resetn_inv_0 ]
set_property -dict [list \
   CONFIG.C_OPERATION {not} \
   CONFIG.C_SIZE {1} \
] $resetn_inv_0

set util_ds_buf [ create_bd_cell -type ip -vlnv xilinx.com:ip:util_ds_buf:2.2 util_ds_buf ]
set_property -dict [list \
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
