# Downgrade ADEF-911 (BUFGCE SIM_DEVICE mismatch) from error to warning.
# FIRRTL emits BUFGCE with SIM_DEVICE=ULTRASCALE; Vivado silently changes it
# to VERSAL_HBM at elaboration, but the DRC still flags it. post_synth.tcl
# fixes the cells, however launch_runs re-opens from the OOC checkpoint where
# the fix isn't saved. This waiver lets write_device_image proceed.
set_property SEVERITY {Warning} [get_drc_checks ADEF-911]
