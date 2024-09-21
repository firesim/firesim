# See LICENSE for license details.

##########################
# Target-RTL generation:
#
# generate the 'FIRRTL_FILE' and 'ANNO_FILE' used by the midas compiler.
# in this case, uses the 'midas.chiselstage.Generator' generator to build the
# configuration specified by the project's 'config.mk' (this
# 'midas.chiselstage.Generator' is specific to FireSim and properly places
# files in the proper directories).
##########################

$(FIRRTL_FILE) $(ANNO_FILE) &: $(TARGET_CP)
	@mkdir -p $(@D)
	$(call run_jar_scala_main,$(firesim_base_dir),$(TARGET_CP),midas.chiselstage.Generator,\
		--target-dir $(GENERATED_DIR) \
		--name $(long_name) \
		--top-module $(DESIGN_PACKAGE).$(DESIGN) \
		--configs $(TARGET_CONFIG_QUALIFIED))
