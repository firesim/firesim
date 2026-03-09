set gen_root       [file normalize "${root_dir}/vivado_proj/aurora_exdes_gen"]
set ip_proj_dir    [file normalize "${gen_root}/ip_proj"]
set ex_proj_dir    [file normalize "${gen_root}/example_proj"]

file mkdir $gen_root

# Clean old temp content if exists
if {[file exists $ip_proj_dir]} {
  file delete -force $ip_proj_dir
}
if {[file exists $ex_proj_dir]} {
  file delete -force $ex_proj_dir
}

create_project aurora_exdes_gen $ip_proj_dir -part $part -force

create_ip -name aurora_64b66b \
          -vendor xilinx.com \
          -library ip \
          -version 12.0 \
          -module_name aurora_64b66b_0

set aurora_ip [get_ips aurora_64b66b_0] 

set_property -dict [list \
  CONFIG.C_AURORA_LANES {4} \
  CONFIG.C_GT_LOC_2 {2} \
  CONFIG.C_GT_LOC_3 {3} \
  CONFIG.C_GT_LOC_4 {4} \
  CONFIG.C_LINE_RATE {15} \
  CONFIG.SupportLevel {1} \
  CONFIG.interface_mode {Streaming} \
  CONFIG.CHANNEL_ENABLE {X1Y44 X1Y45 X1Y46 X1Y47} \
  CONFIG.C_REFCLK_SOURCE {MGTREFCLK0_of_Quad_X1Y11} \
  CONFIG.C_START_LANE {X1Y44} \
  CONFIG.C_START_QUAD {Quad_X1Y11} \
] $aurora_ip

generate_target all $aurora_ip

# create example project
open_example_project -dir $ex_proj_dir -force -in_process $aurora_ip

# find aurora_64b66b_0_cdc_sync_exdes inside example design
set cdc_hits [glob -nocomplain -types f \
  ${ex_proj_dir}/*/imports/aurora_64b66b_0_cdc_sync_exdes.v]

puts "Generated aurora_64b66b_0_cdc_sync_exdes at: $cdc_hits"

# Copy out generated example design to design/aurora
if {[llength $cdc_hits] > 0} {
  file copy -force [lindex $cdc_hits 0] ${root_dir}/design/aurora/aurora_64b66b_0_cdc_sync_exdes.v
}

puts "Copied generated aurora_64b66b_0_cdc_sync_exdes into ${root_dir}/design/aurora/aurora_64b66b_0_cdc_sync_exdes.v"

close_project