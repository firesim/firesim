# See LICENSE for license details.

#########################
# MIDAS Unit Tests      #
#########################

UNITTEST_CONFIG ?= AllUnitTests

firesim_root_sbt_project := {file:$(firesim_base_dir)}firesim

rocketchip_dir := $(firesim_base_dir)/rocket-chip
unittest_generated_dir := $(firesim_base_dir)/generated-src/unittests/$(UNITTEST_CONFIG)
unittest_args = \
		BASE_DIR=$(firesim_base_dir) \
		EMUL=$(EMUL) \
		ROCKETCHIP_DIR=$(rocketchip_dir) \
		GEN_DIR=$(unittest_generated_dir) \
		SBT="$(SBT)" \
		SBT_PROJECT=$(firesim_root_sbt_project) \
		CONFIG=$(UNITTEST_CONFIG)

.PHONY:compile-midas-unittests
compile-midas-unittests: $(chisel_srcs)
	$(MAKE) -f $(simif_dir)/unittest/Makefrag $(unittest_args)

.PHONY:run-midas-unittests
run-midas-unittests: $(chisel_srcs)
	$(MAKE) -f $(simif_dir)/unittest/Makefrag $@ $(unittest_args)

.PHONY:run-midas-unittests-debug
run-midas-unittests-debug: $(chisel_srcs)
	$(MAKE) -f $(simif_dir)/unittest/Makefrag $@ $(unittest_args)
