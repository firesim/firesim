# See LICENSE for license details.

################################################################################
# Utilities helpful for out-of-tree FireSim target projects
################################################################################

# directory where target projects symlink their sources into
firesim_symlink_dir := $(firesim_base_dir)/midas/src/main/scala/target-symlinks

# symlink src/ directory of a target project to the firesim_symlink_dir s.t.
# it can compile with midas. deletes any other symlinks in firesim_symlink_dir that don't
# correspond to what was copied.
# $(1) - space separated list of folders to symlink.
#   i.e. if target has PATH/my-srcs<N>/src/main/scala, paths given should be
#     "PATH/my-srcs0  PATH/my-srcs1 ...".
#   note: directories should be setup with src as subdirectory (otherwise this function will fail)
define symlink_sources_and_clear
	mkdir -p $(firesim_symlink_dir)
	# make the symlink dir
	$(foreach src,$(1),$(shell mkdir -p $(firesim_symlink_dir)/$(notdir $(src))))
	# symlink things
	$(foreach src,$(1),$(shell ln -sf $(src)/src $(firesim_symlink_dir)/$(notdir $(src))/.))
	# remove any folders that don't match
	find $(firesim_symlink_dir)/* -type d | grep -v $(foreach d,$(1),-e "$(firesim_symlink_dir)/$(notdir $(d))") | xargs rm -rf
endef

# target scala directories to copy into midas. each must contain src/ as the 2nd level directory.
# i.e. dirtocopy/src
TARGET_COPY_TO_MIDAS_SCALA_DIRS ?=

# invoke symlink_sources_and_clear function
# also forces downstream rule to always run
.PHONY: firesim_target_symlink_hook
firesim_target_symlink_hook:
	$(call symlink_sources_and_clear,$(TARGET_COPY_TO_MIDAS_SCALA_DIRS))
