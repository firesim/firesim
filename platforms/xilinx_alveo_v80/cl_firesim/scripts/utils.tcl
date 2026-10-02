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
    if {[catch {exec sed -i "${lineno}i ${istr}" ${ifile}} result]} {
        puts "WARNING: Updating ${ifile} via sed failed ($result), trying Tcl file I/O"
        if {[catch {
            set fp [open ${ifile} r]
            set contents [read $fp]
            close $fp
            set fp [open ${ifile} w]
            puts $fp ${istr}
            puts -nonewline $fp $contents
            close $fp
        } result2]} {
            puts "WARNING: Tcl file I/O also failed ($result2), continuing anyway"
        }
    }
}
