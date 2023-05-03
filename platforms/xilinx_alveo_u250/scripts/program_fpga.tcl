# Adapted from https://github.com/Xilinx/open-nic-shell

# Directory variables
set script_path [file normalize [info script]]
set script_dir [file dirname $script_path]
set root_dir [file dirname $script_dir]

# Loading options
#   bitstream_path   Path to the bitstream
#   board            Board name
array set options {
    -bitstream_path ""
    -probes_path    ""
    -board          au50
}

# Expect arguments in the form of `-argument value`
for {set i 0} {$i < $argc} {incr i 2} {
    set arg [lindex $argv $i]
    set val [lindex $argv [expr $i+1]]
    if {[info exists options($arg)]} {
        set options($arg) $val
        puts "Set option $arg to $val"
    } else {
        puts "Skip unknown argument $arg and its value $val"
    }
}

# Settings based on defaults or passed in values
foreach {key value} [array get options] {
    set [string range $key 1 end] $value
}

source ${script_dir}/${board}.tcl

puts "Program file: $options(-bitstream_path)"
puts "Probes file: $options(-probes_path)"
puts "Board: $options(-board)"
puts "HW device: $hw_device"

open_hw_manager
connect_hw_server -allow_non_jtag
open_hw_target
current_hw_device [get_hw_devices $hw_device]
refresh_hw_device -update_hw_probes false [lindex [get_hw_devices $hw_device] 0]
set_property PROBES.FILE ${options(-probes_path)} [get_hw_devices $hw_device]
set_property FULL_PROBES.FILE ${options(-probes_path)} [get_hw_devices $hw_device]
set_property PROGRAM.FILE ${options(-bitstream_path)} [get_hw_devices $hw_device]
program_hw_devices [get_hw_devices $hw_device]
refresh_hw_device [lindex [get_hw_devices $hw_device] 0]

exit
