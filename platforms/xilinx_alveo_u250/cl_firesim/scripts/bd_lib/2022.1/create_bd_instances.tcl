# Create instance: axi_clock_converter_0, and set properties
set axi_clock_converter_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_clock_converter_0 ]


# Create instance: axi_clock_converter_1, and set properties
set axi_clock_converter_1 [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_clock_converter_1 ]


# Create instance: axi_dwidth_converter_0,1,2,3, and set properties
# clock conversion is only available in upsizer FIFO mode. by default we are in downsizer mode so we have to manually enter the correct width.
set axi_dwidth_props [list \
   CONFIG.MI_DATA_WIDTH.VALUE_SRC USER \
   CONFIG.ACLK_ASYNC {1} \
   CONFIG.FIFO_MODE {2} \
   CONFIG.MI_DATA_WIDTH {512} \
   CONFIG.SI_DATA_WIDTH {64} \
   CONFIG.SI_ID_WIDTH {16} \
]
set axi_dwidth_converter_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 axi_dwidth_converter_0 ]
set_property -dict $axi_dwidth_props $axi_dwidth_converter_0
set axi_dwidth_converter_1 [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 axi_dwidth_converter_1 ]
set_property -dict $axi_dwidth_props $axi_dwidth_converter_1
set axi_dwidth_converter_2 [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 axi_dwidth_converter_2 ]
set_property -dict $axi_dwidth_props $axi_dwidth_converter_2
set axi_dwidth_converter_3 [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 axi_dwidth_converter_3 ]
set_property -dict $axi_dwidth_props $axi_dwidth_converter_3


# Create instance: axi_tieoff_master_0, and set properties
set block_name axi_tieoff_master
set block_cell_name axi_tieoff_master_0
if { [catch {set axi_tieoff_master_0 [create_bd_cell -type module -reference $block_name $block_cell_name] } errmsg] } {
   catch {common::send_gid_msg -ssname BD::TCL -id 2095 -severity "ERROR" "Unable to add referenced block <$block_name>. Please add the files for ${block_name}'s definition into the project."}
   return 1
} elseif { $axi_tieoff_master_0 eq "" } {
   catch {common::send_gid_msg -ssname BD::TCL -id 2096 -severity "ERROR" "Unable to referenced block <$block_name>. Please add the files for ${block_name}'s definition into the project."}
   return 1
}
set block_cell_name axi_tieoff_master_1
if { [catch {set axi_tieoff_master_1 [create_bd_cell -type module -reference $block_name $block_cell_name] } errmsg] } {
   catch {common::send_gid_msg -ssname BD::TCL -id 2095 -severity "ERROR" "Unable to add referenced block <$block_name>. Please add the files for ${block_name}'s definition into the project."}
   return 1
} elseif { $axi_tieoff_master_1 eq "" } {
   catch {common::send_gid_msg -ssname BD::TCL -id 2096 -severity "ERROR" "Unable to referenced block <$block_name>. Please add the files for ${block_name}'s definition into the project."}
   return 1
}
set block_cell_name axi_tieoff_master_2
if { [catch {set axi_tieoff_master_2 [create_bd_cell -type module -reference $block_name $block_cell_name] } errmsg] } {
   catch {common::send_gid_msg -ssname BD::TCL -id 2095 -severity "ERROR" "Unable to add referenced block <$block_name>. Please add the files for ${block_name}'s definition into the project."}
   return 1
} elseif { $axi_tieoff_master_2 eq "" } {
   catch {common::send_gid_msg -ssname BD::TCL -id 2096 -severity "ERROR" "Unable to referenced block <$block_name>. Please add the files for ${block_name}'s definition into the project."}
   return 1
}
set block_cell_name axi_tieoff_master_3
if { [catch {set axi_tieoff_master_3 [create_bd_cell -type module -reference $block_name $block_cell_name] } errmsg] } {
   catch {common::send_gid_msg -ssname BD::TCL -id 2095 -severity "ERROR" "Unable to add referenced block <$block_name>. Please add the files for ${block_name}'s definition into the project."}
   return 1
} elseif { $axi_tieoff_master_3 eq "" } {
   catch {common::send_gid_msg -ssname BD::TCL -id 2096 -severity "ERROR" "Unable to referenced block <$block_name>. Please add the files for ${block_name}'s definition into the project."}
   return 1
}


# Create instance: clk_wiz_0, and set properties
set clk_wiz_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:clk_wiz:6.0 clk_wiz_0 ]
set_property -dict [list \
   CONFIG.CLKOUT1_REQUESTED_OUT_FREQ $firesim_freq \
   CONFIG.USE_LOCKED {false} \
] $clk_wiz_0


# Create instance: ddr4_0,1,2,3, and set properties
set ddr4_shared_props  [list \
   CONFIG.ADDN_UI_CLKOUT1_FREQ_HZ {100} \
   CONFIG.C0.DDR4_AUTO_AP_COL_A3 {true} \
   CONFIG.C0.DDR4_AxiAddressWidth {34} \
   CONFIG.C0.DDR4_EN_PARITY {true} \
   CONFIG.C0.DDR4_MCS_ECC {false} \
   CONFIG.C0.DDR4_Mem_Add_Map {ROW_COLUMN_BANK_INTLV} \
   CONFIG.C0_CLOCK_BOARD_INTERFACE {default_300mhz_clk0} \
   CONFIG.C0_DDR4_BOARD_INTERFACE {ddr4_sdram_c0} \
   CONFIG.Debug_Signal {Disable} \
   CONFIG.RESET_BOARD_INTERFACE {resetn} \
]
set ddr4_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:ddr4:2.2 ddr4_0 ]
set_property -dict $ddr4_shared_props $ddr4_0
set_property -dict [list \
   CONFIG.C0_CLOCK_BOARD_INTERFACE {default_300mhz_clk0} \
   CONFIG.C0_DDR4_BOARD_INTERFACE {ddr4_sdram_c0} \
] $ddr4_0
set ddr4_1 [ create_bd_cell -type ip -vlnv xilinx.com:ip:ddr4:2.2 ddr4_1 ]
set_property -dict $ddr4_shared_props $ddr4_1
set_property -dict [list \
   CONFIG.C0_CLOCK_BOARD_INTERFACE {default_300mhz_clk1} \
   CONFIG.C0_DDR4_BOARD_INTERFACE {ddr4_sdram_c1} \
] $ddr4_1
set ddr4_2 [ create_bd_cell -type ip -vlnv xilinx.com:ip:ddr4:2.2 ddr4_2 ]
set_property -dict $ddr4_shared_props $ddr4_2
set_property -dict [list \
   CONFIG.C0_CLOCK_BOARD_INTERFACE {default_300mhz_clk2} \
   CONFIG.C0_DDR4_BOARD_INTERFACE {ddr4_sdram_c2} \
] $ddr4_2
set ddr4_3 [ create_bd_cell -type ip -vlnv xilinx.com:ip:ddr4:2.2 ddr4_3 ]
set_property -dict $ddr4_shared_props $ddr4_3
set_property -dict [list \
   CONFIG.C0_CLOCK_BOARD_INTERFACE {default_300mhz_clk3} \
   CONFIG.C0_DDR4_BOARD_INTERFACE {ddr4_sdram_c3} \
] $ddr4_3


# Create instance: firesim_wrapper_0, and set properties
set block_name firesim_wrapper
set block_cell_name firesim_wrapper_0
if { [catch {set firesim_wrapper_0 [create_bd_cell -type module -reference $block_name $block_cell_name] } errmsg] } {
   catch {common::send_gid_msg -ssname BD::TCL -id 2095 -severity "ERROR" "Unable to add referenced block <$block_name>. Please add the files for ${block_name}'s definition into the project."}
   return 1
} elseif { $firesim_wrapper_0 eq "" } {
   catch {common::send_gid_msg -ssname BD::TCL -id 2096 -severity "ERROR" "Unable to referenced block <$block_name>. Please add the files for ${block_name}'s definition into the project."}
   return 1
}


# Create instance: proc_sys_reset_0, and set properties
set proc_sys_reset_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 proc_sys_reset_0 ]


# Create instance: proc_sys_reset_ddr_0,1,2,3, and set properties
set proc_sys_reset_ddr_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 proc_sys_reset_ddr_0 ]
set proc_sys_reset_ddr_1 [ create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 proc_sys_reset_ddr_1 ]
set proc_sys_reset_ddr_2 [ create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 proc_sys_reset_ddr_2 ]
set proc_sys_reset_ddr_3 [ create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 proc_sys_reset_ddr_3 ]


# Create instance: resetn_inv_0, and set properties
set resetn_inv_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:util_vector_logic:2.0 resetn_inv_0 ]
set_property -dict [list \
   CONFIG.C_OPERATION {not} \
   CONFIG.C_SIZE {1} \
] $resetn_inv_0


# Create instance: util_ds_buf, and set properties
set util_ds_buf [ create_bd_cell -type ip -vlnv xilinx.com:ip:util_ds_buf:2.2 util_ds_buf ]
set_property -dict [list \
   CONFIG.DIFF_CLK_IN_BOARD_INTERFACE {pcie_refclk} \
   CONFIG.USE_BOARD_FLOW {true} \
] $util_ds_buf


# Create instance: xdma_0, and set properties
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


# Create instance: xlconstant_0, and set properties
set xlconstant_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:xlconstant:1.1 xlconstant_0 ]
set_property -dict [ list \
   CONFIG.CONST_VAL {0} \
] $xlconstant_0
