# write reports

open_run synth_1

# FIRRTL generates BUFGCE with no SIM_DEVICE parameter; Vivado defaults to ULTRASCALE.
# Versal requires VERSAL_HBM. Fix it here so the synthesized netlist is correct
# and write_device_image DRC (ADEF-911) passes cleanly.
foreach cell [get_cells -hierarchical -filter {REF_NAME == BUFGCE}] {
    set_property SIM_DEVICE VERSAL_HBM $cell
}

# Report utilization
report_utilization -hierarchical -hierarchical_percentages -file ${rpt_dir}/post_synth_utilization.rpt

# Report control sets
report_control_sets -verbose -file ${rpt_dir}/post_synth_control_sets.rpt

close_design
