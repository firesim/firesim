# Directory variables
set script_path [file normalize [info script]]
set script_dir [file dirname $script_path]
set root_dir [file dirname $script_dir]

set_param labtools.enable_cs_server false

open_hw_manager
connect_hw_server -allow_non_jtag

# by default vivado opens a default hw target
close_hw_target

foreach {hw_target} [get_hw_targets] {
    open_hw_target $hw_target
    set hw_dev [get_hw_device]
    set hw_uid [get_property UID $hw_target]
    puts "hw_dev: $hw_dev"
    puts "hw_uid: $hw_uid"
    close_hw_target
}

exit
