set root_dir [pwd]
set vivado_version [version -short]
set vivado_version_major [string range $vivado_version 0 3]

set ifrequency           [lindex $argv 0]
set istrategy            [lindex $argv 1]
set iboard               [lindex $argv 2]

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
puts "Starting build timing tracking"
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
set phase_start [log_timing "Initialization and project creation" $phase_start]

# Loading all the verilog files
foreach addFile [list \
    ${root_dir}/design/axi_tieoff_master.v \
    ${root_dir}/design/axi.vh \
    ${root_dir}/design/helpers.vh \
    ${root_dir}/design/overall_fpga_top.v \
    ${root_dir}/design/FireSim-generated.sv \
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

# Set synth/impl strategy vars
check_file_exists [set sourceFile ${root_dir}/scripts/strategies/strategy_${strategy}.tcl]
source $sourceFile

# Delete all default report configs to reduce build time.
# We generate utilization and timing reports manually in post_synth/post_impl scripts.
foreach run [get_runs] {
    foreach rc [get_report_configs -of_objects [get_runs $run] -quiet] {
        delete_report_config $rc
    }
}

# Run synth/impl and generate collateral
set sourceFile [retrieveVersionedFile ${root_dir}/scripts/synthesis.tcl $vivado_version]
check_file_exists $sourceFile
source $sourceFile
set phase_start [log_timing "Synthesis" $phase_start]

set sourceFile [retrieveVersionedFile ${root_dir}/scripts/post_synth.tcl $vivado_version]
check_file_exists $sourceFile
source $sourceFile
set phase_start [log_timing "Post-synthesis" $phase_start]

set sourceFile [retrieveVersionedFile ${root_dir}/scripts/implementation.tcl $vivado_version]
check_file_exists $sourceFile
source $sourceFile
set phase_start [log_timing "Implementation" $phase_start]

set sourceFile [retrieveVersionedFile ${root_dir}/scripts/post_impl.tcl $vivado_version]
check_file_exists $sourceFile
source $sourceFile
set phase_start [log_timing "Post-implementation" $phase_start]

# Print timing summary
set total_time [expr {[clock seconds] - $script_start_time}]
puts "=========================================="
puts "BUILD TIMING SUMMARY"
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
puts $timing_file "BUILD TIMING SUMMARY"
puts $timing_file "===================="
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
