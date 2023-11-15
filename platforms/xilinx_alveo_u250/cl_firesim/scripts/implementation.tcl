set impl_run [get_runs impl_1]

reset_runs ${impl_run}

set_property -dict [ list \
  STEPS.OPT_DESIGN.IS_ENABLED $opt \
  STEPS.OPT_DESIGN.DIRECTIVE $opt_directive \
  {STEPS.OPT_DESIGN.MORE OPTIONS} "$opt_options" \

  STEPS.PLACE_DESIGN.DIRECTIVE $place_directive \
  {STEPS.PLACE_DESIGN.MORE OPTIONS} "$place_options" \

  STEPS.PHYS_OPT_DESIGN.IS_ENABLED $phys_opt \
  STEPS.PHYS_OPT_DESIGN.DIRECTIVE $phys_directive \
  {STEPS.PHYS_OPT_DESIGN.MORE OPTIONS} "$phys_options" \

  STEPS.ROUTE_DESIGN.DIRECTIVE $route_directive \
  {STEPS.ROUTE_DESIGN.MORE OPTIONS} "$route_options" \

  STEPS.POST_ROUTE_PHYS_OPT_DESIGN.IS_ENABLED $route_phys_opt \
  STEPS.POST_ROUTE_PHYS_OPT_DESIGN.DIRECTIVE $post_phys_directive \
  {STEPS.POST_ROUTE_PHYS_OPT_DESIGN.MORE OPTIONS} "$post_phys_options" \
] ${impl_run}

launch_runs ${impl_run} -to_step post_route_phys_opt_design -jobs ${jobs}
wait_on_run ${impl_run}

proc check_progress { run errmsg } {
  if {[get_property PROGRESS ${run}] != "100%"} {
      puts "ERROR: $errmsg"
      exit 1
  }
}

check_progress ${impl_run} "first normal implementation failed"

set WNS [get_property STATS.WNS ${impl_run}]
set WHS [get_property STATS.WHS ${impl_run}]

if {$WNS < 0 || $WHS < 0} {
  check_file_exists [set sourceFile [${root_dir}/scripts/implementation_idr_ml/${vivado_version}.tcl]]
  source $sourceFile
  # expects that $WHS/WNS is re-set
}

if {$WNS < 0 || $WHS < 0} {
  puts "ERROR: did not meet timing!"
  exit 1
}

puts "INFO: generate bitstream"
launch_runs ${impl_run} -to_step write_bitstream -jobs ${jobs}
wait_on_run ${impl_run}
check_progress ${impl_run} "bitstream generation failed"
