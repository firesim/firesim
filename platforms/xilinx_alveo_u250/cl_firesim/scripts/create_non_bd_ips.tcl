set current_vivado_version [version -short]

proc get_bd_lib_file { script_folder filename version } {
  return $script_folder/non_bd_lib/$version/$filename
}

proc create_ips { script_folder firesim_freq_mhz current_vivado_version } {
   set firesim_freq_hz [expr $firesim_freq_mhz * 1000000]
   check_file_exists [set sourceFile [get_bd_lib_file $script_folder create_bd_instances.tcl $current_vivado_version]]
   source $sourceFile
}
# End of create_ips()

##################################################################
# MAIN FLOW
##################################################################

create_ips $script_folder $desired_host_frequency $current_vivado_version
