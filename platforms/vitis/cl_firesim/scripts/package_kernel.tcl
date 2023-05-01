#
# Copyright (C) 2020 Xilinx, Inc
#
# Licensed under the Apache License, Version 2.0 (the "License"). You may
# not use this file except in compliance with the License. A copy of the
# License is located at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

# This file was originally a vitis RTL kernel example design.

set path_to_hdl "../design"
set path_to_packaged "./packaged_kernel_${suffix}"
set path_to_tmp_project "./tmp_kernel_pack_${suffix}"

set projPart "xcu250-figd2104-2L-e"

set base_filename "FireSim-generated"
set synth_xdc_path "${path_to_hdl}/${base_filename}.synthesis.xdc"
set impl_xdc_path  "${path_to_hdl}/${base_filename}.implementation.xdc"

# Establish reasonable bounds for the input frequency to catch gross errors.
# These numbers are somewhat arbitrary.
set min_frequency 1.0
set max_frequency 300.0

if {$frequency < ${min_frequency}  || $frequency > ${max_frequency}} {
  puts "Provided simulator frequency must be within \[$min_frequency, $max_frequency\] MHz.\n"
  puts "Provided value: $frequency MHz"
  exit 1
}

create_project -force kernel_pack $path_to_tmp_project -part ${projPart}
add_files -norecurse [ list \
    $path_to_hdl/defines.vh \
    $synth_xdc_path \
    $impl_xdc_path \
    $path_to_hdl/FireSim-generated.post-processed.sv \
]

set_property USED_IN_SYNTHESIS false     [get_files $impl_xdc_path]
set_property USED_IN_IMPLEMENTATION true [get_files $impl_xdc_path]

update_compile_order -fileset sources_1
update_compile_order -fileset sim_1

# Note: The main MMCM generation script expects that frequency variable is defined
set ipgen_scripts [glob $path_to_hdl/FireSim-generated.*.ipgen.tcl]
foreach script $ipgen_scripts {
    source $script
}

# Use RTL models instead of systemC-based ones for Xilinx IP.
# - The AXI clock converter IP does not appear to function correctly when running on
#   millennium, which leads to lost transactions. and the RTL models are easier to debug
# - The RTL models are easier to read and debug
set_property SELECTED_SIM_MODEL rtl [get_ips]

generate_target all [get_ips]

ipx::package_project -root_dir $path_to_packaged -vendor xilinx.com -library RTLKernel -taxonomy /KernelIP -import_files -set_current false
ipx::unload_core $path_to_packaged/component.xml
ipx::edit_ip_in_project -upgrade true -name tmp_edit_project -directory $path_to_packaged $path_to_packaged/component.xml

set core [ipx::current_core]

set_property core_revision 2 $core
foreach up [ipx::get_user_parameters] {
    ipx::remove_user_parameter [get_property NAME $up] $core
}

ipx::associate_bus_interfaces -busif s_axi_lite -clock ap_clk $core
ipx::associate_bus_interfaces -busif host_mem_0 -clock ap_clk $core
ipx::associate_bus_interfaces -busif m_dma -clock ap_clk $core

# set up mem map for axis intf
set mem_map    [::ipx::add_memory_map -quiet "s_axi_lite" $core]
set addr_block [::ipx::add_address_block -quiet "reg0" $mem_map]

# TODO: this is unused but I think this is needed vitis build to pass (verify?)
set host_mem_0_offset      [::ipx::add_register -quiet "host_mem_0_offset" $addr_block]
set_property address_offset 0x010 $host_mem_0_offset
set_property size           64    $host_mem_0_offset

# TODO: this is unused but needed for the vitis build to pass? or is this not similar to the axi4-lite intf?
set m_dma_offset      [::ipx::add_register -quiet "m_dma_offset" $addr_block]
set_property address_offset 0x020 $m_dma_offset
set_property size           64    $m_dma_offset

set_property slave_memory_map_ref "s_axi_lite" [::ipx::get_bus_interfaces -of $core "s_axi_lite"]

# define association between pointer arguments (SRC_ADDR, DEST_ADDR) and axi masters (axi_rmst, axi_wmst)
ipx::add_register_parameter ASSOCIATED_BUSIF $host_mem_0_offset
set_property value {host_mem_0} [::ipx::get_register_parameters -of_objects $host_mem_0_offset ASSOCIATED_BUSIF]

ipx::add_bus_parameter DATA_WIDTH [ipx::get_bus_interfaces host_mem_0 -of_objects [ipx::current_core]]
set_property value          {64}  [ipx::get_bus_parameters DATA_WIDTH -of_objects [ipx::get_bus_interfaces host_mem_0 -of_objects [ipx::current_core]]]

# define association between pointer arguments (SRC_ADDR, DEST_ADDR) and axi masters (axi_rmst, axi_wmst)
ipx::add_register_parameter ASSOCIATED_BUSIF $m_dma_offset
set_property value {m_dma} [::ipx::get_register_parameters -of_objects $m_dma_offset ASSOCIATED_BUSIF]

ipx::add_bus_parameter DATA_WIDTH [ipx::get_bus_interfaces m_dma -of_objects [ipx::current_core]]
set_property value          {64}  [ipx::get_bus_parameters DATA_WIDTH -of_objects [ipx::get_bus_interfaces m_dma -of_objects [ipx::current_core]]]

set_property xpm_libraries {XPM_CDC XPM_MEMORY XPM_FIFO} $core
set_property sdx_kernel true $core
set_property sdx_kernel_type rtl $core
set_property supported_families { } $core
set_property auto_family_support_level level_2 $core
ipx::create_xgui_files $core
ipx::update_checksums $core
ipx::check_integrity -kernel $core
ipx::save_core $core
close_project -delete
