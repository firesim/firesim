# See LICENSE for license details.
# Read-only audit: never reset, program, clear errors, or access kernel BARs.
if {[llength $argv] != 1} {error "Expected status output file"}
open_hw_manager
connect_hw_server -url localhost:3121
set targets [get_hw_targets]
if {[llength $targets] != 1} {error "Expected one physical JTAG target"}
current_hw_target [lindex $targets 0]
open_hw_target
set devices [get_hw_devices -filter {PART =~ xcv80*}]
if {[llength $devices] != 1} {error "Expected one V80 device"}
set device [lindex $devices 0]
refresh_hw_device $device
set output [open [lindex $argv 0] w]
foreach property [list_property $device] {
    if {[regexp {^REGISTER\.(ERROR_STATUS|JTAG_STATUS)\.SLR[012]} $property]} {
        puts $output "$property=[get_property $property $device]"
    }
}
close $output
close_hw_target
close_hw_manager
