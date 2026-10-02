variable synth_run [get_runs synth_1]

reset_runs ${synth_run}

set_property -dict [ list \
    STEPS.SYNTH_DESIGN.ARGS.DIRECTIVE ${synth_directive} \
    {STEPS.SYNTH_DESIGN.ARGS.MORE OPTIONS} "${synth_options}" \
] ${synth_run}

# Vivado 2025.1 Versal deadlock workaround:
# synth_design launches a parallel_synth_helper subprocess that dies after
# synthesis completes, causing the main thread to futex-wait forever.
# The PRE hook disables the helper via synth.enableParallelHelperSpawn=none.
set_property -name {STEPS.SYNTH_DESIGN.TCL.PRE} \
    -value ${root_dir}/scripts/pre_synth_noc_fix.tcl \
    -objects $synth_run

puts "INFO: Launching synthesis..."
launch_runs ${synth_run} -jobs ${jobs}
wait_on_run ${synth_run}

check_progress ${synth_run} "synthesis failed"

puts "INFO: Synthesis completed successfully"
