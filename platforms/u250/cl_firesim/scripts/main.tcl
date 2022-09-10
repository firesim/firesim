variable root_dir [pwd]
variable jobs 12

# Cleanup
foreach path [list ${root_dir}/vivado_proj/firesim.bit] {
    if {[file exists ${path}]} {
        file delete -force -- ${path}
    }
}

create_project -force firesim ${root_dir}/vivado_proj -part xcu250-figd2104-2L-e
set_property board_part xilinx.com:au250:part0:1.3 [current_project]

add_files [list ${root_dir}/design/axi_tieoff_master.v ${root_dir}/design/firesim_wrapper.v ${root_dir}/design/firesim_top.sv]

if {![file exists ${root_dir}/scripts/create_bd_[version -short].tcl]} {
    puts "ERROR: current platform definition does not support your vivado version"
    exit 1
}

add_files [list ${root_dir}/design/firesim_defines.vh]
set_property IS_GLOBAL_INCLUDE 1 [get_files ${root_dir}/design/firesim_defines.vh]

source ${root_dir}/scripts/firesim_env.tcl
source ${root_dir}/scripts/create_bd_[version -short].tcl

make_wrapper -files [get_files ${root_dir}/vivado_proj/firesim.srcs/sources_1/bd/design_1/design_1.bd] -top

add_files -norecurse ${root_dir}/vivado_proj/firesim.gen/sources_1/bd/design_1/hdl/design_1_wrapper.v

if {[file exists ${root_dir}/design/firesim_synth.xdc]} {
    create_fileset -constrset synth
    add_files -fileset synth -norecurse ${root_dir}/design/firesim_synth.xdc
}

if {[file exists ${root_dir}/design/firesim_impl.xdc]} {
    create_fileset -constrset impl
    add_files -fileset impl -norecurse ${root_dir}/design/firesim_impl.xdc
}

update_compile_order -fileset sources_1
set_property top design_1_wrapper [current_fileset]
update_compile_order -fileset sources_1

if {[file exists ${root_dir}/design/firesim_synth.xdc]} {
    set_property constrset synth [get_runs synth_1]
}

if {[file exists ${root_dir}/design/firesim_impl.xdc]} {
    set_property constrset impl [get_runs impl_1]
}

if {! [file exists ${root_dir}/scripts/synthesis.tcl]} {
	puts "ERROR: no ${root_dir}scripts/synthesis.tcl found"
	exit 1
}

source ${root_dir}/scripts/synthesis.tcl

if {! [file exists ${root_dir}/scripts/implementation.tcl]} {
	puts "ERROR: no ${root_dir}/scripts/implementation.tcl found"
	exit 1
}

source ${root_dir}/scripts/implementation.tcl

puts "Done!"
exit 0
