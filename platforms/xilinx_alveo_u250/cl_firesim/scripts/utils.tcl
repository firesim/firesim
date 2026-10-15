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

# Select the block-design script set explicitly. These Vivado releases all
# use the same scripts; add a separate arm when a release needs another set.
proc get_bd_lib_file { script_folder filename version } {
   switch -exact -- $version {
       2021.1 -
       2021.2 -
       2022.1 -
       2022.2 -
       2023.1 - 
       2023.2 - 
       2023.2.2 -
       2024.1 - 
       2024.2 - 
       2025.1 - 
       2025.2 {
           set library_dir [file join $script_folder bd_lib]
       }
       default {
           error "Unsupported Vivado version '$version' for U250 block-design scripts. Supported versions: 2021.1 through 2025.2"
       }
   }
   return [file join $library_dir $filename]
}

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
