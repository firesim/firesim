set root_dir [pwd]
set vivado_version [version -short]
set vivado_version_major [string range $vivado_version 0 3]

set ifrequency           [lindex $argv 0]
set istrategy            [lindex $argv 1]
set iboard               [lindex $argv 2]
# BoomMSHRFile
set pr_module_name       [lindex $argv 3]
# firesim_top/top/sim/target/FireSim_/chiptop0/system/tile_prci_domain/element_reset_domain_boom_tile/dcache/mshrs
set pr_partition_path    [lindex $argv 4]

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

# get utilities
source $root_dir/scripts/utils.tcl

puts "Running with Vivado $vivado_version (Major Version: $vivado_version_major)"
puts "=========================================="
puts "Starting build timing tracking (PR mode)"
puts "=========================================="

set phase_start [clock seconds]

check_file_exists [set sourceFile [retrieveVersionedFile ${root_dir}/scripts/platform_env.tcl $vivado_version]]
source $sourceFile

check_file_exists [set sourceFile [retrieveVersionedFile ${root_dir}/scripts/${iboard}.tcl $vivado_version]]
source $sourceFile

# Cleanup
delete_files [list ${root_dir}/vivado_proj/firesim.bit]

create_project -force firesim ${root_dir}/vivado_proj -part $part
set_property board_part $board_part [current_project]
set_property -name "pr_flow" -value "1" -objects [current_project]
set phase_start [log_timing "Initialization and project creation" $phase_start]


# Loading all the verilog files
set phase_start [log_timing "File loading" $phase_start]
foreach addFile [list \
    ${root_dir}/design/axi_tieoff_master.v \
    ${root_dir}/design/axi.vh \
    ${root_dir}/design/helpers.vh \
    ${root_dir}/design/overall_fpga_top.v \
    ${root_dir}/design/FireSim-generated.defines.vh \
    ${root_dir}/design/aurora/aurora_64b66b_0_driver.v \
    ${root_dir}/design/aurora/aurora_64b66b_0_cdc_sync_exdes.v \
    ${root_dir}/design/aurora/aurora_64b66b_0_utils.v \
] {
  set addFile [retrieveVersionedFile $addFile $vivado_version]
  check_file_exists $addFile
  add_files $addFile
  if {[file extension $addFile] == ".vh"} {
    set_property IS_GLOBAL_INCLUDE 1 [get_files $addFile]
  }
}

# Load split Verilog module files if they exist, otherwise fall back to single file
set split_verilog_dir ${root_dir}/design/split-verilog
if {[file exists $split_verilog_dir] && [file isdirectory $split_verilog_dir]} {
  puts "Loading split Verilog files from $split_verilog_dir"
  set split_files [glob -nocomplain -directory $split_verilog_dir *.sv]
  if {[llength $split_files] > 0} {
    foreach splitFile $split_files {
      set splitFile [retrieveVersionedFile $splitFile $vivado_version]
      check_file_exists $splitFile
      add_files $splitFile
    }
    puts "Loaded [llength $split_files] split Verilog module files"
  } else {
    puts "Warning: split-verilog directory exists but contains no .sv files, falling back to FireSim-generated.sv"
    set addFile [retrieveVersionedFile ${root_dir}/design/FireSim-generated.sv $vivado_version]
    check_file_exists $addFile
    add_files $addFile
  }
} else {
  puts "split-verilog directory not found, using FireSim-generated.sv"
  set addFile [retrieveVersionedFile ${root_dir}/design/FireSim-generated.sv $vivado_version]
  check_file_exists $addFile
  add_files $addFile
}
set phase_start [log_timing "File loading" $phase_start]

set desired_host_frequency $ifrequency
set strategy $istrategy

# Loading create_bd.tcl
check_file_exists [set sourceFile ${root_dir}/scripts/create_bd.tcl]
source $sourceFile

# Making wrapper around bd
generate_target all [get_files ${root_dir}/vivado_proj/firesim.srcs/sources_1/bd/design_1/design_1.bd]
update_compile_order -fileset sources_1
set phase_start [log_timing "Block design creation" $phase_start]

# Mark top-level name for future steps/cmds
set top_level_name overall_fpga_top

# Report if any IPs need to be updated
report_ip_status

# Adding additional constraint sets
create_fileset -constrset synth_fileset
create_fileset -constrset impl_fileset

if {[file exists [set constrFile [retrieveVersionedFile ${root_dir}/design/FireSim-generated.synthesis.xdc $vivado_version]]]} {
    # map L2 banks to URAMs if possible (might warn if cells not present)
    add_line_to_file 1 $constrFile "set_property RAM_STYLE ULTRA \[get_cells -hierarchical -regexp {.*firesim_top.*cc_banks_.*_reg.*}\]"
    add_files -fileset synth_fileset -norecurse $constrFile
}

if {[file exists [set constrFile [retrieveVersionedFile ${root_dir}/design/FireSim-generated.implementation.xdc $vivado_version]]]} {
    # add impl clock to top of xdc
    add_line_to_file 1 $constrFile "create_generated_clock -name host_clock \[get_pins design_1_i/clk_wiz_0/inst/mmcme4_adv_inst/CLKOUT0\]"
    add_files -fileset impl_fileset -norecurse $constrFile
}


if {[file exists [set constrFile [retrieveVersionedFile ${root_dir}/design/bitstream_config.xdc $vivado_version]]]} {
    add_files -fileset impl_fileset -norecurse $constrFile
}
set phase_start [log_timing "Constraint setup" $phase_start]

########################################################

set clk_wiz_instance [get_bd_cells clk_wiz_0]

# Get the actual output frequency (in MHz)
# Note: This property may not be available until after IP generation/validation
set actual_freq_mhz [get_property CONFIG.C_CLKOUT1_ACTUAL_FREQ $clk_wiz_instance -quiet]

# If actual frequency is not available yet, fall back to requested frequency
if {$actual_freq_mhz == "" || $actual_freq_mhz == 0} {
    puts "WARNING: Actual frequency not available yet, using requested frequency: ${desired_host_frequency} MHz"
    set actual_freq_mhz $desired_host_frequency
} else {
    puts "Actual frequency of clk_wiz_0: $actual_freq_mhz MHz"
}

# Validate frequency is valid before calculation
if {$actual_freq_mhz == "" || $actual_freq_mhz <= 0} {
    puts "ERROR: Invalid frequency value: $actual_freq_mhz"
    exit 1
}

# Calculate clock period in nanoseconds (period = 1000 / frequency_MHz)
set clock_period_ns [expr {1000.0 / $actual_freq_mhz}]

# Create blockset for PR module
set phase_start [log_timing "PR setup" $phase_start]
# Note: -define_from requires the module to exist in the current design
if {[catch {create_fileset -blockset -define_from $pr_module_name $pr_module_name} err]} {
    puts "ERROR: Failed to create blockset for $pr_module_name: $err"
    puts "Make sure the module '$pr_module_name' exists in your design"
    exit 1
}
file mkdir ${root_dir}/vivado_proj/firesim.srcs/$pr_module_name/new
close [ open ${root_dir}/vivado_proj/firesim.srcs/$pr_module_name/new/${pr_module_name}_ooc.xdc w ]
add_files -fileset $pr_module_name ${root_dir}/vivado_proj/firesim.srcs/$pr_module_name/new/${pr_module_name}_ooc.xdc

# Build the constraint file content with proper variable substitution
set data {# (c) Copyright 2014 Xilinx, Inc. All rights reserved.

# Add in a clock definition for each input clock to the out-of-context module.
# The module will be synthesized as top so reference the clock origin using get_ports.
# You will need to define a clock on each input clock port, no top level clock information
# is provided to the module when set as out-of-context.
# Here is an example:
# create_clock -name clk_200 -period 5 [get_ports clk]
create_clock -name user_clock -period $clock_period_ns [get_ports clock]
}

set filename "${root_dir}/vivado_proj/firesim.srcs/$pr_module_name/new/${pr_module_name}_ooc.xdc"
set fileId [open $filename "w"]
puts -nonewline $fileId $data
close $fileId
set_property USED_IN {out_of_context synthesis implementation}  [get_files  ${root_dir}/vivado_proj/firesim.srcs/$pr_module_name/new/${pr_module_name}_ooc.xdc]

set_property PR_FLOW 1 [current_project] 
delete_fileset [get_filesets $pr_module_name] -merge [current_fileset]
update_compile_order -fileset sources_1

# Create a partition for the prefetch region
create_partition_def -name prefetch_partition -module $pr_module_name

# Create a first reconfig module for the prefetch region
create_reconfig_module -name prefetch_reconfig_module_1 -partition_def [get_partition_defs prefetch_partition ]  -define_from $pr_module_name
update_compile_order -fileset prefetch_reconfig_module_1
create_pr_configuration -name config_1 -partitions [list $pr_partition_path:prefetch_reconfig_module_1 ]
set_property PR_CONFIGURATION config_1 [get_runs impl_1]
set_property DFX_MODE {ABSTRACT SHELL} [get_runs impl_1]

# Add more reconfig modules for the prefetch region here if needed
set phase_start [log_timing "PR setup" $phase_start] 


################################################################################



update_compile_order -fileset sources_1
set_property top $top_level_name [current_fileset]
update_compile_order -fileset sources_1

foreach f [get_files -of [get_filesets synth_fileset]] {
    set_property USED_IN {synthesis} $f
    set_property USED_IN_IMPLEMENTATION 0 $f
    set_property USED_IN_SYNTHESIS 1 $f
}

foreach f [get_files -of [get_filesets impl_fileset]] {
    set_property USED_IN {implementation} $f
    set_property USED_IN_IMPLEMENTATION 1 $f
    set_property USED_IN_SYNTHESIS 0 $f
    set_property PROCESSING_ORDER LATE $f
}

proc set_fileset_for_run_or_delete { fsname runname } {
    if {[llength [get_filesets -quiet $fsname]]} {
        set_property constrset $fsname [get_runs $runname]
    } else {
        delete_fileset $fsname
    }
}
set_fileset_for_run_or_delete synth_fileset synth_1
set_fileset_for_run_or_delete impl_fileset impl_1

set rpt_dir ${root_dir}/vivado_proj/reports
file mkdir ${rpt_dir}

# save_project_as -force ${root_dir}/vivado_proj/pre_synth.xpr

# Set synth/impl strategy vars
check_file_exists [set sourceFile ${root_dir}/scripts/strategies/strategy_${strategy}.tcl]
source $sourceFile

# Run synth and generate collateral
set sourceFile [retrieveVersionedFile ${root_dir}/scripts/synthesis.tcl $vivado_version]
check_file_exists $sourceFile
source $sourceFile
set phase_start [log_timing "Synthesis" $phase_start]

set sourceFile [retrieveVersionedFile ${root_dir}/scripts/post_synth_pr.tcl $vivado_version]
check_file_exists $sourceFile
source $sourceFile
set phase_start [log_timing "Post-synthesis (PR)" $phase_start]

################################################################################

# Run impl and generate collateral
set sourceFile [retrieveVersionedFile ${root_dir}/scripts/implementation.tcl $vivado_version]
check_file_exists $sourceFile
source $sourceFile
set phase_start [log_timing "Implementation" $phase_start]

set sourceFile [retrieveVersionedFile ${root_dir}/scripts/post_impl.tcl $vivado_version]
check_file_exists $sourceFile
source $sourceFile
set phase_start [log_timing "Post-implementation" $phase_start]

################################################################################

# Print timing summary
set total_time [expr {[clock seconds] - $script_start_time}]
puts "=========================================="
puts "BUILD TIMING SUMMARY (PR MODE)"
puts "=========================================="
foreach timing_entry $timing_log {
    set phase_name [lindex $timing_entry 0]
    set elapsed [lindex $timing_entry 1]
    puts [format "  %-30s %s" $phase_name [format_time $elapsed]]
}
puts "=========================================="
puts [format "  %-30s %s" "TOTAL BUILD TIME" [format_time $total_time]]
puts "=========================================="

# Write timing summary to file in reports directory
set timing_file [open ${rpt_dir}/build_timing_summary.txt w]
puts $timing_file "BUILD TIMING SUMMARY (PR MODE)"
puts $timing_file "=============================="
puts $timing_file ""
foreach timing_entry $timing_log {
    set phase_name [lindex $timing_entry 0]
    set elapsed [lindex $timing_entry 1]
    puts $timing_file [format "%-40s %.2f seconds" $phase_name $elapsed]
}
puts $timing_file ""
puts $timing_file [format "%-40s %.2f seconds" "TOTAL BUILD TIME" $total_time]
close $timing_file
puts "Timing summary written to ${rpt_dir}/build_timing_summary.txt"

puts "Done!"
exit 0
