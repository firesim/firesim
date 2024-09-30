# See LICENSE for license details.

##########################
# Project-specific default make variables
##########################

# These point at the main class of the target's Chisel generator
DESIGN_PACKAGE ?= firesim.examples
DESIGN ?= SimpleCounterHarness

# Re-uses the NoConfig from midasexamples for the default 'SimpleCounterHarness'
# which doesn't use any 'Parameters'
TARGET_CONFIG_PACKAGE ?= firesim.midasexamples
TARGET_CONFIG ?= NoConfig

# Must correlate with the 'PLATFORM' (part of the build N-tuplet / make
# argument). Defaults to the basic F1 config (since 'PLATFORM' should be 'f1')
PLATFORM_CONFIG_PACKAGE ?= midas
PLATFORM_CONFIG ?= F1Config
