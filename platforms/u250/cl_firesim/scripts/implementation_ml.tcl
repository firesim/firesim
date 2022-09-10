variable ml_start_directive Default
variable ml_max_critical_paths 150
variable ml_max_strategies 5

# Cleanup
foreach path [list ${root_dir}/vivado_proj/ml_strategies ${root_dir}/vivado_proj/ml_qor_suggestions.rqs] {
    if {[file exists ${path}]} {
        file delete -force -- ${path}
    }
}

variable impl_run [get_runs impl_1]
variable WNS -1
variable WHS -1

reset_runs ${impl_run}
set_property STEPS.OPT_DESIGN.ARGS.DIRECTIVE ${ml_start_directive} ${impl_run}
set_property STEPS.PLACE_DESIGN.ARGS.DIRECTIVE ${ml_start_directive} ${impl_run}
set_property STEPS.ROUTE_DESIGN.ARGS.DIRECTIVE ${ml_start_directive} ${impl_run}
set_property STEPS.PHYS_OPT_DESIGN.ARGS.DIRECTIVE ${ml_start_directive} ${impl_run}
set_property STEPS.POST_ROUTE_PHYS_OPT_DESIGN.ARGS.DIRECTIVE ${ml_start_directive} ${impl_run}
launch_runs ${impl_run} -to_step route_design -jobs ${jobs}
wait_on_run ${impl_run}

if {[get_property PROGRESS [get_runs ${impl_run}]] != "100%"} {
    puts "ERROR: implementation failed"
    exit 1
}

set WNS [get_property STATS.WNS [get_runs ${impl_run}]]
set WHS [get_property STATS.WHS [get_runs ${impl_run}]]

if {$WNS < 0 || $WHS < 0} {
    # Doing ML directives
    open_run ${impl_run}
    report_qor_suggestions -max_paths ${ml_max_critical_paths} -max_strategies ${ml_max_strategies} -no_split -quiet
    write_qor_suggestions -force -strategy_dir ${root_dir}/vivado_proj/ml_strategies ${root_dir}/vivado_proj/ml_qor_suggestions.rqs
    close_design

    variable i 1
    while {($i <= ${ml_max_strategies}) && ($WNS < 0 || $WHS < 0)} {
        variable tclFile ${root_dir}/vivado_proj/ml_strategies/impl_1Project_MLStrategyCreateRun${i}.tcl
        if {[file exists ${tclFile}]} {
            source ${tclFile}
            set impl_run ${ml_strategy_run}

            launch_runs ${impl_run} -to_step route_design -jobs ${jobs}
            wait_on_run ${impl_run}

            if {[get_property PROGRESS [get_runs ${impl_run}]] != "100%"} {
                puts "ERROR: implementation failed"
                exit 1
            }

            set WNS [get_property STATS.WNS [get_runs ${impl_run}]]
            set WHS [get_property STATS.WHS [get_runs ${impl_run}]]
        }
        set i [expr $i + 1]
    }
    if {$WNS < 0 || $WHS < 0} {
        puts "INFO: no more ML strategies available"
    }
}


if {$WNS < 0 || $WHS < 0} {
    puts "ERROR: did not meet timing!"
    exit 1
}

launch_runs ${impl_run} -next_step -jobs ${jobs}
wait_on_run ${impl_run}

if {[get_property PROGRESS [get_runs ${impl_run}]] != "100%"} {
    puts "ERROR: implementation failed"
    exit 1
}

file copy -force ${root_dir}/vivado_proj/firesim.runs/${impl_run}/design_1_wrapper.bit ${root_dir}/vivado_proj/firesim.bit
