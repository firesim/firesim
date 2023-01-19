# See LICENSE for license details.

SIM_RUNTIME_CONF ?= $(GENERATED_DIR)/$(CONF_NAME)
mem_model_args = $(shell cat $(SIM_RUNTIME_CONF))
COMMON_SIM_ARGS ?= $(mem_model_args)
vcs_args = +vcs+initreg+0 +vcs+initmem+0

$(FIRRTL_FILE) $(ANNO_FILE): $(TARGET_CP)
	@mkdir -p $(@D)
	java -cp $$(cat $(TARGET_CP)) freechips.rocketchip.system.Generator \
		--target-dir $(GENERATED_DIR) \
		--name $(long_name) \
		--top-module $(DESIGN_PACKAGE).$(DESIGN) \
		--configs $(TARGET_CONFIG_QUALIFIED)
