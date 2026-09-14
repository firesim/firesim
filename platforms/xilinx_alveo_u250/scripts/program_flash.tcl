# Program an MCS into the on-board SPI flash of every attached XB-10.
# One Vivado session, internal loop — avoids the back-to-back hw_server
# wedge that plagues per-invocation JTAG flows on this host.
#
# Flash part: Micron MT25QU02GCBB8E12 (2 Gb Quad-SPI, 1.8 V).
# Vivado cfgmem part name: mt25qu02g-spi-x1_x2_x4.
#
# Usage:
#   vivado -mode batch -source program_flash.tcl \
#     -tclargs -mcs_path <path to firesim.mcs> \
#              [-serial <substring>] \
#              [-flash_part <Vivado cfgmem part name>]
# If -serial is omitted, programs every hw_target visible on the chain.
# -flash_part defaults to mt25qu02g-spi-x1_x2_x4 for XB-10's Micron
# MT25QU02GCBB8E12. Override for other boards (e.g. u250 uses
# mt25qu01g-spi-x1_x2_x4).

array set options {
    -mcs_path   ""
    -serial     ""
    -flash_part "mt25qu02g-spi-x1_x2_x4"
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

if {$options(-mcs_path) eq ""} {
    puts "ERROR: -mcs_path is required"
    exit 1
}
if {![file exists $options(-mcs_path)]} {
    puts "ERROR: MCS file not found: $options(-mcs_path)"
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

        set mem_part [lindex [get_cfgmem_parts $options(-flash_part)] 0]
        if {$mem_part eq ""} {
            error "Unknown cfgmem part: $options(-flash_part)"
        }
        create_hw_cfgmem -hw_device $dev -mem_dev $mem_part
        set cfgmem [get_property PROGRAM.HW_CFGMEM $dev]

        set_property PROGRAM.FILES                   [list $options(-mcs_path)] $cfgmem
        set_property PROGRAM.ADDRESS_RANGE           {use_file}                 $cfgmem
        set_property PROGRAM.BLANK_CHECK             0                          $cfgmem
        set_property PROGRAM.ERASE                   1                          $cfgmem
        set_property PROGRAM.CFG_PROGRAM             1                          $cfgmem
        set_property PROGRAM.VERIFY                  1                          $cfgmem
        set_property PROGRAM.CHECKSUM                0                          $cfgmem

        create_hw_bitstream -hw_device $dev [get_property PROGRAM.HW_CFGMEM_BITFILE $dev]
        program_hw_devices $dev
        refresh_hw_device  $dev

        program_hw_cfgmem  -hw_cfgmem $cfgmem

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
