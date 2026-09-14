# JTAG-SRAM-program a bitstream onto every attached FPGA in one Vivado
# session. Avoids the back-to-back hw_server wedge seen when
# program_fpga.tcl is invoked repeatedly from separate processes.
#
# Usage:
#   vivado -mode batch -source program_fpga_all.tcl \
#     -tclargs -bit_path <firesim.bit> [-serial <substring>]

array set options {
    -bit_path ""
    -serial   ""
}

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

if {$options(-bit_path) eq ""} {
    puts "ERROR: -bit_path is required"
    exit 1
}
if {![file exists $options(-bit_path)]} {
    puts "ERROR: bitstream not found: $options(-bit_path)"
    exit 1
}

set_param labtools.enable_cs_server false

open_hw_manager
connect_hw_server -allow_non_jtag
close_hw_target

set targets {}
foreach hw_target [get_hw_targets] {
    if {$options(-serial) ne "" && [string first $options(-serial) $hw_target] == -1} {
        continue
    }
    lappend targets $hw_target
}

if {[llength $targets] == 0} {
    puts "ERROR: No matching hw_targets found"
    get_hw_targets
    exit 1
}

puts "Will program [llength $targets] target(s):"
foreach t $targets { puts "  $t" }

set total [llength $targets]
set idx 0
set failures {}

foreach hw_target $targets {
    incr idx
    puts "\n========== \[$idx/$total\] $hw_target =========="
    set start_time [clock seconds]

    if {[catch {
        open_hw_target $hw_target
        set dev [lindex [get_hw_devices] 0]
        current_hw_device $dev
        set_property PROGRAM.FILE $options(-bit_path) $dev
        program_hw_devices $dev
        refresh_hw_device  $dev
        close_hw_target
    } err]} {
        puts "ERROR on $hw_target: $err"
        lappend failures $hw_target
        catch {close_hw_target}
    } else {
        set dur [expr {[clock seconds] - $start_time}]
        puts "OK $hw_target (${dur}s)"
    }
}

disconnect_hw_server
close_hw_manager

puts "\n================ Summary ================"
puts "Programmed: [expr {$total - [llength $failures]}] / $total"
if {[llength $failures] > 0} {
    puts "FAILED targets:"
    foreach f $failures { puts "  $f" }
    exit 1
}

exit 0
