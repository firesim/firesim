# adapted from aws-fpga's strategies (minus the extra params + hook tcl)

set synth_options "-retiming"
set synth_directive "AreaOptimized_high"

# Everything after this point is identical to the Timing strategy and should be
# explored for future area savings.

set opt 1
set opt_options    ""
set opt_directive  "Explore"

set place_options    "-net_delay_weight high"
set place_directive  "Explore"

set phys_opt 1
set phys_options     ""
set phys_directive   "AggressiveExplore"

set route_options    "-tns_cleanup"
set route_directive  "Explore"

set route_phys_opt 1
set post_phys_options     ""
set post_phys_directive   "AggressiveExplore"
