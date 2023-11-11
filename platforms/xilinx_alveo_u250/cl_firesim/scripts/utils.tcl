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
