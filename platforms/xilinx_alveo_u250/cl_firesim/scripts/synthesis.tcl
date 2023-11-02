variable synth_run [get_runs synth_1]

reset_runs ${synth_run}
launch_runs ${synth_run} -jobs ${jobs}
wait_on_run ${synth_run}

if {[get_property PROGRESS ${synth_run}] != "100%"} {
    puts "ERROR: synthesis failed"
    exit 1
}

open_run synth_1
report_utilization -hierarchical -hierarchical_percentages -file ${rpt_dir}/post_synth_utilization.rpt
report_control_sets -verbose -file ${rpt_dir}/post_synth_control_sets.rpt
close_design
