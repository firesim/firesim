# See LICENSE for license details.

ifdef FIRESIM_STANDALONE
chipyard_dir := $(abspath ..)/target-design/chipyard
else
chipyard_dir := $(abspath chipyard-symlink)
endif
