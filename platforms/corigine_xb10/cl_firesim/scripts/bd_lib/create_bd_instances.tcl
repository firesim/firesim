# Create block design IP instances.
# Configs mirror /scratch/raghavgupta/project_xb10/xb10.tcl (the reference
# Vivado project for the Corigine XB-10 board). Aurora/QSFP IPs from the
# u250 template are intentionally omitted — the XB-10 port does not use them.

set axi_clock_converter_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_clock_converter_0 ]
set axi_clock_converter_1 [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_clock_converter_1 ]

set axi_dwidth_converter_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 axi_dwidth_converter_0 ]
set_property -dict [list \
   CONFIG.ACLK_ASYNC {1} \
   CONFIG.FIFO_MODE {2} \
   CONFIG.MI_DATA_WIDTH {512} \
   CONFIG.SI_DATA_WIDTH {64} \
   CONFIG.SI_ID_WIDTH {16} \
] $axi_dwidth_converter_0

set clk_wiz_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:clk_wiz:6.0 clk_wiz_0 ]
set_property -dict [list \
   CONFIG.CLKIN1_JITTER_PS {33.330000000000005} \
   CONFIG.CLKOUT1_REQUESTED_OUT_FREQ $firesim_freq_mhz \
   CONFIG.MMCM_CLKIN1_PERIOD {3.333} \
   CONFIG.USE_LOCKED {false} \
] $clk_wiz_0

set ddr4_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:ddr4:2.2 ddr4_0 ]
set_property -dict [list \
   CONFIG.ADDN_UI_CLKOUT1_FREQ_HZ {None} \
   CONFIG.C0.DDR4_AUTO_AP_COL_A3 {true} \
   CONFIG.C0.DDR4_AxiAddressWidth {33} \
   CONFIG.C0.DDR4_AxiDataWidth {512} \
   CONFIG.C0.DDR4_CasLatency {17} \
   CONFIG.C0.DDR4_CasWriteLatency {12} \
   CONFIG.C0.DDR4_DataMask {DM_NO_DBI} \
   CONFIG.C0.DDR4_DataWidth {64} \
   CONFIG.C0.DDR4_EN_PARITY {true} \
   CONFIG.C0.DDR4_InputClockPeriod {4000} \
   CONFIG.C0.DDR4_MCS_ECC {false} \
   CONFIG.C0.DDR4_Mem_Add_Map {ROW_COLUMN_BANK_INTLV} \
   CONFIG.C0.DDR4_MemoryPart {MT40A1G16RC-062E} \
   CONFIG.C0.DDR4_MemoryType {Components} \
   CONFIG.C0.DDR4_Specify_MandD {false} \
   CONFIG.C0.DDR4_TimePeriod {833} \
   CONFIG.C0_CLOCK_BOARD_INTERFACE {Custom} \
   CONFIG.C0_DDR4_BOARD_INTERFACE {Custom} \
   CONFIG.Debug_Signal {Disable} \
   CONFIG.No_Controller {1} \
   CONFIG.RESET_BOARD_INTERFACE {Custom} \
] $ddr4_0

set proc_sys_reset_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 proc_sys_reset_0 ]
set proc_sys_reset_ddr_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 proc_sys_reset_ddr_0 ]

set resetn_inv_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:util_vector_logic:2.0 resetn_inv_0 ]
set_property -dict [list \
   CONFIG.C_OPERATION {not} \
   CONFIG.C_SIZE {1} \
] $resetn_inv_0

set util_ds_buf [ create_bd_cell -type ip -vlnv xilinx.com:ip:util_ds_buf:2.2 util_ds_buf ]
set_property -dict [list \
   CONFIG.C_BUF_TYPE {IBUFDSGTE} \
   CONFIG.DIFF_CLK_IN_BOARD_INTERFACE {Custom} \
   CONFIG.USE_BOARD_FLOW {true} \
] $util_ds_buf

set xdma_vlnv [lindex [lsort -dictionary [get_ipdefs xilinx.com:ip:xdma:*]] end]
set xdma_0 [ create_bd_cell -type ip -vlnv $xdma_vlnv xdma_0 ]
set_property -dict [list \
   CONFIG.axilite_master_en {true} \
   CONFIG.axilite_master_size {32} \
   CONFIG.axist_bypass_en {false} \
   CONFIG.pciebar2axibar_axil_master {0xC0000000} \
   CONFIG.xdma_axi_intf_mm {AXI_Memory_Mapped} \
   CONFIG.en_gt_selection {false} \
   CONFIG.mode_selection {Advanced} \
   CONFIG.pf0_device_id {903F} \
   CONFIG.pl_link_cap_max_link_speed {8.0_GT/s} \
   CONFIG.pl_link_cap_max_link_width {X16} \
   CONFIG.xdma_rnum_chnl {4} \
   CONFIG.xdma_wnum_chnl {4} \
] $xdma_0

# XDMA-side AXI interface properties (250 MHz XDMA clock domain; 4 read/write threads on M_AXI).
set_property -dict [ list \
   CONFIG.NUM_READ_OUTSTANDING {32} \
   CONFIG.NUM_WRITE_OUTSTANDING {16} \
   CONFIG.FREQ_HZ {250000000} \
   CONFIG.NUM_READ_THREADS {4} \
   CONFIG.NUM_WRITE_THREADS {4} \
] [get_bd_intf_pins /xdma_0/M_AXI]

set_property -dict [ list \
   CONFIG.FREQ_HZ {250000000} \
] [get_bd_intf_pins /xdma_0/M_AXI_LITE]

set_property -dict [ list \
   CONFIG.FREQ_HZ {250000000} \
] [get_bd_pins /xdma_0/axi_aclk]

set_property -dict [ list \
   CONFIG.FREQ_HZ {100000000} \
] [get_bd_pins /xdma_0/sys_clk]

set_property -dict [ list \
   CONFIG.POLARITY {ACTIVE_LOW} \
] [get_bd_pins /xdma_0/sys_rst_n]

set xlconstant_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:xlconstant:1.1 xlconstant_0 ]
set_property CONFIG.CONST_VAL {0} $xlconstant_0
