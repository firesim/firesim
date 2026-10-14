# Create block design IP instances.
# Configs mirror the vendor reference Vivado project for the Corigine
# MimicTurbo GT board.

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

# TODO: user guide says MT40A1G16KD-062E but the vendor reference design uses
# MT40A1G16RC-062E (different die revision, same timings) — confirm
# on-board markings next time the board is physically accessible.
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
   CONFIG.pf0_msix_cap_pba_bir {BAR_1} \
   CONFIG.pf0_msix_cap_table_bir {BAR_1} \
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

set xlconstant_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:xlconstant xlconstant_0 ]
set_property CONFIG.CONST_VAL {0} $xlconstant_0
proc create_generic_aurora_64b66b { name } {
   set i [ create_bd_cell -type ip -vlnv xilinx.com:ip:aurora_64b66b $name ]
   set_property -dict [ list \
      CONFIG.C_AURORA_LANES {4} \
      CONFIG.C_GT_LOC_2 {2} \
      CONFIG.C_GT_LOC_3 {3} \
      CONFIG.C_GT_LOC_4 {4} \
      CONFIG.C_LINE_RATE {15} \
      CONFIG.SupportLevel {1} \
      CONFIG.drp_mode {Disabled} \
      CONFIG.interface_mode {Streaming} \
   ] $i
   return $i
}
# X1YN where N=4,5,6,7,10,11 (channel enable is N*4 to N*4+3)
set aurora_64b66b_0 [ create_generic_aurora_64b66b aurora_64b66b_0 ]
set_property -dict [ list \
   CONFIG.CHANNEL_ENABLE {X0Y24 X0Y25 X0Y26 X0Y27} \
   CONFIG.C_REFCLK_SOURCE {MGTREFCLK0_of_Quad_X0Y6} \
   CONFIG.C_START_LANE {X0Y24} \
   CONFIG.C_START_QUAD {Quad_X0Y6} \
] $aurora_64b66b_0
set aurora_64b66b_1 [ create_generic_aurora_64b66b aurora_64b66b_1 ]
set_property -dict [ list \
   CONFIG.CHANNEL_ENABLE {X0Y32 X0Y33 X0Y34 X0Y35} \
   CONFIG.C_REFCLK_SOURCE {MGTREFCLK0_of_Quad_X0Y8} \
   CONFIG.C_START_LANE {X0Y32} \
   CONFIG.C_START_QUAD {Quad_X0Y8} \
] $aurora_64b66b_1

proc create_aurora_driver { name } {
   set block_name aurora_64b66b_0_driver
   set block_cell_name $name
   if { [catch {set i [create_bd_cell -type module -reference $block_name $block_cell_name] } errmsg] } {
      catch {common::send_gid_msg -ssname BD::TCL -id 2095 -severity "ERROR" "Unable to add referenced block <$block_name>. Please add the files for ${block_name}'s definition into the project."}
      exit 1
   } elseif { $i eq "" } {
      catch {common::send_gid_msg -ssname BD::TCL -id 2096 -severity "ERROR" "Unable to referenced block <$block_name>. Please add the files for ${block_name}'s definition into the project."}
      exit 1
   }
   set_property -dict [ list \
      CONFIG.POLARITY {ACTIVE_HIGH} \
   ] [get_bd_pins /$name/reset_pb]
   return $i
}
set aurora_64b66b_0_driver [ create_aurora_driver aurora_64b66b_0_driver ]
set aurora_64b66b_1_driver [ create_aurora_driver aurora_64b66b_1_driver ]

proc create_aurora_gt_wrapper { name } {
   set block_name aurora_gt_wrapper
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
set aurora_gt_wrapper_0 [ create_aurora_gt_wrapper aurora_gt_wrapper_0 ]
set aurora_gt_wrapper_1 [ create_aurora_gt_wrapper aurora_gt_wrapper_1 ]

proc create_axis_clock_converter { name } {
   set i [ create_bd_cell -type ip -vlnv xilinx.com:ip:axis_clock_converter $name ]
   set_property -dict [ list \
      CONFIG.SYNCHRONIZATION_STAGES {3} \
      CONFIG.TDATA_NUM_BYTES {32} \
   ] $i
   return $i
}
set axis_clock_converter_0 [ create_axis_clock_converter axis_clock_converter_0 ]
set axis_clock_converter_1 [ create_axis_clock_converter axis_clock_converter_1 ]
set axis_clock_converter_2 [ create_axis_clock_converter axis_clock_converter_2 ]
set axis_clock_converter_3 [ create_axis_clock_converter axis_clock_converter_3 ]

proc create_axis_data_fifo { name } {
   set i [ create_bd_cell -type ip -vlnv xilinx.com:ip:axis_data_fifo $name ]
   set_property -dict [ list \
      CONFIG.FIFO_DEPTH {2048} \
      CONFIG.TDATA_NUM_BYTES {32} \
   ] $i
   return $i
}
set axis_data_fifo_0 [ create_axis_data_fifo axis_data_fifo_0 ]
set axis_data_fifo_1 [ create_axis_data_fifo axis_data_fifo_1 ]
set axis_data_fifo_2 [ create_axis_data_fifo axis_data_fifo_2 ]
set axis_data_fifo_3 [ create_axis_data_fifo axis_data_fifo_3 ]

set clk_wiz_aurora_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:clk_wiz clk_wiz_aurora_0 ]

set_property -dict [list \
  CONFIG.CLKIN1_JITTER_PS {40.0} \
  CONFIG.CLKOUT1_JITTER {134.506} \
  CONFIG.CLKOUT1_PHASE_ERROR {154.678} \
  CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {100.000} \
  CONFIG.MMCM_CLKFBOUT_MULT_F {24.000} \
  CONFIG.MMCM_CLKIN1_PERIOD {4.000} \
  CONFIG.MMCM_CLKIN2_PERIOD {10.0} \
  CONFIG.MMCM_DIVCLK_DIVIDE {5} \
  CONFIG.PRIM_IN_FREQ {100} \
] [get_bd_cells clk_wiz_aurora_0]

proc create_util_vector_logic { name } {
   set i [ create_bd_cell -type ip -vlnv xilinx.com:ip:util_vector_logic $name ]
   set_property -dict [ list \
      CONFIG.C_OPERATION {not} \
      CONFIG.C_SIZE {1} \
      CONFIG.LOGO_FILE {data/sym_notgate.png} \
   ] $i
   return $i
}
set util_vector_logic_0 [ create_util_vector_logic util_vector_logic_0 ]
set util_vector_logic_1 [ create_util_vector_logic util_vector_logic_1 ]
