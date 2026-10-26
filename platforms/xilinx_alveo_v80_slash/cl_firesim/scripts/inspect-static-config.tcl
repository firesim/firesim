if {[llength $argv] != 4} {error "Pass initial/additional checkpoints, site names and output"}
set initial [lindex $argv 0]
set additional [lindex $argv 1]
set sites_file [lindex $argv 2]
set output [lindex $argv 3]
set input [open $sites_file r]
set site_names [split [string trim [read $input]] "\n"]
close $input
set fd [open $output w]
foreach checkpoint [list $initial $additional] {
  open_checkpoint $checkpoint
  foreach name $site_names {
    set site [get_sites -quiet $name]
    if {[llength $site] != 1} {error "Expected one static site: $name"}
    set cells [get_cells -quiet -of_objects $site]
    if {[llength $cells] < 1} {error "Missing static programming at $name in $checkpoint"}
    foreach cell $cells {
      foreach prop [list_property $cell] {
        if {[regexp {^(INIT|REF_NAME|LOC|BEL|LOCK_PINS|IS_.*INVERTED)$} $prop]} {
          puts $fd "$checkpoint|$name|$cell|$prop|[get_property $prop $cell]"
        }
      }
      foreach pin [get_pins -quiet -of_objects $cell] {
        set mapping [get_bel_pins -quiet -of_objects $pin]
        puts $fd "$checkpoint|$name|$cell|PIN:[get_property REF_PIN_NAME $pin]|[join $mapping ,]"
      }
    }
  }
  close_design
}
close $fd
exit
