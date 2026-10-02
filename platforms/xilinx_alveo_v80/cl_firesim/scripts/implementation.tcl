set impl_run [get_runs impl_1]

reset_runs ${impl_run}

# Helper: set a property only if value is non-empty
proc set_prop_if_nonempty { prop val obj } {
    if {$val ne ""} {
        set_property $prop $val $obj
    }
}

# Set impl properties individually to avoid empty-value errors
set_property STEPS.OPT_DESIGN.IS_ENABLED $opt ${impl_run}
set_prop_if_nonempty STEPS.OPT_DESIGN.DIRECTIVE $opt_directive ${impl_run}
set_prop_if_nonempty {STEPS.OPT_DESIGN.MORE OPTIONS} $opt_options ${impl_run}

set_prop_if_nonempty STEPS.PLACE_DESIGN.DIRECTIVE $place_directive ${impl_run}
set_prop_if_nonempty {STEPS.PLACE_DESIGN.MORE OPTIONS} $place_options ${impl_run}

set_property STEPS.PHYS_OPT_DESIGN.IS_ENABLED $phys_opt ${impl_run}
set_prop_if_nonempty STEPS.PHYS_OPT_DESIGN.DIRECTIVE $phys_directive ${impl_run}
set_prop_if_nonempty {STEPS.PHYS_OPT_DESIGN.MORE OPTIONS} $phys_options ${impl_run}

set_prop_if_nonempty STEPS.ROUTE_DESIGN.DIRECTIVE $route_directive ${impl_run}
set_prop_if_nonempty {STEPS.ROUTE_DESIGN.MORE OPTIONS} $route_options ${impl_run}

set_property STEPS.POST_ROUTE_PHYS_OPT_DESIGN.IS_ENABLED $route_phys_opt ${impl_run}
set_prop_if_nonempty STEPS.POST_ROUTE_PHYS_OPT_DESIGN.DIRECTIVE $post_phys_directive ${impl_run}
set_prop_if_nonempty {STEPS.POST_ROUTE_PHYS_OPT_DESIGN.MORE OPTIONS} $post_phys_options ${impl_run}

# Set WRITE_DEVICE_IMAGE.TCL.PRE to downgrade ADEF-911 from error to warning
set_property STEPS.WRITE_DEVICE_IMAGE.TCL.PRE ${root_dir}/scripts/pre_write_device_image.tcl ${impl_run}

if {$route_phys_opt} {
  set run_to_step {phys_opt_design (Post-Route)}
} else {
  set run_to_step route_design
}

puts "INFO: Launching implementation (to_step=$run_to_step)..."
launch_runs ${impl_run} -to_step ${run_to_step} -jobs ${jobs}
wait_on_run ${impl_run}
check_progress ${impl_run} "first normal implementation failed"

set WNS [get_property STATS.WNS ${impl_run}]
set WHS [get_property STATS.WHS ${impl_run}]

puts "INFO: Implementation WNS=$WNS WHS=$WHS"

# run idr flow to close timing
if {$WNS < 0 || $WHS < 0} {
  puts "WARNING: Timing not met (WNS=$WNS WHS=$WHS). Attempting IDR flow..."
  check_file_exists [set sourceFile ${root_dir}/scripts/implementation_idr_ml/${vivado_version}.tcl]
  source $sourceFile
  # expects that $WHS/WNS is re-set
}

if {$WNS < 0 || $WHS < 0} {
  puts "ERROR: did not meet timing!"
  exit 1
}

puts "INFO: generate device image"
launch_runs ${impl_run} -to_step write_device_image -jobs ${jobs}
wait_on_run ${impl_run}
check_progress ${impl_run} "device image generation failed"

puts "INFO: Implementation and device image generation complete"
