# Create interface ports

set ddr4_sdram_c0 [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:ddr4_rtl:1.0 ddr4_sdram_c0 ]

set default_250mhz_clk0 [ create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 default_250mhz_clk0 ]
set_property -dict [ list \
   CONFIG.FREQ_HZ {250000000} \
] $default_250mhz_clk0

set pci_express_x16 [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:pcie_7x_mgt_rtl:1.0 pci_express_x16 ]

set pcie_refclk [ create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 pcie_refclk ]
set_property -dict [ list \
   CONFIG.FREQ_HZ {100000000} \
] $pcie_refclk

set PCIE_M_AXI [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:aximm_rtl:1.0 PCIE_M_AXI ]
set_property -dict [ list \
   CONFIG.ADDR_WIDTH {32} \
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
# Properties mirrored from reference xb10.tcl (lines 266-296) except ID_WIDTH:
# reference sets ID_WIDTH=0, but FireSim's overall_fpga_top.v wires a 16-bit
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
