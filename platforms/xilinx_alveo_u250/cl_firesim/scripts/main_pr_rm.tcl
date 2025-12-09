set root_dir [pwd]
set vivado_version [version -short]
set vivado_version_major [string range $vivado_version 0 3]

set ifrequency           [lindex $argv 0]
set istrategy            [lindex $argv 1]
set iboard               [lindex $argv 2]
# PR module name(s) - can be comma-separated list
set pr_module_name_str   [lindex $argv 3]
# PR partition path(s) - can be comma-separated list
set pr_partition_path_str [lindex $argv 4]
# PR project path (existing .xpr to reuse)
set pr_project_path       [lindex $argv 5]

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
update_compile_order -fileset sources_1

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

# Create reconfig modules for each PR module
# Handle duplicate module names by reusing the partition_def name (pr_partition_<module>)
# but generating unique reconfig module/run names per partition instance.
set rm_runs {}
set unique_modules {}
set module_to_partition_def [dict create]
for {set i 0} {$i < [llength $pr_module_names]} {incr i} {
    set pr_module_name    [lindex $pr_module_names $i]
    set pr_partition_path [lindex $pr_partition_paths $i]

    # Partition definitions were named pr_partition_<module> in main_pr.tcl
    set partition_def_name "pr_partition_${pr_module_name}"
    if {[lsearch -exact $unique_modules $pr_module_name] == -1} {
        lappend unique_modules $pr_module_name
        dict set module_to_partition_def $pr_module_name $partition_def_name
    }

    set reconfig_module_name "pr_reconfig_module_${i}"

    puts "Creating reconfig module '$reconfig_module_name' for partition '$pr_partition_path' using partition def '$partition_def_name'"

    create_reconfig_module -name $reconfig_module_name \
        -partition_def [get_partition_defs $partition_def_name] \
        -define_from $pr_module_name

    # Create a child implementation run for this reconfig module
    set run_name "impl_rm_${i}"
    create_run $run_name -parent_run impl_1 -flow {Vivado Implementation 2023} -rm_instance ${pr_partition_path}:$reconfig_module_name
    lappend rm_runs $run_name
}

# Launch all RM runs together to write bitstreams
if {[llength $rm_runs] > 0} {
    puts "Launching RM runs: $rm_runs with jobs=$jobs"
    launch_runs $rm_runs -jobs $jobs
    wait_on_runs $rm_runs
}

# # Assemble full device image (static + RMs) using link_design per UG909 guidance
# set part [get_property PART [current_project]]
# set top_level_name overall_fpga_top
# set device_image_path "${root_dir}/vivado_proj/reports/firesim_device_image"

# # Paths to routed checkpoints
# set static_dcp "${root_dir}/vivado_proj/firesim.runs/impl_1/impl_1.dcp"
# if {![file exists $static_dcp]} {
#     puts "ERROR: Static checkpoint not found: $static_dcp"
#     exit 1
# }

# set rm_dcp_map {}
# for {set i 0} {$i < [llength $rm_runs]} {incr i} {
#     set run_name [lindex $rm_runs $i]
#     set rm_dcp "${root_dir}/vivado_proj/firesim.runs/${run_name}/${run_name}.dcp"
#     if {![file exists $rm_dcp]} {
#         puts "ERROR: RM checkpoint not found: $rm_dcp"
#         exit 1
#     }
#     set pr_partition_path [lindex $pr_partition_paths $i]
#     lappend rm_dcp_map [list $pr_partition_path $rm_dcp]
# }

# puts "Linking design for full bitstream using link_design..."
# close_design -quiet
# link_design -mode pr -part $part -top $top_level_name -reconfig_partitions $rm_dcp_map -static_nets -quiet

# puts "Writing full device bitstream (no partials): ${device_image_path}.bit"
# write_bitstream -force -no_partial_bitfile ${device_image_path}.bit
# create_reconfig_module -name mshrs_second_rm -partition_def [get_partition_defs prefetch_partition ]  -top mshrs_second
# add_files -norecurse -scan_for_includes /scratch/junhak/build_dir_separate_verilog-testing/platforms/xilinx_alveo_u250/cl_xilinx_alveo_u250-firesim-FireSim-WithDefaultFireSimBridges_WithFireSimTestChipConfigTweaks_chipyard.LargeBoomV3Config-WithAutoILA_FRFCFS16GBQuadRankLLC4MB_BaseXilinxAlveoU250Config/vivado_proj/firesim.srcs/sources_1/new/mshrs_second.sv  -of_objects [get_reconfig_modules mshrs_second_rm]
# create_run impl_2 -parent_run impl_1 -flow {Vivado Implementation 2023} -rm_instance firesim_top/top/sim/target/FireSim_/chiptop0/system/tile_prci_domain/element_reset_domain_boom_tile/dcache/mshrs:mshrs_second_rm
# create_run impl_3 -parent_run impl_1 -flow {Vivado Implementation 2023} -rm_instance firesim_top/top/sim/target/FireSim_/chiptop0/system/tile_prci_domain/element_reset_domain_boom_tile/dcache/mshrs:prefetch_reconfig_module_1
# launch_runs impl_2 -jobs 4