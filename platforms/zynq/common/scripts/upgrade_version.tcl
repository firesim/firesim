open_project [lindex $argv 0]
#update_compile_order -fileset sources_1
export_ip_user_files -of_objects [get_ips -all] -no_script -reset -quiet
upgrade_ip [get_ips -all] -log ip_upgrade.log
validate_bd_design
write_bd_tcl -force [lindex $argv 1]
