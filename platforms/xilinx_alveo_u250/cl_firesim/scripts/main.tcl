set root_dir [pwd]
set vivado_version [version -short]

# set ifrequency           [lindex $argv 0]
# set istrategy            [lindex $argv 1]
# set iboard               [lindex $argv 2]

set ifrequency           60
set istrategy            TIMING
set iboard               au200


proc retrieveVersionedFile {filename version} {
  set first [file rootname $filename]
  set last [file extension $filename]
  if {[file exists ${first}_${version}${last}]} {
    return ${first}_${version}${last}
  }
  return $filename
}

puts $vivado_version

if {![file exists [set sourceFile [retrieveVersionedFile ${root_dir}/scripts/platform_env.tcl $vivado_version]]]} {
    puts "ERROR: could not find $sourceFile"
    exit 1
}
source $sourceFile

if {![file exists [set sourceFile [retrieveVersionedFile ${root_dir}/scripts/${iboard}.tcl $vivado_version]]]} {
    puts "ERROR: could not find $sourceFile"
    exit 1
}
source $sourceFile

# Cleanup
foreach path [list ${root_dir}/vivado_proj/firesim.bit] {
    if {[file exists ${path}]} {
        file delete -force -- ${path}
    }
}

create_project -force firesim ${root_dir}/vivado_proj -part $part
set_property board_part $board_part [current_project]


## DN: Copying this from synth_cl_firesim.tcl
# Generate IP instantated in Golden-Gate generated RTL
file mkdir ${root_dir}/design/ipgen
set ipgen_scripts [glob -nocomplain ${root_dir}/design/FireSim-generated.*.ipgen.tcl]
foreach script $ipgen_scripts {
    source $script
}

# Generate targets for all IPs contained within the generated module hierarchy.
# With the exception of the PLL, these are the only IP instances that don't have
# their output artifacts checked in.
generate_target all [get_ips]

# synth_ip [get_ips]

set xci_files [get_files *.xci]
# Check if any .xci files were found
if {[llength $xci_files] > 0} {
    # Loop through each .xci file and set the property
    foreach ip_file $xci_files {
        # Set the property to disable OOC synthesis for each IP file
        set_property generate_synth_checkpoint 0 $ip_file
        puts "Successfully set 'generate_synth_checkpoint' to 0 for $ip_file"
    }
} else {
    puts "ERROR: No .xci files found in the project."
}


# ## DN: making the ila wrapper
# ## Set the file path to a variable
# set ila_wrapperFile "${root_dir}/design/FireSim-generated.ila_firesim.ipgen.tcl" 
# # Check if the file exists
# if {![file exists $ila_wrapperFile]} {
#   puts "DN WARNING: could not find $ila_wrapperFile"
# } else {
#   # Source the file if it exists
#   source $ila_wrapperFile
# }
# #source "${root_dir}/design/FireSim-generated.ila_firesim.ipgen.tcl"
# generate_target all [get_ips ila_firesim]
# synth_ip [get_ips ila_firesim]
# set_property generate_synth_checkpoint 0 [get_files ila_firesim.xci]
  
# Loading all the verilog files
foreach addFile [list ${root_dir}/design/axi_tieoff_master.v ${root_dir}/design/firesim_wrapper.v ${root_dir}/design/FireSim-generated.sv ${root_dir}/design/FireSim-generated.defines.vh] {
  set addFile [retrieveVersionedFile $addFile $vivado_version]
  if {![file exists $addFile]} {
    puts "ERROR: could not find file $addFile"
    exit 1
  }
  add_files $addFile
  if {[file extension $addFile] == ".vh"} {
    set_property IS_GLOBAL_INCLUDE 1 [get_files $addFile]
  }
}

set desired_host_frequency $ifrequency
set strategy $istrategy


# Loading create_bd.tcl
if {![file exists [set sourceFile [retrieveVersionedFile ${root_dir}/scripts/create_bd_${vivado_version}.tcl $vivado_version]]]} {
  puts "ERROR: could not find $sourceFile"
  exit 1
}
source $sourceFile

# Making wrapper
make_wrapper -files [get_files ${root_dir}/vivado_proj/firesim.srcs/sources_1/bd/design_1/design_1.bd] -top
add_files -norecurse ${root_dir}/vivado_proj/firesim.gen/sources_1/bd/design_1/hdl/design_1_wrapper.v

# add_files -norecurse ${root_dir}/vivado_proj/firesim.gen/sources_1/ip/ila_firesim/synth/ila_firesim.v
# add_files -norecurse ${root_dir}/vivado_proj/firesim.gen/sources_1/ip/ila_firesim/hdl/verilog/

# Adding additional constraint sets

if {[file exists [set constrFile [retrieveVersionedFile ${root_dir}/design/FireSim-generated.synthesis.xdc $vivado_version]]]} {
    create_fileset -constrset synth
    add_files -fileset synth -norecurse $constrFile
}

if {[file exists [set constrFile [retrieveVersionedFile ${root_dir}/design/FireSim-generated.implementation.xdc $vivado_version]]]} {
    create_fileset -constrset impl
    add_files -fileset impl -norecurse $constrFile
}


if {[file exists [set constrFile [retrieveVersionedFile ${root_dir}/design/bitstream_config.xdc $vivado_version]]]} {
    add_files -fileset impl -norecurse $constrFile
}

update_compile_order -fileset sources_1
validate_bd_design
set_property top design_1_wrapper [current_fileset]
update_compile_order -fileset sources_1

if {[llength [get_filesets -quiet synth]]} {
    set_property constrset synth [get_runs synth_1]
}

if {[llength [get_filesets -quiet impl]]} {
    set_property constrset impl [get_runs impl_1]
}


foreach sourceFile [list ${root_dir}/scripts/synthesis.tcl ${root_dir}/scripts/implementation_${vivado_version}.tcl] {
  set sourceFile [retrieveVersionedFile $sourceFile $vivado_version]
  if {![file exists $sourceFile]} {
    puts "ERROR: could not find $sourceFile"
    exit 1
  }
  source $sourceFile
}

puts "Done!"
exit 0
