proc generate_collateral { name script_folder } {
   set xci_file [get_files ${script_folder}/../vivado_proj/firesim.srcs/sources_1/ip/$name/$name.xci]
   generate_target {instantiation_template} $xci_file
   set_property generate_synth_checkpoint false $xci_file
   generate_target all $xci_file
   export_ip_user_files -of_objects $xci_file -no_script -sync -force -quiet
   update_compile_order -fileset sources_1
}

proc create_axi_clock_converter { name } {
   create_ip -name axi_clock_converter -vendor xilinx.com -library ip -version 2.1 -module_name $name
   return [get_ips $name]
}
set axi_clock_converter_0 [ create_axi_clock_converter axi_clock_converter_0 ]
set_property -dict [list \
   CONFIG.ADDR_WIDTH {64} \
   CONFIG.DATA_WIDTH {512} \
   CONFIG.ID_WIDTH {4} \
] $axi_clock_converter_0
generate_collateral axi_clock_converter_0 $script_folder
set axi_clock_converter_1 [ create_axi_clock_converter axi_clock_converter_1 ]
set_property -dict [list \
   CONFIG.ARUSER_WIDTH {0} \
   CONFIG.AWUSER_WIDTH {0} \
   CONFIG.BUSER_WIDTH {0} \
   CONFIG.DATA_WIDTH {32} \
   CONFIG.ID_WIDTH {0} \
   CONFIG.PROTOCOL {AXI4LITE} \
   CONFIG.RUSER_WIDTH {0} \
   CONFIG.WUSER_WIDTH {0} \
] $axi_clock_converter_1
generate_collateral axi_clock_converter_1 $script_folder

proc create_axi_dwidth_converter { name } {
   set axi_dwidth_props [list \
      CONFIG.ACLK_ASYNC {1} \
      CONFIG.FIFO_MODE {2} \
      CONFIG.MI_DATA_WIDTH {512} \
      CONFIG.SI_DATA_WIDTH {64} \
      CONFIG.SI_ID_WIDTH {16} \
      CONFIG.ADDR_WIDTH {34} \
   ]
   create_ip -name axi_dwidth_converter -vendor xilinx.com -library ip -version 2.1 -module_name $name
   set_property -dict $axi_dwidth_props [get_ips $name]
   return [get_ips $name]
}
set axi_dwidth_converter_0 [ create_axi_dwidth_converter axi_dwidth_converter_0 ]
generate_collateral axi_dwidth_converter_0 $script_folder

create_ip -name clk_wiz -vendor xilinx.com -library ip -version 6.0 -module_name clk_wiz_0
set_property -dict [list \
   CONFIG.CLKOUT1_REQUESTED_OUT_FREQ $firesim_freq_mhz \
   CONFIG.USE_LOCKED {false} \
] [get_ips clk_wiz_0]
generate_collateral clk_wiz_0 $script_folder

proc create_ddr { name clk_intf ddr_intf } {
   create_ip -name ddr4 -vendor xilinx.com -library ip -version 2.2 -module_name $name
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
      CONFIG.C0.DDR4_AxiIDWidth {1} \
      CONFIG.C0.DDR4_AxiNarrowBurst {true} \
      CONFIG.C0.DDR4_AxiSelection {true} \
   ] [get_ips $name]
   return [get_ips $name]
}
set ddr4_0 [ create_ddr ddr4_0 default_300mhz_clk0 ddr4_sdram_c0 ]
generate_collateral ddr4_0 $script_folder

create_ip -name proc_sys_reset -vendor xilinx.com -library ip -version 5.0 -module_name proc_sys_reset_0
set_property -dict [list \
   CONFIG.C_AUX_RESET_HIGH {0} \
   CONFIG.C_EXT_RESET_HIGH {0} \
] [get_ips proc_sys_reset_0]
generate_collateral proc_sys_reset_0 $script_folder

create_ip -name proc_sys_reset -vendor xilinx.com -library ip -version 5.0 -module_name proc_sys_reset_1
set_property -dict [list \
   CONFIG.C_AUX_RESET_HIGH {0} \
   CONFIG.C_EXT_RESET_HIGH {0} \
] [get_ips proc_sys_reset_1]
generate_collateral proc_sys_reset_1 $script_folder

create_ip -name debug_bridge -vendor xilinx.com -library ip -version 3.0 -module_name debug_bridge_0
set_property -dict [list \
   CONFIG.C_DEBUG_MODE {1} \
   CONFIG.C_DESIGN_TYPE {1} \
] [get_ips debug_bridge_0]
generate_collateral debug_bridge_0 $script_folder
