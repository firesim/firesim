# Constrain the actual programmable host clock before implementation.
if {![info exists ::env(FIRESIM_HOST_MHZ)]} {error "Missing FIRESIM_HOST_MHZ"}
set mhz $::env(FIRESIM_HOST_MHZ)
if {![string is integer -strict $mhz] || $mhz < 1 || $mhz > 300} {error "Invalid host frequency"}
reset_property SEVERITY [get_drc_checks {HDPR-18 HDPR-59}]
set mmcm [get_cells top_i/static_region/clk_rst_shell/clk_wizard_slash/inst/clock_primitive_inst/MMCME5_inst]
if {[llength $mmcm] != 1} {error "Expected one runtime host MMCM"}
create_generated_clock -name host_clock -source [get_pins $mmcm/CLKIN1] -multiply_by $mhz -divide_by 100 [get_pins $mmcm/CLKOUT0]
set gates [get_cells -hier -filter {NAME =~ top_i/slash/firesim_0/inst/*/u_bufg && REF_NAME == BUFG_FABRIC}]
if {[llength $gates] < 1} {error "No FireSim clock gates found"}
set gate_index 0
foreach gate [lsort $gates] {
  incr gate_index
  set parent [file dirname $gate]
  set reg [get_cells -quiet $parent/clken_l_reg]
  if {[llength $reg] != 1 || [get_property IS_C_INVERTED $reg] ni {1 true TRUE 1'b1}} {error "Expected falling-edge CE register"}
  set_clock_sense -stop_propagation [get_pins $reg/Q]
  create_generated_clock -name target_clock_$gate_index -source [get_pins $parent/I] -divide_by 1 [get_pins $gate/O]
}
