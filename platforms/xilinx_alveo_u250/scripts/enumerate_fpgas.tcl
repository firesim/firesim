# Single-session FPGA enumeration: get serials, bulk program, and per-target
# reprogram for fingerprint correlation — all in one Vivado process.
# Driven by firesim-generate-fpga-db.py through marker files in -work_dir.
#
# Only JTAG targets containing a device whose PART equals -device (e.g.
# xcvu19p) are used; other FPGAs on the host are left untouched.
#
# Phases:
#   1. Enumerate matching hw_targets -> write serials.json + "phase1_done"
#   2. Wait for "start_phase2", program all targets -> write "phase2_done" (OK/FAIL)
#   3. Per-target reprogram -> for each target, wait for "go_<idx>",
#      program, write "done_<idx>" (OK/FAIL) -> write "phase3_done" at end
# An "abort" marker from the orchestrator ends the session cleanly at the
# next target boundary.
#
# Usage:
#   vivado -mode batch -source enumerate_fpgas.tcl \
#     -tclargs -bit_path <firesim.bit> -device <part> -work_dir <temp dir>

array set options {
    -bit_path  ""
    -device    ""
    -work_dir  ""
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
if {$options(-device) eq ""} {
    puts "ERROR: -device is required"
    exit 1
}
if {$options(-work_dir) eq ""} {
    puts "ERROR: -work_dir is required"
    exit 1
}

# Write to a temp file and rename so the reader never sees a partial marker.
proc write_marker {name {content ""}} {
    global options
    set path [file join $options(-work_dir) $name]
    set f [open "$path.tmp" w]
    puts $f $content
    close $f
    file rename -force "$path.tmp" $path
}

proc shutdown {code} {
    catch {close_hw_target}
    catch {disconnect_hw_server}
    catch {close_hw_manager}
    exit $code
}

proc check_abort {} {
    global options
    if {[file exists [file join $options(-work_dir) "abort"]]} {
        puts "Abort requested"
        shutdown 1
    }
}

proc wait_for_marker {name {timeout_s 600}} {
    global options
    set path [file join $options(-work_dir) $name]
    set deadline [expr {[clock seconds] + $timeout_s}]
    while {![file exists $path]} {
        check_abort
        if {[clock seconds] > $deadline} {
            puts "ERROR: timed out waiting for $path"
            shutdown 1
        }
        after 500
    }
}

proc program_target {hw_target dev_name bit_path} {
    open_hw_target $hw_target
    set dev [get_hw_devices $dev_name]
    current_hw_device $dev
    set_property PROGRAM.FILE $bit_path $dev
    program_hw_devices $dev
    refresh_hw_device  $dev
    close_hw_target
}

set_param labtools.enable_cs_server false

open_hw_manager
connect_hw_server -allow_non_jtag
# by default vivado opens a default hw target
close_hw_target

# ── Phase 1: enumerate serials ──────────────────────────────────────────

# targets/devs: matching hw_targets and the device on each to program
set targets {}
set devs {}
set entries {}

foreach hw_target [get_hw_targets] {
    open_hw_target $hw_target
    set hw_uid [get_property UID $hw_target]
    set hw_dev ""
    foreach d [get_hw_devices] {
        if {[string equal -nocase [get_property PART $d] $options(-device)]} {
            set hw_dev $d
            break
        }
    }
    set all_devs [get_hw_devices]
    close_hw_target

    if {$hw_dev eq ""} {
        puts "SKIP: hw_target=$hw_target devices=$all_devs (no $options(-device))"
        continue
    }
    puts "ENUM: hw_target=$hw_target hw_dev=$hw_dev hw_uid=$hw_uid"
    lappend targets $hw_target
    lappend devs $hw_dev
    lappend entries "  \{\"uid\": \"$hw_uid\", \"device\": \"$hw_dev\", \"hw_target\": \"$hw_target\"\}"
}

set f [open [file join $options(-work_dir) "serials.json"] w]
puts $f "\[\n[join $entries ",\n"]\n\]"
close $f

write_marker "phase1_done"
puts "Phase 1 done: [llength $targets] target(s) enumerated"

# ── Phase 2: program all targets ────────────────────────────────────────

# the orchestrator validates the enumeration before anything is programmed
wait_for_marker "start_phase2"

set total [llength $targets]
set idx 0
set failures {}

foreach hw_target $targets hw_dev $devs {
    check_abort
    incr idx
    puts "\n========== Program all \[$idx/$total\] $hw_target =========="
    set start_time [clock seconds]

    if {[catch {program_target $hw_target $hw_dev $options(-bit_path)} err]} {
        puts "ERROR on $hw_target: $err"
        lappend failures $hw_target
        catch {close_hw_target}
    } else {
        puts "OK $hw_target ([expr {[clock seconds] - $start_time}]s)"
    }
}

if {[llength $failures] > 0} {
    puts "ERROR: [llength $failures] target(s) failed in phase 2"
    write_marker "phase2_done" "FAIL"
    shutdown 1
}

write_marker "phase2_done" "OK"
puts "Phase 2 done: all $total target(s) programmed"

# ── Phase 3: per-target reprogram for fingerprint correlation ───────────
# The orchestrator writes fingerprints after phase 2, then signals us per-target.

set idx 0
foreach hw_target $targets hw_dev $devs {
    wait_for_marker "go_$idx"

    puts "\n========== Reprogram \[$idx\] $hw_target =========="
    set start_time [clock seconds]

    if {[catch {program_target $hw_target $hw_dev $options(-bit_path)} err]} {
        puts "ERROR on $hw_target: $err"
        catch {close_hw_target}
        write_marker "done_$idx" "FAIL"
    } else {
        puts "OK $hw_target ([expr {[clock seconds] - $start_time}]s)"
        write_marker "done_$idx" "OK"
    }

    incr idx
}

write_marker "phase3_done"
puts "\n================ Enumeration complete ================"
shutdown 0
