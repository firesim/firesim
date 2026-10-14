# Create interface ports

set ddr4_sdram_c0 [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:ddr4_rtl:1.0 ddr4_sdram_c0 ]

set default_250mhz_clk0 [ create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 default_250mhz_clk0 ]
set_property -dict [ list \
   CONFIG.FREQ_HZ {250000000} \
] $default_250mhz_clk0

create_bd_port -dir I -type clk -freq_hz 100000000 fixed_100mhz_clk

set pci_express_x16 [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:pcie_7x_mgt_rtl:1.0 pci_express_x16 ]

set pcie_refclk [ create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 pcie_refclk ]
set_property -dict [ list \
   CONFIG.FREQ_HZ {100000000} \
] $pcie_refclk

set PCIE_M_AXI [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:aximm_rtl:1.0 PCIE_M_AXI ]
set_property -dict [ list \
   CONFIG.DATA_WIDTH {512} \
   CONFIG.FREQ_HZ $firesim_freq_hz \
   CONFIG.PROTOCOL {AXI4} \
] $PCIE_M_AXI

set PCIE_M_AXI_LITE [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:aximm_rtl:1.0 PCIE_M_AXI_LITE ]
set_property -dict [ list \
   CONFIG.ADDR_WIDTH {32} \
   CONFIG.DATA_WIDTH {32} \
   CONFIG.FREQ_HZ $firesim_freq_hz \
   CONFIG.PROTOCOL {AXI4LITE} \
] $PCIE_M_AXI_LITE

set DDR4_0_S_AXI [ create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:aximm_rtl:1.0 DDR4_0_S_AXI ]
# Properties mirrored from the vendor reference block design except ID_WIDTH:
# the reference sets ID_WIDTH=0, but FireSim's overall_fpga_top.v wires a 16-bit
# AXI ID into design_1 — leaving ID_WIDTH unset lets BD auto-propagate it
# from the connected axi_dwidth_converter_0/S_AXI (SI_ID_WIDTH=16).
set_property -dict [ list \
   CONFIG.ADDR_WIDTH {33} \
   CONFIG.ARUSER_WIDTH {0} \
   CONFIG.AWUSER_WIDTH {0} \
   CONFIG.BUSER_WIDTH {0} \
   CONFIG.DATA_WIDTH {64} \
   CONFIG.FREQ_HZ $firesim_freq_hz \
   CONFIG.HAS_BRESP {1} \
   CONFIG.HAS_BURST {1} \
   CONFIG.HAS_CACHE {1} \
   CONFIG.HAS_LOCK {1} \
   CONFIG.HAS_PROT {1} \
   CONFIG.HAS_QOS {1} \
   CONFIG.HAS_REGION {1} \
   CONFIG.HAS_RRESP {1} \
   CONFIG.HAS_WSTRB {1} \
   CONFIG.MAX_BURST_LENGTH {256} \
   CONFIG.NUM_READ_OUTSTANDING {1} \
   CONFIG.NUM_READ_THREADS {1} \
   CONFIG.NUM_WRITE_OUTSTANDING {1} \
   CONFIG.NUM_WRITE_THREADS {1} \
   CONFIG.PROTOCOL {AXI4} \
   CONFIG.READ_WRITE_MODE {READ_WRITE} \
   CONFIG.RUSER_BITS_PER_BYTE {0} \
   CONFIG.RUSER_WIDTH {0} \
   CONFIG.SUPPORTS_NARROW_BURST {1} \
   CONFIG.WUSER_BITS_PER_BYTE {0} \
   CONFIG.WUSER_WIDTH {0} \
] $DDR4_0_S_AXI

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
