# Compile VCS replay binary

TARGET_VERILOG ?= $(GEN_DIR)/$(DESIGN).v
REPLAY_BINARY ?= $(OUT_DIR)/$(DESIGN)-replay
replay_h := $(midas_dir)/sample.h $(replay_dir)/replay_vpi.h $(replay_dir)/replay.h
replay_cc := $(midas_dir)/sample.cc $(replay_dir)/replay_vpi.cc

ifneq ($(filter $(MAKECMDGOALS),vcs-replay $(REPLAY_BINARY)),)
$(info verilog files: $(TARGET_VERILOG))
$(info replay binary: $(REPLAY_BINARY))
endif

$(REPLAY_BINARY): $(v_dir)/replay.v $(TARGET_VERILOG) $(lib) $(replay_cc) $(replay_h)
	mkdir -p $(OUT_DIR)
	rm -rf $(GEN_DIR)/$(notdir $@).csrc
	rm -rf $(OUT_DIR)/$(notdir $@).daidir
	$(VCS) $(VCS_FLAGS) -CFLAGS -I$(replay_dir) \
	-Mdir=$(GEN_DIR)/$(notdir $@).csrc +vpi -P $(r_dir)/vpi.tab \
	+define+STOP_COND=!replay.reset +define+VFRAG=\"$(GEN_DIR)/$(DESIGN).vfrag\" \
	-o $@ $< $(TARGET_VERILOG) $(lib) $(replay_cc)

vcs-replay: $(REPLAY_BINARY)

.PHONY: vcs-replay
