# Create block design IP instances for the Xilinx Alveo V80 (Versal).

# Create instance: proc_sys_reset_0, and set properties
set proc_sys_reset_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 proc_sys_reset_0 ]

# Create instance: versal_cips_0, and set properties
set versal_cips_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:versal_cips:3.4 versal_cips_0 ]
set_property -dict [list \
    CONFIG.BOOT_MODE {Custom} \
    CONFIG.CLOCK_MODE {Custom} \
    CONFIG.CPM_CONFIG { \
      CPM_PCIE0_MODES {DMA} \
      CPM_PCIE0_PF0_BAR0_QDMA_64BIT {0} \
      CPM_PCIE0_PF0_BAR0_QDMA_TYPE {DMA} \
      CPM_PCIE0_PF0_BAR1_QDMA_ENABLED {1} \
      CPM_PCIE0_PF0_BAR1_QDMA_SCALE {Megabytes} \
      CPM_PCIE0_PF0_BAR1_QDMA_SIZE {32} \
      CPM_PCIE0_PF0_BAR2_QDMA_ENABLED {0} \
      CPM_PCIE0_PF0_CFG_DEV_ID {903F} \
      CPM_PCIE0_PF0_PCIEBAR2AXIBAR_QDMA_0 {0x0_202_0000_0000} \
      CPM_PCIE0_PF0_PCIEBAR2AXIBAR_QDMA_1 {0x0_201_0000_0000} \
      CPM_PCIE0_PL_LINK_CAP_MAX_LINK_WIDTH {X16} \
    } \
    CONFIG.PS_PMC_CONFIG { \
      BOOT_MODE {Custom} \
      CLOCK_MODE {Custom} \
      DESIGN_MODE {1} \
      PCIE_APERTURES_DUAL_ENABLE {0} \
      PCIE_APERTURES_SINGLE_ENABLE {1} \
      PMC_CRP_PL0_REF_CTRL_FREQMHZ {100} \
      PMC_MIO_EN_FOR_PL_PCIE {0} \
      PMC_OSPI_PERIPHERAL {{ENABLE 1} {IO {PMC_MIO 0 .. 11}} {MODE Single}} \
      PMC_QSPI_FBCLK {{ENABLE 1} {IO {PMC_MIO 6}}} \
      PMC_QSPI_PERIPHERAL_ENABLE {0} \
      PMC_SD0 {{CD_ENABLE 0} {CD_IO {PMC_MIO 24}} {POW_ENABLE 0} {POW_IO {PMC_MIO 17}} {RESET_ENABLE 0} {RESET_IO {PMC_MIO 17}} {WP_ENABLE 0} {WP_IO {PMC_MIO 25}}} \
      PMC_SD0_PERIPHERAL {{CLK_100_SDR_OTAP_DLY 0x00} {CLK_200_SDR_OTAP_DLY 0x00} {CLK_50_DDR_ITAP_DLY 0x00} {CLK_50_DDR_OTAP_DLY 0x00} {CLK_50_SDR_ITAP_DLY 0x00} {CLK_50_SDR_OTAP_DLY 0x00} {ENABLE 0} {IO {PMC_MIO 13 .. 25}}} \
      PMC_SD0_SLOT_TYPE {SD 2.0} \
      PMC_SMAP_PERIPHERAL {{ENABLE 0} {IO {32 Bit}}} \
      PS_BOARD_INTERFACE {Custom} \
      PS_NUM_FABRIC_RESETS {1} \
      PS_PCIE1_PERIPHERAL_ENABLE {1} \
      PS_PCIE2_PERIPHERAL_ENABLE {0} \
      PS_PCIE_EP_RESET1_IO {PMC_MIO 24} \
      PS_PCIE_EP_RESET2_IO {None} \
      PS_PCIE_RESET {ENABLE 1} \
      PS_USE_PMCPL_CLK0 {1} \
      SMON_ALARMS {Set_Alarms_On} \
      SMON_ENABLE_TEMP_AVERAGING {0} \
      SMON_TEMP_AVERAGING_SAMPLES {0} \
    } \
] $versal_cips_0


# Create instance: clk_wizard_0, and set properties
set clk_wizard_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:clk_wizard:1.0 clk_wizard_0 ]
set_property -dict [list \
    CONFIG.CLKOUT_DRIVES {BUFG,BUFG,BUFG,BUFG,BUFG,BUFG,BUFG} \
    CONFIG.CLKOUT_DYN_PS {None,None,None,None,None,None,None} \
    CONFIG.CLKOUT_GROUPING {Auto,Auto,Auto,Auto,Auto,Auto,Auto} \
    CONFIG.CLKOUT_MATCHED_ROUTING {false,false,false,false,false,false,false} \
    CONFIG.CLKOUT_PORT {clk_out1,clk_out2,clk_out3,clk_out4,clk_out5,clk_out6,clk_out7} \
    CONFIG.CLKOUT_REQUESTED_DUTY_CYCLE {50.000,50.000,50.000,50.000,50.000,50.000,50.000} \
    CONFIG.CLKOUT_REQUESTED_OUT_FREQUENCY "${firesim_freq_mhz}.000,100.000,100.000,100.000,100.000,100.000,100.000" \
    CONFIG.CLKOUT_REQUESTED_PHASE {0.000,0.000,0.000,0.000,0.000,0.000,0.000} \
    CONFIG.CLKOUT_USED {true,false,false,false,false,false,false} \
    CONFIG.USE_RESET {false} \
] $clk_wizard_0


# Create instance: axi_noc_0, and set properties
set axi_noc_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_noc:1.1 axi_noc_0 ]
set_property -dict [list \
    CONFIG.NUM_CLKS {3} \
    CONFIG.NUM_MI {2} \
    CONFIG.SI_SIDEBAND_PINS {} \
] $axi_noc_0


set_property -dict [ list \
   CONFIG.APERTURES {{0x203_0000_0000 4G}} \
   CONFIG.CATEGORY {pl} \
] [get_bd_intf_pins /axi_noc_0/M00_AXI]

set_property -dict [ list \
   CONFIG.APERTURES {{0x201_0000_0000 1G}} \
   CONFIG.CATEGORY {pl} \
] [get_bd_intf_pins /axi_noc_0/M01_AXI]

set_property -dict [ list \
   CONFIG.CONNECTIONS {M01_AXI {read_bw {500} write_bw {500} read_avg_burst {4} write_avg_burst {4}} M00_AXI {read_bw {1720} write_bw {1720} read_avg_burst {4} write_avg_burst {4}}} \
   CONFIG.DEST_IDS {M01_AXI:0x40:M00_AXI:0xc0} \
   CONFIG.NOC_PARAMS {} \
   CONFIG.CATEGORY {ps_pcie} \
] [get_bd_intf_pins /axi_noc_0/S00_AXI]

set_property -dict [ list \
   CONFIG.ASSOCIATED_BUSIF {S00_AXI} \
] [get_bd_pins /axi_noc_0/aclk0]

set_property -dict [ list \
   CONFIG.ASSOCIATED_BUSIF {M00_AXI:M01_AXI} \
] [get_bd_pins /axi_noc_0/aclk1]

set_property -dict [ list \
   CONFIG.ASSOCIATED_BUSIF {} \
] [get_bd_pins /axi_noc_0/aclk2]

# Create instance: axi_noc_1, and set properties
set axi_noc_1 [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_noc:1.1 axi_noc_1 ]
set_property -dict [list \
    CONFIG.CONTROLLERTYPE {DDR4_SDRAM} \
    CONFIG.MC0_CONFIG_NUM {config21} \
    CONFIG.MC0_FLIPPED_PINOUT {false} \
    CONFIG.MC_CHAN_REGION0 {DDR_CH2} \
    CONFIG.MC_CHAN_REGION1 {NONE} \
    CONFIG.MC_COMPONENT_WIDTH {x4} \
    CONFIG.MC_DATAWIDTH {72} \
    CONFIG.MC_INPUTCLK0_PERIOD {5000} \
    CONFIG.MC_MEMORY_DEVICETYPE {RDIMMs} \
    CONFIG.MC_MEMORY_SPEEDGRADE {DDR4-3200AA(22-22-22)} \
    CONFIG.MC_NO_CHANNELS {Single} \
    CONFIG.MC_PARITY {true} \
    CONFIG.MC_RANK {1} \
    CONFIG.MC_ROWADDRESSWIDTH {18} \
    CONFIG.MC_STACKHEIGHT {1} \
    CONFIG.MC_SYSTEM_CLOCK {Differential} \
    CONFIG.NUM_MC {1} \
    CONFIG.NUM_MCP {4} \
    CONFIG.NUM_MI {0} \
] $axi_noc_1


set_property -dict [ list \
   CONFIG.CONNECTIONS {MC_0 {read_bw {500} write_bw {500} read_avg_burst {4} write_avg_burst {4}}} \
   CONFIG.NOC_PARAMS {} \
   CONFIG.CATEGORY {pl} \
] [get_bd_intf_pins /axi_noc_1/S00_AXI]

set_property -dict [ list \
   CONFIG.ASSOCIATED_BUSIF {S00_AXI} \
] [get_bd_pins /axi_noc_1/aclk0]

# Create instance: smartconnect_0, and set properties
set smartconnect_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:smartconnect:1.0 smartconnect_0 ]
set_property -dict [list \
    CONFIG.NUM_CLKS {1} \
    CONFIG.NUM_SI {1} \
] $smartconnect_0
