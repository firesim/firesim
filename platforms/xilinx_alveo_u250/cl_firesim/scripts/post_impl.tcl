# write reports

open_run impl_1

report_timing_summary -file ${rpt_dir}/final_timing_summary.rpt
report_utilization -hierarchical -hierarchical_percentages -file ${rpt_dir}/final_utilization.rpt

close_design

# write bit/mcs

set firesim_bit_path ${root_dir}/vivado_proj/firesim.bit

file copy -force ${root_dir}/vivado_proj/firesim.runs/${impl_run}/${top_level_name}.bit ${firesim_bit_path}

write_cfgmem -force -format mcs -interface SPIx4 -size 1024 -loadbit "up 0x01002000 ${firesim_bit_path}" -verbose ${root_dir}/vivado_proj/firesim.mcs
