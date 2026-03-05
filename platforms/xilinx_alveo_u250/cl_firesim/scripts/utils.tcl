proc delete_files { file_list } {
   foreach path $file_list {
       if {[file exists ${path}]} {
           file delete -force -- ${path}
       }
   }
}

namespace eval _tcl {
proc get_script_folder {} {
   set script_path [file normalize [info script]]
   set script_folder [file dirname $script_path]
   return $script_folder
}
}
set script_folder [_tcl::get_script_folder]

proc check_file_exists { inFile } {
   if {![file exists $inFile]} {
       puts "ERROR: Could not find $inFile"
       exit 1
   }
}

proc check_progress { run errmsg } {
   set progress [get_property PROGRESS ${run}]
   if {$progress != "100%"} {
       puts "ERROR: $errmsg (progress at $progress/%100)"
       exit 1
   }
}

proc add_line_to_file { lineno ifile istr } {
    if {[catch {exec sed -i "${lineno}i ${istr}\\n" ${ifile}}]} {
        puts "ERROR: Updating ${ifile} failed ($result)"
        exit 1
    }
}

# Adjust the host clock frequency based on post-route timing slack and write the bitstream.
# Modifies the MMCM output divider to achieve a frequency that matches
# the actual timing of the routed design, leaving a configurable margin.
#
# Opens the routed design, adjusts the MMCM if needed, writes the bitstream
# directly from the open design (guarantees the MMCM change is captured),
# then closes the design.
#
# Args:
#   impl_run      - name of the implementation run (e.g. "impl_1")
#   bitstream_path - where to write the .bit file
#   margin_ns     - timing margin to leave (default 0.5ns)
#   min_freq_mhz  - floor frequency; return "" if we'd go below this
#
# Returns: the actual frequency in MHz, or "" on failure.
proc adjust_frequency_and_bitstream { impl_run bitstream_path {margin_ns 0.5} {min_freq_mhz 10.0} } {
    open_run $impl_run

    # Compute WNS/WHS from the open design (not run properties, which can be stale
    # if the IDR/ML flow ran additional optimization steps outside launch_runs)
    set WNS 0.0
    set WHS 0.0
    set setup_path [get_timing_paths -max_paths 1 -nworst 1 -setup -quiet]
    if {$setup_path ne ""} {
        set WNS [get_property SLACK $setup_path]
    }
    set hold_path [get_timing_paths -max_paths 1 -nworst 1 -hold -quiet]
    if {$hold_path ne ""} {
        set WHS [get_property SLACK $hold_path]
    }

    puts "=========================================="
    puts "FREQUENCY ADJUSTMENT CHECK"
    puts "  WNS: ${WNS} ns  WHS: ${WHS} ns"
    puts "=========================================="

    # Find the host clock MMCM
    set mmcm [get_cells -hierarchical -filter {REF_NAME =~ MMCME*} -quiet]
    if {[llength $mmcm] == 0} {
        puts "WARNING: No MMCM found in design, cannot adjust frequency."
        puts "  Writing bitstream at original frequency."
        write_bitstream -force $bitstream_path
        close_design
        return "0"
    }

    set host_mmcm ""
    foreach m $mmcm {
        if {[string match "*clk_wiz_0*" $m]} {
            set host_mmcm $m
            break
        }
    }
    if {$host_mmcm eq ""} {
        set host_mmcm [lindex $mmcm 0]
        puts "WARNING: Could not identify clk_wiz_0 MMCM, using: $host_mmcm"
    }

    set mult_f    [get_property CLKFBOUT_MULT_F $host_mmcm]
    set div_clk   [get_property DIVCLK_DIVIDE $host_mmcm]
    set out_div_f [get_property CLKOUT0_DIVIDE_F $host_mmcm]

    # Get the MMCM input frequency from the input clock pin
    set clkin_pin [get_pins -of_objects $host_mmcm -filter {REF_PIN_NAME == CLKIN1} -quiet]
    set input_freq_mhz 300.0
    if {$clkin_pin ne ""} {
        set clkin_period [get_property PERIOD [get_clocks -of_objects $clkin_pin -quiet] -quiet]
        if {$clkin_period ne "" && $clkin_period > 0} {
            set input_freq_mhz [expr {1000.0 / $clkin_period}]
        }
    }

    set vco_freq [expr {$input_freq_mhz * $mult_f / $div_clk}]
    set current_freq [expr {$vco_freq / $out_div_f}]
    set current_period [expr {1000.0 / $current_freq}]

    puts "  MMCM: $host_mmcm"
    puts "  Input freq:       ${input_freq_mhz} MHz"
    puts "  CLKFBOUT_MULT_F:  $mult_f"
    puts "  DIVCLK_DIVIDE:    $div_clk"
    puts "  CLKOUT0_DIVIDE_F: $out_div_f"
    puts "  VCO freq:         ${vco_freq} MHz"
    puts "  Current freq:     ${current_freq} MHz (period ${current_period} ns)"

    # new_period = current_period + margin - WNS
    # Targets WNS = margin after adjustment.
    set new_period [expr {$current_period + $margin_ns - $WNS}]
    set ideal_freq [expr {1000.0 / $new_period}]

    puts "  Target period:    ${new_period} ns (${ideal_freq} MHz, margin=${margin_ns} ns)"

    # CLKOUT0_DIVIDE_F has 0.125 (1/8) granularity; round UP for safety
    set ideal_div [expr {$vco_freq / $ideal_freq}]
    set new_div_f [expr {ceil($ideal_div * 8.0) / 8.0}]
    if {$new_div_f < 1.0} { set new_div_f 1.0 }
    if {$new_div_f > 128.0} { set new_div_f 128.0 }

    set new_freq [expr {$vco_freq / $new_div_f}]
    set new_period_actual [expr {1000.0 / $new_freq}]

    puts "  New CLKOUT0_DIVIDE_F: $new_div_f (was $out_div_f)"
    puts "  New freq:         ${new_freq} MHz (period ${new_period_actual} ns)"

    if {$new_freq < $min_freq_mhz} {
        puts "ERROR: Adjusted frequency ${new_freq} MHz is below minimum ${min_freq_mhz} MHz."
        close_design
        return ""
    }

    set freq_delta [expr {abs($new_freq - $current_freq)}]
    if {$freq_delta < 0.5} {
        puts "  No significant frequency change needed (delta ${freq_delta} MHz)."
        set new_freq $current_freq
    } else {
        if {$new_freq > $current_freq} {
            puts "  INCREASING frequency: ${current_freq} -> ${new_freq} MHz (+${freq_delta} MHz)"
        } else {
            puts "  DECREASING frequency: ${current_freq} -> ${new_freq} MHz (-${freq_delta} MHz)"
        }
        set_property CLKOUT0_DIVIDE_F $new_div_f $host_mmcm
        puts "  Applied CLKOUT0_DIVIDE_F = $new_div_f to $host_mmcm"
    }

    # Write bitstream directly from the open (possibly modified) design
    puts "  Writing bitstream to: $bitstream_path"
    write_bitstream -force $bitstream_path

    close_design
    puts "  Frequency adjustment + bitstream complete."
    puts "=========================================="

    return $new_freq
}
