set root_dir [pwd]
set vivado_version [version -short]
set vivado_version_major [string range $vivado_version 0 3]

set ifrequency           [lindex $argv 0]
set istrategy            [lindex $argv 1]
set iboard               [lindex $argv 2]
# PR module name(s) - can be comma-separated list (new RM module names)
set pr_module_name_str   [lindex $argv 3]
# PR partition path(s) - can be comma-separated list
set pr_partition_path_str [lindex $argv 4]
# PR project path (existing .xpr to reuse)
set pr_project_path       [lindex $argv 5]
# PR partition module name(s) - module names used in original main_pr.tcl to name
# the partition defs (pr_partition_<name>). If empty, falls back to pr_module_names.
set pr_partition_module_name_str [lindex $argv 6]

# Parse comma-separated lists into TCL lists
# Split on commas and trim whitespace
set pr_module_names {}
foreach name [split $pr_module_name_str ","] {
    lappend pr_module_names [string trim $name]
}

set pr_partition_paths {}
foreach path [split $pr_partition_path_str ","] {
    lappend pr_partition_paths [string trim $path]
}

# Build pr_partition_module_names: used for partition def lookup.
# Falls back to pr_module_names when not provided (backward compatible).
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

# Validate that we have matching counts
if {[llength $pr_module_names] != [llength $pr_partition_paths]} {
    puts "ERROR: Number of PR module names ([llength $pr_module_names]) does not match number of partition paths ([llength $pr_partition_paths])"
    exit 1
}

puts "PR Configuration:"
puts "  Number of PR modules: [llength $pr_module_names]"
for {set i 0} {$i < [llength $pr_module_names]} {incr i} {
    puts "  Module [expr {$i + 1}]: [lindex $pr_module_names $i]"
    puts "    Partition path: [lindex $pr_partition_paths $i]"
}

# Reuse an existing project
if {$pr_project_path eq ""} {
    puts "ERROR: pr_project_path must be provided for main_pr_rm.tcl"
    exit 1
}
if {![file exists $pr_project_path]} {
    puts "ERROR: PR project path does not exist: $pr_project_path"
    exit 1
}
puts "Using existing PR project from: $pr_project_path"

# Copy split-verilog directory from current build to project location
# The project path is something like /path/to/old_build/vivado_proj/firesim.xpr
# We need to copy split-verilog to /path/to/old_build/design/split-verilog
set project_dir [file dirname $pr_project_path]
# Go up one level from vivado_proj to get the build root
set project_build_root [file dirname $project_dir]
set src_split_verilog "${root_dir}/design/split-verilog"
set dst_split_verilog "${project_build_root}/design/split-verilog"

if {[file exists $src_split_verilog] && [file isdirectory $src_split_verilog]} {
    puts "Copying split-verilog from $src_split_verilog to $dst_split_verilog"
    # Remove existing split-verilog directory if it exists
    if {[file exists $dst_split_verilog]} {
        file delete -force $dst_split_verilog
    }
    file copy -force $src_split_verilog $dst_split_verilog
    puts "Successfully copied split-verilog directory"
} else {
    puts "WARNING: Source split-verilog directory does not exist: $src_split_verilog"
}

open_project $pr_project_path

# The project's source file references may point to a stale build directory
# (the original firesim-builds/ path that was cleaned up after rsync).
# Re-add source files from the current build so Vivado can resolve modules.
puts "Updating source file references from current build..."

# Remove stale source files that no longer exist on disk
foreach f [get_files -quiet -of_objects [get_filesets sources_1]] {
    if {![file exists $f]} {
        puts "  Removing stale file reference: $f"
        remove_files -quiet $f
    }
}

# Add source files from the current build
set split_verilog_dir ${root_dir}/design/split-verilog
if {[file exists $split_verilog_dir] && [file isdirectory $split_verilog_dir]} {
    set split_files [glob -nocomplain -directory $split_verilog_dir *.sv]
    if {[llength $split_files] > 0} {
        foreach splitFile $split_files {
            add_files -quiet $splitFile
        }
        puts "  Added [llength $split_files] split-verilog files from $split_verilog_dir"
    }
}
update_compile_order -fileset sources_1
puts "Source files updated."

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

proc retrieveVersionedFile { filename version } {
  set first [file rootname $filename]
  set last [file extension $filename]
  if {[file exists ${first}_${version}${last}]} {
    return ${first}_${version}${last}
  }
  return $filename
}

# Load strategy settings for RM implementation runs
set sourceFile ${project_scripts_dir}/strategies/strategy_${istrategy}.tcl
if {![file exists $sourceFile]} {
    puts "ERROR: Strategy file not found: $sourceFile"
    exit 1
}
source $sourceFile

set phase_start [clock seconds]

# Find the next available index for reconfig modules and impl runs
# (previous main_pr or main_pr_rm runs may have already created some)
set rm_next_idx 0
foreach existing_rm [get_reconfig_modules -quiet] {
    if {[regexp {^pr_reconfig_module_(\d+)$} $existing_rm -> idx]} {
        if {$idx >= $rm_next_idx} { set rm_next_idx [expr {$idx + 1}] }
    }
}
set impl_next_idx 0
foreach existing_run [get_runs -quiet impl_rm_*] {
    if {[regexp {^impl_rm_(\d+)$} $existing_run -> idx]} {
        if {$idx >= $impl_next_idx} { set impl_next_idx [expr {$idx + 1}] }
    }
}
puts "Next available indices: reconfig_module=$rm_next_idx, impl_rm=$impl_next_idx"

# Create reconfig modules for each PR module
# Handle duplicate module names by reusing the partition_def name (pr_partition_<module>)
# but generating unique reconfig module/run names per partition instance.
set rm_runs {}
set rm_synth_runs {}
set unique_modules {}
set module_to_partition_def [dict create]
for {set i 0} {$i < [llength $pr_module_names]} {incr i} {
    set pr_module_name           [lindex $pr_module_names $i]
    set pr_partition_module_name [lindex $pr_partition_module_names $i]
    set pr_partition_path        [lindex $pr_partition_paths $i]

    # Partition definitions were named pr_partition_<original_module> in main_pr.tcl.
    # Use pr_partition_module_name (which may differ from pr_module_name) for lookup.
    set partition_def_name "pr_partition_${pr_partition_module_name}"
    if {[lsearch -exact $unique_modules $pr_module_name] == -1} {
        lappend unique_modules $pr_module_name
        dict set module_to_partition_def $pr_module_name $partition_def_name
    }

    set rm_idx [expr {$rm_next_idx + $i}]
    set reconfig_module_name "pr_reconfig_module_${rm_idx}"

    puts "Creating reconfig module '$reconfig_module_name' (-define_from '$pr_module_name') for partition '$pr_partition_path' using partition def '$partition_def_name'"

    create_reconfig_module -name $reconfig_module_name \
        -partition_def [get_partition_defs $partition_def_name] \
        -define_from $pr_module_name

    # Collect the OOC synthesis run created by create_reconfig_module
    set synth_run_name "${reconfig_module_name}_synth_1"
    if {[llength [get_runs -quiet $synth_run_name]] > 0} {
        # Apply synthesis strategy
        set_property -dict [ list \
            STEPS.SYNTH_DESIGN.ARGS.DIRECTIVE ${synth_directive} \
            {STEPS.SYNTH_DESIGN.ARGS.MORE OPTIONS} "${synth_options}" \
        ] [get_runs $synth_run_name]
        lappend rm_synth_runs $synth_run_name
        puts "  OOC synthesis run: $synth_run_name"
    } else {
        puts "  WARNING: No OOC synthesis run found for $reconfig_module_name"
    }

    # Create a child implementation run for this reconfig module
    set impl_idx [expr {$impl_next_idx + $i}]
    set run_name "impl_rm_${impl_idx}"
    create_run $run_name -parent_run impl_1 -flow {Vivado Implementation 2023} -rm_instance ${pr_partition_path}:$reconfig_module_name

    # Apply strategy settings to the RM run
    set_property -dict [ list \
        STEPS.OPT_DESIGN.IS_ENABLED $opt \
        STEPS.OPT_DESIGN.DIRECTIVE $opt_directive \
        STEPS.PLACE_DESIGN.DIRECTIVE $place_directive \
        STEPS.PHYS_OPT_DESIGN.IS_ENABLED $phys_opt \
        STEPS.PHYS_OPT_DESIGN.DIRECTIVE $phys_directive \
        STEPS.ROUTE_DESIGN.DIRECTIVE $route_directive \
    ] [get_runs $run_name]

    lappend rm_runs $run_name
}

# Delete all default report configs from synth and impl runs to reduce build time.
foreach run [concat $rm_synth_runs $rm_runs] {
    foreach rc [get_report_configs -of_objects [get_runs $run] -quiet] {
        delete_report_config $rc
    }
}

set phase_start [log_timing "RM setup" $phase_start]

# Step 1: OOC synthesis of new RM modules
if {[llength $rm_synth_runs] > 0} {
    puts "Launching RM OOC synthesis: $rm_synth_runs with jobs=$jobs"
    launch_runs $rm_synth_runs -jobs $jobs
    wait_on_runs $rm_synth_runs

    foreach run_name $rm_synth_runs {
        set run_progress [get_property PROGRESS [get_runs $run_name]]
        puts "Synthesis run $run_name: progress=$run_progress"
        if {$run_progress ne "100%"} {
            puts "ERROR: OOC synthesis run $run_name failed"
            exit 1
        }
    }
}

set phase_start [log_timing "RM OOC synthesis" $phase_start]

# Step 2: Implementation (through route) for new RM modules
if {[llength $rm_runs] > 0} {
    puts "Launching RM implementation (through route): $rm_runs with jobs=$jobs"
    launch_runs $rm_runs -to_step route_design -jobs $jobs
    wait_on_runs $rm_runs

    foreach run_name $rm_runs {
        set run_status [get_property STATUS [get_runs $run_name]]
        set run_progress [get_property PROGRESS [get_runs $run_name]]
        puts "Run $run_name: status=$run_status progress=$run_progress"
        if {$run_progress ne "100%"} {
            puts "ERROR: RM route for $run_name did not complete"
            exit 1
        }
    }

    # Check timing across all RM runs
    set worst_wns 999.0
    foreach run_name $rm_runs {
        set run_wns [get_property STATS.WNS [get_runs $run_name]]
        puts "  $run_name WNS: ${run_wns} ns"
        if {$run_wns < $worst_wns} { set worst_wns $run_wns }
    }
    puts "  Worst WNS across RM runs: ${worst_wns} ns"

    # Adjust MMCM frequency and write bitstream for each RM run independently.
    # Each bitstream gets the optimal frequency for its RM configuration.
    set top_level_name overall_fpga_top
    set orig_project_dir [file dirname $pr_project_path]
    set adjusted_freq ""

    foreach run_name $rm_runs {
        set run_dir [get_property DIRECTORY [get_runs $run_name]]
        set bitstream_path "${run_dir}/${top_level_name}.bit"
        set freq [adjust_frequency_and_bitstream $run_name $bitstream_path]
        if {$freq eq ""} {
            puts "ERROR: Frequency adjustment failed for $run_name"
            exit 1
        }
        if {$adjusted_freq eq ""} { set adjusted_freq $freq }
    }
    puts "INFO: Requested frequency: ${ifrequency} MHz -> Actual frequency (RM): ${adjusted_freq} MHz"
}

set phase_start [log_timing "RM implementation" $phase_start]

# Copy the bitstream to the current build's vivado_proj/firesim.bit
# (the deploy system expects it at ${root_dir}/vivado_proj/firesim.bit)
# The RM run directory is inside the original project's firesim.runs/
set top_level_name overall_fpga_top
set orig_project_dir [file dirname $pr_project_path]

set first_run [lindex $rm_runs 0]
set run_dir "${orig_project_dir}/firesim.runs/${first_run}"
set rm_bit_path "${run_dir}/${top_level_name}.bit"

if {![file exists $rm_bit_path]} {
    set rm_bit_files [glob -nocomplain -directory $run_dir *.bit]
    if {[llength $rm_bit_files] > 0} {
        set rm_bit_path [lindex $rm_bit_files 0]
        puts "Found bitstream at: $rm_bit_path"
    } else {
        puts "ERROR: No bitstream found in $run_dir"
        puts "Contents of run directory:"
        foreach f [glob -nocomplain -directory $run_dir *] {
            puts "  [file tail $f]"
        }
        exit 1
    }
}

# Copy to current build's vivado_proj/ where the deploy system expects it
file mkdir ${root_dir}/vivado_proj
set firesim_bit_path "${root_dir}/vivado_proj/firesim.bit"
file copy -force $rm_bit_path $firesim_bit_path
puts "Copied bitstream to: $firesim_bit_path"

write_cfgmem -force -format mcs -interface SPIx4 -size 1024 \
    -loadbit "up 0x01002000 ${firesim_bit_path}" -verbose ${root_dir}/vivado_proj/firesim.mcs
puts "Generated MCS: ${root_dir}/vivado_proj/firesim.mcs"

# If multiple RM runs, copy all bitstreams with descriptive names
if {[llength $rm_runs] > 1} {
    puts "Multiple RM bitstreams generated:"
    for {set i 0} {$i < [llength $rm_runs]} {incr i} {
        set run_name [lindex $rm_runs $i]
        set run_dir "${orig_project_dir}/firesim.runs/${run_name}"
        set bit_files [glob -nocomplain -directory $run_dir *.bit]
        foreach bf $bit_files {
            set dest "${root_dir}/vivado_proj/firesim_${run_name}.bit"
            file copy -force $bf $dest
            puts "  $run_name -> $dest"
        }
    }
}

set phase_start [log_timing "Bitstream generation" $phase_start]

# Print timing summary
set total_time [expr {[clock seconds] - $script_start_time}]
puts "=========================================="
puts "BUILD TIMING SUMMARY (PR RM MODE)"
puts "=========================================="
foreach timing_entry $timing_log {
    set phase_name [lindex $timing_entry 0]
    set elapsed [lindex $timing_entry 1]
    puts [format "  %-30s %s" $phase_name [format_time $elapsed]]
}
puts "=========================================="
puts [format "  %-30s %s" "TOTAL BUILD TIME" [format_time $total_time]]
puts "=========================================="

puts "Done!"
exit 0