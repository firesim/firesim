* midas.platform

This package provides the PlatformShim abstract class, a wrapper module
that should be implemented for each host-platform supported.

This wrappers can be thin; they mainly serve to:

1. Provide different IO names for use in the verilog
2. Generate platform-specific glue logic that should not be put in FPGATop
