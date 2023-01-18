# See LICENSE for license details.

# These point at the main class of the target's Chisel generator
DESIGN_PACKAGE ?= firesim.firesim
DESIGN ?= FireSim
# DESIGN ?= FireBoom

# These guide chisel elaboration of the target design specified above.
# See src/main/scala/SimConfigs.scala
TARGET_CONFIG_PACKAGE ?= firesim.firesim
TARGET_CONFIG ?= FireSimRocketConfig

# These guide chisel elaboration of simulation components by MIDAS, including models and widgets.
# See src/main/scala/SimConfigs.scala
PLATFORM_CONFIG_PACKAGE ?= firesim.firesim
PLATFORM_CONFIG ?= BaseF1Config

