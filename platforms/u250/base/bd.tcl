
################################################################
# This is a generated script based on design: ex_synth
#
# Though there are limitations about the generated script,
# the main purpose of this utility is to make learning
# IP Integrator Tcl commands easier.
################################################################

namespace eval _tcl {
proc get_script_folder {} {
   set script_path [file normalize [info script]]
   set script_folder [file dirname $script_path]
   return $script_folder
}
}
variable script_folder
set script_folder [_tcl::get_script_folder]

################################################################
# Check if script is running in correct Vivado version.
################################################################
set scripts_vivado_version 2020.2
set current_vivado_version [version -short]

if { [string first $scripts_vivado_version $current_vivado_version] == -1 } {
   puts ""
   catch {common::send_gid_msg -ssname BD::TCL -id 2041 -severity "ERROR" "This script was generated using Vivado <$scripts_vivado_version> and is being run in <$current_vivado_version> of Vivado. Please run the script in Vivado <$scripts_vivado_version> then open the design in Vivado <$current_vivado_version>. Upgrade the design by running \"Tools => Report => Report IP Status...\", then run write_bd_tcl to create an updated script."}

   return 1
}

################################################################
# START
################################################################

# To test this script, run the following commands from Vivado Tcl console:
# source ex_synth_script.tcl

# If there is no project opened, this script will create a
# project, but make sure you do not have an existing project
# <./myproj/project_1.xpr> in the current working folder.

set list_projs [get_projects -quiet]
if { $list_projs eq "" } {
   create_project project_1 myproj -part xcu250-figd2104-2L-e
   set_property BOARD_PART xilinx.com:au250:part0:1.3 [current_project]
}


# CHANGE DESIGN NAME HERE
variable design_name
set design_name ex_synth

# If you do not already have an existing IP Integrator design open,
# you can create a design using the following command:
#    create_bd_design $design_name

# Creating design if needed
set errMsg ""
set nRet 0

set cur_design [current_bd_design -quiet]
set list_cells [get_bd_cells -quiet]

if { ${design_name} eq "" } {
   # USE CASES:
   #    1) Design_name not set

   set errMsg "Please set the variable <design_name> to a non-empty value."
   set nRet 1

} elseif { ${cur_design} ne "" && ${list_cells} eq "" } {
   # USE CASES:
   #    2): Current design opened AND is empty AND names same.
   #    3): Current design opened AND is empty AND names diff; design_name NOT in project.
   #    4): Current design opened AND is empty AND names diff; design_name exists in project.

   if { $cur_design ne $design_name } {
      common::send_gid_msg -ssname BD::TCL -id 2001 -severity "INFO" "Changing value of <design_name> from <$design_name> to <$cur_design> since current design is empty."
      set design_name [get_property NAME $cur_design]
   }
   common::send_gid_msg -ssname BD::TCL -id 2002 -severity "INFO" "Constructing design in IPI design <$cur_design>..."

} elseif { ${cur_design} ne "" && $list_cells ne "" && $cur_design eq $design_name } {
   # USE CASES:
   #    5) Current design opened AND has components AND same names.

   set errMsg "Design <$design_name> already exists in your project, please set the variable <design_name> to another value."
   set nRet 1
} elseif { [get_files -quiet ${design_name}.bd] ne "" } {
   # USE CASES: 
   #    6) Current opened design, has components, but diff names, design_name exists in project.
   #    7) No opened design, design_name exists in project.

   set errMsg "Design <$design_name> already exists in your project, please set the variable <design_name> to another value."
   set nRet 2

} else {
   # USE CASES:
   #    8) No opened design, design_name not in project.
   #    9) Current opened design, has components, but diff names, design_name not in project.

   common::send_gid_msg -ssname BD::TCL -id 2003 -severity "INFO" "Currently there is no design <$design_name> in project, so creating one..."

   create_bd_design $design_name

   common::send_gid_msg -ssname BD::TCL -id 2004 -severity "INFO" "Making design <$design_name> as current_bd_design."
   current_bd_design $design_name

}

common::send_gid_msg -ssname BD::TCL -id 2005 -severity "INFO" "Currently the variable <design_name> is equal to \"$design_name\"."

if { $nRet != 0 } {
   catch {common::send_gid_msg -ssname BD::TCL -id 2006 -severity "ERROR" $errMsg}
   return $nRet
}

set bCheckIPsPassed 1
##################################################################
# CHECK IPs
##################################################################
set bCheckIPs 1
if { $bCheckIPs == 1 } {
   set list_check_ips "\ 
xilinx.com:ip:clk_vip:1.0\
xilinx.com:ip:axi_clock_converter:2.1\
xilinx.com:ip:rst_vip:1.0\
xilinx.com:ip:system_ila:1.1\
xilinx.com:ip:xdma:4.1\
xilinx.com:ip:util_ds_buf:2.1\
xilinx.com:ip:clk_wiz:6.0\
xilinx.com:ip:util_vector_logic:2.0\
xilinx.com:ip:proc_sys_reset:5.0\
xilinx.com:ip:axi_intc:4.1\
xilinx.com:ip:cms_subsystem:3.0\
xilinx.com:ip:mdm:3.2\
xilinx.com:ip:axi_dwidth_converter:2.1\
xilinx.com:ip:ddr4:2.2\
xilinx.com:ip:xlconstant:1.1\
"

   set list_ips_missing ""
   common::send_gid_msg -ssname BD::TCL -id 2011 -severity "INFO" "Checking if the following IPs exist in the project's IP catalog: $list_check_ips ."

   foreach ip_vlnv $list_check_ips {
      set ip_obj [get_ipdefs -all $ip_vlnv]
      if { $ip_obj eq "" } {
         lappend list_ips_missing $ip_vlnv
      }
   }

   if { $list_ips_missing ne "" } {
      catch {common::send_gid_msg -ssname BD::TCL -id 2012 -severity "ERROR" "The following IPs are not found in the IP Catalog:\n  $list_ips_missing\n\nResolution: Please add the repository containing the IP(s) to the project." }
      set bCheckIPsPassed 0
   }

}

if { $bCheckIPsPassed != 1 } {
  common::send_gid_msg -ssname BD::TCL -id 2023 -severity "WARNING" "Will not continue with creation of design due to the error(s) above."
  return 3
}

##################################################################
# DESIGN PROCs
##################################################################


# Hierarchical cell: ddr_C0
proc create_hier_cell_ddr_C0 { parentCell nameHier } {

  variable script_folder

  if { $parentCell eq "" || $nameHier eq "" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2092 -severity "ERROR" "create_hier_cell_ddr_C0() - Empty argument(s)!"}
     return
  }

  # Get object for parentCell
  set parentObj [get_bd_cells $parentCell]
  if { $parentObj == "" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2090 -severity "ERROR" "Unable to find parent cell <$parentCell>!"}
     return
  }

  # Make sure parentObj is hier blk
  set parentType [get_property TYPE $parentObj]
  if { $parentType ne "hier" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2091 -severity "ERROR" "Parent <$parentObj> has TYPE = <$parentType>. Expected to be <hier>."}
     return
  }

  # Save current instance; Restore later
  set oldCurInst [current_bd_instance .]

  # Set parent object as current
  current_bd_instance $parentObj

  # Create cell and set as current instance
  set hier_obj [create_bd_cell -type hier $nameHier]
  current_bd_instance $hier_obj

  # Create interface pins
  create_bd_intf_pin -mode Slave -vlnv xilinx.com:interface:aximm_rtl:1.0 S_AXI

  create_bd_intf_pin -mode Master -vlnv xilinx.com:interface:ddr4_rtl:1.0 ddr4_sdram_c0

  create_bd_intf_pin -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 default_300mhz_clk0


  # Create pins
  create_bd_pin -dir I -type rst ddr4_axi_aresetn_sync
  create_bd_pin -dir O ddr_ui_clock
  create_bd_pin -dir O ddr_ui_reset
  create_bd_pin -dir I -type clk s_axi_aclk
  create_bd_pin -dir I -type rst s_axi_aresetn
  create_bd_pin -dir I -type rst sys_rst

  # Create instance: axi_dwidth_converter_0, and set properties
  set axi_dwidth_converter_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 axi_dwidth_converter_0 ]
  set_property -dict [ list \
   CONFIG.ACLK_ASYNC {1} \
   CONFIG.FIFO_MODE {2} \
 ] $axi_dwidth_converter_0

  # Create instance: ddr4_0, and set properties
  set ddr4_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:ddr4:2.2 ddr4_0 ]
  set_property -dict [ list \
   CONFIG.ADDN_UI_CLKOUT1_FREQ_HZ {None} \
   CONFIG.C0_CLOCK_BOARD_INTERFACE {default_300mhz_clk0} \
   CONFIG.C0_DDR4_BOARD_INTERFACE {ddr4_sdram_c0} \
 ] $ddr4_0

  # Create instance: xlconstant_0, and set properties
  set xlconstant_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:xlconstant:1.1 xlconstant_0 ]
  set_property -dict [ list \
   CONFIG.CONST_VAL {0} \
 ] $xlconstant_0

  # Create interface connections
  connect_bd_intf_net -intf_net Conn1 [get_bd_intf_pins ddr4_sdram_c0] [get_bd_intf_pins ddr4_0/C0_DDR4]
  connect_bd_intf_net -intf_net Conn3 [get_bd_intf_pins default_300mhz_clk0] [get_bd_intf_pins ddr4_0/C0_SYS_CLK]
  connect_bd_intf_net -intf_net S_AXI_1 [get_bd_intf_pins S_AXI] [get_bd_intf_pins axi_dwidth_converter_0/S_AXI]
  connect_bd_intf_net -intf_net axi_dwidth_converter_0_M_AXI [get_bd_intf_pins axi_dwidth_converter_0/M_AXI] [get_bd_intf_pins ddr4_0/C0_DDR4_S_AXI]

  # Create port connections
  connect_bd_net -net c0_ddr4_s_axi_ctrl_arvalid_1 [get_bd_pins ddr4_0/c0_ddr4_s_axi_ctrl_arvalid] [get_bd_pins ddr4_0/c0_ddr4_s_axi_ctrl_awvalid] [get_bd_pins ddr4_0/c0_ddr4_s_axi_ctrl_bready] [get_bd_pins ddr4_0/c0_ddr4_s_axi_ctrl_rready] [get_bd_pins ddr4_0/c0_ddr4_s_axi_ctrl_wvalid] [get_bd_pins xlconstant_0/dout]
  connect_bd_net -net ddr4_0_c0_ddr4_ui_clk [get_bd_pins ddr_ui_clock] [get_bd_pins axi_dwidth_converter_0/m_axi_aclk] [get_bd_pins ddr4_0/c0_ddr4_ui_clk]
  connect_bd_net -net ddr4_0_c0_ddr4_ui_clk_sync_rst [get_bd_pins ddr_ui_reset] [get_bd_pins ddr4_0/c0_ddr4_ui_clk_sync_rst]
  connect_bd_net -net ddr4_axi_aresetn_sync_1 [get_bd_pins ddr4_axi_aresetn_sync] [get_bd_pins axi_dwidth_converter_0/m_axi_aresetn] [get_bd_pins ddr4_0/c0_ddr4_aresetn]
  connect_bd_net -net s_axi_aclk_1 [get_bd_pins s_axi_aclk] [get_bd_pins axi_dwidth_converter_0/s_axi_aclk]
  connect_bd_net -net s_axi_aresetn_1 [get_bd_pins s_axi_aresetn] [get_bd_pins axi_dwidth_converter_0/s_axi_aresetn]
  connect_bd_net -net sys_rst_1 [get_bd_pins sys_rst] [get_bd_pins ddr4_0/sys_rst]

  # Restore current instance
  current_bd_instance $oldCurInst
}

# Hierarchical cell: cms
proc create_hier_cell_cms { parentCell nameHier } {

  variable script_folder

  if { $parentCell eq "" || $nameHier eq "" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2092 -severity "ERROR" "create_hier_cell_cms() - Empty argument(s)!"}
     return
  }

  # Get object for parentCell
  set parentObj [get_bd_cells $parentCell]
  if { $parentObj == "" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2090 -severity "ERROR" "Unable to find parent cell <$parentCell>!"}
     return
  }

  # Make sure parentObj is hier blk
  set parentType [get_property TYPE $parentObj]
  if { $parentType ne "hier" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2091 -severity "ERROR" "Parent <$parentObj> has TYPE = <$parentType>. Expected to be <hier>."}
     return
  }

  # Save current instance; Restore later
  set oldCurInst [current_bd_instance .]

  # Set parent object as current
  current_bd_instance $parentObj

  # Create cell and set as current instance
  set hier_obj [create_bd_cell -type hier $nameHier]
  current_bd_instance $hier_obj

  # Create interface pins
  create_bd_intf_pin -mode Slave -vlnv xilinx.com:interface:aximm_rtl:1.0 s_axi

  create_bd_intf_pin -mode Slave -vlnv xilinx.com:interface:aximm_rtl:1.0 s_axi_ctrl

  create_bd_intf_pin -mode Master -vlnv xilinx.com:interface:uart_rtl:1.0 satellite_uart


  # Create pins
  create_bd_pin -dir O -type intr irq
  create_bd_pin -dir I -from 0 -to 0 qsfp0_int_l_0
  create_bd_pin -dir O -from 0 -to 0 qsfp0_lpmode_0
  create_bd_pin -dir I -from 0 -to 0 qsfp0_modprs_l_0
  create_bd_pin -dir O -from 0 -to 0 qsfp0_modsel_l_0
  create_bd_pin -dir O -from 0 -to 0 qsfp0_reset_l_0
  create_bd_pin -dir I -from 0 -to 0 qsfp1_int_l_0
  create_bd_pin -dir O -from 0 -to 0 qsfp1_lpmode_0
  create_bd_pin -dir I -from 0 -to 0 qsfp1_modprs_l_0
  create_bd_pin -dir O -from 0 -to 0 qsfp1_modsel_l_0
  create_bd_pin -dir O -from 0 -to 0 qsfp1_reset_l_0
  create_bd_pin -dir I -type clk s_axi_aclk
  create_bd_pin -dir I -type rst s_axi_aresetn
  create_bd_pin -dir I -from 3 -to 0 -type intr satellite_gpio

  # Create instance: axi_intc_0, and set properties
  set axi_intc_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_intc:4.1 axi_intc_0 ]
  set_property -dict [ list \
   CONFIG.C_IRQ_CONNECTION {1} \
 ] $axi_intc_0

  # Create instance: cms_subsystem_0, and set properties
  set cms_subsystem_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:cms_subsystem:3.0 cms_subsystem_0 ]
  set_property -dict [ list \
   CONFIG.HAS_MDM {true} \
 ] $cms_subsystem_0

  # Create instance: mdm_0, and set properties
  set mdm_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:mdm:3.2 mdm_0 ]

  # Create instance: rst_vip_1, and set properties
  set rst_vip_1 [ create_bd_cell -type ip -vlnv xilinx.com:ip:rst_vip:1.0 rst_vip_1 ]

  # Create interface connections
  connect_bd_intf_net -intf_net Conn1 [get_bd_intf_pins s_axi] [get_bd_intf_pins axi_intc_0/s_axi]
  connect_bd_intf_net -intf_net Conn3 [get_bd_intf_pins satellite_uart] [get_bd_intf_pins cms_subsystem_0/satellite_uart]
  connect_bd_intf_net -intf_net Conn4 [get_bd_intf_pins s_axi_ctrl] [get_bd_intf_pins cms_subsystem_0/s_axi_ctrl]
  connect_bd_intf_net -intf_net mdm_0_MBDEBUG_0 [get_bd_intf_pins cms_subsystem_0/mdm_mbdebug] [get_bd_intf_pins mdm_0/MBDEBUG_0]

  # Create port connections
  connect_bd_net -net axi_intc_0_irq [get_bd_pins irq] [get_bd_pins axi_intc_0/irq]
  connect_bd_net -net cms_subsystem_0_interrupt_host [get_bd_pins axi_intc_0/intr] [get_bd_pins cms_subsystem_0/interrupt_host]
  connect_bd_net -net cms_subsystem_0_qsfp0_lpmode [get_bd_pins qsfp0_lpmode_0] [get_bd_pins cms_subsystem_0/qsfp0_lpmode]
  connect_bd_net -net cms_subsystem_0_qsfp0_modsel_l [get_bd_pins qsfp0_modsel_l_0] [get_bd_pins cms_subsystem_0/qsfp0_modsel_l]
  connect_bd_net -net cms_subsystem_0_qsfp0_reset_l [get_bd_pins qsfp0_reset_l_0] [get_bd_pins cms_subsystem_0/qsfp0_reset_l]
  connect_bd_net -net cms_subsystem_0_qsfp1_lpmode [get_bd_pins qsfp1_lpmode_0] [get_bd_pins cms_subsystem_0/qsfp1_lpmode]
  connect_bd_net -net cms_subsystem_0_qsfp1_modsel_l [get_bd_pins qsfp1_modsel_l_0] [get_bd_pins cms_subsystem_0/qsfp1_modsel_l]
  connect_bd_net -net cms_subsystem_0_qsfp1_reset_l [get_bd_pins qsfp1_reset_l_0] [get_bd_pins cms_subsystem_0/qsfp1_reset_l]
  connect_bd_net -net mdm_0_Debug_SYS_Rst [get_bd_pins mdm_0/Debug_SYS_Rst] [get_bd_pins rst_vip_1/rst_in]
  connect_bd_net -net qsfp0_int_l_0_1 [get_bd_pins qsfp0_int_l_0] [get_bd_pins cms_subsystem_0/qsfp0_int_l]
  connect_bd_net -net qsfp0_modprs_l_0_1 [get_bd_pins qsfp0_modprs_l_0] [get_bd_pins cms_subsystem_0/qsfp0_modprs_l]
  connect_bd_net -net qsfp1_int_l_0_1 [get_bd_pins qsfp1_int_l_0] [get_bd_pins cms_subsystem_0/qsfp1_int_l]
  connect_bd_net -net qsfp1_modprs_l_0_1 [get_bd_pins qsfp1_modprs_l_0] [get_bd_pins cms_subsystem_0/qsfp1_modprs_l]
  connect_bd_net -net rst_vip_1_rst_out [get_bd_pins cms_subsystem_0/mdm_debug_sys_rst] [get_bd_pins rst_vip_1/rst_out]
  connect_bd_net -net s_axi_aclk_1 [get_bd_pins s_axi_aclk] [get_bd_pins axi_intc_0/s_axi_aclk] [get_bd_pins cms_subsystem_0/aclk_ctrl]
  connect_bd_net -net s_axi_aresetn_1 [get_bd_pins s_axi_aresetn] [get_bd_pins axi_intc_0/s_axi_aresetn] [get_bd_pins cms_subsystem_0/aresetn_ctrl]
  connect_bd_net -net satellite_gpio_1 [get_bd_pins satellite_gpio] [get_bd_pins cms_subsystem_0/satellite_gpio]

  # Restore current instance
  current_bd_instance $oldCurInst
}

# Hierarchical cell: base_clocking
proc create_hier_cell_base_clocking { parentCell nameHier } {

  variable script_folder

  if { $parentCell eq "" || $nameHier eq "" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2092 -severity "ERROR" "create_hier_cell_base_clocking() - Empty argument(s)!"}
     return
  }

  # Get object for parentCell
  set parentObj [get_bd_cells $parentCell]
  if { $parentObj == "" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2090 -severity "ERROR" "Unable to find parent cell <$parentCell>!"}
     return
  }

  # Make sure parentObj is hier blk
  set parentType [get_property TYPE $parentObj]
  if { $parentType ne "hier" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2091 -severity "ERROR" "Parent <$parentObj> has TYPE = <$parentType>. Expected to be <hier>."}
     return
  }

  # Save current instance; Restore later
  set oldCurInst [current_bd_instance .]

  # Set parent object as current
  current_bd_instance $parentObj

  # Create cell and set as current instance
  set hier_obj [create_bd_cell -type hier $nameHier]
  current_bd_instance $hier_obj

  # Create interface pins
  create_bd_intf_pin -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 pcie_mgt


  # Create pins
  create_bd_pin -dir O -from 0 -to 0 -type clk IBUF_DS_ODIV2
  create_bd_pin -dir O -from 0 -to 0 -type clk IBUF_OUT
  create_bd_pin -dir O -type clk clkwiz_sysclks_clk_out2
  create_bd_pin -dir O -from 0 -to 0 -type rst ddr_resetn_sync
  create_bd_pin -dir I -type clk ddr_ui_axi_aclk
  create_bd_pin -dir I -type rst ddr_ui_reset
  create_bd_pin -dir I -type clk dma_pcie_axi_aclk
  create_bd_pin -dir I -type rst dma_pcie_axi_aresetn
  create_bd_pin -dir O -from 0 -to 0 -type rst dma_pcie_resetn_sync
  create_bd_pin -dir O -from 0 -to 0 -type rst firesim_reset
  create_bd_pin -dir O locked
  create_bd_pin -dir O -from 0 -to 0 -type rst peripheral_aresetn
  create_bd_pin -dir I -type rst perst_n
  create_bd_pin -dir O -from 0 -to 0 -type rst sys_reset

  # Create instance: buf_refclk_ibuf, and set properties
  set buf_refclk_ibuf [ create_bd_cell -type ip -vlnv xilinx.com:ip:util_ds_buf:2.1 buf_refclk_ibuf ]
  set_property -dict [ list \
   CONFIG.C_BUF_TYPE {IBUFDSGTE} \
 ] $buf_refclk_ibuf

  # Create instance: clkwiz_sysclks, and set properties
  set clkwiz_sysclks [ create_bd_cell -type ip -vlnv xilinx.com:ip:clk_wiz:6.0 clkwiz_sysclks ]
  set_property -dict [ list \
   CONFIG.CLKOUT1_DRIVES {Buffer} \
   CONFIG.CLKOUT1_JITTER {120.190} \
   CONFIG.CLKOUT1_PHASE_ERROR {92.989} \
   CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {50.000} \
   CONFIG.CLKOUT2_DRIVES {Buffer} \
   CONFIG.CLKOUT2_JITTER {123.073} \
   CONFIG.CLKOUT2_PHASE_ERROR {85.928} \
   CONFIG.CLKOUT2_REQUESTED_OUT_FREQ {100.000} \
   CONFIG.CLKOUT2_USED {false} \
   CONFIG.CLKOUT3_DRIVES {Buffer} \
   CONFIG.CLKOUT4_DRIVES {Buffer} \
   CONFIG.CLKOUT5_DRIVES {Buffer} \
   CONFIG.CLKOUT6_DRIVES {Buffer} \
   CONFIG.CLKOUT7_DRIVES {Buffer} \
   CONFIG.MMCM_CLKFBOUT_MULT_F {3} \
   CONFIG.MMCM_CLKIN1_PERIOD {4.0} \
   CONFIG.MMCM_CLKOUT0_DIVIDE_F {15} \
   CONFIG.MMCM_CLKOUT1_DIVIDE {1} \
   CONFIG.MMCM_COMPENSATION {AUTO} \
   CONFIG.MMCM_DIVCLK_DIVIDE {1} \
   CONFIG.NUM_OUT_CLKS {1} \
   CONFIG.PRIMITIVE {PLL} \
   CONFIG.PRIM_SOURCE {Single_ended_clock_capable_pin} \
   CONFIG.RESET_PORT {resetn} \
   CONFIG.RESET_TYPE {ACTIVE_LOW} \
   CONFIG.SECONDARY_SOURCE {Single_ended_clock_capable_pin} \
   CONFIG.USE_PHASE_ALIGNMENT {false} \
 ] $clkwiz_sysclks

  # Create instance: ddr_reset_inv, and set properties
  set ddr_reset_inv [ create_bd_cell -type ip -vlnv xilinx.com:ip:util_vector_logic:2.0 ddr_reset_inv ]
  set_property -dict [ list \
   CONFIG.C_OPERATION {not} \
   CONFIG.C_SIZE {1} \
   CONFIG.LOGO_FILE {data/sym_notgate.png} \
 ] $ddr_reset_inv

  # Create instance: ddr_reset_sync, and set properties
  set ddr_reset_sync [ create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 ddr_reset_sync ]
  set_property -dict [ list \
   CONFIG.C_AUX_RST_WIDTH {1} \
   CONFIG.C_EXT_RST_WIDTH {1} \
 ] $ddr_reset_sync

  # Create instance: firesim_reset_conv, and set properties
  set firesim_reset_conv [ create_bd_cell -type ip -vlnv xilinx.com:ip:util_vector_logic:2.0 firesim_reset_conv ]
  set_property -dict [ list \
   CONFIG.C_OPERATION {not} \
   CONFIG.C_SIZE {1} \
   CONFIG.LOGO_FILE {data/sym_notgate.png} \
 ] $firesim_reset_conv

  # Create instance: pcie_axi4_reset_sync, and set properties
  set pcie_axi4_reset_sync [ create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 pcie_axi4_reset_sync ]
  set_property -dict [ list \
   CONFIG.C_AUX_RST_WIDTH {1} \
   CONFIG.C_EXT_RST_WIDTH {1} \
 ] $pcie_axi4_reset_sync

  # Create instance: psreset_ctrlclk, and set properties
  set psreset_ctrlclk [ create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 psreset_ctrlclk ]
  set_property -dict [ list \
   CONFIG.C_AUX_RST_WIDTH {1} \
   CONFIG.C_EXT_RST_WIDTH {1} \
 ] $psreset_ctrlclk

  # Create instance: sys_reset_n, and set properties
  set sys_reset_n [ create_bd_cell -type ip -vlnv xilinx.com:ip:util_vector_logic:2.0 sys_reset_n ]
  set_property -dict [ list \
   CONFIG.C_OPERATION {not} \
   CONFIG.C_SIZE {1} \
   CONFIG.LOGO_FILE {data/sym_notgate.png} \
 ] $sys_reset_n

  # Create instance: util_vector_logic_0, and set properties
  set util_vector_logic_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:util_vector_logic:2.0 util_vector_logic_0 ]
  set_property -dict [ list \
   CONFIG.C_OPERATION {and} \
   CONFIG.C_SIZE {1} \
   CONFIG.LOGO_FILE {data/sym_andgate.png} \
 ] $util_vector_logic_0

  # Create interface connections
  connect_bd_intf_net -intf_net Conn1 [get_bd_intf_pins pcie_mgt] [get_bd_intf_pins buf_refclk_ibuf/CLK_IN_D]

  # Create port connections
  connect_bd_net -net buf_refclk_ibuf_IBUF_DS_ODIV2 [get_bd_pins IBUF_DS_ODIV2] [get_bd_pins buf_refclk_ibuf/IBUF_DS_ODIV2]
  connect_bd_net -net buf_refclk_ibuf_IBUF_OUT [get_bd_pins IBUF_OUT] [get_bd_pins buf_refclk_ibuf/IBUF_OUT]
  connect_bd_net -net clkwiz_sysclks_clk_out2 [get_bd_pins clkwiz_sysclks_clk_out2] [get_bd_pins clkwiz_sysclks/clk_out1] [get_bd_pins psreset_ctrlclk/slowest_sync_clk]
  connect_bd_net -net clkwiz_sysclks_locked [get_bd_pins locked] [get_bd_pins clkwiz_sysclks/locked] [get_bd_pins ddr_reset_sync/dcm_locked] [get_bd_pins pcie_axi4_reset_sync/dcm_locked] [get_bd_pins psreset_ctrlclk/dcm_locked]
  connect_bd_net -net ddr_reset_inv_Res [get_bd_pins ddr_reset_inv/Res] [get_bd_pins util_vector_logic_0/Op1]
  connect_bd_net -net ddr_reset_sync_peripheral_aresetn [get_bd_pins ddr_resetn_sync] [get_bd_pins ddr_reset_sync/peripheral_aresetn]
  connect_bd_net -net ddr_ui_axi_aclk_1 [get_bd_pins ddr_ui_axi_aclk] [get_bd_pins ddr_reset_sync/slowest_sync_clk]
  connect_bd_net -net ddr_ui_reset_1 [get_bd_pins ddr_ui_reset] [get_bd_pins ddr_reset_inv/Op1]
  connect_bd_net -net dma_pcie_axi_aclk_1 [get_bd_pins dma_pcie_axi_aclk] [get_bd_pins clkwiz_sysclks/clk_in1] [get_bd_pins pcie_axi4_reset_sync/slowest_sync_clk]
  connect_bd_net -net dma_pcie_axi_aresetn_1 [get_bd_pins dma_pcie_axi_aresetn] [get_bd_pins util_vector_logic_0/Op2]
  connect_bd_net -net pcie_axi4_reset_sync_peripheral_aresetn [get_bd_pins dma_pcie_resetn_sync] [get_bd_pins pcie_axi4_reset_sync/peripheral_aresetn]
  connect_bd_net -net perst_n_1 [get_bd_pins perst_n] [get_bd_pins clkwiz_sysclks/resetn] [get_bd_pins ddr_reset_sync/ext_reset_in] [get_bd_pins pcie_axi4_reset_sync/ext_reset_in] [get_bd_pins psreset_ctrlclk/ext_reset_in] [get_bd_pins sys_reset_n/Op1]
  connect_bd_net -net psreset_ctrlclk_peripheral_aresetn [get_bd_pins peripheral_aresetn] [get_bd_pins firesim_reset_conv/Op1] [get_bd_pins psreset_ctrlclk/peripheral_aresetn]
  connect_bd_net -net sys_reset_n1_Res [get_bd_pins firesim_reset] [get_bd_pins firesim_reset_conv/Res]
  connect_bd_net -net sys_reset_n_Res [get_bd_pins sys_reset] [get_bd_pins sys_reset_n/Res]
  connect_bd_net -net util_vector_logic_0_Res [get_bd_pins ddr_reset_sync/aux_reset_in] [get_bd_pins pcie_axi4_reset_sync/aux_reset_in] [get_bd_pins psreset_ctrlclk/aux_reset_in] [get_bd_pins util_vector_logic_0/Res]

  # Restore current instance
  current_bd_instance $oldCurInst
}


# Procedure to create entire design; Provide argument to make
# procedure reusable. If parentCell is "", will use root.
proc create_root_design { parentCell } {

  variable script_folder
  variable design_name

  if { $parentCell eq "" } {
     set parentCell [get_bd_cells /]
  }

  # Get object for parentCell
  set parentObj [get_bd_cells $parentCell]
  if { $parentObj == "" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2090 -severity "ERROR" "Unable to find parent cell <$parentCell>!"}
     return
  }

  # Make sure parentObj is hier blk
  set parentType [get_property TYPE $parentObj]
  if { $parentType ne "hier" } {
     catch {common::send_gid_msg -ssname BD::TCL -id 2091 -severity "ERROR" "Parent <$parentObj> has TYPE = <$parentType>. Expected to be <hier>."}
     return
  }

  # Save current instance; Restore later
  set oldCurInst [current_bd_instance .]

  # Set parent object as current
  current_bd_instance $parentObj


  # Create interface ports
  set SYSCLK0_300 [ create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 SYSCLK0_300 ]
  set_property -dict [ list \
   CONFIG.FREQ_HZ {300000000} \
   ] $SYSCLK0_300

  set c0_ddr4 [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:ddr4_rtl:1.0 c0_ddr4 ]

  set ctrl [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:aximm_rtl:1.0 ctrl ]
  set_property -dict [ list \
   CONFIG.ADDR_WIDTH {32} \
   CONFIG.DATA_WIDTH {32} \
   CONFIG.FREQ_HZ {50000000} \
   CONFIG.HAS_BURST {0} \
   CONFIG.HAS_CACHE {0} \
   CONFIG.HAS_LOCK {0} \
   CONFIG.HAS_PROT {1} \
   CONFIG.HAS_QOS {0} \
   CONFIG.HAS_REGION {0} \
   CONFIG.PROTOCOL {AXI4LITE} \
   ] $ctrl

  set dma [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:aximm_rtl:1.0 dma ]
  set_property -dict [ list \
   CONFIG.ADDR_WIDTH {32} \
   CONFIG.DATA_WIDTH {512} \
   CONFIG.FREQ_HZ {50000000} \
   CONFIG.NUM_READ_OUTSTANDING {2} \
   CONFIG.NUM_WRITE_OUTSTANDING {2} \
   CONFIG.PROTOCOL {AXI4} \
   ] $dma

  set mem_0 [ create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:aximm_rtl:1.0 mem_0 ]
  set_property -dict [ list \
   CONFIG.ADDR_WIDTH {34} \
   CONFIG.ARUSER_WIDTH {0} \
   CONFIG.AWUSER_WIDTH {0} \
   CONFIG.BUSER_WIDTH {0} \
   CONFIG.DATA_WIDTH {64} \
   CONFIG.FREQ_HZ {50000000} \
   CONFIG.HAS_BRESP {1} \
   CONFIG.HAS_BURST {1} \
   CONFIG.HAS_CACHE {1} \
   CONFIG.HAS_LOCK {1} \
   CONFIG.HAS_PROT {1} \
   CONFIG.HAS_QOS {1} \
   CONFIG.HAS_REGION {1} \
   CONFIG.HAS_RRESP {1} \
   CONFIG.HAS_WSTRB {1} \
   CONFIG.ID_WIDTH {4} \
   CONFIG.MAX_BURST_LENGTH {256} \
   CONFIG.NUM_READ_OUTSTANDING {2} \
   CONFIG.NUM_READ_THREADS {1} \
   CONFIG.NUM_WRITE_OUTSTANDING {2} \
   CONFIG.NUM_WRITE_THREADS {1} \
   CONFIG.PROTOCOL {AXI4} \
   CONFIG.READ_WRITE_MODE {READ_WRITE} \
   CONFIG.RUSER_BITS_PER_BYTE {0} \
   CONFIG.RUSER_WIDTH {0} \
   CONFIG.SUPPORTS_NARROW_BURST {1} \
   CONFIG.WUSER_BITS_PER_BYTE {0} \
   CONFIG.WUSER_WIDTH {0} \
   ] $mem_0

  set pcie_7x_mgt [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:pcie_7x_mgt_rtl:1.0 pcie_7x_mgt ]

  set pcie_mgt [ create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 pcie_mgt ]

  set satellite_uart [ create_bd_intf_port -mode Master -vlnv xilinx.com:interface:uart_rtl:1.0 satellite_uart ]


  # Create ports
  set firesim_clock [ create_bd_port -dir O -type clk firesim_clock ]
  set_property -dict [ list \
   CONFIG.ASSOCIATED_RESET {firesim_reset} \
 ] $firesim_clock
  set firesim_reset [ create_bd_port -dir O -from 0 -to 0 -type rst firesim_reset ]
  set pcie_perstn_rst [ create_bd_port -dir I -type rst pcie_perstn_rst ]
  set qsfp0_int_l_0 [ create_bd_port -dir I -from 0 -to 0 qsfp0_int_l_0 ]
  set qsfp0_lpmode_0 [ create_bd_port -dir O -from 0 -to 0 qsfp0_lpmode_0 ]
  set qsfp0_modprs_l_0 [ create_bd_port -dir I -from 0 -to 0 qsfp0_modprs_l_0 ]
  set qsfp0_modsel_l_0 [ create_bd_port -dir O -from 0 -to 0 qsfp0_modsel_l_0 ]
  set qsfp0_reset_l_0 [ create_bd_port -dir O -from 0 -to 0 qsfp0_reset_l_0 ]
  set qsfp1_int_l_0 [ create_bd_port -dir I -from 0 -to 0 qsfp1_int_l_0 ]
  set qsfp1_lpmode_0 [ create_bd_port -dir O -from 0 -to 0 qsfp1_lpmode_0 ]
  set qsfp1_modprs_l_0 [ create_bd_port -dir I -from 0 -to 0 qsfp1_modprs_l_0 ]
  set qsfp1_modsel_l_0 [ create_bd_port -dir O -from 0 -to 0 qsfp1_modsel_l_0 ]
  set qsfp1_reset_l_0 [ create_bd_port -dir O -from 0 -to 0 qsfp1_reset_l_0 ]
  set satellite_gpio [ create_bd_port -dir I -from 3 -to 0 -type intr satellite_gpio ]
  set_property -dict [ list \
   CONFIG.PortWidth {4} \
   CONFIG.SENSITIVITY {EDGE_RISING} \
 ] $satellite_gpio

  # Create instance: axi_interconnect_0, and set properties
  set axi_interconnect_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_interconnect:2.1 axi_interconnect_0 ]
  set_property -dict [ list \
   CONFIG.NUM_MI {3} \
 ] $axi_interconnect_0

  # Create instance: base_clocking
  create_hier_cell_base_clocking [current_bd_instance .] base_clocking

  # Create instance: clk_vip_0, and set properties
  set clk_vip_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:clk_vip:1.0 clk_vip_0 ]

  # Create instance: cms
  create_hier_cell_cms [current_bd_instance .] cms

  # Create instance: ddr_C0
  create_hier_cell_ddr_C0 [current_bd_instance .] ddr_C0

  # Create instance: dma_cdc, and set properties
  set dma_cdc [ create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 dma_cdc ]

  # Create instance: rst_vip_0, and set properties
  set rst_vip_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:rst_vip:1.0 rst_vip_0 ]

  # Create instance: system_ila_0, and set properties
  set system_ila_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:system_ila:1.1 system_ila_0 ]

  # Create instance: xdma_0, and set properties
  set xdma_0 [ create_bd_cell -type ip -vlnv xilinx.com:ip:xdma:4.1 xdma_0 ]
  set_property -dict [ list \
   CONFIG.axil_master_64bit_en {true} \
   CONFIG.axilite_master_en {true} \
   CONFIG.axilite_master_size {32} \
   CONFIG.coreclk_freq {500} \
   CONFIG.mode_selection {Advanced} \
   CONFIG.pcie_blk_locn {X0Y1} \
   CONFIG.pf0_device_id {903F} \
   CONFIG.pf0_msix_cap_pba_bir {BAR_3:2} \
   CONFIG.pf0_msix_cap_table_bir {BAR_3:2} \
   CONFIG.pf0_subsystem_id {0007} \
   CONFIG.pl_link_cap_max_link_speed {8.0_GT/s} \
   CONFIG.pl_link_cap_max_link_width {X16} \
   CONFIG.plltype {QPLL1} \
   CONFIG.xdma_axilite_slave {false} \
   CONFIG.xdma_pcie_64bit_en {true} \
 ] $xdma_0

  # Create interface connections
  connect_bd_intf_net -intf_net S00_AXI_2 [get_bd_intf_pins axi_interconnect_0/S00_AXI] [get_bd_intf_pins xdma_0/M_AXI_LITE]
connect_bd_intf_net -intf_net [get_bd_intf_nets S00_AXI_2] [get_bd_intf_pins system_ila_0/SLOT_0_AXI] [get_bd_intf_pins xdma_0/M_AXI_LITE]
  connect_bd_intf_net -intf_net S_AXI_0_1 [get_bd_intf_ports mem_0] [get_bd_intf_pins ddr_C0/S_AXI]
  connect_bd_intf_net -intf_net axi_clock_converter_0_M_AXI [get_bd_intf_ports dma] [get_bd_intf_pins dma_cdc/M_AXI]
  connect_bd_intf_net -intf_net axi_interconnect_0_M00_AXI [get_bd_intf_pins axi_interconnect_0/M00_AXI] [get_bd_intf_pins cms/s_axi_ctrl]
  connect_bd_intf_net -intf_net axi_interconnect_0_M01_AXI [get_bd_intf_pins axi_interconnect_0/M01_AXI] [get_bd_intf_pins cms/s_axi]
  connect_bd_intf_net -intf_net axi_interconnect_0_M02_AXI [get_bd_intf_ports ctrl] [get_bd_intf_pins axi_interconnect_0/M02_AXI]
  connect_bd_intf_net -intf_net cms_satellite_uart [get_bd_intf_ports satellite_uart] [get_bd_intf_pins cms/satellite_uart]
  connect_bd_intf_net -intf_net ddr_C0_ddr4_sdram_c0 [get_bd_intf_ports c0_ddr4] [get_bd_intf_pins ddr_C0/ddr4_sdram_c0]
  connect_bd_intf_net -intf_net default_300mhz_clk0_1 [get_bd_intf_ports SYSCLK0_300] [get_bd_intf_pins ddr_C0/default_300mhz_clk0]
  connect_bd_intf_net -intf_net pcie_mgt_1 [get_bd_intf_ports pcie_mgt] [get_bd_intf_pins base_clocking/pcie_mgt]
  connect_bd_intf_net -intf_net slr1_pcie_7x_mgt [get_bd_intf_ports pcie_7x_mgt] [get_bd_intf_pins xdma_0/pcie_mgt]
  connect_bd_intf_net -intf_net xdma_0_M_AXI [get_bd_intf_pins dma_cdc/S_AXI] [get_bd_intf_pins xdma_0/M_AXI]

  # Create port connections
  connect_bd_net -net base_clocking_IBUF_DS_ODIV2 [get_bd_pins base_clocking/IBUF_DS_ODIV2] [get_bd_pins xdma_0/sys_clk]
  connect_bd_net -net base_clocking_IBUF_OUT [get_bd_pins base_clocking/IBUF_OUT] [get_bd_pins xdma_0/sys_clk_gt]
  connect_bd_net -net base_clocking_clk_out2 [get_bd_pins base_clocking/clkwiz_sysclks_clk_out2] [get_bd_pins clk_vip_0/clk_in] [get_bd_pins ddr_C0/s_axi_aclk] [get_bd_pins dma_cdc/m_axi_aclk]
  connect_bd_net -net base_clocking_dma_pcie_resetn_sync [get_bd_pins axi_interconnect_0/ARESETN] [get_bd_pins axi_interconnect_0/S00_ARESETN] [get_bd_pins base_clocking/dma_pcie_resetn_sync] [get_bd_pins dma_cdc/s_axi_aresetn]
  connect_bd_net -net base_clocking_firesim_reset [get_bd_ports firesim_reset] [get_bd_pins base_clocking/firesim_reset]
  connect_bd_net -net base_clocking_periph_aresetn [get_bd_pins base_clocking/peripheral_aresetn] [get_bd_pins ddr_C0/s_axi_aresetn] [get_bd_pins dma_cdc/m_axi_aresetn] [get_bd_pins rst_vip_0/rst_in]
  connect_bd_net -net base_clocking_sys_reset [get_bd_pins base_clocking/sys_reset] [get_bd_pins ddr_C0/sys_rst]
  connect_bd_net -net clk_vip_0_out [get_bd_ports firesim_clock] [get_bd_pins axi_interconnect_0/M00_ACLK] [get_bd_pins axi_interconnect_0/M01_ACLK] [get_bd_pins axi_interconnect_0/M02_ACLK] [get_bd_pins clk_vip_0/clk_out] [get_bd_pins cms/s_axi_aclk]
  connect_bd_net -net cms_irq [get_bd_pins cms/irq] [get_bd_pins xdma_0/usr_irq_req]
  connect_bd_net -net cms_qsfp0_lpmode_0 [get_bd_ports qsfp0_lpmode_0] [get_bd_pins cms/qsfp0_lpmode_0]
  connect_bd_net -net cms_qsfp0_modsel_l_0 [get_bd_ports qsfp0_modsel_l_0] [get_bd_pins cms/qsfp0_modsel_l_0]
  connect_bd_net -net cms_qsfp0_reset_l_0 [get_bd_ports qsfp0_reset_l_0] [get_bd_pins cms/qsfp0_reset_l_0]
  connect_bd_net -net cms_qsfp1_lpmode_0 [get_bd_ports qsfp1_lpmode_0] [get_bd_pins cms/qsfp1_lpmode_0]
  connect_bd_net -net cms_qsfp1_modsel_l_0 [get_bd_ports qsfp1_modsel_l_0] [get_bd_pins cms/qsfp1_modsel_l_0]
  connect_bd_net -net cms_qsfp1_reset_l_0 [get_bd_ports qsfp1_reset_l_0] [get_bd_pins cms/qsfp1_reset_l_0]
  connect_bd_net -net ddr4_axi_aresetn_sync_1 [get_bd_pins base_clocking/ddr_resetn_sync] [get_bd_pins ddr_C0/ddr4_axi_aresetn_sync]
  connect_bd_net -net ddr_C0_ddr_ui_clock [get_bd_pins base_clocking/ddr_ui_axi_aclk] [get_bd_pins ddr_C0/ddr_ui_clock]
  connect_bd_net -net ddr_C0_ddr_ui_reset [get_bd_pins base_clocking/ddr_ui_reset] [get_bd_pins ddr_C0/ddr_ui_reset]
  connect_bd_net -net dma_pcie_axi_aclk [get_bd_pins axi_interconnect_0/ACLK] [get_bd_pins axi_interconnect_0/S00_ACLK] [get_bd_pins base_clocking/dma_pcie_axi_aclk] [get_bd_pins dma_cdc/s_axi_aclk] [get_bd_pins system_ila_0/clk] [get_bd_pins xdma_0/axi_aclk]
  connect_bd_net -net perst_n_0_1 [get_bd_ports pcie_perstn_rst] [get_bd_pins base_clocking/perst_n] [get_bd_pins xdma_0/sys_rst_n]
  connect_bd_net -net qsfp0_int_l_0_1 [get_bd_ports qsfp0_int_l_0] [get_bd_pins cms/qsfp0_int_l_0]
  connect_bd_net -net qsfp0_modprs_l_0_1 [get_bd_ports qsfp0_modprs_l_0] [get_bd_pins cms/qsfp0_modprs_l_0]
  connect_bd_net -net qsfp1_int_l_0_1 [get_bd_ports qsfp1_int_l_0] [get_bd_pins cms/qsfp1_int_l_0]
  connect_bd_net -net qsfp1_modprs_l_0_1 [get_bd_ports qsfp1_modprs_l_0] [get_bd_pins cms/qsfp1_modprs_l_0]
  connect_bd_net -net rst_vip_0_out [get_bd_pins axi_interconnect_0/M00_ARESETN] [get_bd_pins axi_interconnect_0/M01_ARESETN] [get_bd_pins axi_interconnect_0/M02_ARESETN] [get_bd_pins cms/s_axi_aresetn] [get_bd_pins rst_vip_0/rst_out]
  connect_bd_net -net satellite_gpio_1 [get_bd_ports satellite_gpio] [get_bd_pins cms/satellite_gpio]
  connect_bd_net -net xdma_0_axi_aresetn [get_bd_pins base_clocking/dma_pcie_axi_aresetn] [get_bd_pins xdma_0/axi_aresetn]

  # Create address segments
  assign_bd_address -offset 0x00140000 -range 0x00010000 -target_address_space [get_bd_addr_spaces xdma_0/M_AXI_LITE] [get_bd_addr_segs cms/axi_intc_0/S_AXI/Reg] -force
  assign_bd_address -offset 0x00100000 -range 0x00040000 -target_address_space [get_bd_addr_spaces xdma_0/M_AXI_LITE] [get_bd_addr_segs cms/cms_subsystem_0/s_axi_ctrl/Mem0] -force
  assign_bd_address -offset 0x00000000 -range 0x00100000 -target_address_space [get_bd_addr_spaces xdma_0/M_AXI_LITE] [get_bd_addr_segs ctrl/Reg] -force
  assign_bd_address -offset 0x44A00000 -range 0x00010000 -target_address_space [get_bd_addr_spaces xdma_0/M_AXI] [get_bd_addr_segs dma/Reg] -force
  assign_bd_address -offset 0x00000000 -range 0x000400000000 -target_address_space [get_bd_addr_spaces mem_0] [get_bd_addr_segs ddr_C0/ddr4_0/C0_DDR4_MEMORY_MAP/C0_DDR4_ADDRESS_BLOCK] -force


  # Restore current instance
  current_bd_instance $oldCurInst

  validate_bd_design
  save_bd_design
}
# End of create_root_design()


##################################################################
# MAIN FLOW
##################################################################

create_root_design ""


