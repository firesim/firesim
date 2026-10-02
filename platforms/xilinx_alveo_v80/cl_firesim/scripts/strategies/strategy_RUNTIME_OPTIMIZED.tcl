# Vivado's Flow_RuntimeOptimized synthesis and implementation strategies:
# trades QoR for runtime, but unlike Flow_Quick keeps timing-driven placement.
# opt_design keeps the Default directive, which Versal needs for its XPHY and
# memory controller cores.

set synth_options "-flatten_hierarchy none -fsm_extraction off"
set synth_directive "RuntimeOptimized"

set opt 1
set opt_options    ""
set opt_directive  "Default"

set place_options    ""
set place_directive  "RuntimeOptimized"

set phys_opt 0
set phys_options     ""
set phys_directive   "Default"

set route_options    ""
set route_directive  "RuntimeOptimized"

set route_phys_opt 0
set post_phys_options     ""
set post_phys_directive   "Default"
