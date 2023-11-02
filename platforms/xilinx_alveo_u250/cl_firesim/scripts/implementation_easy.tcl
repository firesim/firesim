variable impl_run [get_runs impl_1]
variable WNS -1
variable WHS -1

reset_runs ${impl_run}
launch_runs ${impl_run} -to_step route_design -jobs ${jobs}
wait_on_run ${impl_run}

if {[get_property PROGRESS ${impl_run}] != "100%"} {
    puts "ERROR: implementation failed"
    exit 1
}

set WNS [get_property STATS.WNS ${impl_run}]
set WHS [get_property STATS.WHS ${impl_run}]

if {$WNS < 0 || $WHS < 0} {
    puts "ERROR: did not meet timing!"
    exit 1
}

launch_runs ${impl_run} -next_step -jobs ${jobs}
wait_on_run ${impl_run}

if {[get_property PROGRESS ${impl_run}] != "100%"} {
    puts "ERROR: implementation failed"
    exit 1
}

open_run impl_1

# Report final timing
report_timing_summary -file ${rpt_dir}/final_timing_summary.rpt

# Report utilization
report_utilization -hierarchical -hierarchical_percentages -file ${rpt_dir}/utilization.rpt

# Report RAM utilization
report_ram_utilization -include_lutram -file ${rpt_dir}/final_ram_utilization.rpt -csv ${rpt_dir}/final_ram_utilization.csv

# Report clock utilization
report_clock_utilization -file ${rpt_dir}/final_clock_utilization.rpt

close_design

file copy -force ${root_dir}/vivado_proj/firesim.runs/${impl_run}/design_1_wrapper.bit ${root_dir}/vivado_proj/firesim.bit

write_cfgmem -force -format mcs -interface SPIx4 -size 1024 -loadbit "up 0x01002000 ${root_dir}/vivado_proj/firesim.bit" -verbose  ${root_dir}/vivado_proj/firesim.mcs
