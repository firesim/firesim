variable impl_run [get_runs impl_1]
variable WNS -1
variable WHS -1

set idr_start_directive Explore
set impl_run [get_runs impl_1]

reset_runs ${impl_run}
set_property STEPS.OPT_DESIGN.ARGS.DIRECTIVE ${idr_start_directive} ${impl_run}
set_property STEPS.PLACE_DESIGN.ARGS.DIRECTIVE ${idr_start_directive} ${impl_run}
set_property STEPS.ROUTE_DESIGN.ARGS.DIRECTIVE ${idr_start_directive} ${impl_run}
set_property STEPS.PHYS_OPT_DESIGN.IS_ENABLED true ${impl_run}
set_property STEPS.PHYS_OPT_DESIGN.ARGS.DIRECTIVE ${idr_start_directive} ${impl_run}
set_property STEPS.POST_ROUTE_PHYS_OPT_DESIGN.ARGS.DIRECTIVE ${idr_start_directive} ${impl_run}
launch_runs ${impl_run} -to_step route_design -jobs ${jobs}
wait_on_run ${impl_run}

if {[get_property PROGRESS ${impl_run}] != "100%"} {
    puts "ERROR: first normal implementation failed"
    exit 1
}

set WNS [get_property STATS.WNS ${impl_run}]
set WHS [get_property STATS.WHS ${impl_run}]

if {$WNS >= 0 && $WHS >= 0} {
  launch_runs ${impl_run} -next_step -jobs ${jobs}
  wait_on_run ${impl_run}

  if {[get_property PROGRESS ${impl_run}] != "100%"} {
    puts "ERROR: bitstream generation failed"
    exit 1
  }
} else {
  # Intelligent Design Runs (IDR) Flow
  create_run -flow {Vivado IDR Flow 2022} -parent_run synth_1 idr_impl_1
  set_property REFERENCE_RUN ${impl_run} [get_runs idr_impl_1]
  set impl_run [get_runs idr_impl_1]

  launch_runs ${impl_run} -jobs ${jobs}
  wait_on_run ${impl_run}

  if {[get_property PROGRESS ${impl_run}] != "100%"} {
    puts "ERROR: idr implementation failed"
    exit 1
  }

  # We need to figure out which IDR implementation run was successful
  foreach sub_impl_run [get_runs ${impl_run}*] {
    if {[get_property PROGRESS ${sub_impl_run}] == "100%"} {
      set WNS [get_property STATS.WNS ${sub_impl_run}]
      set WHS [get_property STATS.WHS ${sub_impl_run}]
      if {$WNS >= 0 && $WHS >= 0} {
        puts "INFO: timing met in idr run ${sub_impl_run}"
        break
      }
    }
  }

  if {$WNS < 0 || $WHS < 0} {
    puts "ERROR: did not meet timing!"
    exit 1
  }

  puts "INFO: generate bitstream"
  launch_runs ${impl_run} -to_step write_bitstream -jobs ${jobs}
  wait_on_run ${impl_run}
  if {[get_property PROGRESS ${impl_run}] != "100%"} {
    puts "ERROR: bitstream generation failed"
    exit 1
  }
}

file copy -force ${root_dir}/vivado_proj/firesim.runs/${impl_run}/design_1_wrapper.bit ${root_dir}/vivado_proj/firesim.bit

write_cfgmem -force -format mcs -interface SPIx4 -size 1024 -loadbit "up 0x01002000 ${root_dir}/vivado_proj/firesim.bit" -verbose  ${root_dir}/vivado_proj/firesim.mcs
