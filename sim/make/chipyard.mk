# See LICENSE for license details.

ifdef FIRESIM_STANDALONE
chipyard_dir := $(abspath $(firesim_base_dir)/../target-design/chipyard)
else
chipyard_dir := $(abspath $(firesim_base_dir)/chipyard-symlink)
endif
