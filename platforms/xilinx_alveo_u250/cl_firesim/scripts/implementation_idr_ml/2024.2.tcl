# Vivado 2024.2's STATS.WNS/STATS.WHS on impl_1 returns stale or intermediate
# values, triggering this fallback even when first-pass implementation met
# timing. Reset to 0 so the parent implementation.tcl falls through to
# adjust_frequency_and_bitstream, which re-reads timing from the open routed
# design and exits 1 if it genuinely missed.
set WNS 0
set WHS 0
