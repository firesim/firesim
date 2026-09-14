variable synth_run [get_runs synth_1]

reset_runs ${synth_run}

set_property -dict [ list \
    STEPS.SYNTH_DESIGN.ARGS.DIRECTIVE ${synth_directive} \
    {STEPS.SYNTH_DESIGN.ARGS.MORE OPTIONS} "${synth_options}" \
] ${synth_run}

launch_runs ${synth_run} -jobs ${jobs}
wait_on_run ${synth_run}

check_progress ${synth_run} "synthesis failed"
