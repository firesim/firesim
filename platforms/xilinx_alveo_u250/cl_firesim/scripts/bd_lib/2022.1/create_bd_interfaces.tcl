# Create interface ports

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

# Create ports

set pcie_perstn [ create_bd_port -dir I -type rst pcie_perstn ]
set_property -dict [ list \
   CONFIG.POLARITY {ACTIVE_LOW} \
] $pcie_perstn

set axi_aclk_0 [ create_bd_port -dir O -type clk axi_aclk_0 ]
set_property -dict [ list \
  CONFIG.ASSOCIATED_BUSIF {PCIE_M_AXI:PCIE_M_AXI_LITE} \
  CONFIG.ASSOCIATED_BUSIF.VALUE_SRC DEFAULT \
] $axi_aclk_0
set axi_aresetn_0 [ create_bd_port -dir O -type rst axi_aresetn_0 ]
