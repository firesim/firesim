set checkpoint [lindex $argv 0]
set outdir [lindex $argv 1]
if {[llength $argv] != 3} {error "Pass candidate, output directory and full static shell checkpoint"}
set initial [lindex $argv 2]
file mkdir $outdir
file delete -force "$outdir/pr-verify-passed"
open_checkpoint $checkpoint
reset_property SEVERITY [get_drc_checks {HDPR-18 HDPR-59}]
set fd [open "$outdir/flow-properties.txt" w]
foreach object [list [current_design] [get_cells top_i/slash] [get_drc_checks HDPRABS-3]] {
  puts $fd "OBJECT $object"
  foreach prop [list_property $object] {
    if {[regexp -nocase {abstract|flow|pr_|mode|deck|enabled|description} $prop]} {
      puts $fd "$prop = [get_property $prop $object]"
    }
  }
}
puts $fd "INITIAL_STATIC_CHECKPOINT = $initial"
puts $fd "STATIC_LEAF_CELLS = [llength [get_cells -hier -filter {NAME =~ top_i/static_region/* && IS_PRIMITIVE}]]"
close $fd
report_drc -file "$outdir/default-drc.txt"
report_drc -checks [get_drc_checks HDPRABS*] -file "$outdir/abstract-dfx-drc.txt"
report_drc -checks [get_drc_checks -regexp {^HDPR-[0-9]+$}] -file "$outdir/standard-dfx-drc.txt"
pr_verify -initial $initial -additional $checkpoint -full_check -file "$outdir/pr-verify.txt"
set fd [open "$outdir/pr-verify-passed" w]
puts $fd $checkpoint
close $fd
exit
