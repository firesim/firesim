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

set firesim_bit_path ${root_dir}/vivado_proj/firesim.bit

file copy -force ${root_dir}/vivado_proj/firesim.runs/${impl_run}/${top_level_name}.bit ${firesim_bit_path}

# The 2 Gb (256 MB) MT25QU02G has no golden image; the FPGA boots from address 0
write_cfgmem -force -format mcs -interface SPIx4 -size 256 -loadbit "up 0x00000000 ${firesim_bit_path}" -verbose ${root_dir}/vivado_proj/firesim.mcs
