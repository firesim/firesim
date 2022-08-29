variable root_dir [pwd]

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

foreach path [glob -nocomplain ${root_dir}/vivado_proj/*.dcp] {
    file delete -force -- $path
}

reset_runs synth_1
launch_runs synth_1 -jobs 6
wait_on_run synth_1

if {[get_property PROGRESS [get_runs synth_1]] != "100%"} {
    puts "ERROR: synthesis failed"
    exit 1
}

variable i 0
variable opt_design_directives {Default Explore Explore Explore Explore Explore Explore}
variable place_design_directives {Default Explore Explore Explore Explore ExtraTimingOpt ExtraPostPlacementOpt}
variable phys_opt_design_directives {Default AggressiveExplore Explore AggressiveFanoutOpt AlternateFlowWithRetiming AggressiveExplore AggressiveExplore}
variable route_design_directives {Default AggressiveExplore Explore AggressiveExplore AggressiveExplore AggressiveExplore AggressiveExplore}
variable post_route_phys_opt_design_directives {Default AggressiveExplore Explore AggressiveExplore AggressiveExplore AggressiveExplore AggressiveExplore}
variable WNS -1
variable WHS -1

set_property AUTO_INCREMENTAL_CHECKPOINT 1 [get_runs impl_1]
set_property AUTO_INCREMENTAL_CHECKPOINT.DIRECTORY ${root_dir}/vivado_proj [get_runs impl_1]
set_property incremental_checkpoint.directive TimingClosure [get_runs impl_1]

while {($i < [llength $opt_design_directives]) && ($WNS < 0 || $WHS < 0)} {
    set_property STEPS.OPT_DESIGN.ARGS.DIRECTIVE [lindex $opt_design_directives $i] [get_runs impl_1]
    set_property STEPS.PLACE_DESIGN.ARGS.DIRECTIVE [lindex $place_design_directives $i] [get_runs impl_1]
    set_property STEPS.ROUTE_DESIGN.ARGS.DIRECTIVE [lindex $route_design_directives $i] [get_runs impl_1]
    set_property STEPS.PHYS_OPT_DESIGN.ARGS.DIRECTIVE [lindex $phys_opt_design_directives $i] [get_runs impl_1]
    set_property STEPS.POST_ROUTE_PHYS_OPT_DESIGN.ARGS.DIRECTIVE [lindex $post_route_phys_opt_design_directives $i] [get_runs impl_1]

    reset_runs impl_1
    launch_runs impl_1 -to_step route_design -jobs 6
    wait_on_run impl_1

    if {[get_property PROGRESS [get_runs impl_1]] != "100%"} {
        puts "ERROR: implementation failed"
        exit 1
    }

    set i [expr $i + 1]
    set WNS [get_property STATS.WNS [get_runs impl_1]]
    set WHS [get_property STATS.WHS [get_runs impl_1]]
}

if {$WNS < 0 || $WHS < 0} {
    puts "ERROR: did not meet timing!"
    exit 1
}

launch_runs impl_1 -next_step -jobs 6
wait_on_run impl_1

if {[get_property PROGRESS [get_runs impl_1]] != "100%"} {
    puts "ERROR: implementation failed"
    exit 1
}


