if {[llength $argv] != 2} {error "Expected 2 arguments"}
# Reject a candidate unless both enable registers really use the falling
# edge and their half-cycle CE input paths meet setup and hold timing.
set checkpoint [lindex $argv 0]
set outdir [lindex $argv 1]
open_checkpoint $checkpoint
set gates [get_cells -hier -filter {NAME =~ top_i/slash/firesim_0/inst/*/u_bufg && REF_NAME == BUFG_FABRIC}]
if {[llength $gates] < 1} {error "No FireSim clock gates found"}
set report [open "$outdir/gate-enable-signoff.txt" w]
set gate_index 0
foreach gate [lsort $gates] {
  incr gate_index
  set parent [file dirname $gate]
  set reg [get_cells $parent/clken_l_reg]
  if {[llength $reg] != 1} {error "Missing CE register"}
  set inverted [get_property IS_C_INVERTED $reg]
  puts $report "$parent ref=[get_property REF_NAME $reg] IS_C_INVERTED=$inverted"
  if {[get_property REF_NAME $reg] ni {FDRE FDSE FDCE FDPE} || $inverted ni {1 true TRUE 1'b1}} {
    error "CE is not implemented by a falling-edge flip-flop"
  }
  set data [get_pins $reg/D]
  foreach type {max min} {
    set paths [get_timing_paths -to $data -delay_type $type -max_paths 1]
    if {[llength $paths] != 1} {error "CE $type input path is not timed"}
    set slack [get_property SLACK $paths]
    puts $report "$type slack=$slack"
    if {$slack < 0} {error "CE half-cycle $type violation: $slack"}
  }
  report_timing -to $data -delay_type max -max_paths 5 -path_type full_clock_expanded -file "$outdir/$gate_index-[file tail $parent]-enable-setup.txt"
  report_timing -to $data -delay_type min -max_paths 5 -path_type full_clock_expanded -file "$outdir/$gate_index-[file tail $parent]-enable-hold.txt"
}
close $report
exit
