# See LICENSE for license details.
# Vivado IP packaging followed by the independent SLASH shell linker.
PROJECT_NAME := firesim
FREQUENCY ?= 40
PART := xcv80-lsva4737-2MHP-e-S
VIVADO = $(XILINX_VIVADO)/bin/vivado
PACKAGED_KERNEL := packaged_kernel/$(PROJECT_NAME)/component.xml
FREQUENCY_STAMP := .firesim-frequency-$(FREQUENCY)
GENERATED_RTL := design/FireSim-generated.sv
PROCESSED_RTL := design/FireSim-generated.post-processed.sv
IPGEN := $(wildcard design/*.ipgen.tcl)

export FIRESIM_HOST_MHZ := $(FREQUENCY)
export FIRESIM_OPT_PRE_TCL := $(abspath scripts/firesim_opt_pre.tcl)
export FIRESIM_DEBUG_PROBES_TCL := $(abspath scripts/firesim_debug_probes.tcl)

.PHONY: help vbin clean
help:
	@echo 'make vbin SLASH_DIR=<checkout> XILINX_VIVADO=<Vivado prefix> FREQUENCY=40'

$(FREQUENCY_STAMP):
	python3 scripts/write-link-config.py --frequency '$(FREQUENCY)' --output link.cfg
	rm -f .firesim-frequency-*
	touch '$@'

$(PROCESSED_RTL): $(GENERATED_RTL) design/defines.vh
	python3 -c 'from pathlib import Path; Path("$@").write_bytes(b"`include \"defines.vh\"\n" + Path("$<").read_bytes())'

$(PACKAGED_KERNEL): $(PROCESSED_RTL) design/defines.vh design/V80SlashClockGate.v $(IPGEN) scripts/package_kernel.tcl $(FREQUENCY_STAMP)
	test -n "$(XILINX_VIVADO)"
	"$(VIVADO)" -mode batch -source scripts/package_kernel.tcl -tclargs $(PROJECT_NAME) $(PART) '$(FREQUENCY)'

link.cfg: scripts/write-link-config.py $(FREQUENCY_STAMP)
	python3 scripts/write-link-config.py --frequency '$(FREQUENCY)' --output '$@'

vbin: firesim.vbin
firesim.vbin: $(PACKAGED_KERNEL) link.cfg scripts/firesim_presynth.tcl scripts/firesim_opt_pre.tcl scripts/firesim_debug_probes.tcl scripts/check-control-aperture.py
	test -n "$(SLASH_DIR)"
	python3 scripts/check-control-aperture.py --component '$(PACKAGED_KERNEL)'
	PYTHONPATH="$(SLASH_DIR)/linker$${PYTHONPATH:+:$$PYTHONPATH}" python3 -m slashkit link --vivado "$(VIVADO)" -c link.cfg -k '$(PACKAGED_KERNEL)' -o '$@' -p hw --pre-synth-tcls scripts/firesim_presynth.tcl

clean:
	rm -rf packaged_kernel link.cfg firesim.vbin .Xil .firesim-frequency-* design/FireSim-generated.post-processed.sv
