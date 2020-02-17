########################################################################
# CS152 Lab 2                                                          #
########################################################################

SHELL := /bin/bash

lab_dir := $(chipyard_dir)/lab

lab_output = $(OUTPUT_DIR)/$(notdir $(firstword $(BINARY)))

$(OUTPUT_DIR):
	mkdir -p $@

.PHONY: run-pk
run-pk: $(EMUL) | $(OUTPUT_DIR)
	cd $(dir $($(EMUL))) && \
	./$(notdir $($(EMUL))) +permissive $($*_ARGS) $($(EMUL)_args) $(COMMON_SIM_ARGS) $(MIDAS_LEVEL_SIM_ARGS) $(EXTRA_SIM_ARGS) +permissive-off \
	pk $(BINARY) \
	$(disasm) $(call lab_output).out && [ $$PIPESTATUS -eq 0 ]

.PHONY: run-pk-debug
run-pk-debug: $(EMUL)-debug | $(OUTPUT_DIR)
	cd $(dir $($(EMUL)_debug)) && \
	./$(notdir $($(EMUL)_debug)) +permissive +waveform=$(call lab_output).vpd $($*_ARGS) $($(EMUL)_args) $(COMMON_SIM_ARGS) $(MIDAS_LEVEL_SIM_ARGS) $(EXTRA_SIM_ARGS) +permissive-off \
	pk $(BINARY) \
	$(disasm) $(call lab_output).out && [ $$PIPESTATUS -eq 0 ]


# Directed Portion

$(OUTPUT_DIR)/transpose: $(lab_dir)/directed/transpose.riscv
	mkdir -p $(dir $@)
	ln -sf $< $@

.PHONY: run-transpose run-transpose-debug
run-transpose: $(OUTPUT_DIR)/transpose.out
run-transpose-debug: $(OUTPUT_DIR)/transpose.vpd

# Open-Ended Problem 4.1

CCBENCH_ARGS ?= 24576 1000 0

.PHONY: run-ccbench
run-ccbench: run-pk
run-ccbench: BINARY := $(lab_dir)/open1/ccbench/caches/caches $(CCBENCH_ARGS)
run-ccbench: disasm := >&2 2>/dev/null | tee


# Open-Ended Problem 4.2

.PHONY: run-bfs run-bfs-debug
run-bfs: run-pk
run-bfs-debug: run-pk-debug

run-bfs run-bfs-debug: BINARY := $(lab_dir)/open2/bfs -f $(lab_dir)/open2/kron10.sg -n 1
