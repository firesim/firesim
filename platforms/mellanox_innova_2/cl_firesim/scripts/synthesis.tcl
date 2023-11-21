variable synth_run [get_runs synth_1]

reset_runs ${synth_run}
launch_runs ${synth_run} -jobs ${jobs}
wait_on_run ${synth_run}

if {[get_property PROGRESS ${synth_run}] != "100%"} {
    puts "ERROR: synthesis failed"
    exit 1
}
