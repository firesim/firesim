# Intelligent Design Runs (IDR) Flow
create_run -flow "Vivado IDR Flow $vivado_version_major" -parent_run synth_1 idr_impl_1
set_property REFERENCE_RUN ${impl_run} [get_runs idr_impl_1]
set impl_run [get_runs idr_impl_1]

launch_runs ${impl_run} -jobs ${jobs}
wait_on_run ${impl_run}

check_progress ${impl_run} "idr implementation failed"

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
