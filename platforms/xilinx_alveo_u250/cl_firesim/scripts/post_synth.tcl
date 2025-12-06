# write reports

open_run synth_1

# Report utilization
report_utilization -hierarchical -hierarchical_percentages -file ${rpt_dir}/post_synth_utilization.rpt

# Report control sets
report_control_sets -verbose -file ${rpt_dir}/post_synth_control_sets.rpt
write_checkpoint ${root_dir}/vivado_proj/firesim.runs/synth_1/synth.dcp

# Create a pblock for the prefetch region
create_pblock pblock_prefetch_region
resize_pblock pblock_prefetch_region -add {SLICE_X59Y364:SLICE_X92Y477 DSP48E2_X8Y146:DSP48E2_X11Y189 RAMB18_X4Y146:RAMB18_X6Y189 RAMB36_X4Y73:RAMB36_X6Y94}
add_cells_to_pblock pblock_prefetch_region [get_cells [list $pr_partition_path]] -clear_locs

set_property target_constrs_file ${root_dir}/design/bitstream_config.xdc [current_fileset -constrset]
save_constraints -force

close_design
