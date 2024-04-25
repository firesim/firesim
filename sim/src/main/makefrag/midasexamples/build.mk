# See LICENSE for license details.

$(FIRRTL_FILE) $(ANNO_FILE): $(TARGET_CP)
	@mkdir -p $(@D)
	$(call run_jar_scala_main,$(firesim_base_dir),$(TARGET_CP),midas.chiselstage.Generator,\
		--target-dir $(GENERATED_DIR) \
		--name $(long_name) \
		--top-module $(DESIGN_PACKAGE).$(DESIGN) \
		--configs $(TARGET_CONFIG_QUALIFIED))
