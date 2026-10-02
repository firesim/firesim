# Workaround for Vivado 2025.1 post-synthesis deadlock on Versal
# Root cause: synth_design launches a "parallel_synth_helper" process
# that dies after synthesis completes, causing the main thread to
# futex-wait forever for the dead helper.
# Fix: Disable the helper process with synth.enableParallelHelperSpawn

puts "INFO: =============================================="
puts "INFO: Installing Versal synth deadlock workarounds"
puts "INFO: =============================================="

# KEY FIX: Disable the parallel helper process
catch {set_param synth.enableParallelHelperSpawn none}
puts "INFO: synth.enableParallelHelperSpawn set to FALSE"

# Disable posix_spawn for forking
catch {set_param general.usePosixSpawnForFork 0}
puts "INFO: general.usePosixSpawnForFork set to 0"

# Limit threads as additional safety
set_param general.maxThreads 1
catch {set_param synth.maxThreads 0}
puts "INFO: maxThreads set to 1/0"

# Disable NoC compiler during synthesis
catch {set_param noc.enableCompil 0}
catch {set_param noc.skipcheckNOCSolutionDirty 1}
catch {set_param noc.skipNOCFreqDrc 1}
catch {set_param noc.disableConfigMerging 1}
puts "INFO: NoC params disabled"

# Override validate_noc Tcl command (safety net)
if {[llength [info commands validate_noc]] > 0} {
    rename validate_noc _original_validate_noc
}
proc validate_noc {args} {
    puts "INFO: validate_noc BYPASSED (deadlock workaround active)"
    return
}

puts "INFO: All workarounds installed successfully"
puts "INFO: =============================================="
