set root_dir [pwd]
set vivado_version [version -short]
set vivado_version_major [string range $vivado_version 0 3]

set ifrequency           [lindex $argv 0]
set istrategy            [lindex $argv 1]
set iboard               [lindex $argv 2]

proc retrieveVersionedFile {filename version} {
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

check_file_exists [set sourceFile [retrieveVersionedFile ${root_dir}/scripts/platform_env.tcl $vivado_version]]
source $sourceFile

check_file_exists [set sourceFile [retrieveVersionedFile ${root_dir}/scripts/${iboard}.tcl $vivado_version]]
source $sourceFile

# Cleanup
delete_files [list ${root_dir}/vivado_proj/firesim.bit]

create_project -force firesim ${root_dir}/vivado_proj -part $part
set_property board_part $board_part [current_project]

# Loading all the verilog files
foreach addFile [list ${root_dir}/design/axi_tieoff_master.v ${root_dir}/design/firesim_wrapper.v ${root_dir}/design/FireSim-generated.sv ${root_dir}/design/FireSim-generated.defines.vh] {
  set addFile [retrieveVersionedFile $addFile $vivado_version]
  check_file_exists $addFile
  add_files $addFile
  if {[file extension $addFile] == ".vh"} {
    set_property IS_GLOBAL_INCLUDE 1 [get_files $addFile]
  }
}

set desired_host_frequency $ifrequency
set strategy $istrategy

# Loading create_bd.tcl
check_file_exists [set sourceFile [retrieveVersionedFile ${root_dir}/scripts/create_bd_${vivado_version}.tcl $vivado_version]]
source $sourceFile

# Making bd wrapper
make_wrapper -files [get_files ${root_dir}/vivado_proj/firesim.srcs/sources_1/bd/design_1/design_1.bd] -top
add_files -norecurse ${root_dir}/vivado_proj/firesim.gen/sources_1/bd/design_1/hdl/design_1_wrapper.v
update_compile_order -fileset sources_1

# Report if any IPs need to be updated
report_ip_status

# Adding additional constraint sets
create_fileset -constrset synth_fileset
create_fileset -constrset impl_fileset

if {[file exists [set constrFile [retrieveVersionedFile ${root_dir}/design/FireSim-generated.synthesis.xdc $vivado_version]]]} {
    add_files -fileset synth_fileset -norecurse $constrFile
}

if {[file exists [set constrFile [retrieveVersionedFile ${root_dir}/design/FireSim-generated.implementation.xdc $vivado_version]]]} {
    # add impl clock to top of xdc
    if {[catch {exec sed -i "1i create_generated_clock -name host_clock \[get_pins design_1_i/clk_wiz_0/inst/mmcme4_adv_inst/CLKOUT0\]\\n" ${constrFile}}]} {
        puts "ERROR: Updating ${constrFile} failed ($result)"
    }
    add_files -fileset impl_fileset -norecurse $constrFile
}


if {[file exists [set constrFile [retrieveVersionedFile ${root_dir}/design/bitstream_config.xdc $vivado_version]]]} {
    add_files -fileset impl_fileset -norecurse $constrFile
}

update_compile_order -fileset sources_1
set_property top design_1_wrapper [current_fileset]
update_compile_order -fileset sources_1

if {[llength [get_filesets -quiet synth_fileset]]} {
    set_property constrset synth_fileset [get_runs synth_1]
} else {
    delete_fileset synth_fileset
}

if {[llength [get_filesets -quiet impl_fileset]]} {
    set_property constrset impl_fileset [get_runs impl_1]
} else {
    delete_fileset impl_fileset
}

set rpt_dir ${root_dir}/vivado_proj/reports
file mkdir ${rpt_dir}

# Set synth/impl strategy vars
check_file_exists [set sourceFile ${root_dir}/scripts/strategies/strategy_${strategy}.tcl]
source $sourceFile

# Run synth/impl and generate collateral
foreach sourceFile [list ${root_dir}/scripts/synthesis.tcl ${root_dir}/scripts/post_synth.tcl ${root_dir}/scripts/implementation.tcl ${root_dir}/scripts/post_impl.tcl] {
  set sourceFile [retrieveVersionedFile $sourceFile $vivado_version]
  check_file_exists $sourceFile
  source $sourceFile
}

puts "Done!"
exit 0
