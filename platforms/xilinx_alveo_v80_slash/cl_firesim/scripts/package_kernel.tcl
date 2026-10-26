# Packages the GoldenGate-emitted FireSim RTL as a Vivado IP-XACT component
# for consumption by the SLASH linker (slashkit).
#
# Usage (run from the FireSim work directory, i.e. the directory containing
# this script's Makefile):
#   vivado -mode batch -source scripts/package_kernel.tcl \
#     -tclargs <kernel_name> <part> <frequency_mhz>
#
# The emitted component exposes the interface names expected by slashkit:
#   ap_clk          - clock (xilinx.com:signal:clock)
#   ap_rst_n        - active-low reset (xilinx.com:signal:reset)
#   s_axi_control   - AXI4-Lite slave (the shim's s_axi_lite ports)
#   m_axi_gmem0     - AXI4 master, 64-bit address (the shim's host_mem_0 ports)

set expected_arg_count 3
if { $::argc != $expected_arg_count } {
    puts "ERROR: Program \"$::argv0\" requires $expected_arg_count arguments!"
    puts "Usage: $::argv0 <kernel_name> <part> <frequency_mhz>"
    exit 1
}

set kernel_name [lindex $::argv 0]
set part        [lindex $::argv 1]
set frequency   [lindex $::argv 2]
if {![string is integer -strict $frequency] || $frequency < 1 || $frequency > 300} {
    error "Frequency must be an integer in [1, 300] MHz"
}
if {![regexp {^[A-Za-z][A-Za-z0-9_]*$} $kernel_name]} {error "Invalid kernel name"}

set script_path [file dirname [file normalize [info script]]]
set path_to_hdl "design"
set path_to_packaged "./packaged_kernel/${kernel_name}"
set path_to_tmp_project "./tmp_kernel_pack_${kernel_name}"

set base_filename "FireSim-generated"
set synth_xdc_path "${path_to_hdl}/${base_filename}.synthesis.xdc"
set impl_xdc_path "${path_to_hdl}/${base_filename}.implementation.xdc"

create_project -force kernel_pack $path_to_tmp_project -part ${part}

add_files -norecurse [ list \
    ${path_to_hdl}/V80SlashClockGate.v \
    ${path_to_hdl}/defines.vh \
    ${path_to_hdl}/${base_filename}.post-processed.sv \
]

if { [file exists ${synth_xdc_path}] } {
    add_files -norecurse ${synth_xdc_path}
}
if { [file exists ${impl_xdc_path}] } {
    add_files -norecurse ${impl_xdc_path}
    set_property USED_IN_SYNTHESIS false [get_files ${impl_xdc_path}]
    set_property USED_IN_IMPLEMENTATION true [get_files ${impl_xdc_path}]
}

update_compile_order -fileset sources_1

# Generate any IP instances GoldenGate requested (AXI clock converters,
# MMCMs, ...). These scripts expect the `frequency` variable to be defined.
set ipgen_scripts [glob -nocomplain ${path_to_hdl}/${base_filename}.*.ipgen.tcl]
foreach script $ipgen_scripts {
    source $script
}
update_compile_order -fileset sources_1

# Determine the kernel top and cache the physical port names per interface
# prefix while the project context is still available (ipx::package_project
# switches the active context to the packaged core).
set tops [find_top]
# FAME5 can leave additional unused root modules in the emitted file.
# Select the actual V80 platform wrapper, never the first inferred root.
if {[lsearch -exact $tops SlashShim] < 0} {
    error "Expected SlashShim among inferred RTL roots: $tops"
}
set tops [list SlashShim]
if { [llength $tops] == 0 } {
    puts "ERROR: could not determine kernel top module"
    exit 1
}
set_property top [lindex $tops 0] [current_fileset]
update_compile_order -fileset sources_1
puts "Kernel top: [lindex $tops 0]"

# ---------------------------------------------------------------------------
# Package as IP-XACT
# ---------------------------------------------------------------------------
ipx::package_project -root_dir ${path_to_packaged} \
    -vendor firesim.example -library FireSim -taxonomy /FireSim \
    -import_files true -force

set core [ipx::current_core]
set_property name ${kernel_name} $core
set_property display_name ${kernel_name} $core
set_property description "FireSim FPGA-accelerated simulator kernel (SLASH/V80)" $core
set_property vendor_display_name "FireSim" $core
set_property version 1.0 $core
set_property core_revision 1 $core

set_property supported_families { \
    versal    Production \
} $core

# ---------------------------------------------------------------------------
# Bus interface helpers
# ---------------------------------------------------------------------------

# Returns the physical port names of the packaged core that start with prefix.
proc core_ports_with_prefix { core prefix } {
    set names {}
    foreach p [ipx::get_ports -of_objects $core] {
        set n [get_property NAME $p]
        if { [string match "${prefix}_*" $n] } {
            lappend names $n
        }
    }
    if { [llength $names] == 0 } {
        puts "ERROR: no ports found with prefix ${prefix}_"
        exit 1
    }
    return $names
}

# Adds an AXI (full or lite) bus interface named `if_name`, mapping the core's
# ports with physical prefix `prefix` to their uppercase logical AXI names.
proc add_axi_interface { core if_name mode prefix data_width addr_width } {
    # Vivado auto-creates an interface from the port prefix during packaging;
    # drop it so the slashkit-expected interface name is the only one left.
    set auto_bif [ipx::get_bus_interfaces ${prefix} -of_objects $core]
    if { [llength $auto_bif] > 0 } {
        ipx::remove_bus_interface ${prefix} $core
    }
    # Drop the memory map / address space Vivado auto-created for the prefix
    # interface; leaving them unreferenced trips integrity warnings and could
    # confuse downstream IP-XACT parsers.
    catch { ipx::remove_memory_map ${prefix} $core }
    catch { ipx::remove_address_space ${prefix} $core }

    ipx::add_bus_interface ${if_name} $core
    set bif [ipx::get_bus_interfaces ${if_name} -of_objects $core]
    set_property bus_type_vlnv "xilinx.com:interface:aximm:1.0" $bif
    set_property abstraction_type_vlnv "xilinx.com:interface:aximm_rtl:1.0" $bif
    set_property interface_mode ${mode} $bif

    foreach phys [core_ports_with_prefix $core ${prefix}] {
        set suffix [string range $phys [string length "${prefix}_"] end]
        set logical [string toupper $suffix]
        ipx::add_port_map ${logical} $bif
        set_property physical_name $phys [ipx::get_port_maps ${logical} -of_objects $bif]
    }

    ipx::add_bus_parameter DATA_WIDTH $bif
    set_property value ${data_width} \
        [ipx::get_bus_parameters DATA_WIDTH -of_objects $bif]
    ipx::add_bus_parameter ADDR_WIDTH $bif
    set_property value ${addr_width} \
        [ipx::get_bus_parameters ADDR_WIDTH -of_objects $bif]

    # Slave interfaces need a memory map (reg0) so the linker can assign the
    # kernel's control window; masters need an address space.
    if { ${mode} eq "slave" } {
        ipx::add_memory_map ${if_name} $core
        set mm [ipx::get_memory_maps ${if_name} -of_objects $core]
        set_property slave_memory_map_ref ${if_name} $bif
        ipx::add_address_block reg0 $mm
        set ab [ipx::get_address_blocks reg0 -of_objects $mm]
        set_property width 32 $ab
        # Match the resident static shell's fixed NoC aperture. Narrowing it
        # to the RTL register width changes the RM NSU and fails full DFX.
        if { ![info exists ::ctrl_window_bytes] || $::ctrl_window_bytes != 134217728 } {
            error "Missing or incorrect static-shell control aperture"
        }
        set_property range $::ctrl_window_bytes $ab
        set_property usage register $ab
    } else {
        ipx::add_address_space ${if_name} $core
        set as [ipx::get_address_spaces ${if_name} -of_objects $core]
        set_property width ${addr_width} $as
        set_property range 18446744073709551616 $as
    }
    return $bif
}

# ---------------------------------------------------------------------------
# Clock / reset
# ---------------------------------------------------------------------------
ipx::add_bus_interface ap_clk $core
set clk_bif [ipx::get_bus_interfaces ap_clk -of_objects $core]
set_property bus_type_vlnv "xilinx.com:signal:clock:1.0" $clk_bif
set_property abstraction_type_vlnv "xilinx.com:signal:clock_rtl:1.0" $clk_bif
set_property interface_mode slave $clk_bif
ipx::add_port_map CLK $clk_bif
set_property physical_name ap_clk [ipx::get_port_maps CLK -of_objects $clk_bif]

ipx::add_bus_interface ap_rst_n $core
set rst_bif [ipx::get_bus_interfaces ap_rst_n -of_objects $core]
set_property bus_type_vlnv "xilinx.com:signal:reset:1.0" $rst_bif
set_property abstraction_type_vlnv "xilinx.com:signal:reset_rtl:1.0" $rst_bif
set_property interface_mode slave $rst_bif
ipx::add_port_map RST $rst_bif
set_property physical_name ap_rst_n [ipx::get_port_maps RST -of_objects $rst_bif]
ipx::add_bus_parameter POLARITY $rst_bif
set_property value ACTIVE_LOW [ipx::get_bus_parameters POLARITY -of_objects $rst_bif]

# ---------------------------------------------------------------------------
# Control plane: s_axi_lite_* -> s_axi_control (AXI4-Lite slave)
# ---------------------------------------------------------------------------

# Detect the actual RTL width while preserving the fixed static-shell NoC
# contract. Low register bits select widgets; every RM must still describe
# the same 128 MiB incoming NoC aperture.
proc detect_ctrl_window_bytes {} {
    global path_to_hdl base_filename
    set sv "${path_to_hdl}/${base_filename}.sv"
    set width ""
    if { [catch { open $sv r } fh] == 0 } {
        set re {input\s*(?:wire\s*)?\[\s*(\d+)\s*:\s*0\s*\]\s*s_axi_lite_awaddr}
        foreach line [split [read $fh] "\n"] {
            if { [regexp $re $line -> hi] } {
                set width [expr {$hi + 1}]
                break
            }
        }
        close $fh
    }
    if {$width eq "" || $width < 6 || $width > 22} {
        error "Missing or unsupported SLASH control address width: $width"
    }
    set ::ctrl_addr_bits $width
    set bytes 134217728
    puts "Detected control window: RTL awaddr ${width} bits; static NoC aperture ${bytes} bytes"
    return $bytes
}

set ctrl_window_bytes [detect_ctrl_window_bytes]
add_axi_interface $core s_axi_control slave s_axi_lite 32 $::ctrl_addr_bits

# ---------------------------------------------------------------------------
# Memory plane: host_mem_0_* -> m_axi_gmem0 (AXI4 master, 64-bit addressing)
# ---------------------------------------------------------------------------
set mem_bif [add_axi_interface $core m_axi_gmem0 master host_mem_0 64 64]
ipx::add_bus_parameter HAS_BURST $mem_bif
set_property value 1 [ipx::get_bus_parameters HAS_BURST -of_objects $mem_bif]
ipx::add_bus_parameter SUPPORTS_NARROW_BURST $mem_bif
set_property value 0 [ipx::get_bus_parameters SUPPORTS_NARROW_BURST -of_objects $mem_bif]

# Associate clock/reset with both AXI interfaces.
ipx::add_bus_parameter ASSOCIATED_BUSIF $clk_bif
set_property value s_axi_control:m_axi_gmem0 \
    [ipx::get_bus_parameters ASSOCIATED_BUSIF -of_objects $clk_bif]
ipx::add_bus_parameter ASSOCIATED_RESET $clk_bif
set_property value ap_rst_n \
    [ipx::get_bus_parameters ASSOCIATED_RESET -of_objects $clk_bif]

ipx::create_xgui_files $core
ipx::check_integrity $core
ipx::save_core $core

puts "Packaged ${kernel_name} into ${path_to_packaged}/component.xml"
