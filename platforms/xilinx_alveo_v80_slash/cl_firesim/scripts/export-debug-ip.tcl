if {[llength $argv] != 2} {error "Expected 2 arguments"}
# Export the IP's probe layout before full-shell HSI hardware-map lookup.
open_checkpoint [file normalize [lindex $argv 0]]
write_debug_probes -force [file normalize [lindex $argv 1]]
exit
