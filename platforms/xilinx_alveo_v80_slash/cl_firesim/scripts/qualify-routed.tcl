# Sign off the actual runtime RM clock, preserving paths between host/gated clocks.
# The shell checkpoint describes a 200 MHz default; SLASH runtime programs this
# output to the requested frequency. No timing paths are cut by this script.
if {[llength $argv] ni {3 4}} {error "Pass checkpoint, output, MHz, optional export"}
set checkpoint [lindex $argv 0]
set outdir [lindex $argv 1]
set mhz [lindex $argv 2]
if {![string is integer -strict $mhz] || $mhz < 1 || $mhz > 300} {error "Invalid runtime frequency"}
file mkdir $outdir
open_checkpoint $checkpoint
set ids [open "$outdir/module-identities.txt" w]
foreach name {top_i/slash top_i/service_layer} {
  set cell [get_cells -quiet $name]
  if {[llength $cell] != 1} {continue}
  puts $ids "CELL $name"
  foreach prop {HD.MODULE_NID HD.MODULE_UID HD.MODULE_PUID HD.RECONFIGURABLE} {
    puts $ids "$prop [get_property $prop $cell]"
  }
}
close $ids
reset_property SEVERITY [get_drc_checks {HDPR-18 HDPR-59}]
# The RM helper BUFG_FABRIC is for a NoC clock, not the kernel host clock.
# Trace the kernel input to the runtime-programmable static-shell MMCM instead.
set mmcm [get_cells top_i/static_region/clk_rst_shell/clk_wizard_slash/inst/clock_primitive_inst/MMCME5_inst]
if {[llength $mmcm] != 1} {error "Expected one runtime kernel clock MMCM"}
set source_mhz 100
create_generated_clock -name host_clock -source [get_pins $mmcm/CLKIN1] -multiply_by $mhz -divide_by $source_mhz [get_pins $mmcm/CLKOUT0]
set gates [get_cells -hier -filter {NAME =~ top_i/slash/firesim_0/inst/*/u_bufg && REF_NAME == BUFG_FABRIC}]
if {[llength $gates] < 1} {error "No FireSim clock gates found"}
set gate_index 0
foreach gate [lsort $gates] {
  incr gate_index
  set parent [file dirname $gate]
  set enable_q [get_pins -quiet $parent/clken_l_reg/Q]
  set source_i [get_pins -quiet $parent/I]
  if {[llength $enable_q] != 1 || [llength $source_i] != 1} {error "Missing gate latch or source"}
  # Prevent the enable latch from introducing an inverted clock source.
  # Its data paths and the latch's G clock remain timed.
  set_clock_sense -stop_propagation $enable_q
  set name target_clock_$gate_index
  create_generated_clock -name $name -source $source_i -divide_by 1 [get_pins $gate/O]
}
report_methodology -checks {TIMING-5} -file "$outdir/gated-clock-waveform-check.txt"
report_clocks -file "$outdir/clocks.txt"
report_clock_interaction -file "$outdir/clock-interaction.txt"
report_timing_summary -report_unconstrained -file "$outdir/timing-summary.txt"
report_timing -delay_type max -max_paths 10 -path_type full_clock_expanded -file "$outdir/setup.txt"
report_timing -delay_type min -max_paths 10 -path_type full_clock_expanded -file "$outdir/hold.txt"
report_drc -checks [get_drc_checks -regexp {^HDPR-[0-9]+$}] -file "$outdir/standard-dfx-drc.txt"
report_drc -file "$outdir/default-drc.txt"
report_drc -checks [get_drc_checks HDPRABS*] -file "$outdir/abstract-dfx-drc.txt"
write_checkpoint -force "$outdir/clock-qualified.dcp"
# Debug probe metadata is exported from the original SLASH routed design by
# firesim_debug_probes.tcl. Partition extraction does not retain that metadata.
if {[llength $argv] > 3 && [lindex $argv 3] eq "export"} {
  set setup_paths [get_timing_paths -delay_type max -max_paths 1]
  set hold_paths [get_timing_paths -delay_type min -max_paths 1]
  if {[llength $setup_paths] != 1 || [llength $hold_paths] != 1} {error "Missing timed setup/hold paths"}
  set setup [get_property SLACK $setup_paths]
  set hold [get_property SLACK $hold_paths]
  if {![string is double -strict $setup] || ![string is double -strict $hold]} {error "Invalid setup/hold slack"}
  puts "QUALIFIED_RUNTIME_CLOCK mhz=$mhz setup=$setup hold=$hold"
  if {$setup < 0 || $hold < 0} {error "Runtime clock does not meet timing"}
  write_device_image -cell top_i/slash -force "$outdir/partial.pdi"
}
exit
