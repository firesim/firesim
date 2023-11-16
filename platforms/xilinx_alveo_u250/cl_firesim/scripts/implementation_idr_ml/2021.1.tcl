set ml_max_critical_paths 150
set ml_max_strategies 5
set ml_qor_suggestions ${root_dir}/vivado_proj/ml_qor_suggestions.rqs
set ml_strategy_dir ${root_dir}/vivado_proj/ml_strategies

# Cleanup
delete_files [list ${ml_qor_suggestions} ${ml_strategy_dir}]

set ml_tcls [list]

open_run ${impl_run}
report_qor_suggestions -max_paths ${ml_max_critical_paths} -max_strategies ${ml_max_strategies} -no_split -quiet
write_qor_suggestions -force -strategy_dir ${ml_strategy_dir} ${ml_qor_suggestions}
close_design

for {set i 1} {$i <= ${ml_max_strategies}} {incr i} {
  set tclFile ${root_dir}/vivado_proj/ml_strategies/impl_1Project_MLStrategyCreateRun${i}.tcl
  if {[file exists ${tclFile}]} {
    lappend ml_tcls ${tclFile}
  }
}

if {([llength ${ml_tcls}] == 0) && ([file exists ${ml_qor_suggestions}])} {
  puts "INFO: no ML strategies were found, using base qor suggestions"

  add_files -force -fileset utils_1 ${ml_qor_suggestions}

  reset_runs ${impl_run}

  set_property RQS_FILES ${ml_qor_suggestions} ${impl_run}
  set_property STEPS.OPT_DESIGN.ARGS.DIRECTIVE RQS ${impl_run}
  set_property STEPS.PLACE_DESIGN.ARGS.DIRECTIVE RQS ${impl_run}
  set_property STEPS.PHYS_OPT_DESIGN.IS_ENABLED true ${impl_run}
  set_property STEPS.PHYS_OPT_DESIGN.ARGS.DIRECTIVE RQS ${impl_run}
  set_property STEPS.ROUTE_DESIGN.ARGS.DIRECTIVE RQS ${impl_run}

  launch_runs ${impl_run} -to_step route_design -jobs ${jobs}
  wait_on_run ${impl_run}

  check_progress ${impl_run} "implementation failed"

  set WNS [get_property STATS.WNS [get_runs ${impl_run}]]
  set WHS [get_property STATS.WHS [get_runs ${impl_run}]]
} else {
  foreach tclFile ${ml_tcls} {
    puts "INFO: using ML strategy from ${tclFile}"
    source ${tclFile}
    set impl_run ${ml_strategy_run}

    launch_runs ${impl_run} -to_step route_design -jobs ${jobs}
    wait_on_run ${impl_run}

    check_progress ${impl_run} "implementation failed"

    set WNS [get_property STATS.WNS [get_runs ${impl_run}]]
    set WHS [get_property STATS.WHS [get_runs ${impl_run}]]

    if {$WNS >= 0 && $WHS >= 0} {
      break
    }
  }
}
