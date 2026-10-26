if {[llength $argv] != 3} {error "Expected 3 arguments"}
# Try the supplied full static checkpoint without changing its floorplan.
# This is an offline experiment; no hardware programming takes place here.
set full [lindex $argv 0]
set rm [lindex $argv 1]
set out [lindex $argv 2]
file mkdir $out
open_checkpoint $full
set fd [open "$out/context.txt" w]
puts $fd "FULL_STATIC_CHECKPOINT $full"
puts $fd "FULL_STATIC_PRIMITIVES [llength [get_cells -hier -filter {NAME =~ top_i/static_region/* && IS_PRIMITIVE}]]"
foreach cell {top_i/slash top_i/service_layer} {
  puts $fd "$cell IS_BLACKBOX [get_property IS_BLACKBOX [get_cells $cell]]"
}
puts $fd "ORIGINAL_PBLOCK_RANGES [get_property GRID_RANGES [get_pblocks pblock_slash]]"
close $fd
read_checkpoint -cell top_i/slash $rm
reset_property SEVERITY [get_drc_checks {HDPR-18 HDPR-59}]
report_route_status -file "$out/route-status.txt"
report_drc -file "$out/default-drc.txt"
report_drc -checks [get_drc_checks HDPR*] -file "$out/all-dfx-drc.txt"
write_checkpoint -force "$out/full-context.dcp"
pr_verify -initial $full -additional "$out/full-context.dcp" -full_check -file "$out/pr-verify.txt"
set fd [open "$out/pr-verify-passed" w]
puts $fd "$out/full-context.dcp"
close $fd
exit
