# All FireSim gates use BUFG_FABRIC inside the existing DFX floorplan.
# Preserve the static-shell pblock and restore the standard clock DRC levels.
reset_property SEVERITY [get_drc_checks {HDPR-18 HDPR-59}]
