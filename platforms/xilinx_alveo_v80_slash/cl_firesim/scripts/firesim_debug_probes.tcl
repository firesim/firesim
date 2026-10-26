# Called by SLASH with the original implemented design still open. This hook
# only reads design properties and exports metadata; it never edits the image.
set firesim_debug_cores [get_debug_cores -quiet -filter {NAME =~ top_i/slash/firesim_0/inst/*}]
if {[llength $firesim_debug_cores] == 0} {
    write_debug_probes -cell top_i/slash -force $ltx_file
} else {
    if {[info exists ::env(FIRESIM_DEBUG_IP_CHECKPOINT)]} {
        set firesim_debug_ip $::env(FIRESIM_DEBUG_IP_CHECKPOINT)
        set firesim_debug_routed $::env(FIRESIM_DEBUG_ROUTED_CHECKPOINT)
    } else {
        set firesim_debug_run [get_runs -quiet *firesim_0_0_synth_1]
        if {[llength $firesim_debug_run] != 1} {error "Expected one FireSim kernel IP synthesis run"}
        set firesim_debug_dcps [glob -nocomplain [get_property DIRECTORY $firesim_debug_run]/*.dcp]
        if {[llength $firesim_debug_dcps] != 1} {error "Expected one FireSim kernel IP checkpoint"}
        set firesim_debug_ip [lindex $firesim_debug_dcps 0]
        set firesim_debug_routed [file join [get_property DIRECTORY [get_runs impl_1]] top_wrapper_routed.dcp]
    }
    set firesim_debug_records "${ltx_file}.routed-cores.tsv"
    set firesim_debug_file [open $firesim_debug_records w]
    foreach core $firesim_debug_cores {
        set count ""
        if {[lsearch -exact [list_property $core] C_NUM_OF_PROBES] >= 0} {
            set count [get_property C_NUM_OF_PROBES $core]
        }
        puts $firesim_debug_file "$core\t[get_property UUID $core]\t$count"
    }
    close $firesim_debug_file
    set firesim_debug_script [file join [file dirname [info script]] export-debug-probes.py]
    puts [exec python3 $firesim_debug_script --ip-checkpoint $firesim_debug_ip \
        --routed-checkpoint $firesim_debug_routed --identities $firesim_debug_records \
        --output $ltx_file --vivado [file join $::env(XILINX_VIVADO) bin vivado] 2>@1]
}
