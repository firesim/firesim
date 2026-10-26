if {[llength $argv] != 2} {error "Expected 2 arguments"}
open_checkpoint [lindex $argv 0]
write_checkpoint -force -cell top_i/slash [lindex $argv 1]
exit
