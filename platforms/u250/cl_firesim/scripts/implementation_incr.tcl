# Cleanup
foreach path [glob -nocomplain ${root_dir}/vivado_proj/*.dcp] {
    file delete -force -- $path
}

variable impl_run [get_runs impl_1]
variable i 0
variable opt_design_directives {Default Explore Explore Explore Explore Explore Explore}
variable place_design_directives {Default Explore Explore Explore Explore ExtraTimingOpt ExtraPostPlacementOpt}
variable phys_opt_design_directives {Default AggressiveExplore Explore AggressiveFanoutOpt AlternateFlowWithRetiming AggressiveExplore AggressiveExplore}
variable route_design_directives {Default AggressiveExplore Explore AggressiveExplore AggressiveExplore AggressiveExplore AggressiveExplore}
variable post_route_phys_opt_design_directives {Default AggressiveExplore Explore AggressiveExplore AggressiveExplore AggressiveExplore AggressiveExplore}
variable WNS -1
variable WHS -1

set_property AUTO_INCREMENTAL_CHECKPOINT 1 ${impl_run}
set_property AUTO_INCREMENTAL_CHECKPOINT.DIRECTORY ${root_dir}/vivado_proj ${impl_run}
set_property incremental_checkpoint.directive TimingClosure ${impl_run}

while {($i < [llength $opt_design_directives]) && ($WNS < 0 || $WHS < 0)} {
    set_property STEPS.OPT_DESIGN.ARGS.DIRECTIVE [lindex $opt_design_directives $i] ${impl_run}
    set_property STEPS.PLACE_DESIGN.ARGS.DIRECTIVE [lindex $place_design_directives $i] ${impl_run}
    set_property STEPS.ROUTE_DESIGN.ARGS.DIRECTIVE [lindex $route_design_directives $i] ${impl_run}
    set_property STEPS.PHYS_OPT_DESIGN.ARGS.DIRECTIVE [lindex $phys_opt_design_directives $i] ${impl_run}
    set_property STEPS.POST_ROUTE_PHYS_OPT_DESIGN.ARGS.DIRECTIVE [lindex $post_route_phys_opt_design_directives $i] ${impl_run}

    reset_runs ${impl_run}
    launch_runs ${impl_run} -to_step route_design -jobs ${jobs}
    wait_on_run ${impl_run}

    if {[get_property PROGRESS ${impl_run}] != "100%"} {
        puts "ERROR: implementation failed"
        exit 1
    }

    set i [expr $i + 1]
    set WNS [get_property STATS.WNS ${impl_run}]
    set WHS [get_property STATS.WHS ${impl_run}]
}

if {$WNS < 0 || $WHS < 0} {
    puts "ERROR: did not meet timing!"
    exit 1
}

launch_runs ${impl_run} -next_step -jobs ${jobs}
wait_on_run ${impl_run}

if {[get_property PROGRESS ${impl_run}] != "100%"} {
    puts "ERROR: implementation failed"
    exit 1
}

file copy -force ${root_dir}/vivado_proj/firesim.runs/${impl_run}/design_1_wrapper.bit ${root_dir}/vivado_proj/firesim.bit
