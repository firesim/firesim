# FireSim Target Agnostic Make Fragment
#
# Defines make targets for:
# - invoking Golden Gate (phony: verilog / compile)
# - building a simulation driver (phony: f1)
# - populating an FPGA build directory (phony: replace-rtl)
# - generating new runtime configurations (phony: conf)
# - compiling meta-simulators (phony: verilator, vcs, verilator-debug, vcs-debug)
#

# The prefix used for all Golden Gate-generated files
BASE_FILE_NAME ?=

# The directory into which generated verilog and headers will be dumped
# RTL simulations will also be built here
GENERATED_DIR ?=
# Results from RTL simulations live here
OUTPUT_DIR ?=
# Root name for generated binaries
DESIGN ?=

# The target's FIRRTL and associated anotations; inputs to Golden Gate
FIRRTL_FILE ?=
ANNO_FILE ?=

# The host config package and class string
PLATFORM_CONFIG_PACKAGE ?= firesim.midasexamples
PLATFORM_CONFIG ?= DefaultF1Config

# The name of the generated runtime configuration file
CONF_NAME ?= $(BASE_FILE_NAME).runtime.conf

# The host platform type, currently only f1 is supported
PLATFORM ?=

# Driver source files
DRIVER_CC ?=
DRIVER_H ?=

# Target-specific CXX and LD flags for compiling the driver and meta-simulators
# These should be platform independent should be governed by the target-specific makefrag
TARGET_CXX_FLAGS ?=
TARGET_LD_FLAGS ?=

# END MAKEFRAG INTERFACE
