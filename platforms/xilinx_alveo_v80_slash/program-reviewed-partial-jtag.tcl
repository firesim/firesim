open_hw_manager
connect_hw_server -url localhost:3121
set targets [get_hw_targets -quiet]
if {[llength $targets] != 1} {error "Expected exactly one physical JTAG target"}
open_hw_target [lindex $targets 0]
set d [get_hw_devices -filter {PART =~ xcv80*}]
if {[llength $d] != 1} {error "Expected one V80 device"}
if {[llength $argv] != 1} {error "Expected reviewed partial.pdi path"}
set_property PROGRAM.FILE [file normalize [lindex $argv 0]] $d
program_hw_devices $d
verify_hw_devices $d
close_hw_target
close_hw_manager
exit
