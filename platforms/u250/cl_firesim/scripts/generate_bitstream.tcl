create_project -force firesim ./vivado_proj -part xcu250-figd2104-2L-e
set_property board_part xilinx.com:au250:part0:1.3 [current_project]

add_files {./design/axi_tieoff_master.v ./design/firesim_wrapper.v ./design/firesim_top.sv}

if {![file exists ./scripts/create_bd_[version -short].tcl]} {
    puts "ERROR: current platform definition does not support your vivado version"
    exit 1
}

add_files {./design/firesim_defines.vh}
set_property IS_GLOBAL_INCLUDE 1 [get_files ./design/firesim_defines.vh]

source ./scripts/firesim_env.tcl
source ./scripts/create_bd_[version -short].tcl

make_wrapper -files [get_files ./vivado_proj/firesim.srcs/sources_1/bd/design_1/design_1.bd] -top

add_files -norecurse ./vivado_proj/firesim.gen/sources_1/bd/design_1/hdl/design_1_wrapper.v

if {[file exists ./design/firesim_synth.xdc]} {
    create_fileset -constrset synth
    add_files -fileset synth -norecurse ./design/firesim_synth.xdc
}

if {[file exists ./design/firesim_impl.xdc]} {
    create_fileset -constrset impl
    add_files -fileset impl -norecurse ./design/firesim_impl.xdc
}

update_compile_order -fileset sources_1
set_property top design_1_wrapper [current_fileset]
update_compile_order -fileset sources_1

if {[file exists ./design/firesim_synth.xdc]} {
    set_property constrset synth [get_runs synth_1]
}

if {[file exists ./design/firesim_impl.xdc]} {
    set_property constrset impl [get_runs impl_1]
}

launch_runs synth_1 -jobs 6
wait_on_run synth_1

if {[get_property PROGRESS [get_runs synth_1]] != "100%"} {
    puts "ERROR: synth_1 failed"
    exit 1
}

launch_runs impl_1 -to_step write_bitstream -jobs 6
wait_on_run impl_1

if {[get_property PROGRESS [get_runs impl_1]] != "100%"} {
    puts "ERROR: impl_1 failed"
    exit 1
}
