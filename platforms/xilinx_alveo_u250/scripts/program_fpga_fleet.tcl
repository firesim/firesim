# Program multiple FPGAs with potentially different bitstreams in a single
# Vivado session.  Avoids the hw_server wedge caused by back-to-back
# Vivado process launches.
#
# Usage:
#   vivado -mode batch -source program_fpga_fleet.tcl \
#     -tclargs -map_file <path>
#
# map_file format: one line per target
#   <uid_substring> <bitstream_path>
#
# Example:
#   Digilent/210308B356F7 /path/to/slot0/firesim.bit
#   Digilent/210308B356F1 /path/to/slot1/firesim.bit

proc parse_args {} {
    global argc argv
    set result(-map_file) ""

    for {set i 0} {$i < $argc} {incr i 2} {
        set arg [lindex $argv $i]
        set val [lindex $argv [expr {$i + 1}]]
        if {[info exists result($arg)]} {
            set result($arg) $val
        }
    }

    if {$result(-map_file) eq ""} {
        puts "ERROR: -map_file is required"
        exit 1
    }
    if {![file exists $result(-map_file)]} {
        puts "ERROR: map file not found: $result(-map_file)"
        exit 1
    }
    return [array get result]
}

proc read_map_file {path} {
    set fd [open $path r]
    set entries {}
    while {[gets $fd line] >= 0} {
        set line [string trim $line]
        if {$line eq "" || [string index $line 0] eq "#"} continue
        set parts [split $line]
        if {[llength $parts] < 2} {
            puts "WARNING: skipping malformed line: $line"
            continue
        }
        set uid [lindex $parts 0]
        set bit [join [lrange $parts 1 end]]
        if {![file exists $bit]} {
            puts "ERROR: bitstream not found: $bit"
            exit 1
        }
        lappend entries [list $uid $bit]
    }
    close $fd
    return $entries
}

array set opts [parse_args]
set entries [read_map_file $opts(-map_file)]

if {[llength $entries] == 0} {
    puts "ERROR: no entries in map file"
    exit 1
}

puts "Will program [llength $entries] target(s):"
foreach e $entries {
    puts "  [lindex $e 0] -> [lindex $e 1]"
}

set_param labtools.enable_cs_server false

open_hw_manager
connect_hw_server -allow_non_jtag
close_hw_target

set all_targets [get_hw_targets]
puts "Available hw_targets: $all_targets"

set total [llength $entries]
set idx 0
set failures {}

foreach entry $entries {
    set uid [lindex $entry 0]
    set bit [lindex $entry 1]
    incr idx

    set matched ""
    foreach t $all_targets {
        if {[string first $uid $t] != -1} {
            set matched $t
            break
        }
    }

    if {$matched eq ""} {
        puts "ERROR: no hw_target matches uid '$uid'"
        lappend failures $uid
        continue
    }

    puts "\n========== \[$idx/$total\] $matched ($uid) =========="
    set start_time [clock seconds]

    if {[catch {
        open_hw_target $matched
        set dev [lindex [get_hw_devices] 0]
        current_hw_device $dev
        set_property PROGRAM.FILE $bit $dev
        program_hw_devices $dev
        refresh_hw_device $dev
        close_hw_target
    } err]} {
        puts "ERROR on $matched: $err"
        lappend failures $uid
        catch {close_hw_target}
    } else {
        set dur [expr {[clock seconds] - $start_time}]
        puts "OK $matched (${dur}s)"
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
