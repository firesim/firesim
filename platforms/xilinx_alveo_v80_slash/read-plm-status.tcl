# See LICENSE for license details.
# Read-only PMC log buffers through the single owned JTAG transport.
if {[llength $argv] != 1} {error "Expected existing audit directory"}
connect -url tcp:127.0.0.1:3121
targets -set -filter {name == "RPU"}
foreach {slr base} {0 0xf2000000 1 0x10a000000 2 0x112000000} {
    mrd -force -bin -file [file join [lindex $argv 0] "slr${slr}.bin"] $base 32768
}
disconnect
exit
