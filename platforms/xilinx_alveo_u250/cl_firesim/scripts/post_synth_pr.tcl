# write reports

open_run synth_1

# Discover partition paths via get_cells if they were not provided.
# The design is already open so this adds no extra open/close overhead.
if {[llength $pr_partition_paths] == 0} {
    puts "Discovering PR partition paths from synthesized design..."

    set discovered_module_names {}
    set discovered_partition_paths {}
    foreach pr_module_name $pr_module_names {
        set cells [get_cells -hierarchical -filter "REF_NAME == $pr_module_name" -quiet]
        if {[llength $cells] == 0} {
            puts "ERROR: No instances of module '$pr_module_name' found in synthesized design"
            exit 1
        }
        puts "  Found [llength $cells] instance(s) of module '$pr_module_name':"
        foreach cell $cells {
            set cell_name [get_property NAME $cell]
            lappend discovered_module_names $pr_module_name
            lappend discovered_partition_paths $cell_name
            puts "    -> $cell_name"
        }
    }
    set pr_module_names $discovered_module_names
    set pr_partition_paths $discovered_partition_paths
    puts "Total PR partitions discovered: [llength $pr_partition_paths]"

    # Write discovered paths for pr_metadata.py to read after Vivado exits
    set dpf [open ${root_dir}/vivado_proj/discovered_pr_paths.txt w]
    for {set j 0} {$j < [llength $pr_module_names]} {incr j} {
        puts $dpf "[lindex $pr_module_names $j]:[lindex $pr_partition_paths $j]"
    }
    close $dpf

    set pr_config_partitions {}
    for {set i 0} {$i < [llength $pr_module_names]} {incr i} {
        set mn [lindex $pr_module_names $i]
        set pp [lindex $pr_partition_paths $i]
        set rm [dict get $module_to_reconfig_module $mn]
        lappend pr_config_partitions "${pp}:${rm}"
        puts "Mapped partition [expr {$i + 1}] at '$pp' to reconfig module '$rm'"
    }
    create_pr_configuration -name config_1 -partitions $pr_config_partitions
    set_property PR_CONFIGURATION config_1 [get_runs impl_1]
    set_property DFX_MODE {ABSTRACT SHELL} [get_runs impl_1]
}

report_utilization -hierarchical -hierarchical_percentages -file ${rpt_dir}/post_synth_utilization.rpt
write_checkpoint ${root_dir}/vivado_proj/firesim.runs/synth_1/synth.dcp

# Define pblock regions for PR partitions
# Each element corresponds to a PR partition in order
set pr_pblock_regions [list \
    {SLICE_X117Y424:SLICE_X144Y476 DSP48E2_X16Y170:DSP48E2_X18Y189 LAGUNA_X16Y368:LAGUNA_X19Y473 RAMB18_X8Y170:RAMB18_X9Y189 RAMB36_X8Y85:RAMB36_X9Y94 URAM288_X2Y116:URAM288_X2Y123} \
    # {SLICE_X117Y453:SLICE_X144Y476 DSP48E2_X16Y182:DSP48E2_X18Y189 RAMB18_X8Y182:RAMB18_X9Y189 RAMB36_X8Y91:RAMB36_X9Y94} \
    {SLICE_X148Y392:SLICE_X174Y415 DSP48E2_X20Y158:DSP48E2_X23Y165 RAMB18_X10Y158:RAMB18_X10Y165 RAMB36_X10Y79:RAMB36_X10Y82} \
    {SLICE_X117Y392:SLICE_X144Y415 DSP48E2_X16Y158:DSP48E2_X18Y165 RAMB18_X8Y158:RAMB18_X9Y165 RAMB36_X8Y79:RAMB36_X9Y82} \
    {SLICE_X148Y453:SLICE_X175Y478 DSP48E2_X20Y182:DSP48E2_X23Y189} 
]

# Get the number of PR partitions
set num_partitions [llength $pr_partition_paths]

# Validate that we have enough pblock regions
if {$num_partitions > [llength $pr_pblock_regions]} {
    puts "WARNING: Number of PR partitions ($num_partitions) exceeds number of defined pblock regions ([llength $pr_pblock_regions])"
    puts "Only creating pblocks for the first [llength $pr_pblock_regions] partitions"
    set num_partitions [llength $pr_pblock_regions]
}

# Create pblocks for each PR partition
for {set i 0} {$i < $num_partitions} {incr i} {
    set pr_partition_path [lindex $pr_partition_paths $i]
    set pblock_region [lindex $pr_pblock_regions $i]
    set pblock_name "pblock_pr_region_${i}"
    
    puts "Creating pblock '$pblock_name' for partition [expr {$i + 1}] at '$pr_partition_path'"
    
    # Create the pblock
    create_pblock $pblock_name
    
    # Resize the pblock with the specified region
    resize_pblock $pblock_name -add $pblock_region
    
    # Add cells from the partition path to the pblock
    add_cells_to_pblock $pblock_name [get_cells [list $pr_partition_path]] -clear_locs
    
    puts "  Assigned region: $pblock_region"
}

set_property target_constrs_file ${root_dir}/design/bitstream_config.xdc [current_fileset -constrset]
save_constraints -force

close_design
