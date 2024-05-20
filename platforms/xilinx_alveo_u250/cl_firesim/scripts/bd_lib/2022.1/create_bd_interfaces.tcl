# Create interface ports

proc create_ddr_sdram_intf_port { name } {
   return [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:ddr4_rtl:1.0 $name ]
}
set ddr4_sdram_c0 [ create_ddr_sdram_intf_port ddr4_sdram_c0 ]

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

proc create_qsfp_clk_intf_port { name } {
   set i [ create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 $name ]
   set_property -dict [ list \
      CONFIG.FREQ_HZ {156250000} \
   ] $i
   return $i
}
proc create_qsfp_intf_port { name } {
   return [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:gt_rtl:1.0 $name ]
}
set qsfp0_156mhz [ create_qsfp_clk_intf_port qsfp0_156mhz ]
set qsfp1_156mhz [ create_qsfp_clk_intf_port qsfp1_156mhz ]
set qsfp0_4x [ create_qsfp_intf_port qsfp0_4x ]
set qsfp1_4x [ create_qsfp_intf_port qsfp1_4x ]

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

proc create_bd_port_data_vector { name dir from to } {
   set i [ create_bd_port -dir $dir -from $from -to $to $name ]
   return $i
}
proc create_bd_port_data_nonvector { name dir } {
   set i [ create_bd_port -dir $dir $name ]
   return $i
}
# TO/FROM: the perspective of block design to/from the FireSim block
set QSFP0_CHANNEL_UP [ create_bd_port_data_nonvector QSFP0_CHANNEL_UP O ]
set QSFP1_CHANNEL_UP [ create_bd_port_data_nonvector QSFP1_CHANNEL_UP O ]

set TO_QSFP0_READY [ create_bd_port_data_nonvector TO_QSFP0_READY O ]
set TO_QSFP0_VALID [ create_bd_port_data_nonvector TO_QSFP0_VALID I ]
set TO_QSFP0_DATA [ create_bd_port_data_vector TO_QSFP0_DATA I 255 0 ]

set TO_QSFP1_READY [ create_bd_port_data_nonvector TO_QSFP1_READY O ]
set TO_QSFP1_VALID [ create_bd_port_data_nonvector TO_QSFP1_VALID I ]
set TO_QSFP1_DATA [ create_bd_port_data_vector TO_QSFP1_DATA I 255 0 ]

set FROM_QSFP0_READY [ create_bd_port_data_nonvector FROM_QSFP0_READY I ]
set FROM_QSFP0_VALID [ create_bd_port_data_nonvector FROM_QSFP0_VALID O ]
set FROM_QSFP0_DATA [ create_bd_port_data_vector FROM_QSFP0_DATA O 255 0 ]

set FROM_QSFP1_READY [ create_bd_port_data_nonvector FROM_QSFP1_READY I ]
set FROM_QSFP1_VALID [ create_bd_port_data_nonvector FROM_QSFP1_VALID O ]
set FROM_QSFP1_DATA [ create_bd_port_data_vector FROM_QSFP1_DATA O 255 0 ]
