# Adapted from https://github.com/Xilinx/open-nic-shell

# Directory variables
set script_path [file normalize [info script]]
set script_dir [file dirname $script_path]
set root_dir [file dirname $script_dir]

# Loading options
#   bitstream_path   Path to the bitstream
#   serial           Serial number of FPGA board (without trailing A)
array set options {
    -bitstream_path ""
    -probes_path    ""
    -serial         ""
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

puts "Program file: $options(-bitstream_path)"
puts "Probes file: $options(-probes_path)"
puts "Serial Number: $options(-serial)"

set_param labtools.enable_cs_server false

open_hw_manager
connect_hw_server -allow_non_jtag

# by default vivado opens a default hw target
close_hw_target

# check if serial is in hw targets
set final_hw_target ""
foreach {hw_target} [get_hw_targets] {
    if {[string first $serial $hw_target] != -1} {
        set final_hw_target $hw_target
    }
}

if {$final_hw_target == ""} {
    puts "Unable to find $serial in available HW targets. See available HW targets below:"
    get_hw_targets
    exit 1
}

puts "Programming $final_hw_target with ${options(-bitstream_path)}"
open_hw_target $final_hw_target
set_property PROBES.FILE ${options(-probes_path)} [get_hw_device]
set_property FULL_PROBES.FILE ${options(-probes_path)} [get_hw_device]
set_property PROGRAM.FILE ${options(-bitstream_path)} [get_hw_device]
program_hw_devices [get_hw_device]
refresh_hw_device [get_hw_device]
close_hw_target

exit
