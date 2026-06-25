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

if {$route_phys_opt} {
  set run_to_step {phys_opt_design (Post-Route)}
} else {
  set run_to_step route_design
}
launch_runs ${impl_run} -to_step ${run_to_step} -jobs ${jobs}
wait_on_run ${impl_run}
check_progress ${impl_run} "first normal implementation failed"

set WNS [get_property STATS.WNS ${impl_run}]
set WHS [get_property STATS.WHS ${impl_run}]

# run idr or ml flow to close timing
if {$WNS < 0 || $WHS < 0} {
  check_file_exists [set sourceFile ${root_dir}/scripts/implementation_idr_ml/${vivado_version}.tcl]
  source $sourceFile
  # expects that $WHS/WNS is re-set
}

# Adjust MMCM frequency to match actual timing slack, then write bitstream.
# Opens the routed design, modifies the MMCM divider if needed, and writes
# the bitstream directly from the open design (bypasses launch_runs to
# guarantee the MMCM change is captured in the bitstream).
set run_dir [get_property DIRECTORY [get_runs ${impl_run}]]
set bitstream_path "${run_dir}/${top_level_name}.bit"

set adjusted_freq [adjust_frequency_and_bitstream ${impl_run} $bitstream_path]
if {$adjusted_freq eq ""} {
    puts "ERROR: Frequency adjustment failed — cannot meet timing."
    exit 1
}
puts "INFO: Requested frequency: ${ifrequency} MHz -> Actual frequency: ${adjusted_freq} MHz"
