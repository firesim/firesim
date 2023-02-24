# See LICENSE for license details.

################################################################################
# This file can be used to synthesize the `FPGATop` module of a design. It
# emulates the strategy & frequency that the AWS flows might use.
################################################################################

################################################################################
# Command-line Arguments
################################################################################

set strategy                [lindex $argv  0]
set desired_host_frequency  [lindex $argv  1]
set output                  [lindex $argv  2]
set synth_xdc               [lindex $argv  3]
set firesim_xdc             [lindex $argv  4]
set firesim_sv              [lindex $argv  5]

set device_type             "xcvu9p-flgb2104-2-i"
set uram_option             2

################################################################################
# Read design files
################################################################################

if {$uram_option != 2} {
  set_param synth.elaboration.rodinMoreOptions {rt::set_parameter disableOregPackingUram true}
  set_param physynth.ultraRAMOptOutput false
}

# Maintain DONT TOUCH functionality for 2020.2 onwards
if {[string match *2020.2* [version -short]] || [string match *2021.* [version -short]]} {
  set_param project.replaceDontTouchWithKeepHierarchySoft false
}

switch $uram_option {
    "2" {
        set synth_uram_option "-max_uram_cascade_height 2"
        set uramHeight 2
    }
    "3" {
        set synth_uram_option "-max_uram_cascade_height 3"
        set uramHeight 3
    }
    "4" {
        set synth_uram_option "-max_uram_cascade_height 1"
        set uramHeight 4
    }
    default {
        set synth_uram_option "-max_uram_cascade_height 1"
        set uramHeight 4
    }
}

switch $strategy {
    "BASIC" {
        puts "BASIC strategy."
        set synth_options "-keep_equivalent_registers $synth_uram_option -retiming"
        set synth_directive "default"
    }
    "AREA" {
        puts "AREA strategy."
        set synth_options "$synth_uram_option -retiming"
        set synth_directive "AreaOptimized_high"
    }
    "EXPLORE" {
        puts "EXPLORE strategy."
        set synth_options "-keep_equivalent_registers -flatten_hierarchy rebuilt $synth_uram_option -retiming"
        set synth_directive "default"
    }
    "NORETIMING" {
        puts "NORETIMING strategy."
        set synth_options "-no_lc -shreg_min_size 5 -fsm_extraction one_hot -resource_sharing auto $synth_uram_option"
        set synth_directive "default"
    }
    "TIMING" {
        puts "TIMING strategy."
        set synth_options "-no_lc -shreg_min_size 5 -fsm_extraction one_hot -resource_sharing auto $synth_uram_option -retiming"
        set synth_directive "default"
    }
    "CONGESTION" {
        puts "CONGESTION strategy."
        set synth_options "-no_lc -shreg_min_size 10 -control_set_opt_threshold 16 $synth_uram_option -retiming"
        set synth_directive "AlternateRoutability"
    }
    "DEFAULT" {
        puts "DEFAULT strategy."
        set synth_options "-keep_equivalent_registers -flatten_hierarchy rebuilt $synth_uram_option -retiming"
        set synth_directive "default"
    }
    "QUICK" {
        puts "QUICK strategy."
        set synth_options "$synth_uram_option"
        set synth_directive "runtimeoptimized"
    }
    default {
        puts "$strategy is NOT a valid strategy."
        exit 1
    }
}

################################################################################
# Read design files
################################################################################

read_verilog -sv [list $firesim_sv]
read_xdc [list $firesim_xdc $synth_xdc]

# Ignore start/end module synthesis
set_msg_config -id {Synth 8-6155}        -suppress
set_msg_config -id {Synth 8-6157}        -suppress
# Ignore assertions
set_msg_config -id {Synth 8-2898}        -suppress
# Upgrade XDC 'Critical Warnings' to 'Errors'
set_msg_config -id {Common 17-55}        -new_severity {ERROR}

################################################################################
# CL Synthesis
################################################################################

update_compile_order -fileset sources_1

set_param general.maxThreads 1

eval [concat synth_design -top FPGATop -verilog_define XSDB_SLV_DIS -part $device_type -mode out_of_context $synth_options -directive $synth_directive]
set failval [catch {exec grep "FAIL" failfast.csv}]
if { $failval==0 } {
  puts "Synthesis failed"
  exit 1
}

write_checkpoint -force $output.dcp
write_verilog -force -mode funcsim $output
report_utilization -hierarchical -hierarchical_percentages -file $output.rpt

close_project
