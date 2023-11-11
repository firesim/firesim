# write reports

open_run impl_1

# Report final timing
report_timing_summary -file ${rpt_dir}/final_timing_summary.rpt

# Report utilization
report_utilization -hierarchical -hierarchical_percentages -file ${rpt_dir}/final_utilization.rpt

# Report RAM utilization
report_ram_utilization -include_lutram -file ${rpt_dir}/final_ram_utilization.rpt -csv ${rpt_dir}/final_ram_utilization.csv

# Report clock utilization
report_clock_utilization -file ${rpt_dir}/final_clock_utilization.rpt

close_design

# write bit/mcs

file copy -force ${root_dir}/vivado_proj/firesim.runs/${impl_run}/design_1_wrapper.bit ${root_dir}/vivado_proj/firesim.bit

write_cfgmem -force -format mcs -interface SPIx4 -size 1024 -loadbit "up 0x01002000 ${root_dir}/vivado_proj/firesim.bit" -verbose  ${root_dir}/vivado_proj/firesim.mcs
