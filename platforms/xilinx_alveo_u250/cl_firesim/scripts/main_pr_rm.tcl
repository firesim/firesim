set root_dir [pwd]
set vivado_version [version -short]
set vivado_version_major [string range $vivado_version 0 3]

set ifrequency           [lindex $argv 0]
set istrategy            [lindex $argv 1]
set iboard               [lindex $argv 2]
set pr_module_name_str   [lindex $argv 3]
set pr_partition_path_str [lindex $argv 4]
set pr_project_path       [lindex $argv 5]
set pr_partition_module_name_str [lindex $argv 6]

# Parse comma-separated lists into TCL lists
set pr_module_names {}
foreach name [split $pr_module_name_str ","] {
    lappend pr_module_names [string trim $name]
}

set pr_partition_paths {}
foreach path [split $pr_partition_path_str ","] {
    lappend pr_partition_paths [string trim $path]
}

set pr_partition_module_names {}
if {[string trim $pr_partition_module_name_str] ne ""} {
    foreach name [split $pr_partition_module_name_str ","] {
        lappend pr_partition_module_names [string trim $name]
    }
    if {[llength $pr_partition_module_names] != [llength $pr_module_names]} {
        puts "ERROR: pr_partition_module_names count ([llength $pr_partition_module_names]) does not match pr_module_names count ([llength $pr_module_names])"
        exit 1
    }
} else {
    set pr_partition_module_names $pr_module_names
}

if {[llength $pr_module_names] != [llength $pr_partition_paths]} {
    puts "ERROR: Number of PR module names ([llength $pr_module_names]) does not match number of partition paths ([llength $pr_partition_paths])"
    exit 1
}

puts "PR RM Swap Configuration:"
for {set i 0} {$i < [llength $pr_module_names]} {incr i} {
    puts "  Module [expr {$i + 1}]: [lindex $pr_module_names $i] -> [lindex $pr_partition_paths $i]"
}

if {$pr_project_path eq ""} {
    puts "ERROR: pr_project_path must be provided for main_pr_rm.tcl"
    exit 1
}
if {![file exists $pr_project_path]} {
    puts "ERROR: PR project path does not exist: $pr_project_path"
    exit 1
}

# Copy vivado_proj/ so that RM operations (create_reconfig_module, etc.)
# don't corrupt the original. Vivado modifies the .xpr in-place, so a failed
# run would leave the original in a broken state.
# The .xpr uses $PPRDIR-relative paths, so it works from any location.
# Source file refs ($PPRDIR/../design/) resolve to ${root_dir}/design/ which
# has the current build's split-verilog files.
set orig_vivado_proj [file dirname $pr_project_path]
set orig_project_name [file tail $pr_project_path]
set local_vivado_proj "${root_dir}/vivado_proj"

puts "Copying base project to: $local_vivado_proj"
if {[file exists $local_vivado_proj]} {
    file delete -force $local_vivado_proj
}
file copy $orig_vivado_proj $local_vivado_proj

set pr_project_path "${local_vivado_proj}/${orig_project_name}"
puts "Using local project copy: $pr_project_path"

# RM source files live in split-verilog/. Only the RM .sv is added to the
# RM fileset; the static shell is a locked routed checkpoint and does not
# depend on sources_1 at all.
set src_split_verilog "${root_dir}/design/split-verilog"

open_project $pr_project_path

# Source utilities and platform environment
set project_scripts_dir [file dirname [file normalize [info script]]]
source ${project_scripts_dir}/utils.tcl
source ${project_scripts_dir}/platform_env.tcl

# Timing tracking
set script_start_time [clock seconds]
set timing_log {}

proc format_time { seconds } {
    set hours [expr {int($seconds / 3600)}]
    set minutes [expr {int(($seconds % 3600) / 60)}]
    set secs [expr {int($seconds % 60)}]
    if {$hours > 0} {
        return [format "%dh %dm %ds" $hours $minutes $secs]
    } elseif {$minutes > 0} {
        return [format "%dm %ds" $minutes $secs]
    } else {
        return [format "%ds" $secs]
    }
}

proc log_timing { phase_name start_time } {
    global timing_log
    set current_time [clock seconds]
    set elapsed [expr {$current_time - $start_time}]
    lappend timing_log [list $phase_name $elapsed]
    puts "TIMING: $phase_name took [format_time $elapsed]"
    return $current_time
}

# Load strategy settings (used for OOC synth options)
set sourceFile ${project_scripts_dir}/strategies/strategy_${istrategy}.tcl
if {![file exists $sourceFile]} {
    puts "ERROR: Strategy file not found: $sourceFile"
    exit 1
}
source $sourceFile

set phase_start [clock seconds]

# Phase 1: Create RM filesets.
# create_reconfig_module closes any open design, so all RMs must be created
# before opening the synth checkpoint in Phase 2.
set rm_runs {}
set rm_synth_runs {}
set rm_impl_info {}

for {set i 0} {$i < [llength $pr_module_names]} {incr i} {
    set pr_module_name           [lindex $pr_module_names $i]
    set pr_partition_module_name [lindex $pr_partition_module_names $i]
    set pr_partition_path        [lindex $pr_partition_paths $i]
    set partition_def_name       "pr_partition_${pr_partition_module_name}"
    set reconfig_module_name     "pr_reconfig_module_${i}"

    puts "Creating RM '$reconfig_module_name': module=$pr_module_name partition_def=$partition_def_name"

    create_reconfig_module -name $reconfig_module_name \
        -partition_def [get_partition_defs $partition_def_name] \
        -top $pr_module_name

    # Add RM source file
    set rm_source "${src_split_verilog}/${pr_module_name}.sv"
    if {![file exists $rm_source]} {
        puts "ERROR: RM source not found: $rm_source"
        exit 1
    }
    add_files $rm_source -of_objects [get_reconfig_modules $reconfig_module_name]

    # Add OOC clock constraint for RM synthesis
    set rm_xdc_dir "${root_dir}/vivado_proj/rm_xdc"
    file mkdir $rm_xdc_dir
    set rm_xdc "${rm_xdc_dir}/${reconfig_module_name}_ooc.xdc"
    set ooc_period [expr {1000.0 / $ifrequency}]
    set rm_xdc_fh [open $rm_xdc w]
    puts $rm_xdc_fh "create_clock -name user_clock -period $ooc_period \[get_ports clock\]"
    close $rm_xdc_fh
    add_files $rm_xdc -of_objects [get_reconfig_modules $reconfig_module_name]
    set_property USED_IN {out_of_context synthesis implementation} \
        [get_files -of_objects [get_reconfig_modules $reconfig_module_name] $rm_xdc]

    # Configure OOC synthesis run
    set synth_run_name "${reconfig_module_name}_synth_1"
    if {[llength [get_runs -quiet $synth_run_name]] > 0} {
        set_property -dict [ list \
            STEPS.SYNTH_DESIGN.ARGS.DIRECTIVE ${synth_directive} \
            {STEPS.SYNTH_DESIGN.ARGS.MORE OPTIONS} "${synth_options}" \
        ] [get_runs $synth_run_name]
        lappend rm_synth_runs $synth_run_name
    } else {
        puts "WARNING: No OOC synthesis run found for $reconfig_module_name"
    }

    lappend rm_impl_info [list $pr_partition_path $reconfig_module_name $i]
}

# Phase 2: Open synth checkpoint, create impl runs.
# Abstract shell DFX mode requires -rm_instance (not -pr_config).
open_run synth_1

foreach rm_info $rm_impl_info {
    lassign $rm_info pr_partition_path reconfig_module_name impl_idx
    set run_name "impl_rm_${impl_idx}"

    puts "Creating impl run '$run_name': $pr_partition_path -> $reconfig_module_name"
    create_run $run_name -parent_run impl_1 -flow {Vivado Implementation 2023} \
        -rm_instance ${pr_partition_path}:${reconfig_module_name}

    # Use lightweight directives for RM impl — the module is small and
    # constrained to a pblock, so Explore/Aggressive strategies waste time.
    set_property -dict [ list \
        STEPS.OPT_DESIGN.IS_ENABLED 1 \
        STEPS.OPT_DESIGN.DIRECTIVE "Default" \
        STEPS.PLACE_DESIGN.DIRECTIVE "Default" \
        STEPS.PHYS_OPT_DESIGN.IS_ENABLED 0 \
        STEPS.ROUTE_DESIGN.DIRECTIVE "Default" \
    ] [get_runs $run_name]

    lappend rm_runs $run_name
}

close_design

# Delete default report configs to skip unnecessary report generation
foreach run [concat $rm_synth_runs $rm_runs] {
    foreach rc [get_report_configs -of_objects [get_runs $run] -quiet] {
        delete_report_config $rc
    }
}

set phase_start [log_timing "RM setup" $phase_start]

# Step 1: OOC synthesis
if {[llength $rm_synth_runs] > 0} {
    puts "Launching RM OOC synthesis: $rm_synth_runs"
    launch_runs $rm_synth_runs -jobs $jobs
    wait_on_runs $rm_synth_runs

    foreach run_name $rm_synth_runs {
        if {[get_property PROGRESS [get_runs $run_name]] ne "100%"} {
            puts "ERROR: OOC synthesis run $run_name failed"
            exit 1
        }
    }
}

set phase_start [log_timing "RM OOC synthesis" $phase_start]

# Step 2: Implementation (place + route)
if {[llength $rm_runs] > 0} {
    puts "Launching RM implementation: $rm_runs"
    launch_runs $rm_runs -to_step route_design -jobs $jobs
    wait_on_runs $rm_runs

    foreach run_name $rm_runs {
        set run_progress [get_property PROGRESS [get_runs $run_name]]
        if {$run_progress ne "100%"} {
            puts "ERROR: RM implementation $run_name did not complete (progress=$run_progress)"
            exit 1
        }
    }

    # Check timing
    foreach run_name $rm_runs {
        puts "  $run_name WNS: [get_property STATS.WNS [get_runs $run_name]] ns"
    }

    # Write full bitstreams by opening the routed checkpoint directly.
    # open_run opens the abstract shell view which can't produce a full bitstream.
    # Instead, open_checkpoint loads the full routed DCP (static + RM merged),
    # which supports write_bitstream without -cell.
    # No frequency adjustment — the MMCM was already tuned during the base build
    # and the static shell is locked.
    set top_level_name overall_fpga_top

    for {set i 0} {$i < [llength $rm_runs]} {incr i} {
        set run_name [lindex $rm_runs $i]
        set run_dir "${local_vivado_proj}/firesim.runs/${run_name}"
        set routed_dcp "${run_dir}/${top_level_name}_routed.dcp"

        if {![file exists $routed_dcp]} {
            puts "ERROR: Routed checkpoint not found: $routed_dcp"
            exit 1
        }

        puts "Opening routed checkpoint for $run_name: $routed_dcp"
        open_checkpoint $routed_dcp
        set bitstream_path "${root_dir}/vivado_proj/firesim_${run_name}.bit"
        puts "Writing full bitstream for $run_name..."
        write_bitstream -force $bitstream_path
        close_design
        puts "  Wrote: $bitstream_path"
    }

    # Use the first bitstream as the main firesim.bit
    set first_bit "${root_dir}/vivado_proj/firesim_[lindex $rm_runs 0].bit"
    set firesim_bit_path "${root_dir}/vivado_proj/firesim.bit"
    file copy -force $first_bit $firesim_bit_path
    puts "Copied bitstream to: $firesim_bit_path"

    write_cfgmem -force -format mcs -interface SPIx4 -size 1024 \
        -loadbit "up 0x01002000 ${firesim_bit_path}" -verbose ${root_dir}/vivado_proj/firesim.mcs
}

# Timing summary
set total_time [expr {[clock seconds] - $script_start_time}]
puts "=========================================="
puts "BUILD TIMING SUMMARY (PR RM MODE)"
puts "=========================================="
foreach timing_entry $timing_log {
    puts [format "  %-35s %s" [lindex $timing_entry 0] [format_time [lindex $timing_entry 1]]]
}
puts "=========================================="
puts [format "  %-35s %s" "TOTAL BUILD TIME" [format_time $total_time]]
puts "=========================================="

puts "Done!"
exit 0
