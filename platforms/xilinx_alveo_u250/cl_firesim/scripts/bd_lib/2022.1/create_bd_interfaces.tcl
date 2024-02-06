# Create interface ports

proc create_ddr_sdram_intf_port { name } {
   return [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:ddr4_rtl:1.0 $name ]
}
set ddr4_sdram_c0 [ create_ddr_sdram_intf_port ddr4_sdram_c0 ]
set ddr4_sdram_c1 [ create_ddr_sdram_intf_port ddr4_sdram_c1 ]
set ddr4_sdram_c2 [ create_ddr_sdram_intf_port ddr4_sdram_c2 ]
set ddr4_sdram_c3 [ create_ddr_sdram_intf_port ddr4_sdram_c3 ]

proc create_300mhz_clk_intf_port { name } {
   set shared_clk_props [ list \
      CONFIG.FREQ_HZ {300000000} \
   ]
   set i [ create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 $name ]
   set_property -dict $shared_clk_props $i
   return $i
}
set default_300mhz_clk0 [ create_300mhz_clk_intf_port default_300mhz_clk0 ]
set default_300mhz_clk1 [ create_300mhz_clk_intf_port default_300mhz_clk1 ]
set default_300mhz_clk2 [ create_300mhz_clk_intf_port default_300mhz_clk2 ]
set default_300mhz_clk3 [ create_300mhz_clk_intf_port default_300mhz_clk3 ]

set pci_express_x16 [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:pcie_7x_mgt_rtl:1.0 pci_express_x16 ]

set pcie_refclk [ create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 pcie_refclk ]
set_property -dict [ list \
   CONFIG.FREQ_HZ {100000000} \
] $pcie_refclk

set PCIE_M_AXI [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:aximm_rtl:1.0 PCIE_M_AXI ]
set_property -dict [ list \
   CONFIG.FREQ_HZ $firesim_freq_hz \
   CONFIG.DATA_WIDTH 512 \
] $PCIE_M_AXI
set PCIE_M_AXI_LITE [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:aximm_rtl:1.0 PCIE_M_AXI_LITE ]
set_property -dict [ list \
   CONFIG.FREQ_HZ $firesim_freq_hz \
   CONFIG.PROTOCOL AXI4LITE \
] $PCIE_M_AXI_LITE

proc create_ddr_intf_port { name firesim_freq_hz } {
   set i [ create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:aximm_rtl:1.0 $name ]
   set_property -dict [ list \
      CONFIG.FREQ_HZ $firesim_freq_hz \
      CONFIG.DATA_WIDTH 64 \
   ] $i
   return $i
}
set DDR4_0_S_AXI [ create_ddr_intf_port DDR4_0_S_AXI $firesim_freq_hz ]
set DDR4_1_S_AXI [ create_ddr_intf_port DDR4_1_S_AXI $firesim_freq_hz ]
set DDR4_2_S_AXI [ create_ddr_intf_port DDR4_2_S_AXI $firesim_freq_hz ]
set DDR4_3_S_AXI [ create_ddr_intf_port DDR4_3_S_AXI $firesim_freq_hz ]

# Create ports

set pcie_perstn [ create_bd_port -dir I -type rst pcie_perstn ]
set_property -dict [ list \
   CONFIG.POLARITY {ACTIVE_LOW} \
] $pcie_perstn

set resetn [ create_bd_port -dir I -type rst resetn ]
set_property -dict [ list \
   CONFIG.POLARITY {ACTIVE_LOW} \
] $resetn

set sys_clk [ create_bd_port -dir O -type clk sys_clk ]
set_property -dict [ list \
   CONFIG.FREQ_HZ $firesim_freq_hz \
] $sys_clk
set sys_reset_n [ create_bd_port -dir O -type rst sys_reset_n ]
